/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package ps.reso.instaeclipse.utils.download;

import static ps.reso.instaeclipse.utils.download.FragmentedMp4ForTests.DURATION;
import static ps.reso.instaeclipse.utils.download.FragmentedMp4ForTests.FLAGS;
import static ps.reso.instaeclipse.utils.download.FragmentedMp4ForTests.NON_SYNC;
import static ps.reso.instaeclipse.utils.download.FragmentedMp4ForTests.OFFSET;
import static ps.reso.instaeclipse.utils.download.FragmentedMp4ForTests.SIZE;
import static ps.reso.instaeclipse.utils.download.FragmentedMp4ForTests.SYNC;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The join that writes the MP4 itself, for the pictures Android's MediaMuxer won't put in one:
 * VP9 always, AV1 before Android 14. Its inputs are built the way Meta's DASH tracks come, one
 * fragmented MP4 a track, and its output is read back from the sample tables alone
 * ({@link PlainMp4ForTests}). Where FFmpeg is installed, FFmpeg reads and decodes a join of real
 * VP9, AV1 and H.264 too.
 */
public class Mp4JoinTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    /** Three seconds of VP9 at 30 fps in one-second fragments, the way a story's VP9 track comes. */
    private static FragmentedMp4ForTests vp9Picture() {
        FragmentedMp4ForTests picture = FragmentedMp4ForTests.picture("vp09", 1080, 1920, 15_360);
        for (int f = 0; f < 3; f++) {
            FragmentedMp4ForTests.Fragment fragment = picture.fragment((long) f * 15_360);
            // Durations and flags once in tfhd, the key frame's as the run's first sample flags.
            fragment.tfhdDefaults = true;
            fragment.fields = SIZE;
            for (int i = 0; i < 30; i++) fragment.add(i == 0 ? 3_000 : 400 + i * 3 + f, 512, i == 0 ? SYNC : NON_SYNC, 0);
        }
        return picture;
    }

    /** Three seconds of AAC at 44.1 kHz, with the edit list that trims the encoder's 1,024 priming samples. */
    private static FragmentedMp4ForTests aacSound() {
        FragmentedMp4ForTests sound = FragmentedMp4ForTests.sound(44_100);
        sound.edits = new long[][] {{0, 1_024, 0x00010000}};
        sound.trexFlags = SYNC;
        for (int f = 0; f < 2; f++) {
            FragmentedMp4ForTests.Fragment fragment = sound.fragment(f * 66L * 1_024);
            fragment.fields = DURATION | SIZE;
            for (int i = 0; i < (f == 0 ? 66 : 65); i++) fragment.add(180 + (i * 13) % 60, 1_024, SYNC, 0);
        }
        return sound;
    }

    private PlainMp4ForTests.Movie join(FragmentedMp4ForTests picture, FragmentedMp4ForTests sound) throws IOException {
        File out = joined(write(picture, "video"), sound == null ? null : write(sound, "audio"), Downloader.SILENT);
        return PlainMp4ForTests.read(Files.readAllBytes(out.toPath()));
    }

    private File write(FragmentedMp4ForTests track, String name) throws IOException {
        File file = temp.newFile(name + ".mp4");
        Files.write(file.toPath(), track.build());
        return file;
    }

    private File joined(File video, File audio, Downloader.Progress progress) throws IOException {
        File out = temp.newFile();
        assertTrue("the join stopped", Mp4Join.join(video, audio, out, progress));
        return out;
    }

    @Test public void fragmentTimingRecoversDurationAndRequiresEverySample() throws IOException {
        File video = write(vp9Picture(), "no-header-duration");
        assertEquals(3_000_000L, Mp4Join.fragmentDurationUs(video, true, 90));
        assertThrows(IOException.class, () -> Mp4Join.fragmentDurationUs(video, true, 89));
        assertThrows(IOException.class, () -> Mp4Join.fragmentDurationUs(video, true, 91));
        assertThrows(IOException.class, () -> Mp4Join.fragmentDurationUs(video, false, 90));
        try (java.io.RandomAccessFile truncated = new java.io.RandomAccessFile(video, "rw")) {
            truncated.setLength(truncated.length() - 1);
        }
        assertThrows(IOException.class, () -> Mp4Join.fragmentDurationUs(video, true, 90));
    }

    @Test public void fragmentDurationPreservesAudioPrimingEdits() throws IOException {
        File audio = write(aacSound(), "primed-audio");
        assertEquals(3_019_000L, Mp4Join.fragmentDurationUs(audio, false, 131));
    }

    /**
     * Every sample of [in] is in [out] with its own bytes, at its own decode time, with its sync flag
     * and shown when it was shown before, to within the output's millisecond edit list.
     */
    private static void assertSamples(FragmentedMp4ForTests in, PlainMp4ForTests.Track out,
            PlainMp4ForTests.Movie movie, double tolerance) {
        List<FragmentedMp4ForTests.Sample> samples = in.samples();
        long[] decode = in.decodeTimes();
        assertEquals(in.timescale, out.timescale);
        assertEquals(samples.size(), out.count());
        for (int i = 0; i < samples.size(); i++) {
            assertArrayEquals("the bytes of sample " + i, in.content(i), out.sample(movie.file, i));
            assertEquals("the decode time of sample " + i, decode[i] - decode[0], out.decodeTimes[i]);
            assertEquals("the sync flag of sample " + i, (samples.get(i).flags & 0x00010000) == 0, out.sync[i]);
            assertEquals("when sample " + i + " is shown", in.presentation(i), out.presentation(i, movie.timescale),
                    tolerance);
        }
        int last = samples.size() - 1;
        assertEquals(samples.get(last).duration, out.durations[last]);
    }

    @Test
    public void aVp9PictureAndItsSoundBecomeOnePlainMp4() throws IOException {
        FragmentedMp4ForTests picture = vp9Picture();
        FragmentedMp4ForTests sound = aacSound();

        PlainMp4ForTests.Movie movie = join(picture, sound);

        assertEquals(Arrays.asList("ftyp", "moov", "mdat"), movie.topLevel);
        assertFalse("the plain file kept an mvex", movie.moovChildren.contains("mvex"));
        assertEquals(2, movie.tracks.size());
        assertEquals(3, movie.nextTrackId);
        PlainMp4ForTests.Track video = movie.tracks.get(0);
        PlainMp4ForTests.Track audio = movie.tracks.get(1);
        assertEquals(1, video.id);
        assertEquals(2, audio.id);
        assertEquals("vide", video.handler);
        assertEquals("soun", audio.handler);
        assertEquals("vp09", video.sampleEntry);
        assertArrayEquals("the VP9 sample entry and its vpcC weren't copied byte for byte",
                picture.sampleDescriptions(), video.sampleDescriptions);
        assertArrayEquals(sound.sampleDescriptions(), audio.sampleDescriptions);
        assertEquals(1080, video.width);
        assertEquals(1920, video.height);
        assertEquals(0x0100, audio.volume);

        assertSamples(picture, video, movie, 1e-9);
        assertSamples(sound, audio, movie, 1e-9);

        // The picture needs no edit list; the sound keeps its priming trimmed, 1,024 samples in.
        assertNull(video.edits);
        long soundMs = Math.round(130 * 1_024 * 1_000.0 / 44_100);
        assertArrayEquals(new long[] {soundMs, 1_024, 0x00010000}, audio.edits[0]);
        assertEquals(1, audio.edits.length);
        assertEquals(3_000, video.trackDuration);
        assertEquals(soundMs, audio.trackDuration);
        assertEquals(1_000, movie.timescale);
        assertEquals(soundMs, movie.duration);
        assertEquals(90 * 512, video.mediaDuration);
        assertEquals(131 * 1_024, audio.mediaDuration);
    }

    /**
     * Pictures in B-frame order keep their composition offsets, and the edit list that starts them
     * at their first shown frame. When the picture shows and when its sound plays both stay where
     * they were, so the two stay in sync.
     */
    @Test
    public void bFramesKeepTheirOffsetsAndTheirEditList() throws IOException {
        FragmentedMp4ForTests picture = bFramePicture(0, 1_024);
        picture.edits = new long[][] {{0, 1_024, 0x00010000}};

        PlainMp4ForTests.Movie movie = join(picture, aacSound());

        PlainMp4ForTests.Track video = movie.tracks.get(0);
        assertEquals(0, video.compositionVersion);
        assertEquals(1, video.edits.length);
        assertEquals(1_024, video.edits[0][1]);
        assertSamples(picture, video, movie, 1e-9);
        assertSamples(aacSound(), movie.tracks.get(1), movie, 1e-9);
        assertEquals("the first frame isn't shown at the start", 0.0, video.presentation(0, movie.timescale), 1e-9);
    }

    /**
     * A version 1 run can give negative composition offsets and no edit list. The plain file gives
     * every offset plus the most negative one, in a version 0 ctts every player reads, and an edit
     * list that starts that much later: each frame is still shown when it was.
     */
    @Test
    public void negativeOffsetsBecomeAnEditList() throws IOException {
        FragmentedMp4ForTests picture = bFramePicture(1, 0);

        PlainMp4ForTests.Movie movie = join(picture, null);

        PlainMp4ForTests.Track video = movie.tracks.get(0);
        assertEquals("negative offsets were kept", 0, video.compositionVersion);
        for (long offset : video.compositionOffsets) assertTrue(offset >= 0);
        assertEquals(1, video.edits.length);
        assertEquals(512, video.edits[0][1]);
        assertSamples(picture, video, movie, 1e-9);
    }

    /** Thirty frames in I P B B order, offsets from each frame's decode time to its shown time plus [delay]. */
    private static FragmentedMp4ForTests bFramePicture(int trunVersion, long delay) {
        FragmentedMp4ForTests picture = FragmentedMp4ForTests.picture("avc1", 720, 1280, 15_360);
        FragmentedMp4ForTests.Fragment fragment = picture.fragment(0L);
        fragment.trunVersion = trunVersion;
        fragment.fields = DURATION | SIZE | FLAGS | OFFSET;
        for (int k = 0; k < 30; k++) {
            int shown = k == 0 ? 0 : 3 * ((k - 1) / 3) + new int[] {3, 1, 2}[(k - 1) % 3];
            fragment.add(700 + k, 512, k == 0 ? SYNC : NON_SYNC, (shown - k) * 512L + delay);
        }
        return picture;
    }

    /**
     * A track whose first fragment starts past zero is shown that late: the plain file's samples
     * start at zero, and an empty edit keeps the delay. The same when an edit list's media time is
     * before the first sample.
     */
    @Test
    public void aTrackThatStartsLateKeepsItsDelay() throws IOException {
        FragmentedMp4ForTests picture = FragmentedMp4ForTests.picture("vp09", 1080, 1920, 15_360);
        FragmentedMp4ForTests.Fragment fragment = picture.fragment(15_360L);
        for (int i = 0; i < 30; i++) fragment.add(500, 512, i == 0 ? SYNC : NON_SYNC, 0);
        FragmentedMp4ForTests sound = FragmentedMp4ForTests.sound(44_100);
        sound.edits = new long[][] {{0, 1_024, 0x00010000}};
        FragmentedMp4ForTests.Fragment late = sound.fragment(2_048L);
        for (int i = 0; i < 40; i++) late.add(200, 1_024, SYNC, 0);

        PlainMp4ForTests.Movie movie = join(picture, sound);

        PlainMp4ForTests.Track video = movie.tracks.get(0);
        assertEquals(2, video.edits.length);
        assertEquals("the delay isn't an empty edit", -1, video.edits[0][1]);
        assertEquals(1_000, video.edits[0][0]);
        assertEquals(1.0, video.presentation(0, movie.timescale), 1e-9);
        assertSamples(picture, video, movie, 1e-9);
        assertEquals(2, movie.tracks.get(1).edits.length);
        assertSamples(sound, movie.tracks.get(1), movie, 0.0005);
    }

    /**
     * The fragment layouts ISO allows and a DASH packager uses: an absolute base in tfhd, runs that
     * follow each other with no data offset, defaults from trex or tfhd, a fragment with no tfdt, a
     * gap before a fragment's tfdt, a version 1 run, and a last mdat that runs to the end of the file.
     */
    @Test
    public void everyFragmentLayoutIsRead() throws IOException {
        FragmentedMp4ForTests picture = FragmentedMp4ForTests.picture("vp09", 640, 360, 15_360);
        picture.trexDuration = 512;
        picture.trexFlags = NON_SYNC;
        picture.lastBoxToTheEnd = true;

        FragmentedMp4ForTests.Fragment perSample = picture.fragment(0L);
        for (int i = 0; i < 10; i++) perSample.add(300 + i, 400 + i, i == 0 ? SYNC : NON_SYNC, 0);

        FragmentedMp4ForTests.Fragment following = picture.fragment(null);
        following.explicitBase = true;
        following.runs = 3;
        following.fields = SIZE;
        for (int i = 0; i < 12; i++) following.add(200 + i, 512, i == 0 ? SYNC : NON_SYNC, 0);

        FragmentedMp4ForTests.Fragment gap = picture.fragment(4_045L + 12 * 512 + 100);
        gap.tfhdDefaults = true;
        gap.fields = SIZE;
        for (int i = 0; i < 8; i++) gap.add(250 + i, 256, i == 0 ? SYNC : NON_SYNC, 0);

        FragmentedMp4ForTests.Fragment version1 = picture.fragment(null);
        version1.trunVersion = 1;
        version1.explicitBase = true;
        version1.runs = 2;
        version1.fields = DURATION | SIZE | FLAGS | OFFSET;
        for (int i = 0; i < 6; i++) version1.add(100 + i, 512, SYNC, 0);

        PlainMp4ForTests.Movie movie = join(picture, null);

        assertEquals(Arrays.asList("ftyp", "moov", "mdat"), movie.topLevel);
        assertSamples(picture, movie.tracks.get(0), movie, 1e-9);
    }

    /**
     * The tracks alternate in the file by time, half a second of one and then the other, the way
     * MediaMuxer lays them out: a player starting the file finds the picture and the sound of the
     * same moment near each other and never seeks across the whole file.
     */
    @Test
    public void theTracksAlternateEveryHalfSecond() throws IOException {
        PlainMp4ForTests.Movie movie = join(vp9Picture(), aacSound());

        List<double[]> chunks = new ArrayList<>();
        for (PlainMp4ForTests.Track track : movie.tracks) {
            int count = 0;
            for (long[] chunk : track.chunks) {
                int first = (int) chunk[1];
                int last = first + (int) chunk[2] - 1;
                double start = track.decodeTimes[first] / (double) track.timescale;
                double end = (track.decodeTimes[last] + track.durations[last]) / (double) track.timescale;
                double longest = track.durations[last] / (double) track.timescale;
                assertTrue("a chunk of " + (end - start) + " s", end - start <= 0.5 + longest + 1e-9);
                chunks.add(new double[] {chunk[0], start});
                count++;
            }
            assertTrue(track.handler + " is in " + count + " chunks", count >= 6);
        }
        chunks.sort((a, b) -> Double.compare(a[0], b[0]));
        double latest = 0;
        for (double[] chunk : chunks) {
            assertTrue("a chunk starting at " + chunk[1] + " s comes after one at " + latest + " s",
                    chunk[1] >= latest - 0.5 - 1e-9);
            latest = Math.max(latest, chunk[1]);
        }
    }

    /** Cancel is read before every chunk, and a cancelled join says so rather than finishing. */
    @Test
    public void aCancelStopsTheCopyBetweenChunks() throws IOException {
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger joining = new AtomicInteger();
        Downloader.Progress progress = new Downloader.Progress() {
            @Override public void transferred(long done, long total) {
            }

            @Override public void reading(Runnable close) {
            }

            @Override public boolean cancelled() {
                return asked.incrementAndGet() > 4;
            }

            @Override public void joining() {
                joining.incrementAndGet();
            }
        };

        File out = temp.newFile();
        assertFalse(Mp4Join.join(write(vp9Picture(), "video"), write(aacSound(), "audio"), out, progress));
        assertEquals("cancel wasn't read once before the copy and once before each chunk", 5, asked.get());
        assertEquals(1, joining.get());
    }

    @Test
    public void aPictureWithNoSoundIsOneTrack() throws IOException {
        FragmentedMp4ForTests picture = vp9Picture();
        PlainMp4ForTests.Movie movie = join(picture, null);

        assertEquals(1, movie.tracks.size());
        assertEquals(2, movie.nextTrackId);
        assertSamples(picture, movie.tracks.get(0), movie, 1e-9);
    }

    /**
     * What the join can't copy fails as an IOException, never a runtime exception from a cut-short
     * buffer, so the save falls back to the single file and says why.
     */
    @Test
    public void whatTheJoinCantCopyFailsAsAnIOException() throws IOException {
        File sound = write(aacSound(), "audio");

        FragmentedMp4ForTests encrypted = FragmentedMp4ForTests.picture("encv", 1080, 1920, 15_360);
        encrypted.fragment(0L).add(100, 512, SYNC, 0);
        assertRefused("encrypted", write(encrypted, "encrypted"), sound);

        FragmentedMp4ForTests empty = FragmentedMp4ForTests.picture("vp09", 1080, 1920, 15_360);
        assertRefused("no samples", write(empty, "empty"), sound);

        FragmentedMp4ForTests plain = vp9Picture();
        plain.samplesInMoov = true;
        assertRefused("outside its fragments", write(plain, "plain"), sound);

        File picture = write(vp9Picture(), "video");
        assertRefused("holds no sound track", picture, picture);

        // Cut inside the last mdat, which its moof, read first, already points past.
        byte[] whole = vp9Picture().build();
        File cut = temp.newFile("cut.mp4");
        Files.write(cut.toPath(), Arrays.copyOf(whole, whole.length - 100));
        assertRefused("samples run past the end of the video file", cut, sound);

        // Cut inside the moov.
        File head = temp.newFile("head.mp4");
        Files.write(head.toPath(), Arrays.copyOf(whole, indexOf(whole, "stsd")));
        assertRefused("a box runs past the end of the video file", head, sound);

        FragmentedMp4ForTests toTheEnd = vp9Picture();
        toTheEnd.lastBoxToTheEnd = true;
        byte[] endless = toTheEnd.build();
        File short_ = temp.newFile("short.mp4");
        Files.write(short_.toPath(), Arrays.copyOf(endless, endless.length - 100));
        assertRefused("samples run past the end of the video file", short_, sound);

        File garbage = temp.newFile("garbage.mp4");
        byte[] noise = new byte[4_096];
        for (int i = 0; i < noise.length; i++) noise[i] = (byte) (i * 37 + 11);
        Files.write(garbage.toPath(), noise);
        assertThrows(IOException.class, () -> Mp4Join.join(garbage, sound, temp.newFile(), Downloader.SILENT));

        // A box inside the moov that says it's longer than its parent holds.
        byte[] broken = vp9Picture().build();
        broken[indexOf(broken, "stsd")] = 0x7F;
        File inside = temp.newFile("inside.mp4");
        Files.write(inside.toPath(), broken);
        assertThrows(IOException.class, () -> Mp4Join.join(inside, sound, temp.newFile(), Downloader.SILENT));
    }

    private void assertRefused(String why, File video, File audio) {
        IOException refused = assertThrows(IOException.class,
                () -> Mp4Join.join(video, audio, temp.newFile(), Downloader.SILENT));
        assertTrue(refused.getMessage(), refused.getMessage().contains(why));
    }

    private static int indexOf(byte[] bytes, String type) {
        byte[] wanted = type.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + 4 <= bytes.length; i++) {
            for (int k = 0; k < 4; k++) if (bytes[i + k] != wanted[k]) continue outer;
            return i - 4;
        }
        throw new AssertionError("no " + type);
    }

    // ---------------------------------------------------------------- FFmpeg, where it's installed

    private static final File FFMPEG = new File(System.getProperty("instaeclipse.ffmpeg", "ffmpeg"));
    private static final File FFPROBE = new File(System.getProperty("instaeclipse.ffprobe", "ffprobe"));

    /**
     * Real VP9, AV1 and H.264 with B-frames, each beside real AAC, fragmented as a DASH packager
     * writes them. FFmpeg reads every packet of the join as it read it in the tracks, the same size,
     * flags and presentation time, and decodes the whole file without an error. Runs only where
     * FFmpeg is installed.
     *
     * <p>H.264 with negative offsets and no edit list is the one FFmpeg reads differently: it shows
     * such a fragment later by its most negative offset (its dts_shift), where ISO 14496-12 and
     * ExoPlayer show each frame at its composition time. The join turns those offsets into an edit
     * list, which FFmpeg reads the ISO way. So there every frame is shown the same amount earlier
     * than FFmpeg shows the fragment's, and the first, composed at zero, at zero.
     */
    @Test
    public void ffmpegReadsAndDecodesTheJoinOfRealTracks() throws Exception {
        Assume.assumeTrue("FFmpeg isn't installed here", FFMPEG.isFile() && FFPROBE.isFile());
        File folder = temp.newFolder("ffmpeg");
        File sound = encode(folder, "sound.mp4", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "3",
                "-c:a", "aac", "-b:a", "64k", "-f", "mp4", "-movflags", "+dash+global_sidx");
        String[][] pictures = {
            {"vp9", "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "300k", "-g", "30",
                "-f", "mp4", "-movflags", "+dash+global_sidx"},
            {"av1", "-c:v", "libsvtav1", "-preset", "12", "-g", "30", "-f", "mp4", "-movflags", "+dash+global_sidx"},
            {"h264", "-c:v", "libx264", "-preset", "ultrafast", "-bf", "2", "-g", "30", "-f", "mp4", "-movflags",
                "+dash+global_sidx"},
            {"h264-negative", "-c:v", "libx264", "-preset", "ultrafast", "-bf", "2", "-g", "30", "-f", "mp4",
                "-frag_duration", "1000000", "-movflags",
                "frag_keyframe+empty_moov+default_base_moof+negative_cts_offsets"},
        };
        for (String[] kind : pictures) {
            List<String> args = new ArrayList<>(Arrays.asList("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30",
                    "-t", "3"));
            args.addAll(Arrays.asList(kind).subList(1, kind.length));
            File picture = encode(folder, kind[0] + ".mp4", args.toArray(new String[0]));
            File out = new File(folder, kind[0] + "-joined.mp4");
            assertTrue(Mp4Join.join(picture, sound, out, Downloader.SILENT));
            assertEquals(kind[0], Mp4Join.indexedDurationUs(picture, true, 90),
                    Mp4Join.indexedDurationUs(out, true, 90));

            PlainMp4ForTests.Movie movie = PlainMp4ForTests.read(Files.readAllBytes(out.toPath()));
            assertEquals(kind[0], Arrays.asList("ftyp", "moov", "mdat"), movie.topLevel);
            String before = packets(picture, "v:0");
            String after = packets(out, "v:0");
            if (kind[0].equals("h264-negative")) {
                assertEquals("No sidx or header duration: recover exact fragment timing", 3_000_000L,
                        Mp4Join.fragmentDurationUs(picture, true, 90));
                long[] shownBefore = column(before, 0);
                long[] shownAfter = column(after, 0);
                assertEquals(kind[0], shownBefore.length, shownAfter.length);
                long earlier = shownBefore[0] - shownAfter[0];
                assertTrue(kind[0] + " has no negative offset to show", earlier > 0);
                for (int i = 0; i < shownBefore.length; i++) {
                    assertEquals(kind[0] + " packet " + i, shownBefore[i] - earlier, shownAfter[i]);
                }
                assertEquals(0, shownAfter[0]);
                before = before.replaceAll("(?m)^-?\\d+,", "");
                after = after.replaceAll("(?m)^-?\\d+,", "");
            }
            assertEquals(kind[0], before, after);
            assertEquals(kind[0], packets(sound, "a:0"), packets(out, "a:0"));
            String streams = run(FFPROBE.getPath(), "-v", "error", "-show_entries", "stream=codec_name,width,height",
                    "-of", "csv=p=0", out.getPath());
            assertEquals(kind[0], kind[0].replace("-negative", "") + ",320,240\naac\n", streams.replace("\r", ""));
            assertEquals(kind[0] + " didn't decode cleanly", "", run(FFMPEG.getPath(), "-v", "error", "-xerror", "-i",
                    out.getPath(), "-f", "null", "-"));
            retainInspection(kind[0], picture, sound, out);
        }
    }

    @Test
    public void ffmpegVerifies1080PortraitVideoWithAudioWithoutChangingEncodedPackets() throws Exception {
        Assume.assumeTrue("FFmpeg isn't configured here", FFMPEG.isFile() && FFPROBE.isFile());
        File folder = temp.newFolder("portrait");
        File sound = encode(folder, "audio.mp4", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "1",
                "-c:a", "aac", "-b:a", "96k", "-f", "mp4", "-movflags", "+dash+global_sidx");
        File picture = encode(folder, "video.mp4", "-f", "lavfi", "-i", "testsrc2=size=1080x1920:rate=30", "-t", "1",
                "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "1000k", "-g", "30",
                "-f", "mp4", "-movflags", "+dash+global_sidx");
        File joined = new File(folder, "joined.mp4");
        assertTrue(Mp4Join.join(picture, sound, joined, Downloader.SILENT));
        assertEquals(packets(picture, "v:0"), packets(joined, "v:0"));
        assertEquals(packets(sound, "a:0"), packets(joined, "a:0"));
        String metadata = run(FFPROBE.getPath(), "-v", "error", "-show_entries", "stream=codec_name,width,height",
                "-of", "csv=p=0", joined.getPath()).replace("\r", "");
        assertEquals("vp9,1080,1920\naac\n", metadata);
        assertEquals("", run(FFMPEG.getPath(), "-v", "error", "-xerror", "-i", joined.getPath(), "-f", "null", "-"));
        retainInspection("vp9-1080", picture, sound, joined);
    }

    /** Optional synthetic fixtures and inspector evidence; never used for downloaded user media. */
    private static void retainInspection(String name, File picture, File sound, File joined) throws Exception {
        String destination = System.getProperty("instaeclipse.fixtureOutput");
        if (destination == null || destination.isEmpty()) return;
        File folder = new File(destination, name);
        Files.createDirectories(folder.toPath());
        for (File source : Arrays.asList(picture, sound, joined)) {
            String target = source == picture ? "video-source.mp4" : source == sound ? "audio-source.mp4" : "joined.mp4";
            Files.copy(source.toPath(), new File(folder, target).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(new File(folder, "inspection.json").toPath(), run(FFPROBE.getPath(), "-v", "error", "-show_entries",
                "stream=codec_name,codec_tag_string,width,height,duration,start_time,nb_frames:format=duration,size", "-of", "json", joined.getPath()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(new File(folder, "video-packets.csv").toPath(), packets(joined, "v:0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(new File(folder, "audio-packets.csv").toPath(), packets(joined, "a:0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Column [index] of every line of [csv], as numbers. */
    private static long[] column(String csv, int index) {
        String[] lines = csv.trim().split("\\r?\\n");
        long[] values = new long[lines.length];
        for (int i = 0; i < lines.length; i++) values[i] = Long.parseLong(lines[i].split(",")[index]);
        return values;
    }

    private static File encode(File folder, String name, String... args) throws Exception {
        File out = new File(folder, name);
        List<String> command = new ArrayList<>(Arrays.asList(FFMPEG.getPath(), "-v", "error", "-y"));
        command.addAll(Arrays.asList(args));
        command.add(out.getPath());
        // SVT-AV1 prints its settings whatever FFmpeg's log level, so only the exit code counts here.
        run(command.toArray(new String[0]));
        return out;
    }

    /**
     * Each packet of [stream] as its presentation time, size and flags. Not its duration: FFmpeg
     * reports none for a fragment's samples whose duration comes from tfhd. Nor the side data it
     * adds to a plain file's last AAC packet, whose short duration (the same one the fragment gave)
     * it reads as padding to discard.
     */
    private static String packets(File file, String stream) throws Exception {
        return run(FFPROBE.getPath(), "-v", "error", "-select_streams", stream, "-show_entries",
                "packet=pts,size,flags,data_hash", "-show_data_hash", "sha256", "-of", "csv=p=0", file.getPath()).replaceAll(",+(\\r?\\n)", "$1");
    }

    /** What [command] prints, once it ends with 0. Java starts it with no console window. */
    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[8_192];
            for (int read; (read = in.read(buffer)) >= 0; ) printed.write(buffer, 0, read);
        }
        assertTrue("timed out: " + command[0], process.waitFor(120, TimeUnit.SECONDS));
        String text = new String(printed.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(text, 0, process.exitValue());
        return text;
    }
}
