package com.termux.app.opencode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
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
 * It is started with {@code --service}, which registers it as OpenCode's shared
 * background service: the Termux terminal TUI ({@code opencode}) and this app's
 * web client then share one server, one set of sessions and one config.</p>
 *
 * <p>On startup the server prints its listening URL to merged stdout/stderr:</p>
 *
 * <pre>
 * server listening on http://127.0.0.1:45965
 * </pre>
 *
 * <p>In service mode the password is <em>not</em> printed; it is written to the
 * service state file ({@code $HOME/.local/state/opencode/service.json}) together
 * with the URL. {@link #pump} parses the URL and, if needed, reads the matching
 * password from that file, then reports {@link Info} through {@link Listener}.
 * The password is the credential for HTTP Basic authentication
 * ({@code opencode:<password>}); the web client turns it into a session cookie
 * via {@code /api/pair} + {@code /auth/connect/<code>} (see
 * {@code TermuxActivity}) TUI.</p>
 *
 * <p>When not registered as a service the server prints a second line
 * ({@code server password <pw>}) which is still honoured as a fallback.</p>
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

    /**
     * Registration file written by {@code serve --service}, e.g.
     * {@code $HOME/.local/state/opencode/service.json}. In shared-service mode
     * the password is not printed to stdout, so it is read from here.
     */
    private final File serviceStateFile;

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
        this.serviceStateFile =
            new File(new File(new File(home, ".local"), "state/opencode"), "service.json");
    }

    /**
     * Minimal, deterministic environment used for every invocation of the
     * native CLI (the daemon itself and short-lived helper commands such as
     * {@code opencode mcp add}). Keeping it identical everywhere avoids the
     * CLI inheriting a broken Android environment.
     */
    public static Map<String, String> baseEnv(File prefix, File home, File tmpDir) {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", prefix.getPath() + "/bin:/system/bin");
        env.put("HOME", home.getPath());
        env.put("TMPDIR", tmpDir.getPath());
        env.put("LANG", "C.UTF-8");
        // Never let OpenCode replace its own (glibc-patched) binary.
        env.put("OPENCODE_DISABLE_AUTOUPDATE", "1");
        return env;
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
        // Register as OpenCode's shared background service so the Termux
        // terminal TUI and this app's web client talk to the same server.
        argv.add("--service");
        argv.add("--hostname");
        argv.add("127.0.0.1");
        argv.add("--port");
        argv.add("0");

        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(home);
        builder.redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(baseEnv(prefix, home, tmpDir));

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
        if (listening.find()) {
            pendingUrl = listening.group(1);
            // In shared-service mode the password is stored in the service
            // state file instead of being printed to stdout.
            if (pendingPassword == null) pendingPassword = awaitServicePassword(pendingUrl);
        }
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

    /**
     * Poll the service state file for the password of the server we just
     * started. The {@code url} must match so a stale registration from a
     * previous run is ignored. Returns {@code null} after a short timeout.
     */
    private String awaitServicePassword(String url) {
        for (int attempt = 0; attempt < 40; attempt++) {
            String password = readServicePassword(url);
            if (password != null && !password.isEmpty()) return password;
            try {
                Thread.sleep(150L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private String readServicePassword(String url) {
        String json = readFile(serviceStateFile);
        if (json == null) return null;
        if (!url.equals(OpenCodeJson.string(json, "url"))) return null;
        return OpenCodeJson.string(json, "password");
    }

    private static String readFile(File file) {
        if (file == null || !file.isFile()) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader =
                 new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        } catch (IOException e) {
            return null;
        }
        return sb.toString();
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
