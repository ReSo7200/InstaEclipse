package ps.reso.instaeclipse.utils.version;

/** Compares dotted version names such as "0.7.0", "0.7.1-hotfix" or "v1.2". */
public final class VersionComparator {

    private VersionComparator() {}

    /** Negative if {@code a < b}, zero if equal, positive if {@code a > b}. */
    public static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? x[i] : 0;
            int yi = i < y.length ? y[i] : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static int[] parse(String v) {
        if (v == null) return new int[0];
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        // Ignore any suffix such as "-hotfix", "-beta1" or "+build".
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '.' && !Character.isDigit(c)) { cut = i; break; }
        }
        s = s.substring(0, cut);
        if (s.isEmpty()) return new int[0];
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
