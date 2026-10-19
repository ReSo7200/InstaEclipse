package ps.reso.instaeclipse.utils.text;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FancyTextTest {

    @Test
    public void stylesLatinLettersAndDigits() {
        assertEquals("𝐇𝐢 𝟏", FancyText.convert("Hi 1", 1));
        assertEquals("𝘩𝘪", FancyText.convert("hi", 5));
        assertEquals("ℎ𝑖", FancyText.convert("hi", 2));
        assertEquals("ℂℍ𝕒𝟙", FancyText.convert("CHa1", 8));
        assertEquals("Ⓗⓘ①⓪", FancyText.convert("Hi10", 10));
        assertEquals("Hɪ", FancyText.convert("Hi", 12));
    }

    @Test
    public void keepsOtherScriptsAndEmoji() {
        assertEquals("время ⏳ 𝐨𝐤", FancyText.convert("время ⏳ ok", 1));
    }

    @Test
    public void stylesGreek() {
        assertEquals("𝚨𝛂𝛚", FancyText.convert("Αα" + "ω", 1));
        assertEquals("𝜶𝝎", FancyText.convert("αω", 3));
        assertEquals("αω", FancyText.convert(FancyText.convert("αω", 4), 0));
    }

    @Test
    public void marksWorkInAnyLanguage() {
        assertEquals("в̶р̶ ", FancyText.convert("вр ", 13));
        assertEquals("о̲к̲ ب̲", FancyText.convert("ок ب", 14));
        assertEquals("ок ب", FancyText.convert(FancyText.convert("ок ب", 13), 0));
    }

    @Test
    public void restylesAndRestoresStyledText() {
        String bold = FancyText.convert("Hello 42", 1);
        assertEquals("𝙷𝚎𝚕𝚕𝚘 𝟺𝟸", FancyText.convert(bold, 9));
        assertEquals("Hello 42", FancyText.convert(FancyText.convert(bold, 10), 0));
        assertEquals("", FancyText.convert(null, 1));
    }
}
