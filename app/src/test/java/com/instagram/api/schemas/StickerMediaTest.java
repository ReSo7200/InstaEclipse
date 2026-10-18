package com.instagram.api.schemas;

import org.junit.Test;
import java.util.List;
import ps.reso.instaeclipse.mods.media.StickerMedia;
import static org.junit.Assert.*;

public class StickerMediaTest {
    public static class Message {
        public Object attachment;
        public String avatarUrl = "https://cdn.example/avatar.jpg";
        Message(Object attachment) { this.attachment = attachment; }
    }
    public static class Sticker {
        private final List<Image> candidates;
        public Sticker(List<Image> candidates) { this.candidates = candidates; }
        public List<Image> getCandidates() { return candidates; }
    }
    public static class Image {
        private final String source;
        private final int size;
        Image(String source, int size) { this.source = source; this.size = size; }
        public String getUrl() { return source; }
        public int getWidth() { return size; }
        public int getHeight() { return size; }
    }
    @Test public void getsBestStickerAndIgnoresAvatar() {
        Object msg = new Message(new Sticker(List.of(
                new Image("https://cdn.example/small.webp", 64),
                new Image("https://cdn.example/large.webp", 512))));
        assertEquals(List.of("https://cdn.example/large.webp"), StickerMedia.urls(msg));
    }
    @Test public void rejectsNonHttpAndUnrelatedMedia() {
        assertTrue(StickerMedia.urls(new Message(null)).isEmpty());
        assertTrue(StickerMedia.urls(new Sticker(List.of(new Image("file:///tmp/sticker.png", 512)))).isEmpty());
        assertTrue(StickerMedia.urls(new Sticker(List.of(new Image("https://user:password@cdn.example/sticker.png", 512)))).isEmpty());
    }
    public static class PandoComment {
        public ObfuscatedGiphySchema Cq7() { return new ObfuscatedGiphySchema(); }
    }
    public static class ObfuscatedGiphySchema {
        public String Cgm() { return "https://cdn.example/image?format=webp"; }
    }
    @Test public void readsObfuscatedPandoSchemaGettersAndExtensionlessUrls() {
        assertEquals(List.of("https://cdn.example/image?format=webp"), StickerMedia.urls(new PandoComment()));
    }
    enum Kind { STICKER, TEXT, GENERIC_XMA }
    public static class ObfuscatedPayload {
        public String getUrl() { return "https://cdn.example/image?format=webp"; }
    }
    public static class TypedMessage {
        public Kind kind = Kind.STICKER;
        public Object payload = new ObfuscatedPayload();
    }
    @Test public void identifiesObfuscatedPayloadFromMessageType() {
        assertEquals(List.of("https://cdn.example/image?format=webp"), StickerMedia.urls(new TypedMessage()));
    }
    public static class XmaAttachment {
        public String action = "instagram://generated_sticker_action?id=123";
        public Object image = new ObfuscatedPayload();
    }
    @Test public void recognizesGeneratedStickerXmaWithoutTreatingPostSharesAsStickers() {
        assertEquals(List.of("https://cdn.example/image?format=webp"), StickerMedia.urls(new Message(List.of(new XmaAttachment()))));
        XmaAttachment post = new XmaAttachment();
        post.action = "instagram://media?id=123";
        assertTrue(StickerMedia.urls(new Message(List.of(post))).isEmpty());
    }
    public static class PickerMessage {
        public Kind kind = Kind.GENERIC_XMA;
        public Object attachment = new com.instagram.model.mediasize.ExtendedImageUrl();
    }
    @Test public void readsPickerStickerPrimaryImageWithoutNeedingAnActionUri() {
        assertEquals(List.of("https://cdn.example/picker?format=webp"), StickerMedia.urls(new PickerMessage()));
        PickerMessage text = new PickerMessage();
        text.kind = Kind.TEXT;
        assertTrue(StickerMedia.urls(text).isEmpty());
    }
    @Test public void handlesCycles() {
        Message msg = new Message(null);
        msg.attachment = msg;
        assertTrue(StickerMedia.urls(msg).isEmpty());
    }
}
