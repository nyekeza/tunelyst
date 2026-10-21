# Tunelyst — offline music player for Android

Tunelyst is a native Android music player built with Kotlin, Jetpack Compose and Media3. It plays audio already stored on the device; there is no streaming, account, analytics SDK, or Internet permission in the app manifest.

## What is included

- **Device library scan** using Android MediaStore, with title, artist, album, duration and embedded album art.
- **Home, Search and Library** surfaces with a custom dark, editorial interface and responsive album-art fallbacks.
- **Songs, albums, artists, playlists and liked songs** views. Search matches track, artist and album names.
- **Review-first Library cleanup** flags likely duplicate tracks, decorative/filename clutter in titles, and album-level title/artist field mix-ups. The cleanup card supports per-track and batch selection, previews title/artist/filename changes, and confirms them in place. Possible duplicates remain cautious flags: checked files can be deleted only after an in-card review and Android's system approval, with at least one unchecked copy kept in each group.
- **Edge-to-edge Now Playing** with a static, blurred version of the current album art behind the player, large artwork, Apple Music-style shuffle/previous/play/next/repeat controls, sleep timer, and an Up Next preview.
- **Home Now Playing card** fills with the active track's artwork, a white play/pause button and a circular wavy progress ring that projects from the latest player position and snaps on seeks, pauses, buffering or track changes; when idle, it remains a collection card. Its gradient Tunelyst logo opens a left-side drawer with Settings and Listening Stats.
- **Listening Stats** summarizes the on-device playback history. There is no separate Recents playback screen; recent-history data remains available to recommendations and stats.
- **YouTube Music-inspired Quick Picks** show recently played tracks in a compact two-column speed-dial grid.
- **Apple Music-inspired queue behavior**: selecting a song starts its current home/library context; **Play Next** inserts directly after the current track, **Play Last** appends to the end, and the Playing Next sheet supports tap-to-play, drag-to-reorder, remove, and clear-upcoming.
- **Audio sharing support** accepts audio shared from other Android apps; after a Search queue or shared track finishes, autoplay shuffle continues with local-library music.
- **Playback controls** with Smart Shuffle, repeat-all/repeat-one, previous/next, seek, and a persistent mini-player. Album, playlist, radio and saved-queue order is retained; Smart Shuffle-off resorts the queue alphabetically.
- **Background playback** through a Media3 `MediaSessionService`; Android media notification, headset/Bluetooth and lock-screen controls are supplied by the session.
- **Local playlists, favorites, saved queues and listening history** stored privately on the device. Tunelyst tracks starts, heard duration/percentage, completions and skips separately; a play count is awarded only after the configurable listening threshold (30 seconds by default).
- **Taste-aware local recommendations** use qualified lifetime play counts for Deep Cuts and mixes. Song actions can reversibly reduce an artist or genre in local mixes, radio and Smart Shuffle. The optional **Fewer repeats** shuffle style weights recent listening, lifetime counts and artist/album/genre variety more heavily. A lazy on-device analyzer also derives tempo and relative energy from short local audio windows; its cached features add an acoustic-similarity signal to later Smart Shuffle and radio rankings. Raw audio is never stored by the analyzer or sent anywhere.
- **Audio options** include EQ, speed/pitch, ReplayGain normalization when embedded track/album gain tags are available, and configurable crossfade. A sleep-timer fade temporarily suppresses crossfade so both volume fades do not compete.
- **Sleep timer** with 15, 30, 45 and 60 minute presets and an optional fade-out.
- **Personal-data backup/restore** is user-initiated JSON and can include local listening metrics and taste preferences. It never copies audio; portable track links are fingerprint-matched to the current library, with unmatched tracks reported. Device-specific folder paths and picked-artwork URIs are omitted.
- **Permanent audio deletion** is available only after an in-app confirmation and any Android-required system authorization; the app verifies the MediaStore result and reports tracks it could not verify.
- **Offline-first privacy**: no INTERNET permission, login, subscription, analytics or automatic sync; listening signals and recommendations are processed locally. Personal-data backup is an explicit file export.
- **Consistent yellow-gradient brand marks** use the launcher gradient with a proportionally balanced waveform. The black splash uses the adaptive launcher icon, including its monochrome layer for device-themed icon styles; legacy density icons remain packaged for APK/file-manager compatibility.

## Open and run

1. Open the Tunelyst project folder in Android Studio.
2. Use **JDK 21** and install Android SDK Platform **36** plus Build Tools **36.0.0** (the project targets API 36 and supports API 26 / Android 8.0 and later). The app targets JVM 17 bytecode; JDK 21 is required to compile its TagLib dependency.
3. Let Gradle sync, connect an Android device or start an emulator, then run the `app` configuration.

Run JVM tests and lint with:

```bash
bash gradlew :app:testDebugUnitTest :app:lintDebug
```

Run the API 26/API 36 emulator smoke tests when an emulator is available:

```bash
bash gradlew :app:connectedDebugAndroidTest
```

Or build a debug APK from a terminal with:

```bash
bash gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It is a debug build and is not a production release.

### Production release signing

Release APK/AAB packaging is blocked unless a user-controlled production keystore and all credentials are supplied. Configure these variables through a secure local secret manager; do not commit the keystore, passwords, or a `key.properties` file:

- `TUNELYST_RELEASE_STORE_FILE` — absolute path to the private keystore
- `TUNELYST_RELEASE_STORE_PASSWORD`
- `TUNELYST_RELEASE_KEY_ALIAS`
- `TUNELYST_RELEASE_KEY_PASSWORD`

Then run:

```bash
bash gradlew :app:assembleRelease :app:bundleRelease
```

The signed APK and AAB are written under `app/build/outputs/`. For the APK, validate signature, 16 KiB alignment, and required ARM ABIs before distribution:

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
python3 scripts/check_apk_compatibility.py app/build/outputs/apk/release/app-release.apk
python3 scripts/check_aab_native_libraries.py app/build/outputs/bundle/release/app-release.aab
```

The project includes local scripts for checking APK signature/alignment/ABI contents and AAB native libraries. A test-signed build is not a production release and cannot update an installation signed with another key.

On first launch, allow **Music and audio** access. Tunelyst asks Android's MediaStore for locally indexed music and reads audio using the device's available codecs. If a track was copied to the phone recently, use the rescan button in Library.

## Project map

```text
app/src/main/java/com/tunelyst/music/
├── MainActivity.kt                 # Compose host and MediaController connection
├── data/
│   ├── MusicRepository.kt          # MediaStore scan
│   ├── LibraryStore.kt             # device-local library, taste and listening signals
│   ├── AudioFeatureAnalyzer.kt      # bounded local PCM decode, tempo/energy and derived-feature cache
│   ├── HomeMixCache.kt             # invalidated on-device recommendation cache
│   └── Song.kt                     # local track and playlist models
├── playback/MusicPlaybackService.kt # background player + MediaSession
└── ui/
    ├── MusicViewModel.kt           # library, playback and local listening state/actions
    ├── HomeRecommendations.kt      # offline mix and Deep Cuts scoring
    ├── MusicPlayerApp.kt           # permission gate, navigation and app shell
    ├── NavigationDrawer.kt         # Home drawer shortcuts to Settings and Listening Stats
    ├── SettingsScreen.kt           # playback and local listening preferences
    ├── Screens.kt                  # home, search, library and playlist screens
    ├── PlayerOverlay.kt            # now-playing, queue and sleep timer
    ├── MediaProgress.kt            # seek bar and album-art palette helpers
    ├── Components.kt               # album art, track rows, mini-player
    ├── Dialogs.kt
    └── Theme.kt
```

## Notes

- The displayed product name and package ID are **Tunelyst** (`com.tunelyst.music`). This package ID differs from older `com.luma.music` builds, so Android treats it as a separate installation; export a personal-data backup from the old install and restore it in Tunelyst if you want to carry over settings and listening data. The importer accepts the older Luma backup format.
- Selected Library cleanup corrections are stored in Tunelyst and written to supported device TITLE/ARTIST tags through the built-in TagLib writer when Android storage-provider access allows; filename renaming is attempted where possible. Android may request a separate write approval. Possible duplicates are flags, not automatic deletion requests: users must select the exact files and review the batch inside the cleanup card; at least one unchecked copy is kept in each group, and deletion proceeds only through Android's required system authorization/permission. Android may leave empty folders behind.
- Favorites, playlists, saved queues and recent history are private app data. Clearing app storage removes them; a user-created backup can restore supported personal data to a library with matching tracks.
- The project includes the Gradle wrapper. A local Android SDK is required to build; this workspace does not bundle SDK images or a private production signing key.
- JVM tests cover audio-feature math, queue ordering, album grouping, title cleanup and filename planning, song display text, theme fallback, low-memory profile selection, and reboot-safe sleep-timer deadlines. Android instrumentation smoke tests are configured for app startup, API 26 minimum/API 36 target metadata, and the no-INTERNET-permission contract. Local APK/AAB validation scripts check ARMv7/ARM64 packaging and 16 KiB alignment. Real low-RAM Android 8 devices, 32-bit and 64-bit hardware, audio formats, accessibility/font-scale behavior, and production signing still require release-device and key-owner verification.
