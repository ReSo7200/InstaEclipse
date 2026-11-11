package ps.reso.instaeclipse.patcher;

import android.content.Context;
import android.util.Base64;

import com.android.tools.build.apkzlib.sign.SigningExtension;
import com.android.tools.build.apkzlib.sign.SigningOptions;
import com.android.tools.build.apkzlib.zip.AlignmentRules;
import com.android.tools.build.apkzlib.zip.NestedZip;
import com.android.tools.build.apkzlib.zip.StoredEntry;
import com.android.tools.build.apkzlib.zip.ZFile;
import com.android.tools.build.apkzlib.zip.ZFileOptions;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteSource;
import com.wind.meditor.core.ManifestEditor;
import com.wind.meditor.property.AttributeItem;
import com.wind.meditor.property.ModificationProperty;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Patches an Instagram APK so the module loads without root, keeping Instagram's original
 * signature (LSPatch-style, signature-bypass level 2). The original APK is embedded unchanged and
 * the output is re-signed with a bundled key; the signature-bypass layer reports the original
 * signature back to the app at runtime. This replicates the LSPatch v0.8 output format.
 */
public final class ApkPatcher {

    private ApkPatcher() {
    }

    // Paths written INTO the patched output (what the bundled loader expects).
    private static final String ASSET_DIR = "assets/lspatch/";
    private static final String ORIGIN_APK = ASSET_DIR + "origin.apk";
    private static final String CONFIG_JSON = ASSET_DIR + "config.json";
    private static final String LOADER_DEX = ASSET_DIR + "loader.dex";
    private static final String SO_DIR = ASSET_DIR + "so/";
    private static final String MODULES_DIR = ASSET_DIR + "modules/";
    private static final String MANIFEST = "AndroidManifest.xml";

    // Our bundled source assets.
    private static final String SRC = "iepatch/";

    private static final String PROXY_APP_COMPONENT_FACTORY =
            "org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub";
    private static final String ORIGINAL_APP_COMPONENT_FACTORY =
            "androidx.core.app.CoreComponentFactory";

    private static final String KEYSTORE = SRC + "keystore.jks";
    private static final String KEY_ALIAS = "key0";
    private static final char[] STORE_PASSWORD = "123456".toCharArray();
    private static final char[] KEY_PASSWORD = "123456".toCharArray();

    private static final String[] ABIS = {"arm64-v8a", "armeabi-v7a"};

    /**
     * Patches the given Instagram APK set and returns the patched, re-signed APKs ready to install.
     * The first APK that carries application code is treated as the base and fully patched; the rest
     * (config/split APKs) are only re-signed with the same key. Runs on the caller's thread.
     */
    public static List<File> patch(Context ctx, List<File> inputs, PatchLogger log) throws Exception {
        if (inputs == null || inputs.isEmpty()) throw new IOException("No APK supplied");
        File workDir = new File(ctx.getCacheDir(), "iepatch/out");
        deleteRecursively(workDir);
        if (!workDir.mkdirs() && !workDir.isDirectory()) throw new IOException("Cannot create work folder");

        File base = pickBase(inputs);
        List<File> outputs = new ArrayList<>();
        for (File input : inputs) {
            if (input.equals(base)) {
                log.log("Patching base: " + input.getName());
                outputs.add(patchBase(ctx, input, new File(workDir, outName(input, "patched")), log));
            } else {
                log.log("Re-signing split: " + input.getName());
                outputs.add(resign(ctx, input, new File(workDir, outName(input, "signed"))));
            }
        }
        return outputs;
    }

    // ---- base APK -----------------------------------------------------------------------------

    private static File patchBase(Context ctx, File input, File output, PatchLogger log) throws Exception {
        // Step 1: the original signing certificate, hex, before we touch anything.
        log.log("Reading the original Instagram signature");
        String originalSignature = SignatureReader.originalCertHex(input);
        if (originalSignature == null) {
            throw new IOException("Could not read the original Instagram signature from " + input.getName());
        }

        String config = buildConfig(originalSignature);
        byte[] configBytes = config.getBytes(StandardCharsets.UTF_8);

        // Step 2: output ZFile with the LSPatch alignment rules.
        ZFileOptions options = new ZFileOptions();
        options.setAlignmentRule(AlignmentRules.compose(
                AlignmentRules.constantForSuffix(".so", 16384),
                AlignmentRules.constantForSuffix(ORIGIN_APK, 4096)));

        if (output.exists() && !output.delete()) throw new IOException("Cannot overwrite " + output.getName());

        try (ZFile dst = ZFile.openReadWrite(output, options)) {
            // Step 3: register the signing extension on the output.
            registerSigning(ctx, dst);

            // Step 4: embed the original APK as a nested, stored zip.
            log.log("Embedding the original APK");
            NestedZip src = dst.addNestedZip(zf -> ORIGIN_APK, input, false);

            // Step 5: patch the original manifest and write it as the output manifest.
            log.log("Patching the manifest");
            StoredEntry manifestEntry = src.get(MANIFEST);
            if (manifestEntry == null) throw new IOException("The APK has no AndroidManifest.xml");
            byte[] patchedManifest = patchManifest(manifestEntry.read(), configBytes);
            dst.add(MANIFEST, new ByteArrayInputStream(patchedManifest));

            // Step 6: the config.
            dst.add(CONFIG_JSON, new ByteArrayInputStream(configBytes));

            // Step 7: the loader dex.
            log.log("Adding the loader");
            addAsset(ctx, dst, LOADER_DEX, SRC + "dexes/loader.dex");

            // Step 8: the native libraries, stored and aligned.
            for (String abi : ABIS) {
                byte[] so = readAsset(ctx, SRC + "so/" + abi + "/liblspatch.so");
                if (so != null) dst.add(SO_DIR + abi + "/liblspatch.so", ByteSource.wrap(so), false);
            }

            // Step 9: embed the module (this app's own installed APK).
            log.log("Embedding the module");
            File self = new File(ctx.getApplicationInfo().sourceDir);
            try (InputStream in = new FileInputStream(self)) {
                dst.add(MODULES_DIR + ctx.getPackageName() + ".apk", in);
            }

            // Step 10: link every remaining entry from the original APK.
            log.log("Linking remaining files");
            for (StoredEntry entry : src.entries()) {
                String name = entry.getCentralDirectoryHeader().getName();
                if (MANIFEST.equals(name)) continue;
                if (name.startsWith("classes") && name.endsWith(".dex")) continue;
                if (isSignatureFile(name)) continue;
                if (dst.get(name) != null) continue;
                src.addFileLink(name, name);
            }

            // Step 11: the meta loader becomes the app's dex.
            addAsset(ctx, dst, "classes.dex", SRC + "dexes/metaloader.dex");

            // Step 12: realign; closing the ZFile writes and signs the output.
            dst.realign();
            log.log("Signing");
        }
        log.log("Base ready: " + output.getName());
        return output;
    }

    // ---- split / config APKs ------------------------------------------------------------------

    private static File resign(Context ctx, File input, File output) throws Exception {
        ZFileOptions options = new ZFileOptions();
        options.setAlignmentRule(AlignmentRules.constantForSuffix(".so", 16384));
        if (output.exists() && !output.delete()) throw new IOException("Cannot overwrite " + output.getName());
        try (ZFile dst = ZFile.openReadWrite(output, options)) {
            registerSigning(ctx, dst);
            try (ZFile source = ZFile.openReadOnly(input)) {
                // Predicate selects entries to IGNORE: the source's own v1 signature files.
                dst.mergeFrom(source, ApkPatcher::isSignatureFile);
            }
            dst.realign();
        }
        return output;
    }

    // ---- signing ------------------------------------------------------------------------------

    private static void registerSigning(Context ctx, ZFile zf) throws Exception {
        KeyStore keyStore = loadKeyStore(ctx);
        PrivateKey key = (PrivateKey) keyStore.getKey(KEY_ALIAS, KEY_PASSWORD);
        if (key == null) throw new IOException("Signing key '" + KEY_ALIAS + "' not found");
        Certificate[] chain = keyStore.getCertificateChain(KEY_ALIAS);
        if (chain == null || chain.length == 0) throw new IOException("No certificate chain for the signing key");
        ImmutableList.Builder<X509Certificate> certs = ImmutableList.builder();
        for (Certificate c : chain) certs.add((X509Certificate) c);

        SigningOptions options = SigningOptions.builder()
                .setKey(key)
                .setCertificates(certs.build())
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setMinSdkVersion(28)
                .setValidation(SigningOptions.Validation.ASSUME_INVALID)
                .build();
        new SigningExtension(options).register(zf);
    }

    /** The bundled keystore is a BouncyCastle (BKS) store; try that first, then common fallbacks. */
    private static KeyStore loadKeyStore(Context ctx) throws Exception {
        Provider bc = null;
        try {
            bc = (Provider) Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                    .getDeclaredConstructor().newInstance();
        } catch (Throwable ignored) {
        }
        String[] types = {"BKS", "PKCS12", KeyStore.getDefaultType()};
        Exception last = null;
        for (String type : types) {
            try (InputStream in = ctx.getAssets().open(KEYSTORE)) {
                KeyStore ks = (bc != null && "BKS".equals(type))
                        ? KeyStore.getInstance(type, bc)
                        : KeyStore.getInstance(type);
                ks.load(in, STORE_PASSWORD);
                return ks;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IOException("Could not load the signing keystore", last);
    }

    // ---- manifest -----------------------------------------------------------------------------

    private static byte[] patchManifest(byte[] original, byte[] configBytes) throws IOException {
        ModificationProperty property = new ModificationProperty();
        property.addApplicationAttribute(new AttributeItem("appComponentFactory", PROXY_APP_COMPONENT_FACTORY));
        property.addMetaData(new ModificationProperty.MetaData(
                "lspatch", Base64.encodeToString(configBytes, Base64.NO_WRAP)));

        ByteArrayInputStream in = new ByteArrayInputStream(original);
        ByteArrayOutputStream out = new ByteArrayOutputStream(original.length + 256);
        new ManifestEditor(in, out, property).processManifest();
        out.flush();
        byte[] result = out.toByteArray();
        if (result.length == 0) throw new IOException("Manifest patching produced no output");
        return result;
    }

    // ---- config -------------------------------------------------------------------------------

    private static String buildConfig(String originalSignature) throws Exception {
        JSONObject lsp = new JSONObject();
        lsp.put("API_CODE", 93);
        lsp.put("VERSION_CODE", 430);
        lsp.put("VERSION_NAME", "0.7");
        lsp.put("CORE_VERSION_CODE", 7137);
        lsp.put("CORE_VERSION_NAME", "1.10.1");

        JSONObject config = new JSONObject();
        config.put("useManager", false);
        config.put("debuggable", false);
        config.put("overrideVersionCode", false);
        config.put("sigBypassLevel", 2);
        config.put("originalSignature", originalSignature);
        config.put("appComponentFactory", ORIGINAL_APP_COMPONENT_FACTORY);
        config.put("lspConfig", lsp);
        return config.toString();
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** The base is the APK that carries application code (classes.dex); otherwise the first input. */
    private static File pickBase(List<File> inputs) {
        if (inputs.size() == 1) return inputs.get(0);
        for (File f : inputs) {
            try (ZipFile zip = new ZipFile(f)) {
                ZipEntry dex = zip.getEntry("classes.dex");
                if (dex != null && !hasSplitName(zip)) return f;
            } catch (IOException ignored) {
            }
        }
        // No obvious base: fall back to the largest file.
        File largest = inputs.get(0);
        for (File f : inputs) if (f.length() > largest.length()) largest = f;
        return largest;
    }

    /** Config/feature splits declare a split name; the base does not. */
    private static boolean hasSplitName(ZipFile zip) {
        ZipEntry manifest = zip.getEntry(MANIFEST);
        if (manifest == null) return false;
        try (InputStream in = zip.getInputStream(manifest)) {
            byte[] data = readAll(in);
            // Binary AndroidManifest stores the "split" attribute name as UTF-16LE in the string pool.
            byte[] needle = "split".getBytes(StandardCharsets.UTF_16LE);
            return indexOf(data, needle) >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.endsWith(".SF") || upper.endsWith(".MF")
                || upper.endsWith(".RSA") || upper.endsWith(".EC") || upper.endsWith(".DSA");
    }

    private static void addAsset(Context ctx, ZFile zf, String outName, String assetPath) throws IOException {
        try (InputStream in = ctx.getAssets().open(assetPath)) {
            zf.add(outName, in);
        }
    }

    private static byte[] readAsset(Context ctx, String assetPath) {
        try (InputStream in = ctx.getAssets().open(assetPath)) {
            return readAll(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String outName(File input, String suffix) {
        String name = input.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + "-" + suffix + ".apk";
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
