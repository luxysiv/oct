package com.termux.app.opencode;

/**
 * Tiny JSON string-field reader.
 *
 * <p>The installer only ever needs a handful of top-level-ish string fields from
 * npm / update-endpoint responses ({@code version}, {@code package},
 * {@code integrity}, {@code tarball}). A full JSON parser would pull in
 * {@code org.json}, which is stubbed on plain JVM unit tests, so this reads the
 * exact fields we need and stays testable. Pure Java, no Android imports.</p>
 */
public final class OpenCodeJson {

    private OpenCodeJson() {}

    /**
     * Value of the first {@code "key": "..."} string occurrence, or {@code null}.
     * Returns {@code null} for non-string values (numbers, objects, null).
     */
    public static String string(String json, String key) {
        if (json == null || key == null) return null;
        String needle = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int k = json.indexOf(needle, from);
            if (k < 0) return null;
            from = k + needle.length();
            int c = skipWs(json, from);
            if (c >= json.length() || json.charAt(c) != ':') continue; // not a key
            int q = skipWs(json, c + 1);
            if (q >= json.length() || json.charAt(q) != '"') return null; // not a string value
            return readString(json, q + 1);
        }
    }

    private static int skipWs(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
        return i;
    }

    private static String readString(String s, int i) {
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '\\') {
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 <= s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ignored) {
                            }
                            i += 4;
                        }
                        break;
                    default: sb.append(e); break; // covers \" \\ \/
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null; // unterminated
    }
}
