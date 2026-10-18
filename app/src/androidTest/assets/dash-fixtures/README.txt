Synthetic-only Android instrumentation assets. No downloaded Instagram media, account information,
messages, signed URLs, or device identifiers are present. These are packaged only in the test APK.

h264.mp4, h264-negative.mp4, av1.mp4 and aac.mp4 are the generated inputs retained by
Mp4JoinTest.ffmpegReadsAndDecodesTheJoinOfRealTracks. It uses FFmpeg testsrc2 at 320x240,
30 fps, three seconds, and a generated 440 Hz sine wave encoded as AAC. The exact encoder
arguments are in that test. vp9-1080.mp4 and aac-1s.mp4 come from the one-second 1080x1920
fixture test in the same class.

h264-rotated.mp4 is an encoded-stream copy of h264.mp4 with FFmpeg 7 input option
-display_rotation:v:0 90, then -c copy. FFprobe confirms a 90-degree display matrix.

hevc.mp4 was generated with installed FFmpeg 8.1.1:
ffmpeg -f lavfi -i testsrc2=size=320x240:rate=30 -t 3 -c:v libx265 -preset ultrafast
  -x265-params log-level=error:pools=2 -f mp4 -movflags +dash+global_sidx hevc.mp4

h264-sparse15s.mp4 and h264-held15s.mp4 exercise a final frame held beyond its PTS:
ffmpeg -f lavfi -i color=c=black:s=320x240:r=1:d=15 -frames:v 15 -c:v libx264
  -pix_fmt yuv420p -movflags +frag_keyframe+empty_moov+default_base_moof -an h264-sparse15s.mp4
Use r=1/15 and -frames:v 1 for h264-held15s.mp4. Both have a full 15-second sample timeline.
aac15s.mp4 accompanies their join tests:
ffmpeg -f lavfi -i sine=frequency=220:sample_rate=48000:duration=15 -c:a aac -b:a 64k
  -movflags +frag_keyframe+empty_moov+default_base_moof -vn aac15s.mp4
aac-primed15s.mp4 uses the same command, replacing empty_moov with delay_moov.
Its edit list trims 1,024 encoder-priming samples. This reproduces the difference between
Android seeking to zero in a fragmented source and in the joined plain MP4; verification
must inspect all encoded packets, including the priming packet, without seeking it away.

NativeDownloadEngineTest reads these files in the module test process. It tests Android
MediaExtractor/MediaMuxer, portable MP4 joins, actual dimensions/codecs/duration, audio presence,
encoded-sample hashes, relative timestamps, rotation, missing audio, cancellation, truncation,
hard resolution ceilings, and the Android XML parser. It does not launch or hook Instagram.

Build: gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
Run only after device-test authorization and explicit device selection:
  adb -s SERIAL shell am instrument -w -r -e class
    ps.reso.instaeclipse.utils.download.NativeDownloadEngineTest
    ps.reso.instaeclipse.test/androidx.test.runner.AndroidJUnitRunner
Both target and test APKs must already be installed with matching signatures. Do not replace
an upstream-signed target APK or uninstall it to bypass a mismatch.

Local desktop FFmpeg tests passing does not mean this Android suite passed. Each Android suite must be reported as unrun for the current revision until executed.
