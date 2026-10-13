package com.termux.app.opencode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

/**
 * Minimal ustar/gzip reader for npm tarballs.
 *
 * <p>We only need {@code package/bin/opencode} out of the archive, but the whole
 * format is parsed (regular files, GNU long names {@code L}, ustar {@code prefix},
 * PAX headers are skipped) so extraction does not depend on an external
 * {@code tar}. Entries are streamed to a caller-supplied handler; directories and
 * unsupported entry types are skipped. Pure Java, no Android imports.</p>
 */
public final class OpenCodeTar {

    private static final int BLOCK = 512;

    /** Receives the bytes of one regular file; return {@code null} to skip it. */
    public interface EntryHandler {
        OutputStream onEntry(String path, long size) throws IOException;
    }

    private OpenCodeTar() {}

    /** Extract a gzip-compressed tar stream, invoking {@code handler} per file. */
    public static void extractGz(InputStream gz, EntryHandler handler) throws IOException {
        InputStream in = (gz instanceof GZIPInputStream) ? gz : new GZIPInputStream(gz);
        byte[] header = new byte[BLOCK];
        String pendingLongName = null;
        while (readFully(in, header, BLOCK)) {
            if (isZeroBlock(header)) break; // two zero blocks end the archive
            String name = field(header, 0, 100);
            String prefix = field(header, 345, 155);
            long size = octal(header, 124, 12);
            char type = (char) (header[156] & 0xFF);

            if (type == 'L') { // GNU long name for the next entry
                byte[] data = readN(in, size);
                pendingLongName = new String(data, "UTF-8").replace("\u0000", "").trim();
                skipPad(in, size);
                continue;
            }
            if (type == 'x' || type == 'g') { // PAX headers: skip payload, keep name
                skipN(in, size);
                skipPad(in, size);
                continue;
            }
            if (type == 'K' || type == '1' || type == '2' || type == '3'
                || type == '4' || type == '5' || type == '6') {
                // long link / hard-or-symlink / char-or-block / dir / fifo: not a file
                skipN(in, size);
                skipPad(in, size);
                pendingLongName = null;
                continue;
            }

            String path = pendingLongName != null
                ? pendingLongName
                : (prefix.isEmpty() ? name : prefix + "/" + name);
            pendingLongName = null;

            if (type == '0' || type == '\0') {
                OutputStream out = handler.onEntry(sanitize(path), size);
                copyN(in, out, size);
                skipPad(in, size);
                if (out != null) out.close();
            } else {
                skipN(in, size);
                skipPad(in, size);
            }
        }
    }

    /** Strip leading {@code ./} / {@code /} and neutralise {@code ..} segments. */
    public static String sanitize(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        if (p.contains("..")) p = p.replace("..", "_");
        return p;
    }

    private static String field(byte[] b, int off, int len) {
        int end = off;
        int max = Math.min(b.length, off + len);
        while (end < max && b[end] != 0) end++;
        return new String(b, off, end - off).trim();
    }

    private static long octal(byte[] b, int off, int len) {
        // GNU tar also allows base-256 (high bit set) for large sizes, but npm
        // tarballs stay well within the octal range.
        long v = 0;
        int max = Math.min(b.length, off + len);
        for (int i = off; i < max; i++) {
            int c = b[i] & 0xFF;
            if (c == 0 || c == ' ') break;
            if (c < '0' || c > '7') continue;
            v = (v << 3) + (c - '0');
        }
        return v;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static boolean readFully(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static byte[] readN(InputStream in, long size) throws IOException {
        if (size <= 0 || size > Integer.MAX_VALUE) return new byte[0];
        byte[] b = new byte[(int) size];
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) break;
            off += n;
        }
        return b;
    }

    private static void copyN(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buf = new byte[65536];
        long remaining = size;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int n = in.read(buf, 0, toRead);
            if (n < 0) break;
            if (out != null) out.write(buf, 0, n);
            remaining -= n;
        }
    }

    private static void skipN(InputStream in, long size) throws IOException {
        long remaining = size;
        byte[] buf = new byte[8192];
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) break;
            remaining -= n;
        }
    }

    private static void skipPad(InputStream in, long size) throws IOException {
        long pad = (BLOCK - (size % BLOCK)) % BLOCK;
        if (pad > 0) skipN(in, pad);
    }
}
