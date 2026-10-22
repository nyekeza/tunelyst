# Device test checklist

Use this checklist before a public release. Record the phone model, Android version, and result for each check. CI covers builds and emulator smoke tests; a real phone is still needed for audio and device-specific behavior.

## Permissions and library

- Install on Android 8.0 and a current Android version.
- Try opening the app without granting music access, then grant and revoke access in Android Settings.
- Share an audio file from Android Files and another file manager; confirm the app handles both shared and unsupported files safely.

## Playback

- Listen with the screen off and after leaving or dismissing the app.
- Check pause/resume, seeking, queue changes, shuffle, repeat, and continuing past an unreadable track.
- Try an incoming call, unplugging headphones, and switching Bluetooth audio on and off.
- Check crossfade and the sleep timer, including its fade, on each tested phone.
- Try the equalizer, speed, pitch, and ReplayGain on the phone’s speaker and available headphones. Record any device-specific limits.

## Library changes and backups

- Export a backup, restore it to a test profile or device, and confirm that matching tracks are linked correctly.
- Confirm that an ambiguous track match stays unresolved and that the backup contains no audio files or device-only paths.
- Review cleanup suggestions, select a duplicate for deletion, leave one copy unchecked, and test both approval and cancellation in Android’s confirmation screen.

## File formats

Test real files from the formats Tunelyst intends to list as supported. Record the file type, device, and result. Advertise only formats verified on supported Android versions; decoding can vary by device.