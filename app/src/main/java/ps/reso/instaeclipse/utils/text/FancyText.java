package ps.reso.instaeclipse.utils.text;

import java.util.HashMap;
import java.util.Map;

/**
 * "Fonts" for plain text fields (the bio): letters swapped for their Unicode look-alikes
 * (Mathematical Alphanumeric Symbols, circled, fullwidth, small caps). Latin letters and digits
 * have every style, Greek has the bold/italic ones. Strikethrough and underline are combining
 * marks, so they work in any language (Cyrillic, Arabic, ...). The result is ordinary text, so it
 * shows the same in every app. Framework-free so it is unit-tested.
 */
public final class FancyText {

    /** Style names, index = style id; 0 turns styled text back to normal. */
    public static final String[] NAMES = {
            "Normal", "Bold", "Italic", "Bold italic", "Sans bold", "Sans italic", "Script",
            "Fraktur", "Double-struck", "Monospace", "Circled", "Fullwidth", "Small caps",
            "Strikethrough", "Underline"
    };

    // {A, a, 0, Greek Α} first code points per style (-1 = keep as is)
    private static final int[][] BASES = {
            {'A', 'a', '0', -1},
            {0x1D400, 0x1D41A, 0x1D7CE, 0x1D6A8},
            {0x1D434, 0x1D44E, -1, 0x1D6E2},
            {0x1D468, 0x1D482, -1, 0x1D71C},
            {0x1D5D4, 0x1D5EE, 0x1D7EC, 0x1D756},
            {0x1D608, 0x1D622, -1, -1},
            {0x1D4D0, 0x1D4EA, -1, -1},
            {0x1D56C, 0x1D586, -1, -1},
            {0x1D538, 0x1D552, 0x1D7D8, -1},
            {0x1D670, 0x1D68A, 0x1D7F6, -1},
            {0x24B6, 0x24D0, -1, -1},
            {0xFF21, 0xFF41, 0xFF10, -1},
            {'A', -1, '0', -1},
            {-1, -1, -1, -1},
            {-1, -1, -1, -1},
    };
    private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
    private static final int ITALIC = 2, DOUBLE = 8, CIRCLED = 10, SMALL = 12, STRIKE = 13, UNDER = 14;
    private static final int STRIKE_MARK = 0x0336, UNDER_MARK = 0x0332;
    // Greek in the math blocks: Α..Ω (0x391..0x3A9, 0x3A2 = ϴ there), ∇, then α..ω (0x3B1..0x3C9)
    private static final int GREEK_CAP = 0x391, GREEK_SMALL = 0x3B1, GREEK_LEN = 25;

    /** styled code point -> plain character */
    private static final Map<Integer, Integer> PLAIN = new HashMap<>();

    static {
        for (int s = 1; s < NAMES.length; s++) {
            for (int c = '0'; c <= 'z'; c++)
                if (Character.isLetterOrDigit(c)) addPlain(c, s);
            for (int i = 0; i < GREEK_LEN; i++) {
                addPlain(GREEK_CAP + i, s);
                addPlain(GREEK_SMALL + i, s);
            }
        }
    }

    private static void addPlain(int c, int style) {
        int cp = map(c, style);
        if (cp != c) PLAIN.put(cp, c);
    }

    private FancyText() {}

    /** {@code text} in style {@code style} (styled input is turned back to plain first). */
    public static String convert(String text, int style) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length() * 2);
        int mark = style == STRIKE ? STRIKE_MARK : style == UNDER ? UNDER_MARK : 0;
        text.codePoints().forEach(cp -> {
            if (cp == STRIKE_MARK || cp == UNDER_MARK) return;
            Integer plain = PLAIN.get(cp);
            int c = plain != null ? plain : cp;
            out.appendCodePoint(map(c, style));
            if (mark != 0 && !Character.isWhitespace(c) && Character.getType(c) != Character.NON_SPACING_MARK)
                out.appendCodePoint(mark);
        });
        return out.toString();
    }

    private static int map(int c, int style) {
        if (style <= 0 || style >= NAMES.length) return c;
        int[] b = BASES[style];
        if (c >= GREEK_CAP && c < GREEK_CAP + GREEK_LEN) return b[3] < 0 ? c : b[3] + (c - GREEK_CAP);
        if (c >= GREEK_SMALL && c < GREEK_SMALL + GREEK_LEN) return b[3] < 0 ? c : b[3] + 26 + (c - GREEK_SMALL);
        if (c > 127) return c;
        if (c >= 'A' && c <= 'Z') {
            if (style == DOUBLE) {
                switch (c) {
                    case 'C': return 0x2102;
                    case 'H': return 0x210D;
                    case 'N': return 0x2115;
                    case 'P': return 0x2119;
                    case 'Q': return 0x211A;
                    case 'R': return 0x211D;
                    case 'Z': return 0x2124;
                }
            }
            return b[0] < 0 ? c : b[0] + (c - 'A');
        }
        if (c >= 'a' && c <= 'z') {
            if (style == SMALL) return SMALL_CAPS.charAt(c - 'a');
            if (style == ITALIC && c == 'h') return 0x210E;
            return b[1] < 0 ? c : b[1] + (c - 'a');
        }
        if (c >= '0' && c <= '9') {
            if (style == CIRCLED) return c == '0' ? 0x24EA : 0x2460 + (c - '1');
            return b[2] < 0 ? c : b[2] + (c - '0');
        }
        return c;
    }
}
