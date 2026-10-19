# Migration to libxposed API 102 (issue #264) — progress

Branch: `xposed-api-102` (from `0.8.1` at `6f70d5a`). Test branch: do not merge until verified on Vector 2.2.

Read first: [LEGACY_API.md](LEGACY_API.md) (snapshot of what the code used **before** the migration,
per file; `python docs/xposed-api-102/inventory.py .` regenerates it from the current code) and
[MODERN_API.md](MODERN_API.md) (the API 102 reference).

## Goal

Keep **every** feature, and load as a modern (API 102) module on Vector 2.2+ while still loading as
a legacy module on LSPatch / older LSPosed.

## Design (as built)

1. **Compat layer** `ps.reso.instaeclipse.xposedcompat` (NOT `.xposed`: Windows paths are
   case-insensitive and would collide with the existing `ps.reso.instaeclipse.Xposed` package).
   Same names the code already used: `XC_MethodHook` (+ `MethodHookParam`, `Unhook`),
   `XC_MethodReplacement`, `XposedBridge`, `XposedHelpers`, `XC_LoadPackage.LoadPackageParam`,
   `AndroidAppHelper`, plus `ModuleResources` (replaces `XModuleResources`) and `CompanionPrefs`
   (replaces `XSharedPreferences` reads of the companion's `instaeclipse_cache`).
   Feature files only had their imports rewritten; hook bodies are unchanged.
2. **Two backends** behind `HookBackend`, set by the entry:
   - `LegacyBackend`: runs our callbacks inside real `de.robv` hooks (shares the args array).
   - `ModernBackend`: one `hook(m).setPriority(p).intercept(chain)` per callback, replaying legacy
     rules: result/throwable set in before skips the original but after still runs; callback
     exceptions are logged, never thrown into the app.
3. **Entries**: `Module` is the shared core with no framework types (so it loads anywhere);
   `LegacyEntry` (assets/xposed_init) and `ModernEntry` (META-INF/xposed/java_init.list) are thin.
4. **Two APK flavors** (`app/build.gradle`): `legacy` (no META-INF/xposed) and `modern`
   (`app/src/modern/resources/META-INF/xposed/*`, version suffix `-api102`). Required because:

> **Finding (2026-10-18, verified on the phone):** LSPosed v1.11.0 (API 100) loads the modern entry
> whenever `META-INF/xposed/java_init.list` exists — it ignores `xposed_init` and
> `minApiVersion` — and then fails (`NoSuchMethodException: ModernEntry.<init>(XposedInterface,
> ModuleLoadedParam)`, the API 100 constructor). Result: module not loaded at all. So one APK with
> both entries breaks every LSPosed <= 1.11 (and likely LSPatch) user.

## Open questions

- [x] Vector 2.2 with the `modern` APK: loads `ModernEntry`, 86 hooks OK (the 2 failures predate the merge), feed features visible (2026-10-19)
- [x] Vector 2.2 with the `legacy` APK: loads ("legacy XposedBridge 102"); modern APK loads as "libxposed 102 on Vector 2.2 (3080)", 86 hooks OK (2026-10-19)
- [x] LSPatch: 0.8 loads only legacy modules; LSPatch 1.0+ loads the modern APK ("libxposed 102 on LSPatch 1.2 (487)", 87 hooks, remote prefs connected, 2026-10-19). From 0.8.1 the build makes one APK (modern); legacy users stay on the 0.8.1-legacy branch.
- [ ] Remote preferences on modern: does `RemotePrefsMirror` connect on Vector 2.2 (logcat tag
      `IE|RemotePrefs`: "connected to Vector ..., mirrored N values") and does Instagram read them
      on a cold start (`CompanionSync` applies them)?
- [ ] Release plan: ship both APKs (`-legacy`, `-api102`)? Or legacy only until Vector 2.2 is common?

## Steps

- [x] Branch created
- [x] LEGACY_API.md inventory (103 files, 549 uses incl. XModuleResources, AndroidAppHelper)
- [x] MODERN_API.md reference (from the 102.0.0 sources)
- [x] `io.github.libxposed:api:102.0.0` compileOnly next to api-82
- [x] Compat layer + `LegacyBackend` + `ModernBackend`
- [x] Imports rewritten in 97 files; `XSharedPreferences`/`XModuleResources` call sites moved to
      `CompanionPrefs`/`ModuleResources`; `ModuleLog` skips compat/libxposed frames
- [x] `Module` = shared core; `LegacyEntry` + `ModernEntry`; META-INF/xposed files
- [x] Build flavors `legacy` / `modern`; CI uploads both APKs
- [x] **Legacy APK on the phone (LSPosed 1.11.0)**: loads via `LegacyEntry`; same 80+ hook ✅ lines
      and the same warnings as the run before the migration (diffed from the module log); thread
      menu buttons inject on opening a chat; theme/tab order/exact-date format all active.
- [x] Companion: `RemotePrefsMirror` (modern flavor) copies `instaeclipse_cache` into remote
      preferences on connect and on every change; `InstaEclipseApp` starts it; legacy flavor has a
      no-op version. libxposed `service`/`interface` 102 classes are bundled as jars in
      `app/libs/modern/` (their AARs need compileSdk 37; this project is on AGP 8.7 / compileSdk 34)
      and `XposedProvider` is declared in `app/src/modern/AndroidManifest.xml`.
- [x] Legacy APK checked again on the phone: companion starts (new Application class), "Module
      active", Help shows the Vector row
- [x] "LSPosed" -> "Vector" in the README, app strings (all languages), Help screen, issue
      template; README lists the 0.8.1 additions (DM photos/videos, best quality, fancy bio fonts)
- [x] CI: tests per flavor (`testLegacyDebugUnitTest testModernDebugUnitTest`, 60/60 each)
- [ ] Modern APK on Vector 2.2 (needs the phone's LSPosed replaced: user's call)
- [ ] README/FAQ: which APK to install; reply on #264

## Log

- 2026-10-18: research done; phone framework = JingMatrix LSPosed v1.11.0 (7209), API 100 only.
  Vector 2.2 (2026-08-04) brings API 102. JingMatrix/LSPosed is now JingMatrix/Vector.
- 2026-10-18: compat layer + both backends + entries built; found the LSPosed 1.11 java_init.list
  behaviour (above) -> split into legacy/modern flavors; legacy flavor verified on the phone.
- 2026-10-18: companion remote-preferences mirror (modern), Vector naming everywhere, README 0.8.1.
  **Next: user flashes Vector 2.2 and restarts the phone; then install `app-modern-debug.apk`**,
  enable it in Vector (scope Instagram), and work through "Open questions".
