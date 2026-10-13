package com.termux.app.opencode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Builds in-memory ustar/gzip archives for the tar + installer tests. */
final class TarTestUtil {

    private TarTestUtil() {}

    /** Build a gzipped tar with one regular file per entry (name -> bytes). */
    static byte[] gzipTar(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            writeEntry(tar, e.getKey(), e.getValue());
        }
        // two zero blocks terminate the archive
        tar.write(new byte[1024]);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(tar.toByteArray());
        }
        return gz.toByteArray();
    }

    private static void writeEntry(ByteArrayOutputStream out, String name, byte[] data) throws IOException {
        byte[] header = new byte[512];
        putString(header, 0, 100, name);
        putOctal(header, 100, 8, 0644);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, data.length);
        putOctal(header, 136, 12, 0);
        header[156] = '0'; // regular file
        putString(header, 257, 6, "ustar"); // magic "ustar\0"
        header[263] = '0';
        header[264] = '0';
        out.write(header);
        out.write(data);
        int pad = (512 - (data.length % 512)) % 512;
        if (pad > 0) out.write(new byte[pad]);
    }

    private static void putString(byte[] b, int off, int len, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.min(bytes.length, len);
        System.arraycopy(bytes, 0, b, off, n);
    }

    private static void putOctal(byte[] b, int off, int len, long value) {
        String s = Long.toOctalString(value);
        // zero-padded, NUL-terminated when it fits
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < len - 1; i++) sb.append('0');
        sb.append(s);
        putString(b, off, len, sb.toString());
        b[off + len - 1] = 0;
    }
}
