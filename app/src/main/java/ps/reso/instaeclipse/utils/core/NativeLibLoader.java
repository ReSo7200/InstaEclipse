package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class NativeLibLoader {

    public static boolean loadDexKit(Context context, String moduleSourceDir, String moduleLibDir, String hostNativeLibDir) {
        if (moduleLibDir != null) {
            File moduleSo = new File(moduleLibDir, "libdexkit.so");
            if (moduleSo.exists()) {
                try {
                    System.load(moduleSo.getAbsolutePath());
                    ModuleLog.line("(IE|Native): [origin/main path] Loaded libdexkit.so from moduleLibDir");
                    return true;
                } catch (Throwable t) {
                    ModuleLog.line("(IE|Native): [origin/main path] moduleLibDir load failed: " + t.getMessage());
                }
            }
        }

        if (hostNativeLibDir != null) {
            File hostSo = new File(hostNativeLibDir, "libdexkit.so");
            if (hostSo.exists()) {
                try {
                    System.load(hostSo.getAbsolutePath());
                    ModuleLog.line("(IE|Native): [injectdex path] Loaded libdexkit.so from host nativeLibraryDir");
                    return true;
                } catch (Throwable t) {
                    ModuleLog.line("(IE|Native): [injectdex path] Host load failed: " + t.getMessage());
                }
            }
        }

        if (moduleSourceDir != null && context != null) {
            File moduleFile = new File(moduleSourceDir);
            if (moduleFile.exists()) {
                File cachedSo = new File(context.getCacheDir(), "libdexkit.so");
                if (cachedSo.exists() && cachedSo.length() > 0) {
                    try {
                        System.load(cachedSo.getAbsolutePath());
                        ModuleLog.line("(IE|Native): [cache path] Loaded cached libdexkit.so from app cache");
                        return true;
                    } catch (Throwable ignored) {
                        cachedSo.delete();
                    }
                }

                for (String abi : Build.SUPPORTED_ABIS) {
                    String entryName = "lib/" + abi + "/libdexkit.so";
                    try (ZipFile zip = new ZipFile(moduleFile)) {
                        ZipEntry entry = zip.getEntry(entryName);
                        if (entry != null) {
                            try (InputStream in = zip.getInputStream(entry);
                                 FileOutputStream out = new FileOutputStream(cachedSo)) {
                                byte[] buf = new byte[32768];
                                int n;
                                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                            }
                            cachedSo.setReadable(true, false);
                            cachedSo.setExecutable(true, false);
                            System.load(cachedSo.getAbsolutePath());
                            ModuleLog.line("(IE|Native): [cache path] Extracted and loaded " + entryName);
                            return true;
                        }
                    } catch (Throwable t) {
                        ModuleLog.line("(IE|Native): [cache path] Extraction failed for " + abi + ": " + t.getMessage());
                    }
                }
            }
        }

        return false;
    }
}
