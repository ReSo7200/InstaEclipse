# Remaining fork improvements on 0.8.1

This contribution started at upstream `6f70d5afc500183e2d8f1e7bc12152542b811fa4` and incorporates 0.8.1 through `a3d48ab2034080a85fb7d6af23346eb58067794c`. It ports selected behavior and tests onto 0.8.1 rather than merging the older fork's history. The application identity, icon and version remain upstream's. The later upstream followers history, profile-picture viewer, Reel hold-speed controls and share-sheet cleanup are preserved.

## Decisions required before merging

- **License:** the custom downloader includes adapted GPL-3.0 HushGram code. Its notices and license are in [third-party/HushGram](../third-party/HushGram/NOTICE.md). InstaEclipse is now licensed under GPL-3.0 as a whole (it was Apache-2.0 before this merge).
- **Trusted hosts:** companion IPC now requires the official Instagram package and approved signing identity/history, using the real Binder caller UID. Re-signed applications and clone package names are deliberately excluded. This narrows upstream clone support; supporting additional hosts needs an explicit trust policy, not a package-name-only exception.
- **Runtime validation:** the rebased integration has not been installed or exercised in Instagram. Compiling instrumentation tests does not establish host compatibility, media synchronization, or UI behavior. Older fork device results do not count as validation of this tree.

## Behavior and defaults

### Chats

Hidden-thread state is keyed by account, bounded and written atomically. Failed writes leave the live state unchanged. Disabling hiding preserves the saved list. A guarded foreground conversation resolver supplies account/thread identity; uncertain matches do not change state. Inbox filtering copies host lists and refreshes supported local controllers.

Long-pressing the conversation title opens hide/unhide and deleted-history actions. Normal taps remain intact. Hide and deleted-history shortcut buttons have independent settings, both off by default. The upstream thread-name tracker and other header actions remain in use.

History-only unsent-message handling remains the default. Optional in-chat retention applies only to verified received messages during the current process session; own or unresolved messages follow normal deletion. It does not restore retained messages after Instagram restarts. An individual saved-history entry can be removed after confirmation and a successful durable write.

Legacy hidden entries are preserved as unassigned. Users must re-hide the intended conversations to associate them safely with an account. Hiding filters supported inbox lists; search, notifications, suggestions and direct entry can still expose a conversation. Deleted-message history itself is not newly account-scoped. The web-unsend metadata/body issue is not fixed here.

### Downloads and frames

Detached media snapshots keep menu actions tied to the selected Story, post, Reel or carousel child. Original/Custom is remembered, with **Original as the default**. Original retains 0.8.1's selection, taller-DASH upgrade and progressive fallback behavior while using the shared hardened companion transport; it is not an untouched upstream binary.

Custom ranks exposed renditions by measured dimensions, frame rate and appropriate same-codec bitrate ties. Best has no artificial 1080p ceiling. Optional resolution ceilings, H.264/AAC preference and explicit fallback are captured with the request. Unsupported manifests, ambiguous selection and failed verification report an error instead of silently choosing another item. Selected video/audio samples are copied and joined without re-encoding; output tracks, sample counts, duration coverage and relative timing are checked before publication. Added post music remains a separate audio action, not an automatic soundtrack mix.

The frame picker uses Custom Best independently of saved download preferences, previews a chosen time and saves a full-resolution decoded frame as lossless PNG. PNG represents the decoded image, not the original compressed video samples. Its temporary media is owned by the session and cleaned up on save, cancel, failure and startup orphan cleanup.

Photo downloads keep the detected source format and bytes. Existing DM downloads, audio exports, clipping, bulk Highlights, chat/comment exports and upstream media information remain integrated. Large exports can now fail explicit payload bounds rather than pass unbounded data across IPC.

### Security and reliability

- Signature-protected commands, private expiring result callbacks, real caller/signing checks and immutable one-use activity capabilities replace sensitive exported entry paths and broadcast replies.
- Broker inputs have explicit operation/schema/type/size limits. Configuration imports/exports are bounded. Legacy exports can only use the companion's already-granted destination.
- Network policy enforces HTTPS, approved CDN hosts, redirect/address validation, deadlines, size/storage limits, cancellation and partial-file cleanup. Unsupported media is rejected before publication.
- Logging initializes before download work, bounds entries and UTF-8 storage, batches atomic snapshots without postponing writes indefinitely, and retains failed writes for retry. Disk writes and worker flushes avoid holding the in-memory log lock. Existing host log mirroring remains.
- Narrow photo-zoom, Instants and double-tap binding checks reject ambiguous shapes while preserving newer upstream fallback paths. Cheap gesture guards run before expensive checks.

## Verification and review

Run with JDK 17 and the configured Android SDK:

```text
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

Optional media fixture tests use `-Dinstaeclipse.ffmpeg` and `-Dinstaeclipse.ffprobe` paths. Without them, those tests skip explicitly. Android fixture assets are synthetic; no private account content is included.

Local verification of the final source tree completed with 347 JVM tests reported: 345 passed, two optional media-fixture tests skipped, and no failures or errors. Debug application and instrumentation APK assembly passed. Lint reported zero errors and 433 warnings. Instrumentation tests and live Instagram checks were not run. The latest logging failure/retry and audio-preference fixtures are instrumentation tests, so they were compiled but not executed.

New strings use an explicit, file-scoped English fallback where translations are unavailable. This does not suppress lint errors elsewhere. Review the final integrated tree: the commits are grouped by subsystem, and intermediate commits are not independently validated.

Before release, run instrumentation tests and exercise account switching, hidden-list persistence, independent shortcuts, incoming versus own unsends, feed/Reel/Story/own-Highlight/carousel selection, Original/Custom comparison, expected audio, frame save/cancel cleanup, SAF exports, rejection of untrusted callers and hook status on each supported Instagram version.
