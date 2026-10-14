package com.termux.app.opencode;

import android.content.Context;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Installs and registers the bundled Termux:API MCP tool bridge.
 *
 * <p>Copies the MCP stdio server script ({@code termux-api-mcp.sh}, bash + awk)
 * next to the CLI and registers it in OpenCode's global config, so the assistant
 * can call Android/Termux tools (notifications, clipboard, battery, vibrate,
 * TTS, location).</p>
 *
 * <p>Called from both entry points: {@link OpenCodeDaemonService} (the app-owned
 * shared server used by the WebView client) and {@link OpenCodeSetup} (the
 * terminal-first path, which lets the {@code opencode} TUI pick the tools up
 * without ever opening the WebView). Idempotent and best-effort: failures are
 * logged and ignored.</p>
 */
final class OpenCodeMcp {

    private static final String LOG_TAG = "OpenCodeMcp";

    /** OpenCode MCP server entry name for the Termux:API tool bridge. */
    static final String SERVER_NAME = "termux-api";
    /** APK asset holding the MCP stdio server script. */
    private static final String ASSET = "opencode/termux-api-mcp.sh";

    private OpenCodeMcp() {
    }

    /** Copy the bridge script and register it if it is not registered yet. */
    static void ensure(Context context) {
        if (context == null) return;

        File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        File binDir = new File(new File(home, ".opencode"), "bin");
        File script = new File(binDir, SERVER_NAME + "-mcp.sh");
        File cli = new File(binDir, "opencode");

        if (!copyAsset(context, ASSET, script)) {
            Logger.logError(LOG_TAG, "could not install MCP bridge script");
            return;
        }

        File config = new File(new File(new File(home, ".config"), "opencode"), "opencode.json");
        if (fileContains(config, "\"" + SERVER_NAME + "\"")) return; // already registered

        // Register with an explicit interpreter. Termux has no /usr/bin/env, so
        // a shebang-based spawn fails with NotFound; `bash <script>` is robust.
        File bash = new File(new File(prefix, "bin"), "bash");
        String output = runCli(cli, prefix, home,
            "mcp", "add", "--global", SERVER_NAME, "--",
            bash.getAbsolutePath(), script.getAbsolutePath());
        Logger.logInfo(LOG_TAG, "registered MCP bridge: " + output.trim());
    }

    /** Copy an APK asset to a file and mark it executable. */
    static boolean copyAsset(Context context, String assetPath, File dest) {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "asset copy failed: " + e.getMessage());
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        dest.setExecutable(true, false);
        return true;
    }

    /** Run a short-lived CLI command with the daemon's clean environment. */
    private static String runCli(File cli, File prefix, File home, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(cli.getAbsolutePath());
        for (String arg : args) argv.add(arg);
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(home);
        builder.redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(OpenCodeDaemon.baseEnv(prefix, home, new File(prefix, "tmp")));
        try {
            Process process = builder.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append('\n');
            }
            process.waitFor();
            return out.toString();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "CLI command failed: " + e.getMessage());
            return "";
        }
    }

    /** Whether the file exists and contains the given text. */
    private static boolean fileContains(File file, String needle) {
        if (file == null || !file.isFile()) return false;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.indexOf(needle) >= 0;
        } catch (IOException e) {
            return false;
        }
    }
}
