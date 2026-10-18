package ps.reso.instaeclipse.utils.ipc;

import org.junit.Test;
import ps.reso.instaeclipse.BuildConfig;
import static org.junit.Assert.*;

public class ModulePreferencePolicyTest {
    private static final String BOOLEAN = BuildConfig.APPLICATION_ID + ".ACTION_UPDATE_PREF";
    private static final String STRING = BuildConfig.APPLICATION_ID + ".ACTION_UPDATE_PREF_STRING";
    private static final String INTEGER = BuildConfig.APPLICATION_ID + ".ACTION_UPDATE_PREF_INT";

    @Test public void actionCannotChangeTheStoredTypeOfAnExistingPreference() {
        assertTrue(ModuleReplies.isPreferenceUpdate(BOOLEAN, "hideSpecificChats", true));
        assertTrue(ModuleReplies.isPreferenceUpdate(STRING, "downloaderCustomPath", "fixture"));
        assertTrue(ModuleReplies.isPreferenceUpdate(INTEGER, "forceReelQuality", 1080));
        assertFalse(ModuleReplies.isPreferenceUpdate(BOOLEAN, "downloaderCustomPath", "fixture"));
        assertFalse(ModuleReplies.isPreferenceUpdate(STRING, "hideSpecificChats", true));
        assertFalse(ModuleReplies.isPreferenceUpdate(INTEGER, "hideSpecificChats", true));
        assertFalse(ModuleReplies.isPreferenceUpdate(BOOLEAN, "forceReelQuality", 1080));
        assertFalse(ModuleReplies.isPreferenceUpdate(STRING, "forceReelQuality", "1080"));
    }

    @Test public void schemaPreservesStoredCoordinatesAndRejectsCompanionOnlyOrUnknownKeys() {
        assertTrue(ModuleReplies.isPreferenceValue("spoofLat", "0.0"));
        assertTrue(ModuleReplies.isPreferenceValue("spoofLng", "0.0"));
        assertFalse(ModuleReplies.isPreferenceValue("spoofLat", 0.0));
        assertTrue(ModuleReplies.isPreferenceValue("showHideChatButton", false));
        assertTrue(ModuleReplies.isPreferenceValue("showDeletedMessagesButton", false));
        assertFalse(ModuleReplies.isPreferenceValue("pathJustSetLocally", false));
        assertFalse(ModuleReplies.isPreferenceValue("synthetic_unknown_marker", true));
        assertFalse(ModuleReplies.isPreferenceValue(null, true));
        assertFalse(ModuleReplies.isPreferenceValue("hideSpecificChats", null));
        assertFalse(ModuleReplies.isPreferenceUpdate("unrecognized_action", "hideSpecificChats", true));
    }

    @Test public void oversizedStringsNeverEnterPreferencesOrTheReplyBundle() {
        String atLimit = "x".repeat(ModuleReplies.MAX_PREFERENCE_STRING_CHARS);
        assertTrue(ModuleReplies.isPreferenceValue("themePaletteJson", atLimit));
        assertFalse(ModuleReplies.isPreferenceValue("themePaletteJson", atLimit + "x"));
        assertFalse(ModuleReplies.isPreferenceUpdate(STRING, "downloaderCustomPath", atLimit + "x"));
    }

    @Test public void downloaderModeParticipatesInBooleanSchemaAndUpdates() {
        for (boolean original : new boolean[]{false, true}) {
            assertTrue(ModuleReplies.isPreferenceValue("downloaderUseOriginal", original));
            assertTrue(ModuleReplies.isPreferenceUpdate(BOOLEAN, "downloaderUseOriginal", original));
            assertFalse(ModuleReplies.isPreferenceUpdate(STRING, "downloaderUseOriginal", original));
            assertFalse(ModuleReplies.isPreferenceUpdate(INTEGER, "downloaderUseOriginal", original));
        }
    }

    @Test public void downloaderModeRejectsCoercionAndUnknownModeKeys() {
        for (Object value : new Object[]{"true", "false", "Original", 0, 1, 1L, null}) {
            assertFalse(ModuleReplies.isPreferenceValue("downloaderUseOriginal", value));
            assertFalse(ModuleReplies.isPreferenceUpdate(BOOLEAN, "downloaderUseOriginal", value));
        }
        assertFalse(ModuleReplies.isPreferenceUpdate(STRING, "downloaderUseOriginal", "true"));
        assertFalse(ModuleReplies.isPreferenceUpdate(INTEGER, "downloaderUseOriginal", 1));
        assertFalse(ModuleReplies.isPreferenceUpdate(BOOLEAN, "synthetic_unknown_downloader_mode", true));
        assertFalse(ModuleReplies.isPreferenceValue("synthetic_unknown_downloader_mode", false));
    }
}
