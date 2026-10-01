# VLC Android 3.7.2 Beta 2 resume/queue fix — audited revision

Base: official VideoLAN `vlc-android` master snapshot reporting `versionName = 3.7.2 Beta 2` and `versionCode = 3070120`.
The snapshot also contains the Sep 11, 2026 4K player UI crop fix that landed immediately after the Beta 2 version bump.

## Targeted observed bugs

1. Local video -> **Play as audio**: resume time may not survive VLC dismissal/force-stop.
2. SMB video playlist -> **Play as audio** -> widget: item progress may persist, but the widget can restore an older audio queue/current index (for example item 1 instead of the last played item 2).

## Changes

All functional changes are intentionally limited to `PlaylistManager.kt`:

- Classify `MEDIA_FORCE_AUDIO` video playback as an **audio resume session** when saving queue, current item, index and time.
- Apply that classification not only at pause/stop, but also to the existing saves triggered when playback starts and while time changes. This matters for Android force-stop because no final stop callback is guaranteed.
- On pause of a video played as audio, explicitly call `saveMediaMeta()` so a seek shortly before pausing is not left waiting for the periodic metadata save.
- Before stop clears the list, save the current audio-resume queue/current item/index.
- If `saveMediaMeta()` cannot resolve a MediaLibrary row (`id == 0`), add/reuse an external media/stream entry first so `setLastTime()` has a persistent id.
- Ordinary audio and ordinary foreground-video classification is unchanged.

## Build

The workflow builds a **debug APK** (`org.videolan.vlc.debug`) so it can coexist with the official VLC package.
It uses Java 17, Gradle 9.3.1, Android SDK 36 / Build Tools 36.0.0 and NDK 21.4.7075529.
The APK is universal (contains the supported ABIs supplied by the prebuilt VLC/medialibrary dependencies) and therefore runs on ARM64 devices such as the Galaxy S24 Ultra; it is not ARM64-only.
