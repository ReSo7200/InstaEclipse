package ps.reso.instaeclipse.mods.ui.theme;

import android.content.Context;

import androidx.core.view.ViewCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.CommonUtils;

public final class ThemePresets {

    private static final List<ThemePreset> PRESETS;
    private static final String[] PRESET_NAMES = {
            "Midnight Eclipse", "Pure OLED", "Arctic Frost", "Rose Gold", "Ocean Deep",
            "Forest Canopy", "Sunset Amber", "Lavender Dream", "Cherry Blossom", "Cyber Neon",
            "Coffee Mocha", "Royal Purple", "Mint Fresh", "Slate Pro", "Coral Reef",
            "Nordic Ice", "Golden Hour", "Matrix Green", "Amethyst Night", "Peach Soft",
            "Steel Gray", "Tropical Teal", "Wine Burgundy", "Sky Blue", "Sand Dune",
            "Violet Storm", "Emerald City", "Blush Pink", "Carbon Fiber", "Aurora Borealis"
    };

    static {
        List<ThemePreset> list = new ArrayList<>();
        list.add(p(1, -16118249, -15459539, -1512204, -7629400, -12285185, -1512204, -14801096, -16118249, -10773761, -44462));
        list.add(p(2, ViewCompat.MEASURED_STATE_MASK, -15921907, -657931, -9211021, -16738826, -657931, -15066598, ViewCompat.MEASURED_STATE_MASK, -16738826, -1226410));
        list.add(p(3, -460036, -1, -15788246, -10193781, -14326805, -15788246, -1906448, -460036, -14326805, -2349530));
        list.add(p(4, -15068651, -14016732, -661782, -4680290, -1531724, -661782, -12768461, -15068651, -1531724, -38015));
        list.add(p(5, -16507349, -16109500, -2034433, -9722946, -16730920, -2034433, -15584441, -16507349, -12006684, -38037));
        list.add(p(6, -16049649, -15456232, -1509911, -8279934, -11751600, -1509911, -14798046, -16049649, -10044566, -1739917));
        list.add(p(7, -15069176, -14018034, -3104, -4680338, -26624, -3104, -12769256, -15069176, -18611, -43230));
        list.add(p(8, -15396833, -14542797, -792321, -5729084, -6543440, -792321, -13753792, -15396833, -4560696, -44462));
        list.add(p(9, -14740968, -13755868, -3851, -3894626, -40816, -3851, -12572624, -14740968, -28757, -47273));
        list.add(p(10, -16119278, -15592930, -2031617, -9531762, -16711681, -2031617, -15066578, -16119278, -65281, -65434));
        list.add(p(11, -15068144, -14016488, -659996, -5729670, -7508381, -659996, -12767192, -15068144, -6190977, -2604267));
        list.add(p(12, -15594977, -14806477, -1185802, -6982195, -8630785, -1185802, -14018496, -15594977, -6982195, -44462));
        list.add(p(13, -16115180, -15587296, -1507339, -8732768, -16725866, -1507339, -15060184, -16115180, -14233432, -38037));
        list.add(p(14, -14800581, -13418155, -920071, -7035976, -12877066, -920071, -12102295, -14800581, -10443270, -1096636));
        list.add(p(15, -15068656, -14016998, -2576, -4221304, -37023, -2576, -12768732, -15068656, -30080, -44462));
        list.add(p(16, -1249292, -1, -13749184, -8681311, -10583636, -13749184, -2564375, -1249292, -8281663, -4234902));
        list.add(p(17, -15067640, -14015984, -1823, -4677536, -16121, -1823, -12766696, -15067640, -10929, -36797));
        list.add(p(18, -16774656, -16772096, -16711871, -16740591, -16711871, -16711871, -16768512, -16774656, -12976364, -65472));
        list.add(p(19, -15857640, -15200216, -989441, -7311184, -5635841, -989441, -14411720, -15857640, -3238952, -44462));
        list.add(p(20, -2578, -1, -12768481, -6388624, -21615, -12768481, -661798, -2578, -30107, -1754827));
        list.add(p(21, -14606047, -13619152, -328966, -6381922, -8875876, -328966, -12434878, -14606047, -7297874, -1092784));
        list.add(p(22, -16115174, -15718360, -2031617, -10573142, -16742021, -2031617, -15189960, -16115174, -14244198, -36797));
        list.add(p(23, -15070704, -14019552, -203540, -5214080, -7860657, -203540, -12773336, -15070704, -4056997, -44462));
        list.add(p(24, -1838339, -1, -15906911, -10711360, -15108398, -15906911, -4464901, -1838339, -12409355, -1754827));
        list.add(p(25, -659224, -1035, -12701146, -6386050, -2841228, -12701146, -1515568, -659224, -4412764, -2604267));
        list.add(p(26, -15726568, -15069144, -1185802, -8363872, -10354454, -1185802, -14149568, -15726568, -8630785, -44462));
        list.add(p(27, -16246256, -15718376, -1509137, -10444672, -16725933, -1509137, -15189976, -16246256, -9834322, -44462));
        list.add(p(28, -3851, -1, -11919312, -5209968, -749647, -11919312, -469024, -3851, -1294214, -1754827));
        list.add(p(29, -15592942, -14803426, -2039584, -9079435, -16718337, -2039584, -13882324, -15592942, -16718337, -59580));
        list.add(p(30, -16117728, -15722448, -1509121, -10452832, -16718218, -1509121, -15196096, -16117728, -15138817, -49023));
        PRESETS = Collections.unmodifiableList(list);
    }

    private static ThemePreset p(int id, int bg, int surface, int primary, int secondary, int accent, int icon, int divider, int nav, int link, int destructive) {
        return new ThemePreset(id, new IgThemePalette(bg, surface, primary, secondary, accent, accent, icon, icon, divider, divider, nav, bg, link, destructive, destructive));
    }

    public static List<ThemePreset> all() {
        return PRESETS;
    }

    public static ThemePreset getById(int id) {
        for (ThemePreset preset : PRESETS) {
            if (preset.id == id) return preset;
        }
        return PRESETS.get(0);
    }

    public static String getDisplayName(Context context, int id) {
        int index = id - 1;
        if (index >= 0 && index < PRESET_NAMES.length) return PRESET_NAMES[index];
        if (context != null) {
            try {
                String[] names = ps.reso.instaeclipse.utils.i18n.I18n.moduleResources(context).getStringArray(R.array.theme_preset_names);
                if (index >= 0 && index < names.length) return names[index];
            } catch (Throwable ignored) {}
        }
        return PRESET_NAMES.length > 0 ? PRESET_NAMES[0] : "";
    }

    /** Values for the light-mode picker (#251): same theme, stock Instagram, then every preset. */
    public static int[] lightModeChoices() {
        int[] out = new int[PRESETS.size() + 2];
        out[0] = 0;
        out[1] = -1;
        for (int i = 0; i < PRESETS.size(); i++) out[i + 2] = PRESETS.get(i).id;
        return out;
    }

    private ThemePresets() {}
}
