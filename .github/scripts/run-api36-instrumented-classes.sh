#!/usr/bin/env bash
# Run Android 36 instrumentation selectors separately so a slow/hung test is identifiable.
set -euo pipefail

log_file="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/connectedDebugAndroidTest.log"
mkdir -p "$(dirname "$log_file")"
: > "$log_file"

# AndroidJUnitRunner accepts ClassName#methodName in its class argument.
test_selectors=(
  "com.tunelyst.music.playback.PlaybackTransitionInstrumentedTest#ordinaryTrackSwitchesDoNotAttachDisabledAudioEffects"
  "com.tunelyst.music.playback.PlaybackTransitionInstrumentedTest#memoryGuardForegroundPlayerReplacementPreservesPlaybackState"
  "com.tunelyst.music.playback.PlaybackTransitionInstrumentedTest#manualAndAutomaticCrossfadesRecycleTheStandbyPlayerAcrossQueueTransitions"
  "com.tunelyst.music.IncomingAudioIntentParserInstrumentedTest"
  "com.tunelyst.music.data.PersonalDataBackupInstrumentedTest"
  "com.tunelyst.music.ReleaseCompatibilitySmokeTest"
)

for index in "${!test_selectors[@]}"; do
  selector="${test_selectors[$index]}"
  display_index=$((index + 1))
  printf '\n=== Android 36 test selector %s/%s: %s ===\n' \
    "$display_index" "${#test_selectors[@]}" "$selector" | tee -a "$log_file"

  if timeout --signal=TERM --kill-after=30s 10m \
    bash ./gradlew --no-daemon connectedDebugAndroidTest --stacktrace \
      "-Pandroid.testInstrumentationRunnerArguments.class=$selector" 2>&1 | tee -a "$log_file"; then
    printf '=== PASS: %s ===\n' "$selector" | tee -a "$log_file"
  else
    result=$?
    printf '=== FAIL/TIMEOUT: %s (exit status %s) ===\n' \
      "$selector" "$result" | tee -a "$log_file"
    exit "$result"
  fi
done
