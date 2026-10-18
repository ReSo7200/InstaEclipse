package ps.reso.instaeclipse.utils.ghost;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Copy-on-write state. A failed durable write never commits the in-memory change. */
public final class HiddenThreadStore {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public interface Storage {
        String read() throws IOException;
        String readLegacy() throws IOException;
        void write(String document) throws IOException;
    }
    public static final class Result {
        public final boolean success, hidden;
        public final String error;
        public Result(boolean success, boolean hidden, String error) {
            this.success = success; this.hidden = hidden; this.error = error;
        }
    }
    private final Storage storage;
    private JsonObject state;
    private String error;
    public HiddenThreadStore(Storage storage) { this.storage = storage; }
    private boolean load() {
        if (state != null) return true;
        try {
            String current = storage.read();
            if (current != null) {
                JsonObject parsed = parse(current);
                if (!parsed.has("version") || parsed.get("version").getAsInt() != 2
                        || !parsed.has("accounts") || !parsed.get("accounts").isJsonObject()
                        || !parsed.has("legacyUnassigned") || !parsed.get("legacyUnassigned").isJsonObject())
                    throw new IOException("Unsupported hidden state");
                for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject("accounts").entrySet()) {
                    if (!ConversationIdentity.validId(entry.getKey()) || !entry.getValue().isJsonObject())
                        throw new IOException("Invalid account state");
                    validateLabels(entry.getValue().getAsJsonObject(), true);
                }
                validateLabels(parsed.getAsJsonObject("legacyUnassigned"), false);
                state = parsed;
            } else {
                JsonObject next = new JsonObject();
                next.addProperty("version", 2);
                next.add("accounts", new JsonObject());
                String legacy = storage.readLegacy();
                JsonObject unassigned = legacy == null ? new JsonObject() : parse(legacy);
                validateLabels(unassigned, false);
                next.add("legacyUnassigned", unassigned);
                state = next; // no automatic disk mutation or account attribution
            }
            error = null;
            return true;
        } catch (IOException | RuntimeException failure) {
            error = "Hidden chat state could not be read; existing files were preserved.";
            return false;
        }
    }
    private static JsonObject parse(String text) throws IOException {
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("Oversized hidden state");
        JsonElement value = JsonParser.parseString(text);
        if (!value.isJsonObject()) throw new IOException("Invalid hidden state");
        return value.getAsJsonObject();
    }
    private static void validateLabels(JsonObject map, boolean checkIds) throws IOException {
        for (Map.Entry<String, JsonElement> entry : map.entrySet()) {
            if ((checkIds && !ConversationIdentity.validId(entry.getKey()))
                    || !entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                throw new IOException("Invalid hidden entry");
        }
    }
    public synchronized Result setHidden(String account, String thread, String title, boolean hidden) {
        if (!ConversationIdentity.validId(account) || !ConversationIdentity.validId(thread))
            return new Result(false, false, "The current account or conversation is unresolved.");
        if (!load()) return new Result(false, false, error);
        boolean previous = isHidden(account, thread);
        if (previous == hidden) return new Result(true, hidden, null);
        JsonObject next = state.deepCopy();
        JsonObject accounts = next.getAsJsonObject("accounts");
        JsonObject chats = accounts.has(account) ? accounts.getAsJsonObject(account) : new JsonObject();
        accounts.add(account, chats);
        if (hidden) chats.addProperty(thread, title == null ? "" : title.substring(0, Math.min(title.length(), 256)));
        else chats.remove(thread);
        try {
            String document = next.toString();
            if (document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
                throw new IOException("Hidden state size limit");
            storage.write(document);
            state = next;
            error = null;
            return new Result(true, hidden, null);
        } catch (IOException | RuntimeException failure) {
            error = "Hidden chat state could not be saved; no change was applied.";
            return new Result(false, previous, error);
        }
    }
    public synchronized boolean isHidden(String account, String thread) {
        if (!load() || account == null || thread == null) return false;
        JsonObject accounts = state.getAsJsonObject("accounts");
        return accounts.has(account) && accounts.getAsJsonObject(account).has(thread);
    }
    public synchronized Map<String, String> all(String account) {
        if (!load() || account == null) return new LinkedHashMap<>();
        JsonObject accounts = state.getAsJsonObject("accounts");
        return snapshot(accounts.has(account) ? accounts.getAsJsonObject(account) : new JsonObject());
    }
    public synchronized Map<String, String> legacyEntries() {
        return load() ? snapshot(state.getAsJsonObject("legacyUnassigned")) : new LinkedHashMap<>();
    }
    private static Map<String, String> snapshot(JsonObject object) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) map.put(entry.getKey(), entry.getValue().getAsString());
        return map;
    }
    public synchronized String lastError() { load(); return error; }
}
