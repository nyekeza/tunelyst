#!/usr/bin/env bash
# Wait until the Android emulator is online, fully booted, and its package
# manager responds before Gradle tries to discover a compatible test device.
set -euo pipefail

expected_api="${1:?Usage: wait-for-android-device.sh EXPECTED_API [MAX_ATTEMPTS] [DELAY_SECONDS]}"
max_attempts="${2:-36}"
delay_seconds="${3:-5}"
required_samples=3
consecutive_samples=0
device=""

printf 'Waiting for an Android API %s emulator with a responsive package manager.\n' "$expected_api"
adb devices -l || true

for ((attempt = 1; attempt <= max_attempts; attempt++)); do
  device="$(timeout 5s adb devices 2>/dev/null | awk '$2 == "device" { print $1; exit }' || true)"

  if [[ -z "$device" ]]; then
    consecutive_samples=0
    printf 'Probe %s/%s: no online ADB device yet.\n' "$attempt" "$max_attempts"
  else
    state="$(timeout 5s adb -s "$device" get-state 2>/dev/null || true)"
    boot_completed="$(timeout 5s adb -s "$device" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    sdk_level="$(timeout 5s adb -s "$device" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
    package_path="$(timeout 5s adb -s "$device" shell cmd package path android 2>&1 | tr -d '\r' || true)"

    if [[ "$state" == "device" && "$boot_completed" == "1" && "$sdk_level" == "$expected_api" && "$package_path" == package:* ]]; then
      consecutive_samples=$((consecutive_samples + 1))
      printf 'Probe %s/%s: ready (%s/%s consecutive checks; API %s; package manager responsive).\n' \
        "$attempt" "$max_attempts" "$consecutive_samples" "$required_samples" "$sdk_level"

      if [[ "$consecutive_samples" -ge "$required_samples" ]]; then
        printf 'Emulator is ready: %s, API %s.\n' "$device" "$sdk_level"
        exit 0
      fi
    else
      consecutive_samples=0
      printf 'Probe %s/%s: not ready (state=%s, boot=%s, API=%s, package=%s).\n' \
        "$attempt" "$max_attempts" "${state:-unknown}" "${boot_completed:-unknown}" \
        "${sdk_level:-unknown}" "${package_path:-unresponsive}"
    fi
  fi

  sleep "$delay_seconds"
done

printf 'ERROR: Android API %s emulator did not become ready after %s probes.\n' \
  "$expected_api" "$max_attempts" >&2
adb devices -l >&2 || true
if [[ -n "$device" ]]; then
  printf '\nFinal emulator readiness diagnostics for %s:\n' "$device" >&2
  timeout 10s adb -s "$device" shell getprop sys.boot_completed >&2 || true
  timeout 10s adb -s "$device" shell getprop ro.build.version.sdk >&2 || true
  timeout 10s adb -s "$device" shell cmd package path android >&2 || true
  timeout 10s adb -s "$device" logcat -d -v threadtime -t 100 >&2 || true
fi
exit 1
