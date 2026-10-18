/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package ps.reso.instaeclipse.utils.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The fetch against a local server. The policy lets exactly that server through and hands every
 * other address, redirects included, to the real rules with a fixed lookup, so no test ever
 * leaves the machine. Each failure has to leave no file and never reach the sink.
 */
public class DownloaderTest {
    @Test public void audioSniffAcceptsWebmAndBoundedMp3Headers() {
        byte[] webm = {(byte)0x1a, 0x45, (byte)0xdf, (byte)0xa3, 0, 0, 0, 0};
        byte[] id3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0};
        byte[] frame = {(byte)0xff, (byte)0xfb, (byte)0x90, 0};
        assertEquals("audio/webm", Downloader.sniff(Downloader.Kind.AUDIO, webm, webm.length));
        assertEquals("audio/mpeg", Downloader.sniff(Downloader.Kind.AUDIO, id3, id3.length));
        assertEquals("audio/mpeg", Downloader.sniff(Downloader.Kind.AUDIO, frame, frame.length));
        id3[6] = (byte)0x80; // ID3 sizes are sync-safe, not arbitrary integers.
        assertEquals(null, Downloader.sniff(Downloader.Kind.AUDIO, id3, id3.length));
        frame[2] = (byte)0xfc; // Reserved bitrate and sample-rate values.
        assertEquals(null, Downloader.sniff(Downloader.Kind.AUDIO, frame, frame.length));
        byte[] text = "ID3This is an HTML error".getBytes(StandardCharsets.US_ASCII);
        assertEquals(null, Downloader.sniff(Downloader.Kind.AUDIO, text, text.length));
    }

    @Test public void audioBytesDetermineMimeRatherThanMisleadingMp4Header() throws IOException {
        byte[] webm = new byte[100];
        webm[0] = 0x1a; webm[1] = 0x45; webm[2] = (byte)0xdf; webm[3] = (byte)0xa3;
        serve("/voice", "audio/mp4", webm);
        File into = temp.newFile();
        Downloader.Result result = fetch("/voice", Downloader.Kind.AUDIO, into, Downloader.MAX_BYTES);
        assertEquals(Downloader.Status.OK, result.status);
        assertEquals("audio/webm", result.mime);
        assertArrayEquals(webm, Files.readAllBytes(into.toPath()));
    }

    @Test public void pageClaimingAudioStillLeavesNoFile() throws IOException {
        serve("/fake-audio", "audio/mpeg", HTML);
        assertRefused(Downloader.Status.REFUSED, "/fake-audio", Downloader.Kind.AUDIO, Downloader.MAX_BYTES);
    }

    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private LocalServer server;
    private String origin;

    /** A Meta name that the fixed lookup answers with a private address. */
    private static final String REBOUND = "https://scontent-rebound.xx.fbcdn.net/v.mp4";

    private MediaUrlPolicy policy;

    @Before
    public void startServer() throws IOException {
        server = new LocalServer();
        int port = server.port();
        origin = server.origin();
        policy = new MediaUrlPolicy(host -> {
            if (host.equals("scontent-rebound.xx.fbcdn.net")) return new InetAddress[] { InetAddress.getByName("10.0.0.7") };
            return new InetAddress[] { InetAddress.getByName("157.240.22.19") };
        }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
    }

    @After
    public void stopServer() throws IOException {
        server.close();
    }

    // ------------------------------------------------------------------ fixtures

    private static byte[] mp4(int size) {
        byte[] body = new byte[size];
        byte[] head = { 0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm' };
        System.arraycopy(head, 0, body, 0, head.length);
        for (int i = head.length; i < size; i++) body[i] = (byte) i;
        return body;
    }

    private static byte[] jpeg(int size) {
        byte[] body = new byte[size];
        body[0] = (byte) 0xFF;
        body[1] = (byte) 0xD8;
        body[2] = (byte) 0xFF;
        for (int i = 3; i < size; i++) body[i] = (byte) (i * 7);
        return body;
    }

    private static final byte[] HTML =
            "<!DOCTYPE html><html><body>Log in to continue</body></html>".getBytes(StandardCharsets.UTF_8);

    private void serve(String path, int code, String type, byte[] body, long announced) {
        server.serve(path, code, type, body, announced);
    }

    private void serve(String path, String type, byte[] body) {
        server.serve(path, type, body);
    }

    private void redirect(String path, String location) {
        server.redirect(path, location);
    }

    private int hitsOf(String path) {
        return server.hits(path);
    }

    private Downloader.Result fetch(String path, Downloader.Kind kind, File into, long max) {
        return Downloader.fetch(origin + path, kind, into, policy, max, Downloader.SILENT);
    }

    /** A fetch that must fail with [status] and leave no file behind. */
    private void assertRefused(Downloader.Status status, String path, Downloader.Kind kind, long max) throws IOException {
        File into = temp.newFile();
        Downloader.Result result = fetch(path, kind, into, max);
        assertEquals(path + ": " + result, status, result.status);
        assertFalse(path + " left its file behind", into.exists());
    }

    // ------------------------------------------------------------------ what still saves

    @Test
    public void aProgressiveVideoArrivesWhole() throws IOException {
        byte[] body = mp4(200_000);
        serve("/v.mp4", "video/mp4", body);
        File into = temp.newFile();

        Downloader.Result result = fetch("/v.mp4", Downloader.Kind.VIDEO, into, Downloader.MAX_BYTES);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals("video/mp4", result.mime);
        assertArrayEquals(body, Files.readAllBytes(into.toPath()));
    }

    @Test
    public void aPictureArrivesWhole() throws IOException {
        byte[] body = jpeg(40_000);
        serve("/p.jpg", "image/jpeg; charset=binary", body);
        File into = temp.newFile();

        Downloader.Result result = fetch("/p.jpg", Downloader.Kind.IMAGE, into, Downloader.MAX_BYTES);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals("image/jpeg", result.mime);
        assertArrayEquals(body, Files.readAllBytes(into.toPath()));
    }

    @Test
    public void aDashSoundTrackArrivesAsAudio() throws IOException {
        serve("/a.mp4", "audio/mp4", mp4(5000));
        File into = temp.newFile();
        Downloader.Result result = fetch("/a.mp4", Downloader.Kind.AUDIO, into, Downloader.MAX_BYTES);
        assertEquals(result.toString(), Downloader.Status.OK, result.status);
    }

    @Test
    public void aGenericTypeIsNamedByWhatTheBytesAre() throws IOException {
        serve("/bin", "application/octet-stream", mp4(3000));
        File into = temp.newFile();
        Downloader.Result result = fetch("/bin", Downloader.Kind.VIDEO, into, Downloader.MAX_BYTES);
        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals("video/mp4", result.mime);
    }

    @Test
    public void aWrongImageTypeDoesNotGiveTheSavedFileTheWrongExtension() throws IOException {
        byte[] png = new byte[40];
        byte[] signature = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
        System.arraycopy(signature, 0, png, 0, signature.length);
        serve("/picture", "image/jpeg", png);
        File into = temp.newFile();

        Downloader.Result result = fetch("/picture", Downloader.Kind.IMAGE, into, Downloader.MAX_BYTES);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals("image/png", result.mime);
        assertArrayEquals(png, Files.readAllBytes(into.toPath()));
    }

    @Test
    public void aRedirectWithinTheAllowedServerIsFollowed() throws IOException {
        serve("/final.mp4", "video/mp4", mp4(4000));
        redirect("/hop", origin + "/final.mp4");
        File into = temp.newFile();
        Downloader.Result result = fetch("/hop", Downloader.Kind.VIDEO, into, Downloader.MAX_BYTES);
        assertEquals(result.toString(), Downloader.Status.OK, result.status);
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    public void aPageIsRefused() throws IOException {
        serve("/login", "text/html", HTML);
        assertRefused(Downloader.Status.REFUSED, "/login", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
    }

    @Test
    public void aPageSentAsVideoIsRefused() throws IOException {
        serve("/fake.mp4", "video/mp4", HTML);
        assertRefused(Downloader.Status.REFUSED, "/fake.mp4", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
    }

    /**
     * The sniff reads a box name in the fifth to eighth bytes, and text can spell one there. The
     * size in front of it is what gives text away: four printable characters read as more than
     * 512 MB.
     */
    @Test
    public void textThatSpellsABoxNameIsNotAContainer() throws IOException {
        String[] texts = {
                "The free trial has ended. Log in to watch.", "<!--free-->", "Get free music at example",
                "Now skip ahead to the good part", "Too wide a request for this page",
        };
        for (String text : texts) {
            byte[] body = text.getBytes(StandardCharsets.US_ASCII);
            assertEquals(text, null, Downloader.sniff(Downloader.Kind.VIDEO, body, body.length));
            assertEquals(text, null, Downloader.sniff(Downloader.Kind.AUDIO, body, body.length));
        }
        byte[] trial = texts[0].getBytes(StandardCharsets.US_ASCII);
        serve("/trial.mp4", "video/mp4", trial);
        assertRefused(Downloader.Status.REFUSED, "/trial.mp4", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
    }

    /** 0 runs to the end of the file and 1 means a 64-bit size follows. Nothing else under 8 is a box. */
    @Test
    public void aBoxSizeHasToBeOneAContainerCanStartWith() {
        for (long size : new long[] { 0, 1, 8, 0x20, Downloader.MAX_BYTES }) {
            assertEquals("size " + size, "video/mp4", Downloader.sniff(Downloader.Kind.VIDEO, box(size), 12));
        }
        for (long size : new long[] { 2, 7, Downloader.MAX_BYTES + 1, 0xFFFFFFFFL }) {
            assertEquals("size " + size, null, Downloader.sniff(Downloader.Kind.VIDEO, box(size), 12));
        }
    }

    private static byte[] box(long size) {
        return new byte[] {
                (byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size,
                'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
        };
    }

    /**
     * mif1 and msf1 only say "a HEIF", and the brands listed after them say how it's coded. An
     * AVIF encoder can put mif1 first and list avif later: read by its first brand alone it was
     * called HEIC, and since the bytes pick the saved type it saved as .heic.
     */
    @Test
    public void aHeifImageIsNamedByTheBrandsItLists() {
        byte[] mif1Avif = ftyp("mif1", "mif1", "avif", "miaf");
        assertEquals("image/avif", Downloader.sniff(Downloader.Kind.IMAGE, mif1Avif, mif1Avif.length));
        byte[] mif1Heic = ftyp("mif1", "mif1", "heic");
        assertEquals("image/heic", Downloader.sniff(Downloader.Kind.IMAGE, mif1Heic, mif1Heic.length));
        byte[] avif = ftyp("avif", "avif", "mif1", "miaf", "MA1B");
        assertEquals("image/avif", Downloader.sniff(Downloader.Kind.IMAGE, avif, avif.length));
        byte[] avifSequence = ftyp("msf1", "msf1", "iso8", "avis");
        assertEquals("image/avif", Downloader.sniff(Downloader.Kind.IMAGE, avifSequence, avifSequence.length));

        // One that names no coding says only that it's a HEIF.
        byte[] generic = ftyp("mif1", "mif1", "miaf");
        assertEquals("image/heif", Downloader.sniff(Downloader.Kind.IMAGE, generic, generic.length));
        // Past the end of the box is the next box, whatever its bytes spell.
        byte[] next = startingWith(generic, 40);
        put(next, 28, "avif");
        assertEquals("image/heif", Downloader.sniff(Downloader.Kind.IMAGE, next, next.length));

        // Whatever its coding, a picture asked for as a video is refused.
        assertEquals(null, Downloader.sniff(Downloader.Kind.VIDEO, mif1Avif, mif1Avif.length));
    }

    /** The case that saved as .heic: an AVIF typed image/avif, with mif1 first and avif after it. */
    @Test
    public void anAvifThatListsMif1FirstSavesAsAvif() throws IOException {
        serve("/a.avif", "image/avif", startingWith(ftyp("mif1", "mif1", "avif", "miaf"), 3000));
        Downloader.Result result = fetch("/a.avif", Downloader.Kind.IMAGE, temp.newFile(), Downloader.MAX_BYTES);
        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals("image/avif", result.mime);

        // Untyped, the brand that names the coding counts even fifth, past the 32 bytes the sniff
        // used to read.
        serve("/late", "application/octet-stream",
                startingWith(ftyp("mif1", "mif1", "miaf", "MA1B", "MA1A", "avif"), 3000));
        result = fetch("/late", Downloader.Kind.IMAGE, temp.newFile(), Downloader.MAX_BYTES);
        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals("image/avif", result.mime);
    }

    /**
     * A HEIF that names no coding can't say whether it's AVIF or HEIC, and the server's type can.
     * One of the three stands. Anything else saves as a HEIF, not as the HEIC it may not be.
     */
    @Test
    public void aHeifThatNamesNoCodingKeepsTheServersHeifType() throws IOException {
        byte[] body = startingWith(ftyp("mif1", "mif1", "miaf"), 3000);
        String[][] cases = {
                {"image/avif", "image/avif"}, {"image/heic", "image/heic"}, {"image/heif", "image/heif"},
                {"application/octet-stream", "image/heif"}, {"image/jpeg", "image/heif"},
        };
        for (int i = 0; i < cases.length; i++) {
            serve("/generic" + i, cases[i][0], body);
            Downloader.Result result = fetch("/generic" + i, Downloader.Kind.IMAGE, temp.newFile(), Downloader.MAX_BYTES);
            assertEquals(cases[i][0] + ": " + result, Downloader.Status.OK, result.status);
            assertEquals(cases[i][0], cases[i][1], result.mime);
        }
    }

    /** An ftyp box with [major] (version 0) and the [compatible] brands after it, sized to hold them. */
    private static byte[] ftyp(String major, String... compatible) {
        int size = 16 + 4 * compatible.length;
        byte[] box = new byte[size];
        box[3] = (byte) size;
        put(box, 4, "ftyp");
        put(box, 8, major);
        for (int i = 0; i < compatible.length; i++) put(box, 16 + 4 * i, compatible[i]);
        return box;
    }

    /** [start], then made-up bytes up to [size] in all. */
    private static byte[] startingWith(byte[] start, int size) {
        byte[] body = new byte[size];
        System.arraycopy(start, 0, body, 0, start.length);
        for (int i = start.length; i < size; i++) body[i] = (byte) (i * 11);
        return body;
    }

    private static void put(byte[] into, int at, String ascii) {
        byte[] bytes = ascii.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, into, at, bytes.length);
    }

    @Test
    public void aPictureAskedForAsVideoIsRefused() throws IOException {
        serve("/typed.jpg", "image/jpeg", jpeg(5000));
        assertRefused(Downloader.Status.REFUSED, "/typed.jpg", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
        serve("/sneaky.mp4", "video/mp4", jpeg(5000));
        assertRefused(Downloader.Status.REFUSED, "/sneaky.mp4", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
    }

    /**
     * Announces a million bytes and sends a valid start only. Refused on the announcement: a
     * fetch that read first would find a short body instead and answer something else.
     */
    @Test
    public void aBodyAnnouncedTooLargeIsRefusedBeforeItIsRead() throws IOException {
        serve("/huge.mp4", 200, "video/mp4", mp4(64), 1_000_000);
        assertRefused(Downloader.Status.TOO_LARGE, "/huge.mp4", Downloader.Kind.VIDEO, 1024);
    }

    /** A DASH sound track is an MP4 file too. Saved as a video, it plays as a black screen. */
    @Test
    public void aSoundTrackAskedForAsVideoIsRefused() throws IOException {
        serve("/sound.mp4", "audio/mp4", mp4(5000));
        assertRefused(Downloader.Status.REFUSED, "/sound.mp4", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
    }

    @Test
    public void aBodyThatRunsPastTheCapWithoutALengthIsRefused() throws IOException {
        serve("/stream.mp4", 200, "video/mp4", mp4(8192), -1);
        assertRefused(Downloader.Status.TOO_LARGE, "/stream.mp4", Downloader.Kind.VIDEO, 1024);
    }

    @Test
    public void aTruncatedBodyIsNotKept() throws IOException {
        // Announces 8000 bytes and sends 3000.
        serve("/short.mp4", 200, "video/mp4", mp4(3000), 8000);
        File into = temp.newFile();
        Downloader.Result result = fetch("/short.mp4", Downloader.Kind.VIDEO, into, Downloader.MAX_BYTES);
        assertNotEquals(result.toString(), Downloader.Status.OK, result.status);
        assertFalse("the short file was kept", into.exists());
    }

    @Test
    public void anExpiredSignatureSaysSo() throws IOException {
        serve("/old.mp4", 403, "text/html", HTML, HTML.length);
        assertRefused(Downloader.Status.EXPIRED, "/old.mp4", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
        serve("/broken.mp4", 500, "text/html", HTML, HTML.length);
        assertRefused(Downloader.Status.HTTP_ERROR, "/broken.mp4", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
    }

    /** A loop is the server's fault, not a file that isn't Meta's, so it reads as a failed download. */
    @Test
    public void aRedirectLoopIsRefused() throws IOException {
        redirect("/a", origin + "/b");
        redirect("/b", origin + "/a");
        assertRefused(Downloader.Status.HTTP_ERROR, "/a", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
        assertEquals("the loop was fetched again", 1, hitsOf("/a"));
    }

    @Test
    public void tooManyRedirectsAreRefused() throws IOException {
        for (int i = 0; i < 7; i++) redirect("/r" + i, origin + "/r" + (i + 1));
        assertRefused(Downloader.Status.HTTP_ERROR, "/r0", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
        assertEquals("a hop past the limit was fetched", 0, hitsOf("/r6"));
    }

    /** Offline, or a lookup that timed out: a network failure, told as one, and nothing connects. */
    @Test
    public void aFailedLookupIsANetworkErrorNotARefusal() throws IOException {
        MediaUrlPolicy offline = new MediaUrlPolicy(host -> { throw new java.net.UnknownHostException(host); });
        File into = temp.newFile();
        Downloader.Result result = Downloader.fetch("https://scontent.xx.fbcdn.net/v.mp4", Downloader.Kind.VIDEO,
                into, offline, Downloader.MAX_BYTES, Downloader.SILENT);
        assertEquals(result.toString(), Downloader.Status.NETWORK_ERROR, result.status);
        assertEquals("the address: its host does not resolve", result.reason);
        assertFalse(into.exists());
    }

    /**
     * Each redirect is held to the real rules before anything connects to it. None of these
     * targets exists, so a connection attempt would fail with a network error, not a refusal.
     */
    @Test
    public void everyRedirectIsCheckedBeforeItIsFollowed() throws IOException {
        String[] unsafe = {
                "http://scontent.xx.fbcdn.net/v.mp4",
                "https://example.com/v.mp4",
                "https://scontent.xx.fbcdn.net@example.com/v.mp4",
                "https://scontent.xx.fbcdn.net:8443/v.mp4",
                "https://127.0.0.1/v.mp4",
                "https://169.254.169.254/latest/meta-data/",
                REBOUND,
                "file:///etc/passwd",
        };
        for (int i = 0; i < unsafe.length; i++) {
            redirect("/to" + i, unsafe[i]);
            File into = temp.newFile();
            Downloader.Result result = fetch("/to" + i, Downloader.Kind.VIDEO, into, Downloader.MAX_BYTES);
            // A Meta name answering with a private address is the network's doing; the rest aren't Meta's.
            Downloader.Status expected = unsafe[i].equals(REBOUND) ? Downloader.Status.NETWORK_ERROR : Downloader.Status.REFUSED;
            assertEquals(unsafe[i] + ": " + result, expected, result.status);
            assertTrue(unsafe[i] + ": " + result, result.reason.startsWith("redirect 1: "));
            assertFalse(into.exists());
        }
    }

    @Test
    public void theRealPolicyRefusesPlainHttpWithoutConnecting() throws IOException {
        File into = temp.newFile();
        Downloader.Result result = Downloader.fetch(origin + "/v.mp4", Downloader.Kind.VIDEO, into, MediaUrlPolicy.META,
                Downloader.MAX_BYTES, Downloader.SILENT);
        assertEquals(result.toString(), Downloader.Status.REFUSED, result.status);
        assertEquals(0, hitsOf("/v.mp4"));
        assertFalse(into.exists());
    }

    // ------------------------------------------------------------------ answers that aren't the file

    /**
     * A 206 answers a Range request, and none was sent. Its body is part of a file, of just the
     * length it announces, so the length check passed it and the gallery got a truncated video
     * that said it saved.
     */
    @Test
    public void aPartialAnswerNobodyAskedForIsRefused() throws IOException {
        serve("/part.mp4", 206, "video/mp4", mp4(4000), 4000);
        assertRefused(Downloader.Status.HTTP_ERROR, "/part.mp4", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
    }

    /**
     * An error page that never ends held its fetch, and one of the three save slots, for as long
     * as it went on: the body was read to its end before the connection was let go. Only so much
     * of it is read now, and the connection is closed.
     */
    @Test
    public void anErrorPageThatNeverEndsIsReadOnlySoFar() throws Exception {
        server.serveEndless("/denied.mp4", 403, "text/html", 64 * 1024);
        File into = temp.newFile();

        Downloader.Result result = within(15_000,
                () -> fetch("/denied.mp4", Downloader.Kind.VIDEO, into, Downloader.MAX_BYTES));

        assertEquals(result.toString(), Downloader.Status.EXPIRED, result.status);
        assertTrue("the connection stayed open", server.letGoWithin("/denied.mp4", 5_000));
        assertFalse(into.exists());
    }

    /** One that trickles too slowly to reach that much is read only for so long. */
    @Test
    public void aSlowErrorPageIsReadOnlySoLong() throws Exception {
        server.serveEndless("/broken.mp4", 500, "text/html", 16);
        File into = temp.newFile();

        Downloader.Result result = within(15_000,
                () -> fetch("/broken.mp4", Downloader.Kind.VIDEO, into, Downloader.MAX_BYTES));

        assertEquals(result.toString(), Downloader.Status.HTTP_ERROR, result.status);
        assertTrue("the connection stayed open", server.letGoWithin("/broken.mp4", 5_000));
    }

    /**
     * A cancel stops the reading of an error page between two reads, well before the time limit,
     * and the save ends cancelled rather than failed. A read that's waiting is ended by the closer
     * the fetch handed over, which the JDK these tests run on can't show (see readTimeoutMs).
     */
    @Test
    public void aCancelStopsTheReadingOfAnErrorPage() throws Exception {
        server.serveEndless("/slow.mp4", 500, "text/html", 16);
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Downloader.Progress progress = new Downloader.Progress() {
            @Override public void transferred(long done, long total) {
            }

            @Override public void reading(Runnable close) {
            }

            @Override public boolean cancelled() {
                return stop.get();
            }
        };
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                return;
            }
            stop.set(true);
        });
        File into = temp.newFile();

        long before = System.nanoTime();
        canceller.start();
        Downloader.Result result = within(15_000, () -> Downloader.fetch(origin + "/slow.mp4", Downloader.Kind.VIDEO,
                into, policy, Downloader.MAX_BYTES, progress));
        long tookMs = (System.nanoTime() - before) / 1_000_000;

        assertEquals(result.toString(), Downloader.Status.CANCELLED, result.status);
        assertTrue("the error page was read for " + tookMs + " ms, past the cancel", tookMs < 2_000);
    }

    /** A redirect's body isn't read at all, so one that never ends doesn't hold the hop. */
    @Test
    public void aRedirectWhoseBodyNeverEndsIsFollowed() throws Exception {
        serve("/final.mp4", "video/mp4", mp4(4000));
        server.redirectEndless("/hop", origin + "/final.mp4", 16);
        File into = temp.newFile();

        Downloader.Result result = within(15_000, () -> fetch("/hop", Downloader.Kind.VIDEO, into, Downloader.MAX_BYTES));

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertTrue("the redirect's connection stayed open", server.letGoWithin("/hop", 5_000));
    }

    /**
     * An answer that never starts ends the fetch at the read timeout, as a network failure, while
     * the headers are read and before the connection is handed back to the fetch's own cleanup.
     * It's closed all the same. The closer handed over for a cancel can run afterwards, twice,
     * without harm.
     */
    @Test
    public void anAnswerThatNeverStartsLeavesNoConnectionOpen() throws Exception {
        server.neverAnswer("/stuck.mp4");
        List<Runnable> closers = new ArrayList<>();
        File into = temp.newFile();

        Downloader.readTimeoutMs = 500;
        Downloader.Result result;
        try {
            result = within(15_000, () -> Downloader.fetch(origin + "/stuck.mp4", Downloader.Kind.VIDEO, into, policy,
                    Downloader.MAX_BYTES, recording(closers)));
        } finally {
            Downloader.readTimeoutMs = 20_000;
        }

        assertEquals(result.toString(), Downloader.Status.NETWORK_ERROR, result.status);
        assertTrue("the connection stayed open", server.letGoWithin("/stuck.mp4", 5_000));
        assertFalse(into.exists());
        assertEquals(1, closers.size());
        for (Runnable close : closers) {
            close.run();
            close.run();
        }
    }

    /**
     * A cache that can't take the file is the phone's storage, not the network, and the fetch
     * says so. It used to end as a network failure. The connection is let go all the same, and
     * running its closer again afterwards is harmless.
     */
    @Test
    public void aCacheThatCannotTakeTheFileIsAStorageFailure() throws Exception {
        server.serveGenerated("/v.mp4", "video/mp4", mp4(64), 8L * 1024 * 1024, 64 * 1024,
                new java.util.concurrent.CountDownLatch(1));
        File into = new File(temp.getRoot(), "gone/video.part");
        List<Runnable> closers = new ArrayList<>();

        Downloader.Result result = within(15_000, () -> Downloader.fetch(origin + "/v.mp4", Downloader.Kind.VIDEO, into,
                policy, Downloader.MAX_BYTES, recording(closers)));

        assertEquals(result.toString(), Downloader.Status.STORAGE_ERROR, result.status);
        assertTrue(result.reason, result.reason.startsWith("the cache could not hold the file"));
        assertTrue("the connection stayed open", server.letGoWithin("/v.mp4", 5_000));
        assertFalse(into.exists());
        for (Runnable close : closers) {
            close.run();
            close.run();
        }
    }

    /** A progress that keeps every closer the fetch hands over, and never cancels. */
    private static Downloader.Progress recording(List<Runnable> closers) {
        return new Downloader.Progress() {
            @Override public void transferred(long done, long total) {
            }

            @Override public void reading(Runnable close) {
                closers.add(close);
            }

            @Override public boolean cancelled() {
                return false;
            }
        };
    }

    /** [fetch] on a thread of its own, so a fetch that never ends fails the test rather than hangs it. */
    private static Downloader.Result within(long ms, java.util.concurrent.Callable<Downloader.Result> fetch)
            throws Exception {
        java.util.concurrent.FutureTask<Downloader.Result> task = new java.util.concurrent.FutureTask<>(fetch);
        Thread thread = new Thread(task, "fetch");
        thread.setDaemon(true);
        thread.start();
        try {
            return task.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("the fetch was still going after " + ms + " ms");
        }
    }

    // ------------------------------------------------------------------ the sink
    // What a save does with a refused fetch, the gallery never reached and no work file left, is
    // pinned on the save's own path in MediaSaveTest. These are the publish every save ends in.

    /** A checked file in the cache, as a fetch leaves it. */
    private File fetched(byte[] body) throws IOException {
        File file = temp.newFile();
        Files.write(file.toPath(), body);
        return file;
    }

    @Test
    public void aCheckedFileIsPublishedWhole() throws IOException {
        byte[] body = mp4(50_000);
        RecordingSink sink = new RecordingSink();

        Downloader.Result result = Downloader.publish(fetched(body), "video/mp4", sink, Downloader.SILENT);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals(1, sink.opened);
        assertEquals("video/mp4", sink.mime);
        assertTrue(sink.committed);
        assertFalse(sink.abandoned);
        assertArrayEquals(body, sink.bytes.toByteArray());
    }

    @Test
    public void aSinkThatFailsIsAbandoned() throws IOException {
        RecordingSink sink = new RecordingSink();
        sink.failCommit = true;

        Downloader.Result result = Downloader.publish(fetched(mp4(5000)), "video/mp4", sink, Downloader.SILENT);

        assertEquals(Downloader.Status.WRITE_ERROR, result.status);
        assertTrue("a failed publish left its entry", sink.abandoned);
    }

    /**
     * MediaStoreWriter inserts its pending row before it asks for a stream, so an open() that
     * throws has already left one. The publish has to abandon it all the same.
     */
    @Test
    public void anEntryWhoseOpenFailedIsAbandoned() throws IOException {
        RecordingSink sink = new RecordingSink();
        sink.failOpen = true;

        Downloader.Result result = Downloader.publish(fetched(mp4(5000)), "video/mp4", sink, Downloader.SILENT);

        assertEquals(Downloader.Status.WRITE_ERROR, result.status);
        assertTrue("an open that failed after its insert left the entry", sink.abandoned);
    }

    private static final class RecordingSink implements Downloader.Sink {
        int opened;
        String mime;
        boolean committed;
        boolean abandoned;
        boolean failCommit;
        boolean failOpen;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public OutputStream open(String mime) throws IOException {
            opened++;
            this.mime = mime;
            if (failOpen) throw new IOException("the gallery gave no way to write");
            return bytes;
        }

        @Override
        public void commit() throws IOException {
            if (failCommit) throw new IOException("the gallery went away");
            committed = true;
        }

        @Override
        public void abandon() {
            abandoned = true;
        }
    }
}
