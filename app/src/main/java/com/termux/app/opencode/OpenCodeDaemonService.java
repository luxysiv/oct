package com.termux.app.opencode;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground service that owns the OpenCode server ("the master") so it keeps
 * running with the app in the background and the client can be a thin window.
 *
 * <p>Startup order on a worker thread:</p>
 * <ol>
 *   <li>ensure the native CLI + runtime are installed ({@link OpenCodeInstaller});</li>
 *   <li>start {@link OpenCodeDaemon} ({@code opencode serve} on loopback);</li>
 *   <li>on readiness, take a partial wake lock and keep a foreground
 *       notification so Android does not stop the server.</li>
 * </ol>
 *
 * <p>If the server exits unexpectedly it is restarted with capped backoff. The
 * service returns {@link #START_STICKY} so Android recreates it after being
 * killed. It never daemonises OpenCode itself: the server is a direct child, so
 * {@link #ACTION_STOP} (and {@link #onDestroy}) stop it cleanly.</p>
 *
 * <p>Clients bind and register a {@link Listener} to receive the current
 * connection details (URL + password) whenever they change, e.g. after a
 * restart. The password is served over HTTP Basic as {@code opencode:<pw>}.</p>
 */
public final class OpenCodeDaemonService extends Service implements OpenCodeDaemon.Listener {

    private static final String LOG_TAG = "OpenCodeDaemonService";

    /** Start (or keep) the daemon. Sent with no action too. */
    public static final String ACTION_START = TermuxConstants.TERMUX_PACKAGE_NAME + ".opencode.DAEMON_START";
    /** Stop the daemon and the service. */
    public static final String ACTION_STOP = TermuxConstants.TERMUX_PACKAGE_NAME + ".opencode.DAEMON_STOP";

    private static final String CHANNEL_ID = "opencode-daemon";
    private static final String CHANNEL_NAME = "OpenCode daemon";
    private static final int NOTIFICATION_ID = 20011;

    /** Backoff (ms) between automatic restarts, capped at the last value. */
    private static final long[] RESTART_DELAYS = {1000L, 2000L, 5000L, 10000L, 30000L};

    /** Receives the daemon connection details; may be {@code null} when down. */
    public interface Listener {
        void onDaemonStateChanged(@Nullable OpenCodeDaemon.Info info);
    }

    public final class LocalBinder extends Binder {
        public OpenCodeDaemonService getService() {
            return OpenCodeDaemonService.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();
    private final Handler mHandler = new Handler();
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final Runnable mRestartRunnable = new Runnable() {
        @Override
        public void run() {
            ensureAndStart();
        }
    };

    @Nullable
    private static OpenCodeDaemonService sInstance;

    private OpenCodeDaemon mDaemon;
    private volatile OpenCodeDaemon.Info mInfo;
    private volatile String mState = "Đang chuẩn bị…";
    private volatile PowerManager.WakeLock mWakeLock;
    private boolean mStarting;
    private boolean mStopping;
    private int mRestartAttempts;

    @Nullable
    public static OpenCodeDaemonService getInstance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.logVerbose(LOG_TAG, "onCreate");
        sInstance = this;
        setupNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @SuppressLint("Wakelock")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.logDebug(LOG_TAG, "onStartCommand");
        startForeground(NOTIFICATION_ID, buildNotification());

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopDaemonAndSelf();
            return Service.START_NOT_STICKY;
        }

        mStopping = false;
        mRestartAttempts = 0;
        ensureAndStart();
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        Logger.logVerbose(LOG_TAG, "onDestroy");
        sInstance = null;
        mStopping = true;
        mHandler.removeCallbacks(mRestartRunnable);
        mListeners.clear();
        OpenCodeDaemon daemon = mDaemon;
        if (daemon != null) daemon.stop();
        mDaemon = null;
        mInfo = null;
        releaseWakeLock();
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /** Current connection details, or {@code null} while starting/down. */
    @Nullable
    public OpenCodeDaemon.Info getInfo() {
        return mInfo;
    }

    /** Register for state changes; called immediately with the current state. */
    public void addListener(Listener listener) {
        if (listener == null) return;
        mListeners.add(listener);
        listener.onDaemonStateChanged(mInfo);
    }

    public void removeListener(Listener listener) {
        if (listener != null) mListeners.remove(listener);
    }

    // ------------------------------------------------------------------
    // Lifecycle.
    // ------------------------------------------------------------------

    private void ensureAndStart() {
        if (mStopping) return;
        if (mDaemon != null && mDaemon.isRunning()) return;
        if (mStarting) return;

        mStarting = true;
        setState("Đang chuẩn bị…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!ensureInstalled()) {
                        setState("Cài đặt thất bại");
                        scheduleRestart();
                        return;
                    }
                    startDaemon();
                } finally {
                    mStarting = false;
                }
            }
        }, "opencode-daemon-start").start();
    }

    /** Run the installer if needed; returns {@code true} when the CLI is usable. */
    private boolean ensureInstalled() {
        File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (!new File(prefix, "bin").isDirectory()) {
            Logger.logError(LOG_TAG, "Termux prefix not ready at " + prefix);
            return false;
        }

        String arch = OpenCodeSetup.deviceArch();
        OpenCodeInstaller.Paths paths = OpenCodeInstaller.Paths.of(prefix, home, arch);
        OpenCodeInstaller installer = new OpenCodeInstaller(
            paths,
            new OpenCodeInstaller.NpmSource(),
            new OpenCodeInstaller.UrlHttp(),
            new OpenCodeInstaller.ProcessRunner(),
            new InstallProgress(),
            arch,
            Build.VERSION.SDK_INT);
        OpenCodeInstaller.Result result = installer.ensure(false, false);
        if (result == null) return false;
        if (!result.ok()) {
            Logger.logError(LOG_TAG, "installer failed: " + result.message);
            return false;
        }
        return true;
    }

    private void startDaemon() {
        File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        File bin = new File(new File(home, ".opencode"), "bin/opencode");
        setState("Đang khởi động daemon…");
        OpenCodeDaemon daemon = new OpenCodeDaemon(
            bin, prefix, home, new File(prefix, "tmp"), this);
        mDaemon = daemon;
        daemon.start();
    }

    private void scheduleRestart() {
        if (mStopping) return;
        int index = Math.min(mRestartAttempts, RESTART_DELAYS.length - 1);
        long delay = RESTART_DELAYS[index];
        if (mRestartAttempts < RESTART_DELAYS.length) mRestartAttempts++;
        Logger.logInfo(LOG_TAG, "scheduling daemon restart in " + delay + "ms");
        mHandler.removeCallbacks(mRestartRunnable);
        mHandler.postDelayed(mRestartRunnable, delay);
    }

    @SuppressLint("Wakelock")
    private void stopDaemonAndSelf() {
        Logger.logDebug(LOG_TAG, "Stopping daemon and service");
        mStopping = true;
        mHandler.removeCallbacks(mRestartRunnable);
        OpenCodeDaemon daemon = mDaemon;
        if (daemon != null) daemon.stop();
        mDaemon = null;
        mInfo = null;
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------------
    // OpenCodeDaemon.Listener (called on the pumping thread).
    // ------------------------------------------------------------------

    @Override
    public void onReady(OpenCodeDaemon.Info info) {
        mInfo = info;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mRestartAttempts = 0;
                acquireWakeLock();
                setState("Đang chạy " + (mInfo != null ? mInfo.url : ""));
                notifyListeners();
            }
        });
    }

    @Override
    public void onLog(String line) {
        Logger.logInfo(LOG_TAG, line);
        appendDaemonLog(line);
    }

    @Override
    public void onExit(final int exitCode) {
        mInfo = null;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                releaseWakeLock();
                setState("Đã dừng (exit " + exitCode + ")");
                notifyListeners();
                scheduleRestart();
            }
        });
    }

    private void notifyListeners() {
        OpenCodeDaemon.Info info = mInfo;
        for (Listener listener : mListeners) {
            try {
                listener.onDaemonStateChanged(info);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "listener failed: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Wake lock + foreground notification.
    // ------------------------------------------------------------------

    private void acquireWakeLock() {
        if (mWakeLock != null) return;
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) return;
        mWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "opencode:daemon-wakelock");
        mWakeLock.acquire();
        if (!PermissionUtils.checkIfBatteryOptimizationsDisabled(this)) {
            PermissionUtils.requestDisableBatteryOptimizations(this);
        }
        updateNotification();
    }

    private void releaseWakeLock() {
        if (mWakeLock == null) return;
        try {
            mWakeLock.release();
        } catch (Exception ignored) {
        }
        mWakeLock = null;
        updateNotification();
    }

    private void setState(String state) {
        mState = state;
        updateNotification();
    }

    private Notification buildNotification() {
        Intent openIntent = OpenCodeClientActivity.newInstance(this);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
            pendingIntentFlags());

        boolean wakeLockHeld = mWakeLock != null;
        int priority = wakeLockHeld ? Notification.PRIORITY_HIGH : Notification.PRIORITY_LOW;

        Notification.Builder builder = NotificationUtils.geNotificationBuilder(this,
            CHANNEL_ID, priority, getString(R.string.opencode_daemon_notification_title),
            mState, null, contentIntent, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null) return null;

        builder.setShowWhen(false);
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setColor(0xFF607D8B);
        builder.setOngoing(true);

        Intent stopIntent = new Intent(this, OpenCodeDaemonService.class).setAction(ACTION_STOP);
        builder.addAction(android.R.drawable.ic_delete,
            getString(R.string.notification_action_exit),
            PendingIntent.getService(this, 0, stopIntent, pendingIntentFlags()));

        return builder.build();
    }

    /** {@code FLAG_IMMUTABLE} where available; keeps us ready for targetSdk 31+. */
    private static int pendingIntentFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_IMMUTABLE;
        }
        return 0;
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationUtils.setupNotificationChannel(this, CHANNEL_ID, CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW);
    }

    private void updateNotification() {
        // While shutting down, stopForeground() removes the notification; never
        // re-post it from releaseWakeLock()/setState() after that.
        if (mStopping) return;
        NotificationManager manager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        try {
            manager.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "could not post notification: " + e.getMessage());
        }
    }

    private void appendDaemonLog(String line) {
        File file = new File(OpenCodeSetup.OPENCODE_DIR, "daemon.log");
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (OutputStream out = new FileOutputStream(file, true)) {
            out.write((line + "\n").getBytes(Charset.forName("UTF-8")));
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Install progress (notification only; no dialogs from a service).
    // ------------------------------------------------------------------

    private final class InstallProgress implements OpenCodeInstaller.Progress {
        @Override
        public void state(String code, String detail) {
            if ("ok".equals(code)) {
                setState("Đã cài đặt OpenCode");
            } else if ("error".equals(code)) {
                setState("Lỗi: " + detail);
            } else if (detail != null && !detail.isEmpty()) {
                setState(detail);
            }
        }

        @Override
        public void log(String line) {
            Logger.logInfo(LOG_TAG, line);
        }
    }
}
