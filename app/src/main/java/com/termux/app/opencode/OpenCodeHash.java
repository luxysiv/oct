package com.termux.app.opencode;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-512 hashing + Base64 for verifying npm tarball integrity
 * ({@code dist.integrity} looks like {@code sha512-<base64>}).
 *
 * <p>Base64 is implemented inline because {@code java.util.Base64} needs API 26
 * while the app supports API 21, and {@code android.util.Base64} is not usable
 * from plain JVM unit tests. Pure Java, no Android imports.</p>
 */
public final class OpenCodeHash {

    private static final char[] B64 =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private OpenCodeHash() {}

    /** SHA-512 of the stream, Base64-encoded (no newlines). */
    public static String sha512Base64(InputStream in) throws IOException {
        MessageDigest md = digest();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        return base64(md.digest());
    }

    public static byte[] sha512(byte[] data) {
        return digest().digest(data);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    /** Standard Base64 (with {@code =} padding), no line breaks. */
    public static String base64(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = (i + 1 < data.length) ? data[i + 1] & 0xFF : -1;
            int b2 = (i + 2 < data.length) ? data[i + 2] & 0xFF : -1;
            sb.append(B64[b0 >> 2]);
            sb.append(B64[((b0 & 0x03) << 4) | (b1 < 0 ? 0 : b1 >> 4)]);
            sb.append(b1 < 0 ? '=' : B64[((b1 & 0x0F) << 2) | (b2 < 0 ? 0 : b2 >> 6)]);
            sb.append(b2 < 0 ? '=' : B64[b2 & 0x3F]);
        }
        return sb.toString();
    }
}
