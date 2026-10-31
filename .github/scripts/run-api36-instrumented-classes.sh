#!/usr/bin/env bash
# Run each Android 36 instrumentation class separately so a slow/hung class is identifiable.
set -euo pipefail

log_file="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/connectedDebugAndroidTest.log"
mkdir -p "$(dirname "$log_file")"
: > "$log_file"

classes=(
  "com.tunelyst.music.playback.PlaybackTransitionInstrumentedTest"
  "com.tunelyst.music.IncomingAudioIntentParserInstrumentedTest"
  "com.tunelyst.music.data.PersonalDataBackupInstrumentedTest"
  "com.tunelyst.music.ReleaseCompatibilitySmokeTest"
)

for index in "${!classes[@]}"; do
  test_class="${classes[$index]}"
  display_index=$((index + 1))
  printf '\n=== Android 36 test class %s/%s: %s ===\n' \
    "$display_index" "${#classes[@]}" "$test_class" | tee -a "$log_file"

  if timeout --signal=TERM --kill-after=30s 10m \
    bash ./gradlew --no-daemon connectedDebugAndroidTest --stacktrace \
      "-Pandroid.testInstrumentationRunnerArguments.class=$test_class" 2>&1 | tee -a "$log_file"; then
    printf '=== PASS: %s ===\n' "$test_class" | tee -a "$log_file"
  else
    result=$?
    printf '=== FAIL/TIMEOUT: %s (exit status %s) ===\n' \
      "$test_class" "$result" | tee -a "$log_file"
    exit "$result"
  fi
done
