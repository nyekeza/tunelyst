# Tunelyst product brief

## Product promise

A calm, beautiful home for the music already on your Android phone. Tunelyst makes personal audio feel as thoughtfully presented as a streaming catalogue, while remaining genuinely offline and private.

## Experience principles

1. **Your library is the catalogue.** Start from music the user already owns; do not imitate streaming availability or imply tracks can be downloaded from the app.
2. **Playback is always close.** Keep a mini-player visible and make the full player one tap away.
3. **Organise without friction.** Search across metadata, like tracks, create local playlists, and play albums or artists directly.
4. **Offline is a feature, not a fallback.** No login flow, web calls, tracking, or hidden network dependency.
5. **Editorial, not derivative.** Use expressive artwork, generous spacing, soft gradients and a bespoke plum/lilac palette without copying another service's branding.

## Main journeys

- First run → explain local audio access → scan → listen.
- Home → see the active track in a full-art Now Playing card, use local Smart Shuffle, explore Deep Cuts ranked with qualified lifetime plays, or jump to a recently played track from Quick Picks. Short on-device tempo/energy descriptors can add acoustic similarity to later Smart Shuffle and radio rankings.
- Search → find a local track, album or artist → play or add it to a playlist; after search playback ends, continue with a shuffled local track.
- Library → browse songs, albums, artists, playlists, saved queues or liked songs; preserve album/playlist/radio/saved-queue order, and use Play Next or Play Last to shape the queue.
- Now playing → immerse in blurred album-art ambience; seek, like, Smart Shuffle, repeat, reorder/remove upcoming songs, or set a sleep timer. Turning Smart Shuffle off alphabetizes the queue.
- Song actions → choose “Less like this artist” or “Less like this genre”; the option becomes a reversible “Show more” action and affects only on-device mixes, radio and Smart Shuffle.
- Settings → choose the qualified-play threshold and optional Fewer repeats shuffle style. Starts, heard duration/percentage, completions, skips and qualified plays are tracked separately on-device.
- Settings → create a personal-data backup or restore it after an explicit confirmation; audio files are never included and track links are matched to the current library where possible.
- Library → permanently delete selected device audio only after explicit in-app confirmation and Android's required authorization, then verify the result.
- Share an audio URI from another Android app → play it in Tunelyst, then continue with shuffled local-library music.

## Technical decisions

- **Kotlin + Jetpack Compose:** native Android interface and platform permission handling.
- **MediaStore:** user-visible audio library; no custom filesystem crawling.
- **Media3 ExoPlayer + MediaSessionService:** focus handling, background playback and system media controls.
- **SharedPreferences:** small, private, device-local personalisation state and cached derived audio descriptors. Room can replace it if richer playlist editing or large libraries are added.
- **MediaCodec + bounded PCM analysis:** lazily decode a short window of local audio on an IO worker to estimate tempo and relative energy; persist descriptors only, never copied audio, and fall back to metadata when analysis is unavailable.
- **No Internet permission:** prevents accidental network use in this offline-first version.
