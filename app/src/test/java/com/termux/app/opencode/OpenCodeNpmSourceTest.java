package com.termux.app.opencode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class OpenCodeNpmSourceTest {

    private static final class MapHttp implements OpenCodeInstaller.Http {
        final Map<String, String> responses = new HashMap<>();
        final List<String> urls = new ArrayList<>();

        @Override
        public String getString(String url, int maxBytes) {
            urls.add(url);
            for (Map.Entry<String, String> e : responses.entrySet()) {
                if (url.startsWith(e.getKey())) return e.getValue();
            }
            return "";
        }

        @Override
        public void download(String url, java.io.File dest, OpenCodeInstaller.Progress progress) {}
    }

    @Test
    public void resolvesPlatformPackageTarballAndIntegrity() throws Exception {
        MapHttp http = new MapHttp();
        http.responses.put(OpenCodeInstaller.NpmSource.UPDATE_ENDPOINT,
            "{\"version\":\"2.0.27\",\"package\":\"@opencode/cli\"}");
        http.responses.put("https://registry.npmjs.org/@opencode/cli-linux-arm64/2.0.27",
            "{\"dist\":{\"integrity\":\"sha512-abc\",\"tarball\":\"https://reg/cli.tgz\"}}");

        OpenCodeInstaller.Release release =
            new OpenCodeInstaller.NpmSource().latest(http, "arm64");

        assertEquals("2.0.27", release.version);
        assertEquals("sha512-abc", release.integrity);
        assertEquals("https://reg/cli.tgz", release.tarballUrl);
        assertTrue(http.urls.get(1).contains("@opencode/cli-linux-arm64/2.0.27"));
    }

    @Test
    public void buildsTarballUrlWhenManifestOmitsIt() throws Exception {
        MapHttp http = new MapHttp();
        http.responses.put(OpenCodeInstaller.NpmSource.UPDATE_ENDPOINT,
            "{\"version\":\"2.0.27\",\"package\":\"@opencode/cli\"}");
        http.responses.put("https://registry.npmjs.org/@opencode/cli-linux-arm64/2.0.27",
            "{\"dist\":{\"integrity\":\"sha512-abc\"}}");

        OpenCodeInstaller.Release release =
            new OpenCodeInstaller.NpmSource().latest(http, "arm64");

        assertEquals(
            "https://registry.npmjs.org/@opencode/cli-linux-arm64/-/cli-linux-arm64-2.0.27.tgz",
            release.tarballUrl);
    }

    @Test
    public void resolvesExactPinnedVersionAgainstRegistry() throws Exception {
        MapHttp http = new MapHttp();
        http.responses.put(OpenCodeInstaller.NpmSource.UPDATE_ENDPOINT,
            "{\"version\":\"2.0.27\",\"package\":\"@opencode/cli\"}");
        http.responses.put("https://registry.npmjs.org/@opencode/cli-linux-arm64/2.0.25",
            "{\"dist\":{\"integrity\":\"sha512-old\",\"tarball\":\"https://reg/old.tgz\"}}");

        OpenCodeInstaller.Release release =
            new OpenCodeInstaller.NpmSource().version(http, "arm64", "2.0.25");

        assertEquals("2.0.25", release.version);
        assertEquals("sha512-old", release.integrity);
        assertEquals("https://reg/old.tgz", release.tarballUrl);
    }

    @Test
    public void resolvesPinnedVersionEvenWhenUpdateEndpointIsOffline() throws Exception {
        MapHttp http = new MapHttp();
        // No UPDATE_ENDPOINT response: the default package name must be used.
        http.responses.put("https://registry.npmjs.org/@opencode/cli-linux-arm64/2.0.25",
            "{\"dist\":{\"integrity\":\"sha512-old\"}}");

        OpenCodeInstaller.Release release =
            new OpenCodeInstaller.NpmSource().version(http, "arm64", "2.0.25");

        assertEquals("2.0.25", release.version);
        assertEquals(
            "https://registry.npmjs.org/@opencode/cli-linux-arm64/-/cli-linux-arm64-2.0.25.tgz",
            release.tarballUrl);
    }

    @Test
    public void jsonReaderHandlesEscapes() {
        assertEquals("a\"b", OpenCodeJson.string("{\"k\":\"a\\\"b\"}", "k"));
        assertEquals("x", OpenCodeJson.string("{\"other\":1,\"k\":\"x\"}", "k"));
        assertEquals(null, OpenCodeJson.string("{\"k\":123}", "k"));
        assertEquals(null, OpenCodeJson.string("{}", "k"));
    }
}
