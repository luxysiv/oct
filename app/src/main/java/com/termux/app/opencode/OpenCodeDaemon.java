package com.termux.app.opencode;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supervises a native OpenCode server process ({@code opencode serve}) so the
 * app can treat OpenCode as the host and itself as a thin client.
 *
 * <p>The process is started on {@code 127.0.0.1} with an OS-assigned port
 * ({@code --port 0}) and no daemonisation, so it stays a direct child of the
 * Android foreground service that owns this object and can be stopped cleanly.
 * On startup the server prints two lines to its merged stdout/stderr:</p>
 *
 * <pre>
 * server listening on http://127.0.0.1:45965
 * server password 46Jxc8QV4-VdBCPPyoWFbiGDXhQALcmjU21ZgyfUYcM
 * </pre>
 *
 * <p>{@link #pump} parses them and reports {@link Info} through {@link Listener}.
 * The password is the credential for HTTP Basic authentication
 * ({@code opencode:<password>}); the web client turns it into a session cookie
 * via {@code /api/pair} + {@code /auth/connect/<code>} (see
 * {@code OpenCodeClientActivity}).</p>
 *
 * <p>Deliberately free of Android imports so the parsing and lifecycle can be
 * exercised on the JVM. Only APIs available since API 21 are used: no
 * {@code Process.isAlive()}, {@code Process.pid()} or
 * {@code Process.destroyForcibly()}.</p>
 */
public final class OpenCodeDaemon {

    /** Callbacks delivered on the pumping thread (never the main thread). */
    public interface Listener {
        /** The server is listening and authenticated calls will work. */
        void onReady(Info info);

        /** One line of merged server output. */
        void onLog(String line);

        /** The process exited on its own (not via {@link #stop()}). */
        void onExit(int exitCode);
    }

    /** Connection details parsed from the server's startup output. */
    public static final class Info {
        /** Base URL, e.g. {@code http://127.0.0.1:45965}. */
        public final String url;
        /** Server password for Basic auth as {@code opencode:<password>}. */
        public final String password;

        public Info(String url, String password) {
            this.url = url;
            this.password = password;
        }

        public boolean isValid() {
            return url != null && !url.isEmpty() && password != null && !password.isEmpty();
        }

        @Override
        public String toString() {
            return "Info{" + url + "}";
        }
    }

    static final Pattern LISTENING = Pattern.compile("server listening on (https?://\\S+)");
    static final Pattern PASSWORD = Pattern.compile("server password (\\S+)");

    private final File binary;
    private final File prefix;
    private final File home;
    private final File tmpDir;
    private final Listener listener;

    private volatile Process process;
    private volatile boolean running;
    private volatile boolean stopping;
    private volatile Info info;

    // Accumulated between the two startup lines (parsed on the pumping thread).
    private String pendingUrl;
    private String pendingPassword;

    public OpenCodeDaemon(File binary, File prefix, File home, File tmpDir, Listener listener) {
        this.binary = binary;
        this.prefix = prefix;
        this.home = home;
        this.tmpDir = tmpDir;
        this.listener = listener;
    }

    /** Start the server if it is not already running. Idempotent. */
    public synchronized void start() {
        if (running) return;
        if (binary == null || !binary.isFile()) {
            if (listener != null) listener.onExit(-1);
            return;
        }

        stopping = false;
        info = null;
        pendingUrl = null;
        pendingPassword = null;

        List<String> argv = new ArrayList<>();
        argv.add(binary.getAbsolutePath());
        argv.add("serve");
        argv.add("--hostname");
        argv.add("127.0.0.1");
        argv.add("--port");
        argv.add("0");

        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(home);
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("PATH", prefix.getPath() + "/bin:/system/bin");
        env.put("HOME", home.getPath());
        env.put("TMPDIR", tmpDir.getPath());
        env.put("LANG", "C.UTF-8");
        // Never let the server replace its own (glibc-patched) binary.
        env.put("OPENCODE_DISABLE_AUTOUPDATE", "1");

        final Process started;
        try {
            started = builder.start();
        } catch (IOException e) {
            if (listener != null) listener.onLog("daemon start failed: " + e.getMessage());
            if (listener != null) listener.onExit(-1);
            return;
        }

        process = started;
        running = true;

        Thread pump = new Thread(new Runnable() {
            @Override
            public void run() {
                pump(started);
            }
        }, "opencode-daemon");
        pump.setDaemon(true);
        pump.start();
    }

    /** Read server output, detect readiness, then report exit. */
    private void pump(Process p) {
        try (BufferedReader reader =
                 new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (listener != null) listener.onLog(line);
                maybeReady(line);
            }
        } catch (IOException ignored) {
            // Process died / stream closed.
        }

        int exitCode;
        try {
            exitCode = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exitCode = -1;
        }

        synchronized (this) {
            if (process == p) {
                process = null;
                running = false;
                info = null;
            }
        }
        if (!stopping && listener != null) listener.onExit(exitCode);
    }

    private synchronized void maybeReady(String line) {
        if (info != null) return;
        Matcher listening = LISTENING.matcher(line);
        if (listening.find()) pendingUrl = listening.group(1);
        Matcher password = PASSWORD.matcher(line);
        if (password.find()) pendingPassword = password.group(1);
        if (pendingUrl != null && pendingPassword != null) {
            Info ready = new Info(pendingUrl, pendingPassword);
            pendingUrl = null;
            pendingPassword = null;
            info = ready;
            if (listener != null) listener.onReady(ready);
        }
    }

    /** Stop the server (SIGTERM). The pumping thread observes the exit. */
    public synchronized void stop() {
        stopping = true;
        Process p = process;
        process = null;
        running = false;
        info = null;
        if (p != null) {
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
        }
    }

    /** True while the server process is believed to be alive. */
    public boolean isRunning() {
        return running && process != null;
    }

    /** Connection details once ready, else {@code null}. */
    public Info getInfo() {
        return info;
    }
}
