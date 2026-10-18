/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package ps.reso.instaeclipse.utils.download;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads a plain MP4 back the way a player's extractor does, from its sample tables, and fails on
 * anything a plain MP4 mustn't be: a box that runs past its parent, tables that disagree on the
 * number of samples, a chunk outside the mdat, or anything of a fragmented file left in it. Written
 * apart from the join, so the two can't share a mistake.
 */
final class PlainMp4ForTests {

    /** The whole file as read. */
    static final class Movie {
        final List<String> topLevel = new ArrayList<>();
        final List<String> moovChildren = new ArrayList<>();
        final List<Track> tracks = new ArrayList<>();
        long timescale;
        long duration;
        long nextTrackId;
        long mdatStart;
        long mdatEnd;
        byte[] file;
    }

    /** One track, each sample expanded from the tables. */
    static final class Track {
        long id;
        String handler;
        long timescale;
        long mediaDuration;
        long trackDuration;
        int width;
        int height;
        int volume;
        byte[] sampleDescriptions;
        String sampleEntry;
        /** Each edit as {segment duration in the movie timescale, media time, rate}, or null for no edts. */
        long[][] edits;
        int compositionVersion = -1;
        long[] offsets;
        int[] sizes;
        long[] decodeTimes;
        long[] durations;
        long[] compositionOffsets;
        boolean[] sync;
        /** Each chunk as {file offset, first sample, sample count}. */
        final List<long[]> chunks = new ArrayList<>();

        int count() {
            return sizes.length;
        }

        byte[] sample(byte[] file, int index) {
            return Arrays.copyOfRange(file, (int) offsets[index], (int) offsets[index] + sizes[index]);
        }

        double presentation(int index, long movieTimescale) {
            return present(decodeTimes[index] + compositionOffsets[index], timescale, edits, movieTimescale);
        }
    }

    /**
     * When a sample composed at [composed] (in [timescale]) is shown, in seconds, under [edits]:
     * the empty edits before the first media edit delay it, and that edit's media time is where showing
     * starts. With no edit list it's shown at its composition time. One media edit is all either side
     * of the join uses.
     */
    static double present(long composed, long timescale, long[][] edits, long movieTimescale) {
        if (edits == null) return composed / (double) timescale;
        double delay = 0;
        for (long[] edit : edits) {
            if (edit[1] == -1 || edit[1] == 0xFFFFFFFFL) {
                delay += edit[0] / (double) movieTimescale;
                continue;
            }
            return delay + (composed - edit[1]) / (double) timescale;
        }
        throw new AssertionError("an edit list with no media edit");
    }

    static Movie read(byte[] file) {
        Movie movie = new Movie();
        movie.file = file;
        ByteBuffer moov = null;
        for (Child box : children(ByteBuffer.wrap(file), 0)) {
            movie.topLevel.add(box.type);
            if (box.type.equals("moov")) moov = box.payload;
            if (box.type.equals("mdat")) {
                movie.mdatStart = box.payloadStart;
                movie.mdatEnd = box.payloadStart + box.payload.remaining();
            }
        }
        if (moov == null) throw new AssertionError("no moov");
        for (Child box : children(moov, 0)) {
            movie.moovChildren.add(box.type);
            if (box.type.equals("mvhd")) {
                ByteBuffer b = box.payload;
                int version = b.get() & 0xFF;
                b.position(version == 1 ? 20 : 12);
                movie.timescale = u32(b);
                movie.duration = version == 1 ? b.getLong() : u32(b);
                b.position(b.limit() - 4);
                movie.nextTrackId = u32(b);
            } else if (box.type.equals("trak")) {
                movie.tracks.add(track(box.payload, movie));
            }
        }
        return movie;
    }

    private static Track track(ByteBuffer trak, Movie movie) {
        Track track = new Track();
        for (Child box : children(trak, 0)) {
            if (box.type.equals("tkhd")) {
                ByteBuffer b = box.payload;
                int version = b.get() & 0xFF;
                b.position(version == 1 ? 20 : 12);
                track.id = u32(b);
                b.getInt();
                track.trackDuration = version == 1 ? b.getLong() : u32(b);
                b.position(b.position() + 12);
                track.volume = b.getShort();
                b.position(b.position() + 2 + 36);
                track.width = b.getInt() >>> 16;
                track.height = b.getInt() >>> 16;
            } else if (box.type.equals("edts")) {
                for (Child elst : children(box.payload, 0)) {
                    ByteBuffer b = elst.payload;
                    int version = b.get() & 0xFF;
                    b.position(4);
                    long[][] edits = new long[(int) u32(b)][];
                    for (int i = 0; i < edits.length; i++) {
                        long duration = version == 1 ? b.getLong() : u32(b);
                        long media = version == 1 ? b.getLong() : b.getInt();
                        edits[i] = new long[] {duration, media, u32(b)};
                    }
                    track.edits = edits;
                }
            } else if (box.type.equals("mdia")) {
                media(box.payload, track, movie);
            }
        }
        return track;
    }

    private static void media(ByteBuffer mdia, Track track, Movie movie) {
        for (Child box : children(mdia, 0)) {
            if (box.type.equals("mdhd")) {
                ByteBuffer b = box.payload;
                int version = b.get() & 0xFF;
                b.position(version == 1 ? 20 : 12);
                track.timescale = u32(b);
                track.mediaDuration = version == 1 ? b.getLong() : u32(b);
            } else if (box.type.equals("hdlr")) {
                track.handler = ascii(box.payload, 8);
            } else if (box.type.equals("minf")) {
                for (Child part : children(box.payload, 0)) {
                    if (part.type.equals("stbl")) tables(part.payload, track, movie);
                    if (part.type.equals("dinf")) {
                        ByteBuffer dref = children(part.payload, 0).get(0).payload;
                        if (dref.getInt(4) != 1) throw new AssertionError("dref lists other than one entry");
                        Child url = children(slice(dref, 8), 0).get(0);
                        if (!url.type.equals("url ") || url.payload.getInt(0) != 1) {
                            throw new AssertionError("the data isn't in this file");
                        }
                    }
                }
            }
        }
    }

    private static void tables(ByteBuffer stbl, Track track, Movie movie) {
        long[] stts = null;
        long[] ctts = null;
        long[] stss = null;
        long[] stsc = null;
        long[] chunkOffsets = null;
        int uniformSize = -1;
        int[] sizes = null;
        for (Child box : children(stbl, 0)) {
            ByteBuffer b = box.payload;
            int version = b.get(0) & 0xFF;
            b.position(4);
            switch (box.type) {
                case "stsd":
                    track.sampleDescriptions = new byte[b.limit()];
                    b.position(0);
                    b.get(track.sampleDescriptions);
                    track.sampleEntry = children(slice(box.payload, 8), 0).get(0).type;
                    break;
                case "stts":
                    stts = pairs(b, false);
                    break;
                case "ctts":
                    track.compositionVersion = version;
                    ctts = pairs(b, version == 1);
                    break;
                case "stss":
                    stss = new long[(int) u32(b)];
                    for (int i = 0; i < stss.length; i++) stss[i] = u32(b);
                    break;
                case "stsc":
                    stsc = new long[(int) u32(b) * 3];
                    for (int i = 0; i < stsc.length; i++) stsc[i] = u32(b);
                    break;
                case "stsz":
                    uniformSize = b.getInt();
                    sizes = new int[(int) u32(b)];
                    if (uniformSize == 0) {
                        for (int i = 0; i < sizes.length; i++) sizes[i] = b.getInt();
                    } else {
                        Arrays.fill(sizes, uniformSize);
                    }
                    break;
                case "stco":
                case "co64":
                    chunkOffsets = new long[(int) u32(b)];
                    for (int i = 0; i < chunkOffsets.length; i++) {
                        chunkOffsets[i] = box.type.equals("co64") ? b.getLong() : u32(b);
                    }
                    break;
                default:
                    break;
            }
        }
        if (stts == null || stsc == null || sizes == null || chunkOffsets == null) throw new AssertionError("a table is missing");
        int count = sizes.length;
        track.sizes = sizes;
        track.durations = expand(stts, count, "stts");
        track.compositionOffsets = ctts == null ? new long[count] : expand(ctts, count, "ctts");
        track.decodeTimes = new long[count];
        for (int i = 1; i < count; i++) track.decodeTimes[i] = track.decodeTimes[i - 1] + track.durations[i - 1];
        long total = count == 0 ? 0 : track.decodeTimes[count - 1] + track.durations[count - 1];
        if (total != track.mediaDuration) throw new AssertionError("mdhd says " + track.mediaDuration + ", stts " + total);
        track.sync = new boolean[count];
        if (stss == null) {
            Arrays.fill(track.sync, true);
        } else {
            for (long number : stss) track.sync[(int) number - 1] = true;
        }

        track.offsets = new long[count];
        int sample = 0;
        for (int entry = 0; entry < stsc.length / 3; entry++) {
            int firstChunk = (int) stsc[entry * 3] - 1;
            int lastChunk = entry + 1 < stsc.length / 3 ? (int) stsc[(entry + 1) * 3] - 1 : chunkOffsets.length;
            for (int chunk = firstChunk; chunk < lastChunk; chunk++) {
                long at = chunkOffsets[chunk];
                track.chunks.add(new long[] {at, sample, stsc[entry * 3 + 1]});
                for (int i = 0; i < stsc[entry * 3 + 1]; i++) {
                    if (sample >= count) throw new AssertionError("stsc lists more samples than stsz");
                    if (at < movie.mdatStart || at + sizes[sample] > movie.mdatEnd) {
                        throw new AssertionError("sample " + sample + " lies outside the mdat");
                    }
                    track.offsets[sample++] = at;
                    at += sizes[sample - 1];
                }
            }
        }
        if (sample != count) throw new AssertionError("stsc lists " + sample + " samples, stsz " + count);
    }

    private static long[] pairs(ByteBuffer b, boolean signed) {
        long[] pairs = new long[(int) u32(b) * 2];
        for (int i = 0; i < pairs.length; i += 2) {
            pairs[i] = u32(b);
            pairs[i + 1] = signed ? b.getInt() : u32(b);
        }
        return pairs;
    }

    private static long[] expand(long[] pairs, int count, String table) {
        long[] values = new long[count];
        int at = 0;
        for (int i = 0; i < pairs.length; i += 2) {
            for (long k = 0; k < pairs[i]; k++) {
                if (at >= count) throw new AssertionError(table + " lists more samples than stsz");
                values[at++] = pairs[i + 1];
            }
        }
        if (at != count) throw new AssertionError(table + " lists " + at + " samples, stsz " + count);
        return values;
    }

    /** A child box: its type, its payload, and where that payload starts in the whole file. */
    static final class Child {
        final String type;
        final ByteBuffer payload;
        final long payloadStart;

        Child(String type, ByteBuffer payload, long payloadStart) {
            this.type = type;
            this.payload = payload;
            this.payloadStart = payloadStart;
        }
    }

    static List<Child> children(ByteBuffer parent, long parentStart) {
        List<Child> boxes = new ArrayList<>();
        int at = 0;
        while (at < parent.limit()) {
            if (parent.limit() - at < 8) throw new AssertionError("a box header is cut short");
            long size = parent.getInt(at) & 0xFFFFFFFFL;
            String type = ascii(parent, at + 4);
            int header = 8;
            if (size == 1) {
                size = parent.getLong(at + 8);
                header = 16;
            }
            if (size == 0) throw new AssertionError("a " + type + " box runs to the end of the file");
            if (size < header || at + size > parent.limit()) throw new AssertionError("a " + type + " box runs past its parent");
            ByteBuffer payload = slice(parent, at + header);
            payload.limit((int) size - header);
            boxes.add(new Child(type, payload, parentStart + at + header));
            at += (int) size;
        }
        return boxes;
    }

    private static ByteBuffer slice(ByteBuffer buffer, int from) {
        ByteBuffer copy = buffer.duplicate();
        copy.position(from);
        return copy.slice();
    }

    private static String ascii(ByteBuffer buffer, int at) {
        byte[] type = new byte[4];
        for (int i = 0; i < 4; i++) type[i] = buffer.get(at + i);
        return new String(type, StandardCharsets.US_ASCII);
    }

    private static long u32(ByteBuffer buffer) {
        return buffer.getInt() & 0xFFFFFFFFL;
    }
}
