package ps.reso.instaeclipse.patcher;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.cert.Certificate;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Reads the original signing certificate of an unmodified APK and returns its DER bytes as a
 * lowercase hex string. This value is written into the patched app's config as
 * {@code originalSignature}; the signature-bypass layer reports it back to Instagram so the app
 * still sees its own signature after being re-signed with a different key.
 *
 * <p>It first parses the APK Signing Block (v2/v3), and falls back to the v1 (JAR) signature.
 */
final class SignatureReader {

    private SignatureReader() {
    }

    // APK Signing Block: "APK Sig Block 42" magic and the v2 signature scheme block id.
    private static final long APK_SIG_BLOCK_MAGIC_LO = 0x20676953204b5041L; // "APK Sig "
    private static final long APK_SIG_BLOCK_MAGIC_HI = 0x3234206b636f6c42L; // "Block 42"
    private static final int V2_BLOCK_ID = 0x7109871a;
    private static final int V3_BLOCK_ID = 0xf05368c0;

    /** Lowercase hex of the first signing certificate's DER bytes, or null if none could be read. */
    static String originalCertHex(File apk) {
        byte[] der = fromSigningBlock(apk);
        if (der == null) der = fromJar(apk);
        return der == null ? null : toHex(der);
    }

    // ---- APK Signing Block (v2 / v3) ----------------------------------------------------------

    private static byte[] fromSigningBlock(File apk) {
        try (RandomAccessFile raf = new RandomAccessFile(apk, "r")) {
            long cdOffset = centralDirectoryOffset(raf);
            if (cdOffset < 24) return null;

            // Footer: 8-byte size + 16-byte magic, ending right before the central directory.
            byte[] footer = readAt(raf, cdOffset - 24, 24);
            ByteBuffer fb = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN);
            long blockSizeFooter = fb.getLong(0);
            long magicLo = fb.getLong(8);
            long magicHi = fb.getLong(16);
            if (magicLo != APK_SIG_BLOCK_MAGIC_LO || magicHi != APK_SIG_BLOCK_MAGIC_HI) return null;

            long blockStart = cdOffset - blockSizeFooter - 8;
            if (blockStart < 0) return null;
            byte[] header = readAt(raf, blockStart, 8);
            long blockSizeHeader = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getLong(0);
            if (blockSizeHeader != blockSizeFooter) return null;

            // id-value pairs between the header size field and the footer.
            long pairsStart = blockStart + 8;
            long pairsEnd = cdOffset - 24;
            byte[] pairs = readAt(raf, pairsStart, (int) (pairsEnd - pairsStart));
            ByteBuffer pb = ByteBuffer.wrap(pairs).order(ByteOrder.LITTLE_ENDIAN);

            byte[] v2 = null, v3 = null;
            while (pb.remaining() >= 12) {
                long len = pb.getLong();
                if (len < 4 || len > pb.remaining()) break;
                int id = pb.getInt();
                int valueLen = (int) (len - 4);
                byte[] value = new byte[valueLen];
                pb.get(value);
                if (id == V2_BLOCK_ID) v2 = value;
                else if (id == V3_BLOCK_ID) v3 = value;
            }
            byte[] scheme = v2 != null ? v2 : v3;
            return scheme == null ? null : firstCertificate(scheme);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * v2/v3 scheme block: a length-prefixed sequence of signers; each signer holds a length-prefixed
     * signed-data block whose structure begins with the length-prefixed digests, then the
     * length-prefixed certificates. The first certificate is the app's signing certificate (DER).
     */
    private static byte[] firstCertificate(byte[] scheme) {
        ByteBuffer buf = ByteBuffer.wrap(scheme).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer signers = lengthPrefixed(buf);
        if (signers == null) return null;
        ByteBuffer signer = lengthPrefixed(signers);
        if (signer == null) return null;
        ByteBuffer signedData = lengthPrefixed(signer);
        if (signedData == null) return null;
        // Skip digests.
        ByteBuffer digests = lengthPrefixed(signedData);
        if (digests == null) return null;
        ByteBuffer certificates = lengthPrefixed(signedData);
        if (certificates == null) return null;
        ByteBuffer cert = lengthPrefixed(certificates);
        if (cert == null) return null;
        byte[] der = new byte[cert.remaining()];
        cert.get(der);
        return der.length == 0 ? null : der;
    }

    /** Reads a uint32 length, then returns a view of that many bytes (advancing the source). */
    private static ByteBuffer lengthPrefixed(ByteBuffer src) {
        if (src.remaining() < 4) return null;
        long len = src.getInt() & 0xFFFFFFFFL;
        if (len < 0 || len > src.remaining()) return null;
        ByteBuffer view = src.slice();
        view.order(ByteOrder.LITTLE_ENDIAN);
        view.limit((int) len);
        src.position(src.position() + (int) len);
        return view;
    }

    private static long centralDirectoryOffset(RandomAccessFile raf) throws IOException {
        long fileLen = raf.length();
        // EOCD is at most 22 + 65535 bytes from the end.
        int maxScan = (int) Math.min(fileLen, 22 + 0xFFFF);
        byte[] tail = readAt(raf, fileLen - maxScan, maxScan);
        for (int i = tail.length - 22; i >= 0; i--) {
            if (tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) {
                ByteBuffer eocd = ByteBuffer.wrap(tail, i, 22).order(ByteOrder.LITTLE_ENDIAN);
                return eocd.getInt(i + 16) & 0xFFFFFFFFL;
            }
        }
        return -1;
    }

    private static byte[] readAt(RandomAccessFile raf, long offset, int length) throws IOException {
        byte[] out = new byte[length];
        raf.seek(offset);
        raf.readFully(out);
        return out;
    }

    // ---- v1 (JAR) fallback --------------------------------------------------------------------

    private static byte[] fromJar(File apk) {
        try (JarFile jar = new JarFile(apk, true)) {
            Enumeration<JarEntry> entries = jar.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().toUpperCase(java.util.Locale.ROOT);
                if (name.startsWith("META-INF/")) continue;
                // Read the entry fully so its certificates become available.
                try (java.io.InputStream in = jar.getInputStream(entry)) {
                    while (in.read(buffer) != -1) {
                        // consume
                    }
                }
                Certificate[] certs = entry.getCertificates();
                if (certs != null && certs.length > 0) {
                    return certs[0].getEncoded();
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
