package com.termux.app.opencode;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Terminal-first OpenCode bootstrap for the app (no native chat UI on this
 * branch).
 *
 * <p>When the app opens, {@code TermuxActivity} calls
 * {@link #ensureAsync(Activity)} (again, right after the Termux bootstrap
 * {@code $PREFIX} is ready), which runs {@link OpenCodeInstaller#ensure} on a
 * background thread. That installs the missing runtime libraries (glibc
 * runtime + patchelf via {@code apt-get}) and downloads the patched OpenCode
 * binary from the npm registry, so typing {@code opencode} in the terminal
 * just works once the app has been opened at least once.</p>
 *
 * <p>The user sees what is happening: a (dismissable) progress dialog shows
 * the current stage while the app is open, and a background notification with
 * a progress bar keeps reporting — naming the apt package currently being
 * fetched/installed and the real download percentage of the OpenCode binary —
 * so the install can run with the app in the background. Progress/log are also
 * mirrored into {@code ~/.opencode/state} and {@code ~/.opencode/setup.log}.</p>
 */
public final class OpenCodeSetup {

    private static final String LOG_TAG = "OpenCodeSetup";

    private static final AtomicBoolean INSTALL_RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean NOTIFICATION_PERMISSION_REQUESTED = new AtomicBoolean(false);

    private static final int REQUEST_POST_NOTIFICATIONS = 7001;
    private static final String CHANNEL_ID = "opencode-setup";
    private static final int NOTIFICATION_ID = 10007;

    /** Main-thread handler used for all dialog/toast updates. */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The visible install-progress dialog, or {@code null}. One at a time. */
    private static volatile ProgressDialogHolder DIALOG;
    private static volatile boolean DIALOG_CANCELLED;

    // Current install UI state (one install at a time).
    private static volatile String uiHeadline = "";
    private static volatile String uiDetail = "";
    private static volatile int uiPercent = -1;   // -1 = indeterminate
    private static volatile int uiLastPostedPercent = -1;
    private static volatile long uiLastPostedBytes;
    private static volatile boolean uiShowDialog;

    public static final File HOME_DIR = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
    public static final File PREFIX_DIR = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
    public static final File OPENCODE_DIR = new File(HOME_DIR, ".opencode");
    public static final File STATE_FILE = new File(OPENCODE_DIR, "state");
    public static final File SETUP_LOG_FILE = new File(OPENCODE_DIR, "setup.log");

    /**
     * Marker file {@code ~/.opencode/reinstall}: while this file exists, the
     * next app open forces a fresh download + re-patch (for testing / repair).
     * The marker is consumed (deleted) when the install runs.
     */
    public static final File REINSTALL_MARKER_FILE = new File(OPENCODE_DIR, "reinstall");

    /** States that represent real (potentially slow) install work. */
    private static final java.util.Set<String> WORK_STATES = new java.util.HashSet<>(java.util.Arrays.asList(
        "deps", "download", "configure"));

    /** apt-output → package name parsers (to name the library being installed). */
    private static final Pattern APT_GET = Pattern.compile("^Get:\\d+\\s+\\S+\\s+\\S+\\s+\\S+\\s+(\\S+)");
    private static final Pattern APT_SELECT = Pattern.compile("^Selecting previously unselected package\\s+(\\S+)");
    private static final Pattern APT_UNPACK = Pattern.compile("^Unpacking\\s+(\\S+)");
    private static final Pattern APT_SETUP = Pattern.compile("^Setting up\\s+(\\S+)");

    private OpenCodeSetup() {
    }

    /**
     * Ensure the native OpenCode CLI is installed, when the app opens.
     *
     * <p>Runs at most one install at a time on a background thread so the
     * terminal UI stays responsive. The Termux bootstrap ({@code $PREFIX}) is
     * owned exclusively by {@code TermuxActivity} (it runs it whenever the app
     * opens with no sessions), so this method never bootstraps by itself —
     * {@code $PREFIX/bin/apt-get} and the glibc loader only exist after the
     * bootstrap is extracted. {@code TermuxActivity} calls this again from its
     * bootstrap completion callback, so a fresh install self-heals right after
     * the bootstrap finishes.</p>
     */
    public static void ensureAsync(final Activity activity) {
        if (!INSTALL_RUNNING.compareAndSet(false, true)) {
            // An install is already in flight; it will surface the result itself.
            return;
        }
        if (!new File(PREFIX_DIR, "bin").isDirectory()) {
            // Bootstrap not ready yet (fresh install): TermuxActivity runs the
            // bootstrap and calls us again from its completion callback.
            INSTALL_RUNNING.set(false);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                install(activity);
            }
        }, "opencode-install").start();
    }

    private static void install(final Activity activity) {
        try {
            boolean reinstall = REINSTALL_MARKER_FILE.isFile();
            if (reinstall) {
                //noinspection ResultOfMethodCallIgnored
                REINSTALL_MARKER_FILE.delete();
                Logger.logInfo(LOG_TAG, "reinstall marker found — forcing a fresh install");
            }
            String arch = deviceArch();
            OpenCodeInstaller.Paths paths = OpenCodeInstaller.Paths.of(PREFIX_DIR, HOME_DIR, arch);
            OpenCodeInstaller installer = new OpenCodeInstaller(
                paths,
                new OpenCodeInstaller.NpmSource(),
                new OpenCodeInstaller.UrlHttp(),
                new OpenCodeInstaller.ProcessRunner(),
                new FileProgress(activity),
                arch,
                Build.VERSION.SDK_INT);
            OpenCodeInstaller.Result result = installer.ensure(reinstall, false);
            StringBuilder msg = new StringBuilder();
            if (result != null && result.outcome != null) {
                msg.append("OpenCode: ").append(result.outcome.name());
                if (result.version != null && !result.version.isEmpty()) {
                    msg.append(" (v").append(result.version).append(')');
                }
            } else {
                msg.append("OpenCode: cài đặt thất bại");
            }
            if (result != null && result.message != null && !result.message.isEmpty()) {
                msg.append(" — ").append(result.message);
            }
            Logger.logInfo(LOG_TAG, msg.toString());
            if (result != null && result.ok()) {
                writeState("ok\nOpenCode sẵn sàng.");
            } else {
                writeState("error\n" + msg);
            }
            finishUI(activity, result != null && result.ok(), msg.toString());
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "OpenCode install failed: " + t);
            writeState("error\nOpenCode install failed: " + t);
            finishUI(activity, false, "OpenCode: cài đặt thất bại — " + t.getMessage());
        } finally {
            INSTALL_RUNNING.set(false);
        }
    }

    /** End-of-install: dismiss dialog, final Toast + notification. */
    private static void finishUI(final Activity activity, final boolean ok, final String message) {
        // Fast path (already installed / fresh cache) never entered a WORK
        // state, so stay completely silent: no toast, no notification.
        final boolean didWork = uiShowDialog;
        dismissDialog();
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (!didWork) return;
                if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                    Toast.makeText(activity, message, ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                }
            }
        });
        Context context = activity == null ? null : activity.getApplicationContext();
        if (didWork) notifyDone(context, ok, message);
    }

    /**
     * {@link OpenCodeInstaller.Progress} that mirrors diagnostics to
     * {@code ~/.opencode/setup.log} and {@code ~/.opencode/state}, names the apt
     * package currently being installed, reports the real download percentage,
     * and shows all of it in a dismissable dialog + a background notification.
     */
    private static final class FileProgress implements OpenCodeInstaller.Progress {
        private final Activity activity;
        private final Context appContext;

        FileProgress(Activity activity) {
            this.activity = activity;
            this.appContext = activity == null ? null : activity.getApplicationContext();
            resetUI(activity);
        }

        @Override
        public void state(String code, String detail) {
            writeState((code == null ? "unknown" : code) + "\n" + (detail == null ? "" : detail));
            Logger.logInfo(LOG_TAG, (code == null ? "" : code + ": ") + detail);

            boolean done = "ok".equals(code) || "error".equals(code);
            if (done) {
                dismissDialog();
                cancelNotification(appContext);
                return;
            }
            if ("checking".equals(code)) {
                // Quiet: on the fast path this is over in a blink, so don't
                // flicker dialog or notification; the first real WORK state
                // (deps/download/configure) is what surfaces the UI.
                uiHeadline = "Đang kiểm tra phiên bản OpenCode…";
                return;
            }
            if (!WORK_STATES.contains(code)) return;
            maybeRequestNotificationPermission(activity);
            uiShowDialog = true;
            String headline;
            if ("deps".equals(code)) {
                headline = "Đang cài OpenCode — thư viện hệ thống…";
            } else if ("download".equals(code)) {
                headline = "Đang cài OpenCode — tải binary…";
            } else {
                headline = "Đang cài OpenCode — cấu hình chạy trực tiếp…";
            }
            setHeadline(activity, headline);
            if (detail != null && !detail.isEmpty() && !"download".equals(code)) {
                setDetail(activity, detail);
            }
        }

        @Override
        public void log(String line) {
            appendText(SETUP_LOG_FILE, line);
            Logger.logInfo(LOG_TAG, line);
            String pkg = aptPackage(line);
            if (pkg != null) {
                setDetail(activity, pkg);
            }
        }

        @Override
        public void download(long done, long total) {
            int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
            long mb = done >> 20;
            boolean changed;
            if (percent >= 0) {
                changed = percent != uiLastPostedPercent;
            } else {
                changed = mb != uiLastPostedBytes;
            }
            if (!changed) return;
            uiPercent = percent;
            uiLastPostedPercent = percent;
            uiLastPostedBytes = mb;
            if (percent >= 0) {
                setDetail(activity, percent + "% (" + mb(done) + " / " + mb(total) + ")");
            } else {
                setDetail(activity, mb(done) + " đã tải…");
            }
        }

        /** Best-effort: which apt package is currently being fetched/installed. */
        private static String aptPackage(String line) {
            if (line == null) return null;
            Matcher m;
            m = APT_GET.matcher(line);
            if (m.find()) return "tải gói " + m.group(1);
            m = APT_SELECT.matcher(line);
            if (m.find()) return "chuẩn bị gói " + m.group(1);
            m = APT_UNPACK.matcher(line);
            if (m.find()) return "giải nén gói " + m.group(1);
            m = APT_SETUP.matcher(line);
            if (m.find()) return "cấu hình gói " + m.group(1);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // UI state (single install at a time, main thread only).
    // ------------------------------------------------------------------

    private static void resetUI(final Activity activity) {
        uiHeadline = "Đang chuẩn bị…";
        uiDetail = "";
        uiPercent = -1;
        uiLastPostedPercent = -1;
        uiLastPostedBytes = 0;
        uiShowDialog = false;
        DIALOG_CANCELLED = false;
        publish(activity);
    }

    private static void setHeadline(final Activity activity, final String headline) {
        uiHeadline = headline == null ? "" : headline;
        publish(activity);
    }

    private static void setDetail(final Activity activity, final String detail) {
        uiDetail = detail == null ? "" : detail;
        publish(activity);
    }

    /** Re-render the dialog + notification with the latest state. */
    private static void publish(final Activity activity) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                renderDialog(activity);
                renderNotification(activity);
            }
        });
    }

    private static void renderDialog(final Activity activity) {
        if (DIALOG_CANCELLED) return;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (DIALOG != null) {
            if (DIALOG.isShowing()) DIALOG.setMessage(message());
            return;
        }
        // Only surface a dialog once there is real install work to show.
        if (!uiShowDialog) return;
        ProgressDialogHolder dialog = new ProgressDialogHolder(activity);
        dialog.setMessage(message());
        dialog.setCancelable(true);
        dialog.setOnDismissListener(new ProgressDialogHolder.DismissListener() {
            @Override
            public void onDismiss() {
                DIALOG = null;
                DIALOG_CANCELLED = true;
            }
        });
        dialog.show();
        DIALOG = dialog;
    }

    private static void dismissDialog() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                ProgressDialogHolder dialog = DIALOG;
                DIALOG = null;
                if (dialog != null && dialog.isShowing()) {
                    try {
                        dialog.dismiss();
                    } catch (Exception ignored) {
                        // Window already gone.
                    }
                }
            }
        });
    }

    /** Current dialog/notification body: headline + detail (+ percent). */
    private static String message() {
        StringBuilder sb = new StringBuilder();
        if (uiHeadline != null && !uiHeadline.isEmpty()) sb.append(uiHeadline);
        if (uiDetail != null && !uiDetail.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(uiDetail);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Background notification (keeps the install visible in the background).
    // ------------------------------------------------------------------

    private static void renderNotification(final Activity activity) {
        if (activity == null) return;
        // Only surface the background notification once there is real install
        // work, so the already-installed fast path stays completely silent.
        if (!uiShowDialog) return;
        Context context = activity.getApplicationContext();
        NotificationManager nm = NotificationUtils.getNotificationManager(context);
        if (nm == null) return;
        NotificationUtils.setupNotificationChannel(context, CHANNEL_ID, "Cài đặt OpenCode",
            NotificationManager.IMPORTANCE_LOW);

        Notification.Builder builder = new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setContentTitle(uiHeadline == null || uiHeadline.isEmpty() ? "Đang cài OpenCode" : uiHeadline);
        builder.setContentText(uiDetail == null ? "" : uiDetail);
        builder.setPriority(Notification.PRIORITY_LOW);
        builder.setOnlyAlertOnce(true);
        builder.setContentIntent(openTermuxIntent(context));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID);
        }
        int percent = uiPercent;
        boolean indeterminate = percent < 0;
        builder.setProgress(100, indeterminate ? 0 : percent, indeterminate);
        try {
            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "could not post setup notification: " + e);
        }
    }

    private static void notifyDone(Context context, boolean ok, String text) {
        if (context == null) return;
        NotificationManager nm = NotificationUtils.getNotificationManager(context);
        if (nm == null) return;
        NotificationUtils.setupNotificationChannel(context, CHANNEL_ID, "Cài đặt OpenCode",
            NotificationManager.IMPORTANCE_LOW);
        Notification.Builder builder = new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setContentTitle(ok ? "OpenCode sẵn sàng" : "OpenCode cài đặt thất bại");
        builder.setContentText(text);
        builder.setPriority(Notification.PRIORITY_LOW);
        builder.setContentIntent(openTermuxIntent(context));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID);
        }
        builder.setAutoCancel(true);
        try {
            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "could not post done notification: " + e);
        }
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                cancelNotification(context);
            }
        }, 15000L);
    }

    private static void cancelNotification(Context context) {
        if (context == null) return;
        NotificationManager nm = NotificationUtils.getNotificationManager(context);
        if (nm != null) {
            try {
                nm.cancel(NOTIFICATION_ID);
            } catch (Exception ignored) {
            }
        }
    }

    private static PendingIntent openTermuxIntent(Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** On Android 13+ plain notifications need runtime permission; ask once when real work starts. */
    private static void maybeRequestNotificationPermission(final Activity activity) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (!NOTIFICATION_PERMISSION_REQUESTED.compareAndSet(false, true)) return;
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                try {
                    ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // State/log files.
    // ------------------------------------------------------------------

    private static void writeState(String text) {
        writeText(STATE_FILE, text);
    }

    private static void writeText(File file, String text) {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (OutputStream out = new FileOutputStream(file, false)) {
            out.write(text.getBytes("UTF-8"));
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "could not write " + file.getAbsolutePath() + ": " + e);
        }
    }

    private static void appendText(File file, String text) {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (OutputStream out = new FileOutputStream(file, true)) {
            out.write((text + "\n").getBytes("UTF-8"));
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "could not append " + file.getAbsolutePath() + ": " + e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /**
     * Minimal wrapper around the deprecated {@link ProgressDialog} so the rest
     * of this class only touches the subset we need (indeterminate, dismissable,
     * live message updates).
     */
    private static final class ProgressDialogHolder {
        interface DismissListener {
            void onDismiss();
        }

        private final ProgressDialog dialog;

        ProgressDialogHolder(Activity activity) {
            dialog = new ProgressDialog(activity);
            dialog.setIndeterminate(true);
        }

        void setMessage(CharSequence message) {
            dialog.setMessage(message);
        }

        void setCancelable(boolean cancelable) {
            dialog.setCancelable(cancelable);
        }

        void setOnDismissListener(final DismissListener listener) {
            dialog.setOnDismissListener(d -> listener.onDismiss());
        }

        boolean isShowing() {
            return dialog.isShowing();
        }

        void show() {
            dialog.show();
        }

        void dismiss() {
            dialog.dismiss();
        }
    }

    /** Detect the npm target architecture ("arm64", "arm", "x64", "x86"). */
    private static String deviceArch() {
        String abi = null;
        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
            abi = Build.SUPPORTED_ABIS[0];
        }
        if (abi == null) abi = System.getProperty("os.arch", "");
        if (abi == null) abi = "";
        abi = abi.toLowerCase(java.util.Locale.US);
        if (abi.contains("arm64") || abi.contains("aarch64")) return "arm64";
        if (abi.contains("armeabi") || abi.contains("armv7") || abi.equals("arm")) return "arm";
        if (abi.contains("x86_64") || abi.contains("amd64")) return "x64";
        if (abi.contains("x86") || abi.contains("i686") || abi.contains("i386")) return "x86";
        return "arm64";
    }
}