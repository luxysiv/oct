package com.termux.app.opencode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Installs and updates the native OpenCode CLI without proot and without the
 * vendor installer.
 *
 * <p>The whole state machine lives here: check installed version, install the
 * glibc runtime + {@code patchelf} (the single {@code apt-get} invocation with
 * {@code full-upgrade}), download the release tarball over {@link HttpURLConnection},
 * verify its {@code sha512} ({@code dist.integrity} from the npm registry), extract
 * only {@code package/bin/opencode} with a pure-Java tar reader, patch the ELF
 * interpreter to the Termux glibc loader with {@code patchelf}, run the freshly
 * staged binary to prove it starts, then publish it and record the version.</p>
 *
 * <p><b>Versioned layout.</b> A release is patched once and kept under
 * {@code ~/.opencode/installed/opencode-v<version>}; {@code ~/.opencode/bin/opencode}
 * is only a symlink to the current build. An update never rewrites a patched ELF:
 * it moves the new build into {@code installed/} and swaps the symlink with a
 * single {@code rename(2)}. That also means the installed binary can be identified
 * (and rolled back) from the symlink target instead of guessing from a size/mtime
 * marker. The patch itself is still required: {@code opencode serve --service}
 * spawns the server from {@code process.execPath}, which only resolves to the real
 * binary when it is executed directly rather than through {@code ld.so}.</p>
 *
 * <p>Only two external programs are executed in the normal path: {@code apt-get}
 * (as a bionic process, so {@code libtermux-exec} is kept in its environment) and
 * {@code patchelf} (as a clean-env helper; {@code LD_PRELOAD}/{@code LD_LIBRARY_PATH}
 * are dropped). On Android below 8, where {@code java.nio.file} is missing, creating
 * the symlink falls back to {@code ln}. Nothing is written back into the ELF by
 * hand.</p>
 *
 * <p>The class has no Android imports on purpose, so the state machine, integrity
 * check, tar reader and version comparison can all be exercised on the JVM with a
 * fake {@link Runner}, {@link Http} and {@link Source}.</p>
 */
public final class OpenCodeInstaller {

    /** How long a successful update check is trusted before hitting the network again. */
    static final long UPDATE_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    /**
     * Marker written into the {@code $PREFIX/bin/opencode} launcher. Bump it to
     * force an already-installed wrapper to be rewritten (the old shell bootstrap
     * wrote {@code opencode-native-wrapper-vN} markers that must be replaced).
     */
    static final String WRAPPER_MARKER = "opencode-native-wrapper-java-v4";

    /** How many patched builds (the newest, including the current one) to keep for rollback. */
    static final int RETAINED_VERSIONS = 3;

    public enum Outcome { UP_TO_DATE, INSTALLED, UPDATED, FAILED, BUSY }

    /** Typed result of {@link #ensure}. */
    public static final class Result {
        public final Outcome outcome;
        public final String version;
        public final String message;

        Result(Outcome outcome, String version, String message) {
            this.outcome = outcome;
            this.version = version;
            this.message = message;
        }

        public boolean ok() {
            return outcome != Outcome.FAILED && outcome != Outcome.BUSY;
        }

        @Override
        public String toString() {
            return "Result{" + outcome + ", v" + version + ", " + message + "}";
        }
    }

    /** Runs a command with an explicit environment; return value is the exit code. */
    public interface Runner {
        int run(List<String> argv, File cwd, Map<String, String> env, LineSink out) throws IOException;
    }

    /** Receives one line of command output (stdout/stderr merged). */
    public interface LineSink {
        void line(String line);
    }

    /** Metadata + binary transport. */
    public interface Http {
        String getString(String url, int maxBytes) throws IOException;

        void download(String url, File dest, Progress progress) throws IOException;
    }

    /** Progress + diagnostics surfaced to the UI. */
    public interface Progress {
        void state(String code, String detail);

        void log(String line);

        /**
         * Download progress while the release tarball is being fetched.
         * {@code done} is the number of bytes written so far; {@code total}
         * is {@code -1} when the server did not send Content-Length.
         */
        default void download(long done, long total) {
        }
    }

    /** Where the release comes from; swappable so a bionic build can be tried later. */
    public interface Source {
        Release latest(Http http, String arch) throws IOException;

        /**
         * Resolve one exact version, used when a version is pinned in
         * {@code ~/.opencode/pin}. The default refuses, so a source that cannot
         * do exact lookups degrades to the plain update path.
         */
        default Release version(Http http, String arch, String version) throws IOException {
            throw new IOException("pinned versions are not supported by this source");
        }
    }

    /** A downloadable CLI build. */
    public static final class Release {
        public final String version;
        public final String tarballUrl;
        /** {@code sha512-<base64>} as published in npm {@code dist.integrity}, or null. */
        public final String integrity;

        public Release(String version, String tarballUrl, String integrity) {
            this.version = version;
            this.tarballUrl = tarballUrl;
            this.integrity = integrity;
        }
    }

    /** Resolved on-device paths for a given Termux prefix/home. */
    public static final class Paths {
        public final File prefix;
        public final File home;
        public final File homeDir;
        public final File binDir;
        /** Launcher path; a symlink to the current build under {@link #installedDir}. */
        public final File bin;
        public final File staged;
        /** {@code ~/.opencode/installed}: one patched binary per version. */
        public final File installedDir;
        public final File versionFile;
        public final File stateFile;
        public final File lockFile;
        public final File tmpDir;
        public final File lastCheckFile;
        public final File wrapper;
        public final File glibcLib;
        public final File loader;
        public final File patchelfGlibc;
        public final File patchelfBin;
        public final File aptGet;
        /** {@code <size> <mtime>} of the binary whose interpreter was patched. */
        public final File patchedFile;
        /** Optional pinned version, one line, e.g. {@code 2.0.27}. */
        public final File pinFile;

        private Paths(File prefix, File home, File homeDir, File binDir, File bin, File staged,
                      File installedDir, File versionFile, File stateFile, File lockFile, File tmpDir,
                      File lastCheckFile, File wrapper, File glibcLib, File loader,
                      File patchelfGlibc, File patchelfBin, File aptGet,
                      File patchedFile, File pinFile) {
            this.prefix = prefix;
            this.home = home;
            this.homeDir = homeDir;
            this.binDir = binDir;
            this.bin = bin;
            this.staged = staged;
            this.installedDir = installedDir;
            this.versionFile = versionFile;
            this.stateFile = stateFile;
            this.lockFile = lockFile;
            this.tmpDir = tmpDir;
            this.lastCheckFile = lastCheckFile;
            this.wrapper = wrapper;
            this.glibcLib = glibcLib;
            this.loader = loader;
            this.patchelfGlibc = patchelfGlibc;
            this.patchelfBin = patchelfBin;
            this.aptGet = aptGet;
            this.patchedFile = patchedFile;
            this.pinFile = pinFile;
        }

        public static Paths of(File prefix, File home, String arch) {
            File homeDir = new File(home, ".opencode");
            File binDir = new File(homeDir, "bin");
            File bin = new File(binDir, "opencode");
            File glibcLib = new File(prefix, "glibc/lib");
            return new Paths(
                prefix, home, homeDir, binDir, bin,
                new File(binDir, "opencode.new"),
                new File(homeDir, "installed"),
                new File(homeDir, "version"),
                new File(homeDir, "state"),
                new File(homeDir, "install.lock"),
                new File(prefix, "tmp"),
                new File(homeDir, "last-update-check"),
                new File(prefix, "bin/opencode"),
                glibcLib,
                new File(glibcLib, loaderName(arch)),
                new File(prefix, "glibc/bin/patchelf"),
                new File(prefix, "bin/patchelf"),
                new File(prefix, "bin/apt-get"),
                new File(homeDir, ".patched"),
                new File(homeDir, "pin"));
        }

        File patchelf() {
            if (patchelfGlibc.isFile()) return patchelfGlibc;
            if (patchelfBin.isFile()) return patchelfBin;
            return null;
        }

        /**
         * Where the patched build of {@code version} lives. The version is
         * sanitised to a plain file name (dots/dashes survive, so pre-releases
         * like {@code 2.0.0-beta.2} keep their identity).
         */
        File versionedBinary(String version) {
            String v = (version == null) ? "" : version.trim();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                sb.append((Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') ? c : '_');
            }
            if (sb.length() == 0) sb.append("unknown");
            return new File(installedDir, "opencode-v" + sb);
        }

        /** The version encoded in an {@code opencode-v*} file name, or {@code null}. */
        static String versionOf(File versioned) {
            if (versioned == null) return null;
            String name = versioned.getName();
            if (!name.startsWith("opencode-v")) return null;
            String v = name.substring("opencode-v".length());
            return v.isEmpty() ? null : v;
        }

        static String loaderName(String arch) {
            if (arch == null) return "ld-linux-aarch64.so.1";
            switch (arch) {
                case "arm": return "ld-linux-armhf.so.3";
                case "x64": return "ld-linux-x86-64.so.2";
                case "x86": return "ld-linux.so.2";
                case "arm64":
                default: return "ld-linux-aarch64.so.1";
            }
        }
    }

    private final Paths paths;
    private final Source source;
    private final Http http;
    private final Runner runner;
    private final Progress progress;
    private final String arch;
    private final int sdkInt;
    private final long updateTtlMillis;

    public OpenCodeInstaller(Paths paths, Source source, Http http, Runner runner,
                             Progress progress, String arch, int sdkInt) {
        this(paths, source, http, runner, progress, arch, sdkInt, UPDATE_TTL_MILLIS);
    }

    OpenCodeInstaller(Paths paths, Source source, Http http, Runner runner,
                      Progress progress, String arch, int sdkInt, long updateTtlMillis) {
        this.paths = paths;
        this.source = source;
        this.http = http;
        this.runner = runner;
        this.progress = progress;
        this.arch = arch;
        this.sdkInt = sdkInt;
        this.updateTtlMillis = updateTtlMillis;
    }

    /**
     * Ensure a working, up-to-date binary is installed.
     *
     * @param reinstall force a fresh download even if the installed binary runs
     * @param update    force an update check even if the cached check is fresh
     */
    public Result ensure(boolean reinstall, boolean update) {
        if (paths.prefix == null || !new File(paths.prefix, "bin").isDirectory()) {
            return fail("Termux prefix is not ready at " + paths.prefix);
        }
        if (paths.aptGet == null || !paths.aptGet.isFile()) {
            return fail("apt-get is missing from " + paths.prefix);
        }

        FileLock lock = null;
        FileChannel channel = null;
        try {
            //noinspection ResultOfMethodCallIgnored
            paths.homeDir.mkdirs();
            channel = new RandomAccessFile(paths.lockFile, "rw").getChannel();
            lock = channel.tryLock();
            if (lock == null) {
                try {
                    channel.close();
                } catch (Exception ignored) {
                }
                return new Result(Outcome.BUSY, null, "Another OpenCode install is already running");
            }
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return new Result(Outcome.BUSY, null, "Another OpenCode install is already running");
        } catch (Exception e) {
            // A lock is best-effort (some filesystems refuse locks); continue unguarded.
            log("lock unavailable: " + e.getMessage());
        }

        try {
            return runLocked(reinstall, update);
        } catch (Exception e) {
            return fail("Unexpected error: " + e);
        } finally {
            try {
                if (lock != null) lock.release();
            } catch (Exception ignored) {
            }
            try {
                if (channel != null) channel.close();
            } catch (Exception ignored) {
            }
        }
    }

    private Result runLocked(boolean reinstall, boolean update) {
        // The launcher and terminal integration are owned by Java now: refresh the
        // wrapper and drop any legacy profile.d shell bootstrap on every run,
        // including the up-to-date fast path.
        removeLegacyBootstrap();
        ensureWrapper();

        // Installs made by older builds kept the patched ELF at bin/opencode
        // itself. Adopt it: the binary moves into installed/ and a symlink takes
        // its place, so from now on an update only ever moves the symlink and the
        // patched file is never rewritten in place.
        String current = installedVersion();
        adoptLegacyInstall(current);

        // Track what is really installed: the binary runs directly only when its
        // ELF interpreter is still the glibc loader we patched.
        boolean binOk = verifyDirect(paths.bin);

        // Keep the launcher's "this binary is patched" marker in sync whenever
        // the installed binary really runs directly. This heals the marker if
        // the binary was swapped by something that still starts (e.g.
        // `opencode upgrade`), so the launcher stops taking the loader fallback.
        if (binOk) writePatchedMarker();

        // `~/.opencode/pin` holds a single version (e.g. "2.0.27"). It keeps the
        // app on an exact build even if the release channel moves on, and allows
        // an explicit downgrade. Anything after the first token is ignored.
        String pinned = readString(paths.pinFile);
        if (pinned != null) {
            String[] tokens = pinned.split("\\s+");
            pinned = (tokens.length > 0 && !tokens[0].isEmpty()) ? tokens[0] : null;
        }

        if (!reinstall && !update && pinned == null && binOk && current != null && !checkExpired()) {
            return upToDate(current, "Cached check is fresh");
        }

        Release release;
        try {
            state("checking", "Đang kiểm tra phiên bản OpenCode…");
            release = (pinned != null) ? source.version(http, arch, pinned) : source.latest(http, arch);
        } catch (IOException e) {
            if (binOk) {
                return upToDate(current, "Metadata unavailable: " + e.getMessage());
            }
            return fail("Không lấy được thông tin bản phát hành: " + e.getMessage());
        }

        if (release == null || release.version == null || release.version.isEmpty()) {
            if (binOk) return upToDate(current, "Metadata has no version");
            return fail("Thông tin bản phát hành thiếu version.");
        }

        // With a pin, "already there" means the installed version equals the
        // pin (which may point at an older build, so a downgrade is allowed).
        // Without one, only a strictly newer release triggers work.
        boolean targetReached = (pinned != null)
            ? (current != null && OpenCodeSemVer.compare(pinned, current) == 0)
            : !OpenCodeSemVer.isNewer(release.version, current);
        if (!reinstall && binOk && current != null && targetReached) {
            writeLastCheck();
            return upToDate(current, null);
        }

        if (!ensureDependencies()) {
            return failWithSdkHint("Không cài được glibc runtime / patchelf.");
        }

        File work = new File(paths.tmpDir, "oc-" + System.nanoTime());
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();
        try {
            File tarball = new File(work, "cli.tgz");
            state("download", "Đang tải OpenCode v" + release.version + " (" + arch + ")…");
            http.download(release.tarballUrl, tarball, progress);

            if (release.integrity != null && !release.integrity.isEmpty()) {
                String actual;
                try (InputStream in = new FileInputStream(tarball)) {
                    actual = "sha512-" + OpenCodeHash.sha512Base64(in);
                }
                if (!release.integrity.equals(actual)) {
                    return fail("Tệp tải về không toàn vẹn (sha512 không khớp).");
                }
            }

            File extracted = new File(work, "opencode");
            if (!extractBinary(tarball, "package/bin/opencode", extracted)) {
                return fail("Gói tải về thiếu package/bin/opencode.");
            }

            //noinspection ResultOfMethodCallIgnored
            paths.binDir.mkdirs();
            delete(paths.staged);
            copy(extracted, paths.staged);
            setExecutable(paths.staged);

            if (!configure(paths.staged)) {
                return failWithSdkHint("Không cấu hình được OpenCode để chạy trực tiếp.");
            }
            if (!installStaged(release.version)) {
                return fail("Không cài đặt được binary mới.");
            }

            writeString(paths.versionFile, release.version);
            writeLastCheck();
            writePatchedMarker();
            state("ok", "OpenCode sẵn sàng (v" + release.version + ").");
            // A running server would otherwise keep serving the old build while
            // the TUI runs the new one, and the v2 API changes often. Only do
            // this when we are replacing an existing binary. Done after the "ok"
            // state so a slow restart cannot hold up the success UI.
            if (binOk) restartBackgroundService();
            return new Result(binOk ? Outcome.UPDATED : Outcome.INSTALLED, release.version, null);
        } catch (IOException e) {
            return fail("Tải/cài đặt thất bại: " + e.getMessage());
        } finally {
            deleteRec(work);
        }
    }

    private Result upToDate(String version, String note) {
        String label = (version == null || version.isEmpty()) ? "" : (" (v" + version + ")");
        state("ok", "OpenCode đã sẵn sàng" + label + ".");
        return new Result(Outcome.UP_TO_DATE, version, note);
    }

    private Result fail(String message) {
        state("error", message);
        log("ERROR: " + message);
        return new Result(Outcome.FAILED, null, message);
    }

    private Result failWithSdkHint(String message) {
        if (sdkInt == 29 || sdkInt == 30) {
            message += " Thiết bị này chạy Android 10/11 (API " + sdkInt
                + ") vốn không tương thích với runtime Bun của OpenCode; "
                + "hãy thử lại, hoặc dùng thiết bị Android 12 trở lên.";
        }
        return fail(message);
    }

    // ------------------------------------------------------------------
    // Dependencies: one apt-get bootstrap (glibc runtime + patchelf).
    // ------------------------------------------------------------------

    private boolean ensureDependencies() {
        if (paths.loader.isFile() && paths.patchelf() != null) return true;
        state("deps", "Đang cài glibc runtime & patchelf…");
        if (paths.loader.isFile() || new File(paths.prefix, "bin/glibc-runner").isFile()) {
            // Runtime already present, only patchelf is missing.
            return apt("install", "patchelf");
        }
        return apt("update")
            && apt("-o", "Dpkg::Options::=--force-confnew", "full-upgrade")
            && apt("install", "glibc-repo")
            && apt("update")
            && apt("install", "glibc-runner", "patchelf");
    }

    private boolean apt(String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(paths.aptGet.getAbsolutePath());
        argv.add("-y");
        argv.addAll(Arrays.asList(args));
        return run(argv, paths.home, bionicEnv());
    }

    /**
     * Environment for bionic Termux programs (apt-get). Keeps
     * {@code libtermux-exec.so} preloaded so package maintainer scripts with
     * {@code #!/bin/sh} shebangs resolve correctly.
     */
    Map<String, String> bionicEnv() {
        Map<String, String> env = baseEnv();
        File termuxExec = new File(paths.prefix, "lib/libtermux-exec.so");
        if (termuxExec.isFile()) env.put("LD_PRELOAD", termuxExec.getAbsolutePath());
        env.put("DEBIAN_FRONTEND", "noninteractive");
        return env;
    }

    /** Clean environment for glibc programs (patchelf, the opencode binary). */
    Map<String, String> cleanEnv() {
        return baseEnv();
    }

    private Map<String, String> baseEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", paths.prefix.getPath() + "/bin:/system/bin");
        env.put("HOME", paths.home.getPath());
        env.put("TMPDIR", paths.tmpDir.getPath());
        env.put("LANG", "C.UTF-8");
        return env;
    }

    // ------------------------------------------------------------------
    // Download / extract.
    // ------------------------------------------------------------------

    /** Extract a single entry from a gzipped tar into {@code dest}. */
    static boolean extractBinary(final File tarball, final String entry, final File dest) throws IOException {
        final boolean[] found = {false};
        try (InputStream in = new FileInputStream(tarball)) {
            OpenCodeTar.extractGz(in, new OpenCodeTar.EntryHandler() {
                @Override
                public OutputStream onEntry(String path, long size) throws IOException {
                    if (!entry.equals(path)) return null;
                    found[0] = true;
                    return new FileOutputStream(dest);
                }
            });
        }
        return found[0];
    }

    // ------------------------------------------------------------------
    // ELF interpreter patching (the only place patchelf is used).
    // ------------------------------------------------------------------

    private boolean configure(File target) {
        if (!paths.loader.isFile()) {
            log("glibc loader missing at " + paths.loader);
            return false;
        }
        File patchelf = paths.patchelf();
        if (patchelf == null) {
            log("patchelf missing");
            return false;
        }

        String current = printInterpreter(patchelf, target);
        if (paths.loader.getAbsolutePath().equals(current) && verifyDirect(target)) {
            return true;
        }

        state("configure", "Đang cấu hình OpenCode chạy trực tiếp…");
        boolean patched = run(Arrays.asList(
            patchelf.getAbsolutePath(),
            "--set-interpreter", paths.loader.getAbsolutePath(),
            target.getAbsolutePath()), paths.home, cleanEnv());
        if (!patched) return false;
        return verifyDirect(target);
    }

    private String printInterpreter(File patchelf, File target) {
        final StringBuilder out = new StringBuilder();
        List<String> argv = Arrays.asList(patchelf.getAbsolutePath(), "--print-interpreter",
            target.getAbsolutePath());
        try {
            runner.run(argv, paths.home, cleanEnv(), new LineSink() {
                @Override
                public void line(String line) {
                    if (out.length() > 0) out.append('\n');
                    out.append(line);
                }
            });
        } catch (IOException e) {
            return null;
        }
        String s = out.toString().trim();
        return s.isEmpty() ? null : s;
    }

    // ------------------------------------------------------------------
    // Staging / install.
    // ------------------------------------------------------------------

    /**
     * Publish the verified staged binary as the build for {@code version} and
     * point {@code bin/opencode} at it.
     *
     * <p>The staged file is moved (not copied) into
     * {@code ~/.opencode/installed/opencode-v<version>}, keeping the exact ELF we
     * just patched and verified. The launcher path itself is only ever a symlink,
     * swapped in with a same-directory {@code rename(2)}, so a crash mid-swap can
     * never leave the launcher with no binary at all and the patched build stays
     * intact even if an update is interrupted.</p>
     */
    private boolean installStaged(String version) {
        if (!paths.staged.isFile()) return false;
        if (!paths.installedDir.isDirectory() && !paths.installedDir.mkdirs()) {
            log("could not create " + paths.installedDir);
            return false;
        }
        File versioned = paths.versionedBinary(version);
        delete(versioned); // re-install of the same version
        if (!paths.staged.renameTo(versioned)) {
            log("could not move staged binary into place");
            return false;
        }
        setExecutable(versioned);
        if (!pointBinAt(versioned)) return false;
        pruneInstalled(versioned);
        return true;
    }

    /**
     * Adopt an install from a build that kept the patched ELF at {@code bin/opencode}
     * itself: move the binary into {@code installed/} under its version and leave a
     * symlink in its place. Best-effort — if anything fails the legacy layout is
     * put back and the next update replaces {@code bin/opencode} directly.
     */
    private void adoptLegacyInstall(String version) {
        File bin = paths.bin;
        if (bin == null || !bin.isFile() || isSymlink(bin)) return;
        if (!paths.installedDir.isDirectory() && !paths.installedDir.mkdirs()) {
            log("could not create " + paths.installedDir + "; keeping the binary in place");
            return;
        }
        String stamp = (version != null && !version.isEmpty())
            ? version : "unknown-" + System.nanoTime();
        File versioned = paths.versionedBinary(stamp);
        delete(versioned);
        if (!bin.renameTo(versioned)) {
            log("could not adopt the installed binary; keeping it in place");
            return;
        }
        if (!pointBinAt(versioned)) {
            if (!versioned.renameTo(bin)) log("could not restore " + bin.getAbsolutePath());
            log("could not link " + bin.getAbsolutePath() + " to " + versioned.getName());
            return;
        }
        log("adopted the installed binary as " + versioned.getName());
    }

    /**
     * Make {@code ~/.opencode/bin/opencode} point at {@code target} atomically.
     *
     * <p>The new link is created next to the destination and renamed over it, a
     * single {@code rename(2)}: a reader sees the old target or the new one, never
     * a missing path.</p>
     */
    private boolean pointBinAt(File target) {
        File tmp = new File(paths.binDir, "opencode.link.new");
        delete(tmp);
        if (!createSymlink(tmp, target)) {
            log("could not create symlink " + tmp + " -> " + target);
            delete(tmp);
            return false;
        }
        if (tmp.renameTo(paths.bin)) return true;
        // rename(2) normally replaces the old symlink; if the filesystem refuses,
        // drop the old link and try once more.
        delete(paths.bin);
        if (tmp.renameTo(paths.bin)) return true;
        log("could not move symlink into place");
        delete(tmp);
        return false;
    }

    /**
     * Create {@code link} as a symlink to {@code target}.
     *
     * <p>{@code java.nio.file.Files} only exists from Android 8 (API 26) on, so it
     * is reached reflectively; on older devices the {@code ln} binary is used.</p>
     */
    private boolean createSymlink(File link, File target) {
        if (createSymlinkNio(link, target)) return true;
        File ln = new File(paths.prefix, "bin/ln");
        if (!ln.isFile()) ln = new File("/system/bin/ln");
        return run(Arrays.asList(ln.getAbsolutePath(), "-s", target.getAbsolutePath(),
            link.getAbsolutePath()), paths.home, cleanEnv());
    }

    /**
     * {@code Files.createSymbolicLink} without a compile-time reference to
     * {@code java.nio.file} (and to {@code File.toPath}), which are absent below
     * API 26. Returns {@code false} when unavailable or on any failure.
     */
    static boolean createSymlinkNio(File link, File target) {
        try {
            Class<?> filesClass = Class.forName("java.nio.file.Files");
            Class<?> pathClass = Class.forName("java.nio.file.Path");
            Class<?> attrClass = Class.forName("java.nio.file.attribute.FileAttribute");
            Object attrs = java.lang.reflect.Array.newInstance(attrClass, 0);
            java.lang.reflect.Method toPath = File.class.getMethod("toPath");
            java.lang.reflect.Method create = filesClass.getMethod("createSymbolicLink",
                pathClass, pathClass, attrs.getClass());
            create.invoke(null, toPath.invoke(link), toPath.invoke(target), attrs);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when {@code f} is a symbolic link (works without {@code java.nio.file}). */
    static boolean isSymlink(File f) {
        if (f == null) return false;
        try {
            File parent = f.getParentFile();
            File absolute = (parent == null) ? f : new File(parent.getCanonicalFile(), f.getName());
            return !absolute.getCanonicalFile().equals(absolute.getAbsoluteFile());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Drop old patched builds, keeping the newest {@link #RETAINED_VERSIONS}
     * (the current one plus the newest others) so a bad update can be rolled back
     * by pointing the symlink at the previous build. Best-effort: a file that
     * cannot be deleted simply stays.
     */
    private void pruneInstalled(File current) {
        File[] kids = paths.installedDir.listFiles();
        if (kids == null) return;
        List<File> rest = new ArrayList<>();
        for (File kid : kids) {
            if (kid.isFile() && !kid.equals(current) && Paths.versionOf(kid) != null) rest.add(kid);
        }
        if (rest.size() <= RETAINED_VERSIONS - 1) return;
        Collections.sort(rest, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                int c = OpenCodeSemVer.compare(Paths.versionOf(a), Paths.versionOf(b));
                return (c != 0) ? -c : a.getName().compareTo(b.getName());
            }
        });
        for (int i = RETAINED_VERSIONS - 1; i < rest.size(); i++) {
            File old = rest.get(i);
            if (old.delete()) log("removed old build " + old.getName());
            else log("could not remove old build " + old.getAbsolutePath());
        }
    }

    /**
     * Write (or refresh) the {@code $PREFIX/bin/opencode} launcher.
     *
     * <p>Java owns this file now; the POSIX bootstrap is gone. The launcher is
     * rewritten whenever it is missing, carries a different marker (e.g. a legacy
     * {@code opencode-native-wrapper-vN} script), or the marker was bumped.</p>
     */
    private void ensureWrapper() {
        File w = paths.wrapper;
        if (w.isFile()) {
            String existing = readString(w);
            if (existing != null && existing.contains(WRAPPER_MARKER)) return;
        }
        try {
            writeString(w, wrapperScript());
            setExecutable(w);
        } catch (IOException e) {
            log("could not write wrapper: " + e.getMessage());
        }
    }

    /**
     * The managed launcher (terminal-first). There is no native chat UI on
     * this branch: a bare interactive call (or any argument) simply execs the
     * patched binary so the original TUI runs in the terminal.
     *
     * <p>{@code $BIN} is a symlink into {@code ~/.opencode/installed}; the
     * launcher's marker check therefore dereferences it ({@code stat -L}) to
     * compare against the real patched file. The launcher is also the first line
     * of self-defence: {@code opencode upgrade} (or a glibc update) can leave a
     * binary whose ELF interpreter is no longer the Termux glibc loader. Rather
     * than failing with a cryptic error it falls back to invoking the loader
     * explicitly, which works for both patched and unpatched binaries.</p>
     *
     * <p>The file is run by {@code dash} (Termux's {@code /bin/sh}), so the
     * script sticks to POSIX shell.</p>
     */
    String wrapperScript() {
        String bin = paths.bin.getAbsolutePath();
        String loader = paths.loader.getAbsolutePath();
        String glibcLib = paths.glibcLib.getAbsolutePath();
        String patched = paths.patchedFile.getAbsolutePath();
        // Absolute paths to coreutils, so the check also works when PATH is
        // minimal (the app spawns the CLI with a trimmed environment).
        String stat = new File(paths.prefix, "bin/stat").getAbsolutePath();
        String cat = new File(paths.prefix, "bin/cat").getAbsolutePath();

        StringBuilder sb = new StringBuilder();
        sb.append("#!").append(paths.prefix.getAbsolutePath()).append("/bin/sh\n");
        sb.append("# ").append(WRAPPER_MARKER).append(" (managed by OpenCodeInstaller)\n");
        sb.append("BIN=\"").append(bin).append("\"\n");
        sb.append("LOADER=\"").append(loader).append("\"\n");
        sb.append("GLIBCLIB=\"").append(glibcLib).append("\"\n");
        sb.append("PATCHED=\"").append(patched).append("\"\n");
        sb.append("STAT=\"").append(stat).append("\"\n");
        sb.append("CAT=\"").append(cat).append("\"\n");
        sb.append("\n");
        sb.append("if [ ! -x \"$BIN\" ]; then\n");
        sb.append("  echo \"opencode: chưa cài xong — mở lại app Termux để tự cài đặt.\" >&2\n");
        sb.append("  exit 127\n");
        sb.append("fi\n");
        sb.append("\n");
        sb.append("# The binary must be started through the Termux glibc loader. If the\n");
        sb.append("# runtime was removed, say so instead of a meaningless \"not found\".\n");
        sb.append("if [ ! -x \"$LOADER\" ]; then\n");
        sb.append("  echo \"opencode: thiếu glibc runtime — mở lại app Termux để cài lại.\" >&2\n");
        sb.append("  exit 127\n");
        sb.append("fi\n");
        sb.append("\n");
        sb.append("# The marker records \"<size> <mtime>\" of the binary whose interpreter we\n");
        sb.append("# patched. `-L` follows $BIN, which is a symlink to the current build\n");
        sb.append("# under ~/.opencode/installed. `opencode upgrade` (or a glibc update) can\n");
        sb.append("# overwrite it; then a bare exec dies with a confusing error, so invoke the\n");
        sb.append("# loader explicitly.\n");
        sb.append("MARK=$(\"$CAT\" \"$PATCHED\" 2>/dev/null)\n");
        sb.append("NOW=$(\"$STAT\" -L -c '%s %Y' \"$BIN\" 2>/dev/null)\n");
        sb.append("if [ -z \"$MARK\" ] || [ \"$MARK\" != \"$NOW\" ]; then\n");
        sb.append("  echo \"opencode: binary chưa được vá — chạy tạm qua loader; mở lại app Termux để vá lại.\" >&2\n");
        sb.append("  export OPENCODE_DISABLE_AUTOUPDATE=1\n");
        sb.append("  unset LD_PRELOAD\n");
        sb.append("  unset LD_LIBRARY_PATH\n");
        sb.append("  exec \"$LOADER\" --library-path \"$GLIBCLIB\" \"$BIN\" \"$@\"\n");
        sb.append("fi\n");
        sb.append("\n");
        sb.append("export OPENCODE_DISABLE_AUTOUPDATE=1\n");
        sb.append("# Termux preloads the bionic libtermux-exec; the glibc loader would\n");
        sb.append("# otherwise try to load the bionic libc.so and fail.\n");
        sb.append("unset LD_PRELOAD\n");
        sb.append("unset LD_LIBRARY_PATH\n");
        sb.append("exec \"$BIN\" \"$@\"\n");
        return sb.toString();
    }

    /**
     * Record {@code <size> <mtime-in-seconds>} of the binary that currently runs
     * directly, so the launcher can tell whether it is still the build we
     * patched. {@code File.length()}/{@code lastModified()} follow the
     * {@code bin/opencode} symlink, mirroring {@code stat -L -c '%s %Y'} in
     * {@link #wrapperScript()}.
     */
    private void writePatchedMarker() {
        File bin = paths.bin;
        if (bin == null || !bin.isFile()) return;
        long size = bin.length();
        // mtime is positive (post-1970), so integer division is floor division.
        long mtimeSeconds = bin.lastModified() / 1000L;
        try {
            writeString(paths.patchedFile, size + " " + mtimeSeconds);
        } catch (IOException e) {
            log("could not write patch marker: " + e.getMessage());
        }
    }

    /**
     * Ask the shared background service to restart after the binary changed, so
     * a previously running server does not keep serving the old build while the
     * TUI runs the new one. Best-effort: a missing or unreachable service is
     * not an install failure.
     */
    private void restartBackgroundService() {
        if (!paths.bin.isFile()) return;
        log("restarting OpenCode background service…");
        run(Arrays.asList(paths.bin.getAbsolutePath(), "service", "restart"), paths.home, cleanEnv());
    }

    /** Delete the legacy POSIX bootstrap so login shells stop re-installing it. */
    private void removeLegacyBootstrap() {
        File legacy = new File(paths.prefix, "etc/profile.d/10-opencode.sh");
        if (legacy.isFile() && legacy.delete()) {
            log("removed legacy shell bootstrap " + legacy);
        }
    }

    // ------------------------------------------------------------------
    // Version probing / bookkeeping.
    // ------------------------------------------------------------------

    /** Version reported by running the installed binary, or {@code null}. */
    String installedVersion() {
        String probed = probeVersion(paths.bin);
        if (probed != null) return probed;
        return readString(paths.versionFile);
    }

    /** Run {@code bin --version} and parse {@code x.y.z}, or {@code null} if it fails. */
    private String probeVersion(File bin) {
        if (bin == null || !bin.isFile()) return null;
        final StringBuilder out = new StringBuilder();
        try {
            int code = runner.run(Arrays.asList(bin.getAbsolutePath(), "--version"),
                paths.home, cleanEnv(), new LineSink() {
                    @Override
                    public void line(String line) {
                        if (out.length() > 0) out.append('\n');
                        out.append(line);
                    }
                });
            if (code != 0) return null;
        } catch (IOException e) {
            return null;
        }
        return OpenCodeSemVer.parse(out.toString());
    }

    /** True if {@code bin} starts directly (no loader, no proot). */
    private boolean verifyDirect(File bin) {
        if (bin == null || !bin.isFile()) return false;
        try {
            return runner.run(versionArgv(bin), paths.home, cleanEnv(), null) == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> versionArgv(File bin) {
        return Arrays.asList(bin.getAbsolutePath(), "--version");
    }

    private boolean checkExpired() {
        String raw = readString(paths.lastCheckFile);
        if (raw == null) return true;
        try {
            long last = Long.parseLong(raw.trim());
            return System.currentTimeMillis() - last >= updateTtlMillis;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private void writeLastCheck() {
        try {
            writeString(paths.lastCheckFile, String.valueOf(System.currentTimeMillis()));
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Small helpers.
    // ------------------------------------------------------------------

    private boolean run(List<String> argv, File cwd, Map<String, String> env) {
        log("$ " + join(argv));
        try {
            int code = runner.run(argv, cwd, env, new LineSink() {
                @Override
                public void line(String line) {
                    progress.log(line);
                }
            });
            if (code != 0) log("exit " + code);
            return code == 0;
        } catch (IOException e) {
            log("command failed: " + e.getMessage());
            return false;
        }
    }

    private void state(String code, String detail) {
        progress.state(code, detail);
    }

    private void log(String line) {
        progress.log(line);
    }

    private static String join(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (String a : argv) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(a);
        }
        return sb.toString();
    }

    static void delete(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRec(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    static void setExecutable(File f) {
        //noinspection ResultOfMethodCallIgnored
        f.setExecutable(true, false);
        //noinspection ResultOfMethodCallIgnored
        f.setReadable(true, false);
    }

    static String readString(File f) {
        if (f == null || !f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            String s = sb.toString().trim();
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            return null;
        }
    }

    static void writeString(File f, String content) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (OutputStream out = new FileOutputStream(f, false)) {
            out.write(content.getBytes("UTF-8"));
        }
    }

    // ------------------------------------------------------------------
    // Default implementations (still pure Java).
    // ------------------------------------------------------------------

    /** ProcessBuilder-backed {@link Runner}. */
    public static final class ProcessRunner implements Runner {
        @Override
        public int run(List<String> argv, File cwd, Map<String, String> env, LineSink out) throws IOException {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.environment().clear();
            if (env != null) pb.environment().putAll(env);
            if (cwd != null) pb.directory(cwd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Close the child's stdin (Redirect is API 26+). Many tools treat EOF
            // as "no more input", which is what we want for apt-get/patchelf.
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out != null) out.line(line);
                }
            }
            try {
                return p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }

    /** HttpURLConnection-backed {@link Http}. */
    public static final class UrlHttp implements Http {
        @Override
        public String getString(String url, int maxBytes) throws IOException {
            HttpURLConnection c = open(url, "application/json");
            try (InputStream in = c.getInputStream()) {
                byte[] data = readAll(in, maxBytes);
                return new String(data, "UTF-8");
            } finally {
                c.disconnect();
            }
        }

        @Override
        public void download(String url, File dest, Progress progress) throws IOException {
            HttpURLConnection c = open(url, "application/octet-stream");
            try (InputStream in = c.getInputStream();
                 OutputStream out = new FileOutputStream(dest)) {
                long total = c.getContentLengthLong();
                byte[] buf = new byte[65536];
                long done = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null) progress.download(done, total);
                }
            } finally {
                c.disconnect();
            }
        }

        private static HttpURLConnection open(String url, String accept) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(120000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "oct-opencode-installer");
            c.setRequestProperty("Accept", accept);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                c.disconnect();
                throw new IOException("HTTP " + code + " for " + url);
            }
            return c;
        }

        private static byte[] readAll(InputStream in, int maxBytes) throws IOException {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() >= maxBytes) break;
            }
            return bos.toByteArray();
        }
    }

    /**
     * Official OpenCode npm releases. Resolves the platform package
     * ({@code @opencode/cli-linux-<arch>}), its tarball URL and its
     * {@code sha512} integrity from the registry manifest.
     */
    public static final class NpmSource implements Source {
        static final String UPDATE_ENDPOINT = "https://opencode.ai/update/api/latest/cli/npm";
        static final String REGISTRY = "https://registry.npmjs.org";
        static final String DEFAULT_PACKAGE = "@opencode/cli";

        @Override
        public Release latest(Http http, String arch) throws IOException {
            String meta = http.getString(UPDATE_ENDPOINT, 1 << 16);
            String version = OpenCodeJson.string(meta, "version");
            if (version == null || version.isEmpty()) {
                throw new IOException("update endpoint returned no version");
            }
            String pkg = OpenCodeJson.string(meta, "package");
            if (pkg == null || pkg.isEmpty()) pkg = DEFAULT_PACKAGE;
            return resolve(http, arch, pkg, version);
        }

        @Override
        public Release version(Http http, String arch, String version) throws IOException {
            if (version == null || version.isEmpty()) {
                throw new IOException("no pinned version");
            }
            // The update endpoint is the only place the package name is
            // published; consult it for the name only, and tolerate it being
            // unavailable (offline pinning should still work).
            String pkg = DEFAULT_PACKAGE;
            try {
                String meta = http.getString(UPDATE_ENDPOINT, 1 << 16);
                String p = OpenCodeJson.string(meta, "package");
                if (p != null && !p.isEmpty()) pkg = p;
            } catch (IOException ignored) {
                // Offline or endpoint down: the default package name is fine.
            }
            return resolve(http, arch, pkg, version);
        }

        private static Release resolve(Http http, String arch, String pkg, String version)
            throws IOException {
            String target = "linux-" + arch;
            String scope = scopeOf(pkg);                 // "@opencode"
            String platform = scope + "/cli-" + target;  // "@opencode/cli-linux-arm64"

            String manifest = http.getString(REGISTRY + "/" + platform + "/" + version, 1 << 20);
            String integrity = OpenCodeJson.string(manifest, "integrity");
            String tarball = OpenCodeJson.string(manifest, "tarball");
            if (tarball == null || tarball.isEmpty()) {
                tarball = REGISTRY + "/" + platform + "/-/cli-" + target + "-" + version + ".tgz";
            }
            return new Release(version, tarball, integrity);
        }

        private static String scopeOf(String pkg) {
            if (pkg.startsWith("@")) {
                int slash = pkg.indexOf('/');
                if (slash > 0) return pkg.substring(0, slash);
            }
            return "@opencode";
        }
    }
}
