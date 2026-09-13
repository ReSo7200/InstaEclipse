package ps.reso.instaeclipse.mods.media;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Detects downloaded media from its response header and file signature. */
final class MediaTypeDetector {

    enum Kind { VIDEO, IMAGE, UNKNOWN }

    static final class Result {
        final Kind kind;
        final String mimeType;
        final String filename;

        Result(Kind kind, String mimeType, String filename) {
            this.kind = kind;
            this.mimeType = mimeType;
            this.filename = filename;
        }

        boolean isVideo() {
            return kind == Kind.VIDEO;
        }
    }

    private MediaTypeDetector() {}

    static Result resolve(File file, String responseContentType, String requestedMime,
                          String requestedFilename) throws IOException {
        Kind kind = sniff(file);
        if (kind == Kind.UNKNOWN) kind = fromContentType(responseContentType);
        if (kind == Kind.UNKNOWN) kind = fromContentType(requestedMime);

        String mime = kind == Kind.VIDEO ? "video/mp4"
                : kind == Kind.IMAGE ? "image/jpeg"
                : normalizeContentType(requestedMime);
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        return new Result(kind, mime, withCorrectExtension(requestedFilename, kind));
    }

    static Kind fromContentType(String contentType) {
        String normalized = normalizeContentType(contentType);
        if (normalized == null) return Kind.UNKNOWN;
        if (normalized.startsWith("video/")) return Kind.VIDEO;
        if (normalized.startsWith("image/")) return Kind.IMAGE;
        return Kind.UNKNOWN;
    }

    static String withCorrectExtension(String filename, Kind kind) {
        if (filename == null || filename.isEmpty() || kind == Kind.UNKNOWN) return filename;
        String wanted = kind == Kind.VIDEO ? ".mp4" : ".jpg";
        String lower = filename.toLowerCase(Locale.US);
        if (lower.endsWith(wanted)) return filename;

        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        int dot = filename.lastIndexOf('.');
        if (dot > slash) return filename.substring(0, dot) + wanted;
        return filename + wanted;
    }

    static Kind sniff(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] header = new byte[16];
            int read = 0;
            while (read < header.length) {
                int n = in.read(header, read, header.length - read);
                if (n < 0) break;
                read += n;
            }
            return sniff(header, read);
        }
    }

    static Kind sniff(byte[] header, int length) {
        if (header == null || length < 3) return Kind.UNKNOWN;

        if (u(header[0]) == 0xff && u(header[1]) == 0xd8 && u(header[2]) == 0xff) {
            return Kind.IMAGE;
        }
        if (length >= 8
                && u(header[0]) == 0x89 && header[1] == 'P' && header[2] == 'N'
                && header[3] == 'G' && u(header[4]) == 0x0d && u(header[5]) == 0x0a
                && u(header[6]) == 0x1a && u(header[7]) == 0x0a) {
            return Kind.IMAGE;
        }
        if (length >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F'
                && header[3] == '8' && (header[4] == '7' || header[4] == '9')
                && header[5] == 'a') {
            return Kind.IMAGE;
        }
        if (length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F'
                && header[3] == 'F' && header[8] == 'W' && header[9] == 'E'
                && header[10] == 'B' && header[11] == 'P') {
            return Kind.IMAGE;
        }

        // MP4 and HEIF/AVIF share ISO-BMFF's ftyp box. Inspect the major brand so
        // still images are not mislabeled as video.
        if (length >= 12 && header[4] == 'f' && header[5] == 't'
                && header[6] == 'y' && header[7] == 'p') {
            String brand = new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII)
                    .toLowerCase(Locale.US);
            if (brand.equals("heic") || brand.equals("heix") || brand.equals("hevc")
                    || brand.equals("hevx") || brand.equals("mif1") || brand.equals("msf1")
                    || brand.equals("avif") || brand.equals("avis")) {
                return Kind.IMAGE;
            }
            return Kind.VIDEO;
        }
        return Kind.UNKNOWN;
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null) return null;
        int semicolon = contentType.indexOf(';');
        String normalized = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType)
                .trim().toLowerCase(Locale.US);
        return normalized.isEmpty() ? null : normalized;
    }

    private static int u(byte value) {
        return value & 0xff;
    }
}
