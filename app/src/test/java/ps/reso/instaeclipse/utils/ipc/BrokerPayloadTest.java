package ps.reso.instaeclipse.utils.ipc;

import org.junit.Test;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import ps.reso.instaeclipse.BuildConfig;
import ps.reso.instaeclipse.utils.download.DownloadRequest;
import static org.junit.Assert.*;

public class BrokerPayloadTest {
    private static final String URL = "https://test.cdninstagram.com/fixture.mp4";
    private DownloadRequest request(String name, String url) {
        return new DownloadRequest("fixture", "owner", name, "",
                Collections.singletonList(new DownloadRequest.Rendition(url, 1920, 1080, 30, "avc1", 2000)),
                new DownloadRequest.Options(1080, true, false), DownloadRequest.AudioExpectation.PRESENT);
    }
    private static void rejects(Runnable action) {
        try { action.run(); fail("Expected bounded payload rejection"); }
        catch (IllegalArgumentException expected) { }
    }
    private static Map<String, String> entry(String key, String value) {
        Map<String, String> result = new HashMap<>(); result.put(key, value); return result;
    }

    @Test public void selectedRequestPreservesCapturedQualityAudioAndCandidate() {
        DownloadRequest original = request("fixture.mp4", URL);
        DownloadRequest validated = BrokerPayload.request(original.toJson());
        assertEquals(original.toJson(), validated.toJson());
        assertFalse(validated.options.allowFallback);
        assertEquals(DownloadRequest.AudioExpectation.PRESENT, validated.audio);
    }
    @Test public void frameActionUsesBestWithoutMutatingVideoPreferencesOrSelection() {
        DownloadRequest original = request("fixture.mp4", URL);
        String json = original.toJson();
        Map<String, String> payload = BrokerPayload.activity(BrokerPayload.FRAME,
                entry(BrokerPayload.FRAME_REQUEST, json));
        DownloadRequest frame = BrokerPayload.request(payload.get(BrokerPayload.FRAME_REQUEST));
        assertEquals(0, frame.options.maxShortSide);
        assertFalse(frame.options.compatibility);
        assertFalse(frame.options.allowFallback);
        assertEquals(json, original.toJson());
        assertEquals(original.mediaId, frame.mediaId);
        assertEquals(original.direct.get(0).url, frame.direct.get(0).url);
        assertEquals(original.audio, frame.audio);
    }
    @Test public void frameActivityRejectsMissingRequestForeignUrlAndUnapprovedExtras() {
        rejects(() -> BrokerPayload.activity(BrokerPayload.FRAME, Collections.emptyMap()));
        rejects(() -> BrokerPayload.activity(BrokerPayload.FRAME,
                entry(BrokerPayload.FRAME_REQUEST, request("fixture.mp4", "https://example.org/file").toJson())));
        Map<String,String> bad = entry(BrokerPayload.FRAME_REQUEST, request("fixture.mp4", URL).toJson());
        bad.put("localFile", "/private/file");
        rejects(() -> BrokerPayload.activity(BrokerPayload.FRAME, bad));
    }
    @Test public void selectedRequestRejectsTraversalForeignUrlAndEmptyCandidates() {
        rejects(() -> BrokerPayload.request(request("../fixture.mp4", URL).toJson()));
        rejects(() -> BrokerPayload.request(request("fixture.mp4", "https://example.org/file").toJson()));
        rejects(() -> BrokerPayload.request("{\"filename\":\"fixture.mp4\"}"));
    }
    @Test public void legacyRejectsUnsafeSchemesHostsAndMixedAudioPhoto() {
        BrokerPayload.legacy(URL, null, "fixture.mp4", "video/mp4", "owner");
        rejects(() -> BrokerPayload.legacy("http://test.cdninstagram.com/file", null, "a.jpg", "image/jpeg", "owner"));
        rejects(() -> BrokerPayload.legacy("https://cdninstagram.com@example.org/file", null, "a.jpg", "image/jpeg", "owner"));
        rejects(() -> BrokerPayload.legacy(URL, URL, "a.jpg", "image/jpeg", "owner"));
        rejects(() -> BrokerPayload.legacy(URL, null, "a\n.jpg", "image/jpeg", "owner"));
        rejects(() -> BrokerPayload.legacy(URL, null, "a.jpg", "text/html", "owner"));
    }
    @Test public void importOnlyTargetsEnrolledHostAndAllowlistedActions() {
        Map<String, String> validated = BrokerPayload.activity(BrokerPayload.IMPORT, Collections.emptyMap());
        assertEquals(BrokerPayload.HOST, validated.get("target_package"));
        assertEquals(BuildConfig.APPLICATION_ID + ".ACTION_IMPORT_CONFIG", validated.get("broadcast_action"));
        rejects(() -> BrokerPayload.activity(BrokerPayload.IMPORT, entry("target_package", "com.instagram.clone")));
        rejects(() -> BrokerPayload.activity(BrokerPayload.IMPORT, entry("broadcast_action", "arbitrary.action")));
        Map<String, String> restore = entry("broadcast_action", BuildConfig.APPLICATION_ID + ".ACTION_RESTORE_SETTINGS");
        assertEquals(restore.get("broadcast_action"), BrokerPayload.activity(BrokerPayload.IMPORT, restore).get("broadcast_action"));
    }
    @Test public void activityRejectsUnlistedComponentsAndExtras() {
        rejects(() -> BrokerPayload.activity("android.app.Activity", Collections.emptyMap()));
        rejects(() -> BrokerPayload.activity(BrokerPayload.THEME, entry("target_package", BrokerPayload.HOST)));
        rejects(() -> BrokerPayload.activity(BrokerPayload.EXPORT, entry("unknown", "value")));
        assertTrue(BrokerPayload.activity(BrokerPayload.THEME, Collections.emptyMap()).isEmpty());
    }
    @Test public void locationRequiresFiniteBoundedCoordinates() {
        assertEquals("90", BrokerPayload.activity(BrokerPayload.LOCATION, entry("lat", "90")).get("lat"));
        rejects(() -> BrokerPayload.activity(BrokerPayload.LOCATION, entry("lat", "90.1")));
        rejects(() -> BrokerPayload.activity(BrokerPayload.LOCATION, entry("lng", "-180.1")));
        rejects(() -> BrokerPayload.activity(BrokerPayload.LOCATION, entry("lat", "NaN")));
        rejects(() -> BrokerPayload.activity(BrokerPayload.LOCATION, entry("lng", "Infinity")));
    }
    @Test public void exportBoundsSizeDepthSyntaxAndFilename() {
        Map<String, String> export = entry("json_content", "{\"fixture\":true}");
        assertEquals(export, BrokerPayload.activity(BrokerPayload.EXPORT, export));
        rejects(() -> BrokerPayload.activity(BrokerPayload.EXPORT, entry("json_content", "{broken}")));
        rejects(() -> BrokerPayload.activity(BrokerPayload.EXPORT, entry("json_content", "[]")));
        rejects(() -> BrokerPayload.activity(BrokerPayload.EXPORT, entry("json_content", "{\"x\":\"" + repeated("x", 200_000) + "\"}")));
        rejects(() -> BrokerPayload.activity(BrokerPayload.EXPORT, entry("json_content", "{\"x\":" + repeated("[", 33) + "0" + repeated("]", 33) + "}")));
        export.put("file_name", "../export.json");
        rejects(() -> BrokerPayload.activity(BrokerPayload.EXPORT, export));
    }
    @Test public void requestRejectsOversizedArraysAndJsonBeforeDispatch() {
        String rendition = "{\"url\":\"" + URL + "\"}";
        StringBuilder json = new StringBuilder("{\"filename\":\"fixture.mp4\",\"direct\":[");
        for (int i = 0; i < 33; i++) { if (i > 0) json.append(','); json.append(rendition); }
        json.append("]}");
        rejects(() -> BrokerPayload.request(json.toString()));
        rejects(() -> BrokerPayload.request(repeated(" ", DownloadRequest.MAX_JSON_CHARS + 1)));
    }
    @Test public void activityValidationDetachesTheInputMap() {
        Map<String, String> input = entry("lat", "1");
        Map<String, String> captured = BrokerPayload.activity(BrokerPayload.LOCATION, input);
        input.put("lat", "2");
        assertEquals("1", captured.get("lat"));
        try { captured.put("lat", "3"); fail("Expected immutable map"); }
        catch (UnsupportedOperationException expected) { }
    }
    private static String repeated(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    @Test public void unicodeConfigUsesSameImportAndExportCharacterLimit() throws Exception {
        String json = "{\"fixture\":\"" + repeated("\u05d0", 150_000) + "\"}";
        byte[] utf8 = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(utf8.length > BrokerPayload.MAX_CONFIG_CHARS);
        String read = BrokerPayload.readConfig(new java.io.ByteArrayInputStream(utf8));
        assertEquals(json, BrokerPayload.activity(BrokerPayload.EXPORT, entry("json_content", read)).get("json_content"));
    }

    @Test public void configReaderRejectsOversizeAndInvalidUtf8() throws Exception {
        for (byte[] bytes : new byte[][]{
                repeated(" ", BrokerPayload.MAX_CONFIG_CHARS + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new byte[]{'{', '"', 'x', '"', ':', '"', (byte)0xff, '"', '}'}}) {
            try { BrokerPayload.readConfig(new java.io.ByteArrayInputStream(bytes)); fail("Invalid stream accepted"); }
            catch (java.io.IOException expected) { }
        }
    }
}
