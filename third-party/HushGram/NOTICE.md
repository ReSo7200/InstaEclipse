# HushGram downloader source attribution

Initial integration base: ReSo7200/InstaEclipse, commit `74bd7e1bc97386b06b595440d82106d9daa2dfae`.
Current upstream integration base: branch 0.8.1, commit `a3d48ab2034080a85fb7d6af23346eb58067794c`.
Donor: SysAdminDoc/HushGram, commit `f460a6125d8b7c4da9764e33e16dbb6e297a6bfe`.

HushGram's root LICENSE is GNU GPL version 3, reproduced in this directory. InstaEclipse is licensed under GPL-3.0 as a whole (see the LICENSE in the repository root); releases before this change were Apache-2.0. Distribution must comply with GPLv3, including providing the complete corresponding source and build material and preserving license and copyright notices.

Copied and adapted from `extensions/instagram/src/main/java/app/hushgram/extension/instagram/download/`:

- `Downloader.java`: Copyright 2026 Andrew Liang (GPL-3.0); modified for Hushfacebook and HushGram, 2026. Its original source URL is retained in the file header. Package adapted; malformed response MIME values are not reflected to user output.
- `MediaUrlPolicy.java`: Copyright 2026 Hushfacebook contributors; modified for HushGram, 2026. Package adapted; additional URL-length/character limits.
- `Mp4Join.java`: Copyright 2026 Hushfacebook contributors; modified for HushGram, 2026. Portable MP4 sample-copy writer; original notices retained.
- `DashManifest`, `RenditionPicker`, and the native mux path in `DownloadEngine` are adaptations of the donor's manifest, rendition, and `DashSave` design. Parsing and ranking were substantially rewritten for explicit unsupported outcomes, dimensions-based ranking, and opt-in fallback. Request capture and LSPosed integration are specific to this fork.

Copied test sources under the matching donor test package: `DownloaderTest`, `MediaUrlPolicyTest`, `Mp4JoinTest`, `FragmentedMp4ForTests`, `PlainMp4ForTests`, and `LocalServer`. Their copyright headers remain. No private user media is included in these fixtures.

The donor's `DownloadQuality`, `MediaSave`, `InstagramMedia`, `StoryDownload`, `MediaBridges.kt`, and associated tests were inspected as integration references. Morphe's placeholder bridge methods are not copied into this module.

# Engine boundaries and policy

The parser accepts a static MPD with exactly one Period, at most 16 adaptation sets and 64 representations, and exactly one absolute whole-file HTTPS BaseURL on each representation. The XML input is capped at 128 KiB characters, node count/depth are bounded, and DTD/entity declarations, encryption, dynamic manifests, inherited/multiple BaseURLs, SegmentTemplate, and SegmentList are rejected explicitly. A supported SegmentBase may index the same whole file; no segment fetch implementation is claimed.

Best ranks measured short side, pixel area, then available frame rate. Known metadata precedes unknown metadata. Bitrate only breaks ties within the same exact codec/profile and frame rate. Codec text and address provide a deterministic final tie-break, not a visual-quality claim. URL quality labels, encoded URL metadata, source type (DASH/direct), and file size never establish dimensions or boost quality. Best has no 1080p cap. Resolution ceilings are hard bounds: known candidates above the selected limit are excluded before compatibility preference, and unknown dimensions must satisfy the bound after file inspection. If no discovered candidate fits, the engine fails clearly rather than exceeding the ceiling. Compatibility mode prefers H.264 and AAC-LC/HE-AAC; it does not forbid another supported format when that is the selected available option. Unknown values remain zero/empty until verified from the saved file.

A failed selected candidate does not run a second candidate unless `allowFallback` was captured as true. Failure reports can explicitly offer fallback. A malformed/unsupported exposed manifest differs from no exposed manifest and does not silently select direct video. Cancellation and low storage stop all attempts. Fallback results are marked in their verification summary. An exposed audio track or explicit PRESENT expectation requires audio in the result. A silent manifest can produce a video-only file.

Networking retains the donor HTTPS/CDN allowlist, redirect checks, public-address checks, 15 s connect and 20 s read timeouts, bounded error reads, 512 MiB combined input and output ceiling, cancellation/disconnect, sniffing, exact advertised-length checking and partial-file cleanup. Truncated responses remain distinct from local storage failures; only the latter stop explicitly enabled fallback. The caller starts this on a worker, forwards cancellation, and owns final gallery/SAF publication. Requests serialize to a bounded JSON extra and contain values only, never live Instagram models. The public single-file fetch adapter applies the same network, storage and cancellation limits to photo/legacy paths, returns the detected MIME, validates video/audio tracks, and accepts only an empty internal-cache destination.

VP9/AV1 fragmented MP4 uses the donor sample-copy writer, preserving sample entries, edit lists, rotation matrix and timestamps. H.264/HEVC use the adapted Android MediaMuxer path, interleaving samples and applying one common offset for negative presentation times. Unsupported/encrypted shapes fail rather than transcode. Output verification uses MediaExtractor to read every sample and check dimensions, nonempty tracks, expected audio, duration coverage and video/audio duration agreement before success. Joined output also must preserve source sample counts, rotation and relative audio/video start/end timing. Alternative audio groups and separate initialization resources are rejected rather than guessed. Portable join fixtures verify byte-for-byte encoded samples and timing; Android mux/extractor behavior and synchronization on a real Instagram item still require device verification.

The synthetic fixtures and tests exercise the parser, network policy, candidate ordering, encoded sample copying, timing, Android extraction, publication, and frame selection. Results from another integration are not verification of this upstream port. Run the applicable JVM and Android suites against the built revision before reporting passes; live host binding remains a separate check.

## Original mode on upstream 0.8.1

Original captures upstream 0.8.1's progressive choice and any taller DASH video/audio upgrade at the tap. It retains upstream's native merge and automatic progressive fallback, independently of Custom quality settings. Exact candidate membership is checked against the selected item's detached direct list and manifest. Original uses the shared validated companion transport and is not an untouched upstream binary. Custom mode uses the ranked, verified engine described above; the frame picker always requests Custom Best without changing saved preferences. Separate post music/audio remains an explicit audio-only action and is not mixed into Custom video downloads.
