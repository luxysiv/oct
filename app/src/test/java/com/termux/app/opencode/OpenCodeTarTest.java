package com.termux.app.opencode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class OpenCodeTarTest {

    @Test
    public void extractsRequestedEntryAndSkipsTheRest() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("package/package.json", "{\"name\":\"x\"}".getBytes("UTF-8"));
        files.put("package/bin/opencode", "BINARY-CONTENT".getBytes("UTF-8"));
        files.put("package/README.md", "readme".getBytes("UTF-8"));
        byte[] gz = TarTestUtil.gzipTar(files);

        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final int[] count = {0};
        OpenCodeTar.extractGz(new ByteArrayInputStream(gz), new OpenCodeTar.EntryHandler() {
            @Override
            public OutputStream onEntry(String path, long size) {
                if (!"package/bin/opencode".equals(path)) return null;
                count[0]++;
                return captured;
            }
        });

        assertEquals(1, count[0]);
        assertEquals("BINARY-CONTENT", new String(captured.toByteArray(), "UTF-8"));
    }

    @Test
    public void readsMultiBlockEntries() throws Exception {
        byte[] big = new byte[1500];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i & 0xFF);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("package/bin/opencode", big);
        byte[] gz = TarTestUtil.gzipTar(files);

        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OpenCodeTar.extractGz(new ByteArrayInputStream(gz), new OpenCodeTar.EntryHandler() {
            @Override
            public OutputStream onEntry(String path, long size) {
                assertEquals(1500, size);
                return captured;
            }
        });
        assertEquals(1500, captured.toByteArray().length);
        assertEquals(big[1499], captured.toByteArray()[1499]);
    }

    @Test
    public void sanitizeRejectsTraversalAndAbsolutePaths() {
        assertEquals("etc/passwd", OpenCodeTar.sanitize("/etc/passwd"));
        assertEquals("etc/passwd", OpenCodeTar.sanitize("./etc/passwd"));
        assertFalse(OpenCodeTar.sanitize("../../etc/passwd").contains(".."));
        assertEquals("", OpenCodeTar.sanitize(null));
    }
}
