package com.termux.app.opencode;

/**
 * Minimal semantic-version comparison for OpenCode release strings.
 *
 * <p>Handles the shapes the npm registry and the update endpoint actually return,
 * e.g. {@code 2.0.26}, {@code v2.0.26}, {@code 2.0.0-android-termux.1},
 * {@code 2.0.0-beta.2}. Build metadata after {@code +} is ignored. A release is
 * considered newer than any of its pre-releases.</p>
 *
 * <p>Pure Java (no Android imports) so it can be unit-tested on the JVM.</p>
 */
public final class OpenCodeSemVer {

    private OpenCodeSemVer() {}

    /** {@code >0} when {@code a} is newer, {@code <0} when older, {@code 0} equal. */
    public static int compare(String a, String b) {
        int[] ca = core(a);
        int[] cb = core(b);
        for (int i = 0; i < 3; i++) {
            if (ca[i] != cb[i]) return ca[i] > cb[i] ? 1 : -1;
        }
        String pa = pre(a);
        String pb = pre(b);
        boolean ea = pa.isEmpty();
        boolean eb = pb.isEmpty();
        if (ea && eb) return 0;
        if (ea) return 1;   // release > pre-release
        if (eb) return -1;  // pre-release < release
        return comparePre(pa, pb);
    }

    /** True when {@code candidate} is strictly newer than {@code current}. */
    public static boolean isNewer(String candidate, String current) {
        if (candidate == null || candidate.isEmpty()) return false;
        if (current == null || current.isEmpty()) return true;
        return compare(candidate, current) > 0;
    }

    /** Extract the first {@code x.y.z} from arbitrary text, or {@code null}. */
    public static String parse(String text) {
        if (text == null) return null;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) continue;
            int j = i;
            int dots = 0;
            while (j < text.length()) {
                char c = text.charAt(j);
                if (Character.isDigit(c)) {
                    j++;
                } else if (c == '.' && dots < 2) {
                    dots++;
                    j++;
                } else {
                    break;
                }
            }
            if (dots == 2) {
                String s = text.substring(i, j);
                return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
            }
            i = j;
        }
        return null;
    }

    private static int[] core(String v) {
        int[] out = new int[3];
        String[] parts = strip(v).split("\\.");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            out[i] = leadingInt(parts[i]);
        }
        return out;
    }

    private static String strip(String v) {
        if (v == null) return "";
        String s = v.trim();
        if (!s.isEmpty() && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) s = s.substring(1);
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        return s;
    }

    private static String pre(String v) {
        String s = strip(v);
        int dash = s.indexOf('-');
        return dash >= 0 ? s.substring(dash + 1) : "";
    }

    private static int leadingInt(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0) return 0;
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int comparePre(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            if (i >= pa.length) return -1; // fewer fields => lower precedence
            if (i >= pb.length) return 1;
            String x = pa[i];
            String y = pb[i];
            boolean nx = isNumeric(x);
            boolean ny = isNumeric(y);
            if (nx && ny) {
                int c = Integer.compare(leadingInt(x), leadingInt(y));
                if (c != 0) return c;
            } else if (nx != ny) {
                return nx ? -1 : 1; // numeric identifiers sort lower
            } else {
                int c = x.compareTo(y);
                if (c != 0) return c < 0 ? -1 : 1;
            }
        }
        return 0;
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
