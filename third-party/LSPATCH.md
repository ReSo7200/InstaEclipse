# LSPatch attribution

The no-root "Patch Instagram" feature produces a patched APK in the **LSPatch** format and bundles
LSPatch's loader so the module can run without root while preserving the app's original signature
(signature bypass level 2).

- Upstream: https://github.com/LSPosed/LSPatch and the maintained fork
  https://github.com/JingMatrix/LSPatch
- License: GNU General Public License v3.0 (GPL-3.0). InstaEclipse as a whole is GPL-3.0 (see the
  repository root `LICENSE`), which is compatible; distribution must preserve these notices and
  provide the complete corresponding source.

## Bundled components

Under `app/src/main/assets/iepatch/`:

- `dexes/loader.dex`, `dexes/metaloader.dex` — the LSPatch loader, **version 0.8**
  (`loader-version.txt`: `lspatch-loader 0.8`).
- `so/arm64-v8a/liblspatch.so`, `so/armeabi-v7a/liblspatch.so` — the LSPatch native library.
- `keystore.jks` — signing keystore used to re-sign the patched output.

The patch pipeline replicates LSPatch's v0.8 output layout (`assets/lspatch/origin.apk`,
`config.json`, `loader.dex`, `so/<abi>/liblspatch.so`, `modules/<package>.apk`, the meta-loader as
`classes.dex`, and the `appComponentFactory` / `lspatch` manifest edits).

## Libraries used by the patcher

- `apkzlib` (`com/android/tools/build/apkzlib`) — Apache-2.0 — zip assembly and v2 signing.
- `apksig` (`com.android.tools.build:apksig`) — Apache-2.0 — APK signature scheme v2.
- ManifestEditor (`com.wind.meditor`, `ManifestEditor-1.0.2.jar`) — Apache-2.0 — binary
  `AndroidManifest.xml` editing.
- Guava (Apache-2.0) and Bouncy Castle (MIT-style) — transitive runtime dependencies of the above.
