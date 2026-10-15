package com.termux.app.opencode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class OpenCodeInstallerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File prefix;
    private File home;
    private File loader;
    private OpenCodeInstaller.Paths paths;

    private FakeRunner runner;
    private FakeHttp http;
    private FakeSource source;

    private byte[] tarBytes;

    @Before
    public void setUp() throws Exception {
        prefix = tmp.newFolder("usr");
        home = tmp.newFolder("home");
        // prefix/bin + apt-get
        assertTrue(new File(prefix, "bin").mkdirs());
        assertTrue(new File(prefix, "bin/apt-get").createNewFile());
        assertTrue(new File(prefix, "tmp").mkdirs());
        // glibc runtime + patchelf
        File glibcLib = new File(prefix, "glibc/lib");
        assertTrue(glibcLib.mkdirs());
        loader = new File(glibcLib, "ld-linux-aarch64.so.1");
        assertTrue(loader.createNewFile());
        assertTrue(new File(prefix, "glibc/bin").mkdirs());
        assertTrue(new File(prefix, "glibc/bin/patchelf").createNewFile());

        paths = OpenCodeInstaller.Paths.of(prefix, home, "arm64");
        assertTrue(paths.binDir.mkdirs());

        runner = new FakeRunner(loader.getAbsolutePath());
        http = new FakeHttp();
        source = new FakeSource();

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("package/package.json", "{}".getBytes("UTF-8"));
        files.put("package/bin/opencode", "ELF-BINARY".getBytes("UTF-8"));
        tarBytes = TarTestUtil.gzipTar(files);
    }

    private OpenCodeInstaller installer() {
        return new OpenCodeInstaller(paths, source, http, runner, new SilentProgress(), "arm64", 34);
    }

    private static String integrityOf(byte[] data) throws IOException {
        return "sha512-" + OpenCodeHash.sha512Base64(new java.io.ByteArrayInputStream(data));
    }

    @Test
    public void installsFreshBinary() throws Exception {
        http.bytes = tarBytes;
        source.release = new OpenCodeInstaller.Release(
            "2.0.27", "https://example.test/cli.tgz", integrityOf(tarBytes));

        OpenCodeInstaller.Result result = installer().ensure(false, false);

        assertEquals(OpenCodeInstaller.Outcome.INSTALLED, result.outcome);
        assertEquals("2.0.27", result.version);
        assertTrue(paths.bin.isFile());
        assertFalse(paths.staged.exists());
        assertEquals("2.0.27", OpenCodeInstaller.readString(paths.versionFile));
        // A wrapper was generated because none existed.
        assertTrue(paths.wrapper.isFile());
    }

    @Test
    public void wrapperExecsBinaryInTerminal() {
        // wrapperScript() is pure text; on the terminal-first branch there is no
        // native chat, so a Termux-style prefix must NOT emit the am-start block.
        OpenCodeInstaller.Paths termuxPaths = OpenCodeInstaller.Paths.of(
            new File("/data/data/com.termux/files/usr"),
            new File("/data/data/com.termux/files/home"), "arm64");
        OpenCodeInstaller inst = new OpenCodeInstaller(termuxPaths, source, http, runner,
            new SilentProgress(), "arm64", 34);
        String wrapper = inst.wrapperScript();
        assertTrue(wrapper.contains(OpenCodeInstaller.WRAPPER_MARKER));
        // The binary is exec'd directly so the original TUI runs in the terminal.
        assertTrue(wrapper.contains("exec \"$BIN\" \"$@\""));
        // No native chat activity exists on this branch.
        assertFalse(wrapper.contains("am start"));
        assertFalse(wrapper.contains("OpenCodeChatActivity"));
        // The glibc environment is cleaned before executing the binary.
        assertTrue(wrapper.contains("unset LD_PRELOAD"));
        assertTrue(wrapper.contains("unset LD_LIBRARY_PATH"));
        // Self-healing: if the binary is no longer the one we patched (e.g. after
        // `opencode upgrade`), fall back to running it through the loader.
        assertTrue(wrapper.contains("LOADER=\""));
        assertTrue(wrapper.contains("--library-path \"$GLIBCLIB\""));
        assertTrue(wrapper.contains("PATCHED=\""));
    }

    @Test
    public void writesPatchMarkerAfterInstall() throws Exception {
        http.bytes = tarBytes;
        source.release = new OpenCodeInstaller.Release(
            "2.0.27", "https://example.test/cli.tgz", integrityOf(tarBytes));

        installer().ensure(false, false);

        assertTrue(paths.bin.isFile());
        assertTrue(paths.patchedFile.isFile());
        String expected = paths.bin.length() + " "
            + (paths.bin.lastModified() / 1000L);
        assertEquals(expected, OpenCodeInstaller.readString(paths.patchedFile));
    }

    @Test
    public void shortCircuitsWhenFreshAndRunnable() throws Exception {
        assertTrue(paths.bin.createNewFile());
        runner.runnable.add(paths.bin.getAbsolutePath());
        OpenCodeInstaller.writeString(paths.versionFile, "2.0.27");
        OpenCodeInstaller.writeString(paths.lastCheckFile, String.valueOf(System.currentTimeMillis()));
        source.fail = true; // must not be consulted

        OpenCodeInstaller.Result result = installer().ensure(false, false);

        assertEquals(OpenCodeInstaller.Outcome.UP_TO_DATE, result.outcome);
        assertEquals("2.0.27", result.version);
        assertFalse(source.called);
    }

    @Test
    public void replacesLegacyWrapperEvenWhenBinaryIsCurrent() throws Exception {
        assertTrue(paths.bin.createNewFile());
        runner.runnable.add(paths.bin.getAbsolutePath());
        OpenCodeInstaller.writeString(paths.versionFile, "2.0.27");
        OpenCodeInstaller.writeString(paths.lastCheckFile, String.valueOf(System.currentTimeMillis()));
        // A leftover legacy wrapper (no Java marker) must be replaced.
        OpenCodeInstaller.writeString(paths.wrapper, "#!/bin/sh\nexec old-opencode \"$@\"\n");

        OpenCodeInstaller.Result result = installer().ensure(false, false);

        assertEquals(OpenCodeInstaller.Outcome.UP_TO_DATE, result.outcome);
        String wrapper = OpenCodeInstaller.readString(paths.wrapper);
        assertNotNull(wrapper);
        assertTrue(wrapper.contains(OpenCodeInstaller.WRAPPER_MARKER));
    }

    @Test
    public void updatesWhenNewerReleaseFound() throws Exception {
        assertTrue(paths.bin.createNewFile());
        runner.runnable.add(paths.bin.getAbsolutePath());
        runner.versionOutput = "2.0.26";
        OpenCodeInstaller.writeString(paths.versionFile, "2.0.26");
        OpenCodeInstaller.writeString(paths.lastCheckFile, "0");
        http.bytes = tarBytes;
        source.release = new OpenCodeInstaller.Release(
            "2.0.27", "https://example.test/cli.tgz", integrityOf(tarBytes));

        OpenCodeInstaller.Result result = installer().ensure(false, false);

        assertEquals(OpenCodeInstaller.Outcome.UPDATED, result.outcome);
        assertEquals("2.0.27", OpenCodeInstaller.readString(paths.versionFile));
    }

    @Test
    public void pinnedVersionIsInstalledEvenWhenOlderThanCurrent() throws Exception {
        // An installed, directly-runnable 2.0.27 with a fresh check cache...
        assertTrue(paths.bin.createNewFile());
        runner.runnable.add(paths.bin.getAbsolutePath());
        runner.versionOutput = "2.0.27";
        OpenCodeInstaller.writeString(paths.versionFile, "2.0.27");
        OpenCodeInstaller.writeString(paths.lastCheckFile, String.valueOf(System.currentTimeMillis()));
        // ...must still be replaced when the user pins an older build. The pin
        // also disables the cached "no network needed" short circuit, and a
        // trailing comment must not turn it into a pre-release.
        OpenCodeInstaller.writeString(paths.pinFile, "2.0.25  # hold");
        http.bytes = tarBytes;
        source.release = new OpenCodeInstaller.Release(
            "2.0.25", "https://example.test/cli.tgz", integrityOf(tarBytes));

        OpenCodeInstaller.Result result = installer().ensure(false, false);

        assertEquals(OpenCodeInstaller.Outcome.UPDATED, result.outcome);
        assertTrue(source.versionCalled);
        assertEquals("2.0.25", source.requestedVersion);
        assertFalse(source.called);
        assertEquals("2.0.25", OpenCodeInstaller.readString(paths.versionFile));
        assertEquals("2.0.25", result.version);
    }

    @Test
    public void pinnedVersionMatchingCurrentIsUpToDate() throws Exception {
        assertTrue(paths.bin.createNewFile());
        runner.runnable.add(paths.bin.getAbsolutePath());
        runner.versionOutput = "2.0.25";
        OpenCodeInstaller.writeString(paths.versionFile, "2.0.25");
        OpenCodeInstaller.writeString(paths.lastCheckFile, "0");
        OpenCodeInstaller.writeString(paths.pinFile, "2.0.25");
        source.release = new OpenCodeInstaller.Release(
            "2.0.25", "https://example.test/cli.tgz", integrityOf(tarBytes));

        OpenCodeInstaller.Result result = installer().ensure(false, false);

        assertEquals(OpenCodeInstaller.Outcome.UP_TO_DATE, result.outcome);
        assertTrue(source.versionCalled);
        assertFalse(source.called);
    }

    @Test
    public void rejectsCorruptDownload() throws Exception {
        http.bytes = tarBytes;
        source.release = new OpenCodeInstaller.Release(
            "2.0.27", "https://example.test/cli.tgz", "sha512-not-the-real-hash");

        OpenCodeInstaller.Result result = installer().ensure(false, false);

        assertEquals(OpenCodeInstaller.Outcome.FAILED, result.outcome);
        assertFalse(paths.bin.exists());
    }

    @Test
    public void failsWhenBinaryCannotBePatched() throws Exception {
        http.bytes = tarBytes;
        runner.patchSucceeds = false;
        source.release = new OpenCodeInstaller.Release(
            "2.0.27", "https://example.test/cli.tgz", integrityOf(tarBytes));

        OpenCodeInstaller.Result result = installer().ensure(false, false);

        assertEquals(OpenCodeInstaller.Outcome.FAILED, result.outcome);
        assertFalse(paths.bin.exists());
    }

    @Test
    public void failsWithoutPrefix() {
        OpenCodeInstaller.Paths bad =
            OpenCodeInstaller.Paths.of(new File(tmp.getRoot(), "missing"), home, "arm64");
        OpenCodeInstaller.Result result =
            new OpenCodeInstaller(bad, source, http, runner, new SilentProgress(), "arm64", 34)
                .ensure(false, false);
        assertEquals(OpenCodeInstaller.Outcome.FAILED, result.outcome);
    }

    @Test
    public void installsVersionedBinaryAndSymlinksLauncher() throws Exception {
        http.bytes = tarBytes;
        source.release = new OpenCodeInstaller.Release(
            "2.0.27", "https://example.test/cli.tgz", integrityOf(tarBytes));

        OpenCodeInstaller.Result result = installer().ensure(false, false);

        assertEquals(OpenCodeInstaller.Outcome.INSTALLED, result.outcome);
        // bin/opencode is only a symlink into installed/, never the patched ELF.
        assertTrue(OpenCodeInstaller.isSymlink(paths.bin));
        assertTrue(paths.bin.isFile());
        File versioned = new File(paths.installedDir, "opencode-v2.0.27");
        assertTrue(versioned.isFile());
        assertEquals(versioned.getCanonicalPath(), paths.bin.getCanonicalPath());
        // The (fake) patch was applied to the published build, not to a copy.
        assertEquals(1, countInstalled());
    }

    @Test
    public void adoptsLegacyRegularFileBinary() throws Exception {
        // An install written by an older build: the ELF itself at bin/opencode.
        assertTrue(paths.bin.createNewFile());
        runner.runnable.add(paths.bin.getAbsolutePath());
        OpenCodeInstaller.writeString(paths.versionFile, "2.0.27");
        OpenCodeInstaller.writeString(paths.lastCheckFile, String.valueOf(System.currentTimeMillis()));
        source.fail = true; // must not be consulted

        OpenCodeInstaller.Result result = installer().ensure(false, false);

        assertEquals(OpenCodeInstaller.Outcome.UP_TO_DATE, result.outcome);
        assertTrue(OpenCodeInstaller.isSymlink(paths.bin));
        File versioned = new File(paths.installedDir, "opencode-v2.0.27");
        assertTrue(versioned.isFile());
        assertEquals(versioned.getCanonicalPath(), paths.bin.getCanonicalPath());
        assertFalse(source.called);
    }

    @Test
    public void updateSwapsSymlinkAndKeepsPreviousBuild() throws Exception {
        assertTrue(paths.bin.createNewFile());
        runner.runnable.add(paths.bin.getAbsolutePath());
        runner.versionOutput = "2.0.26";
        OpenCodeInstaller.writeString(paths.versionFile, "2.0.26");
        OpenCodeInstaller.writeString(paths.lastCheckFile, "0");
        http.bytes = tarBytes;
        source.release = new OpenCodeInstaller.Release(
            "2.0.27", "https://example.test/cli.tgz", integrityOf(tarBytes));

        OpenCodeInstaller.Result result = installer().ensure(false, false);

        assertEquals(OpenCodeInstaller.Outcome.UPDATED, result.outcome);
        File current = new File(paths.installedDir, "opencode-v2.0.27");
        assertTrue(current.isFile());
        assertEquals(current.getCanonicalPath(), paths.bin.getCanonicalPath());
        // The previous build stays behind for rollback.
        assertTrue(new File(paths.installedDir, "opencode-v2.0.26").isFile());
    }

    @Test
    public void prunesOldBuildsKeepingNewest() throws Exception {
        File installed = paths.installedDir;
        assertTrue(installed.mkdirs());
        for (String v : new String[]{"2.0.18", "2.0.20", "2.0.24"}) {
            assertTrue(new File(installed, "opencode-v" + v).createNewFile());
        }
        assertTrue(paths.bin.createNewFile());
        runner.runnable.add(paths.bin.getAbsolutePath());
        runner.versionOutput = "2.0.24";
        OpenCodeInstaller.writeString(paths.versionFile, "2.0.24");
        OpenCodeInstaller.writeString(paths.lastCheckFile, "0");
        http.bytes = tarBytes;
        source.release = new OpenCodeInstaller.Release(
            "2.0.27", "https://example.test/cli.tgz", integrityOf(tarBytes));

        OpenCodeInstaller.Result result = installer().ensure(false, false);

        assertEquals(OpenCodeInstaller.Outcome.UPDATED, result.outcome);
        assertEquals(OpenCodeInstaller.RETAINED_VERSIONS, countInstalled());
        assertTrue(new File(installed, "opencode-v2.0.27").isFile());
        assertTrue(new File(installed, "opencode-v2.0.24").isFile());
        assertTrue(new File(installed, "opencode-v2.0.20").isFile());
        assertFalse(new File(installed, "opencode-v2.0.18").exists());
    }

    private int countInstalled() {
        File[] kids = paths.installedDir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File kid : kids) {
            if (kid.isFile() && OpenCodeInstaller.Paths.versionOf(kid) != null) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------

    private static final class SilentProgress implements OpenCodeInstaller.Progress {
        @Override
        public void state(String code, String detail) {}

        @Override
        public void log(String line) {}
    }

    private static final class FakeSource implements OpenCodeInstaller.Source {
        OpenCodeInstaller.Release release;
        boolean fail;
        boolean called;
        boolean versionCalled;
        String requestedVersion;

        @Override
        public OpenCodeInstaller.Release latest(OpenCodeInstaller.Http http, String arch) throws IOException {
            called = true;
            if (fail) throw new IOException("offline");
            return release;
        }

        @Override
        public OpenCodeInstaller.Release version(OpenCodeInstaller.Http http, String arch, String version)
            throws IOException {
            versionCalled = true;
            requestedVersion = version;
            if (fail) throw new IOException("offline");
            return release;
        }
    }

    private static final class FakeHttp implements OpenCodeInstaller.Http {
        byte[] bytes;

        @Override
        public String getString(String url, int maxBytes) {
            return "";
        }

        @Override
        public void download(String url, File dest, OpenCodeInstaller.Progress progress) throws IOException {
            try (OutputStream out = new FileOutputStream(dest)) {
                out.write(bytes);
            }
        }
    }

    private static final class FakeRunner implements OpenCodeInstaller.Runner {
        final String loaderPath;
        final Set<String> runnable = new HashSet<>();
        final List<List<String>> calls = new ArrayList<>();
        String versionOutput = "2.0.27";
        boolean patchSucceeds = true;

        FakeRunner(String loaderPath) {
            this.loaderPath = loaderPath;
        }

        @Override
        public int run(List<String> argv, File cwd, Map<String, String> env, OpenCodeInstaller.LineSink out) {
            calls.add(new ArrayList<>(argv));
            String exe = argv.get(0);
            if (exe.endsWith("apt-get")) return 0;
            if (exe.endsWith("patchelf")) {
                if (argv.contains("--print-interpreter")) {
                    String target = argv.get(argv.size() - 1);
                    if (out != null && runnable.contains(target)) out.line(loaderPath);
                    return 0;
                }
                if (argv.contains("--set-interpreter")) {
                    if (!patchSucceeds) return 1;
                    runnable.add(argv.get(argv.size() - 1));
                    return 0;
                }
                return 0;
            }
            if (argv.size() >= 2 && "--version".equals(argv.get(1))) {
                if (runnable.contains(exe)) {
                    if (out != null) out.line(versionOutput);
                    return 0;
                }
                return 1;
            }
            return 0;
        }
    }
}
