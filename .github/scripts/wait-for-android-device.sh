#!/usr/bin/env bash
# Wait for the API-level emulator and Android shell/package manager to stay responsive
# before Gradle discovers an instrumented-test device.
set -euo pipefail

expected_api="${1:?Usage: wait-for-android-device.sh EXPECTED_API [MAX_ATTEMPTS] [DELAY_SECONDS]}"
max_attempts="${2:-36}"
delay_seconds="${3:-5}"
required_samples=3
consecutive_samples=0
device=""

printf 'Waiting for an Android API %s emulator with responsive device properties and package manager.\n' "$expected_api"
adb devices -l || true

for ((attempt = 1; attempt <= max_attempts; attempt++)); do
  device="$(timeout 5s adb devices 2>/dev/null | awk '$2 == "device" { print $1; exit }' || true)"

  if [[ -z "$device" ]]; then
    consecutive_samples=0
    printf 'Probe %s/%s: no online ADB device yet.\n' "$attempt" "$max_attempts"
  else
    state="$(timeout 5s adb -s "$device" get-state 2>/dev/null || true)"
    # Test the full property-table read, not just individual properties: Gradle's
    # device discovery can fail when this shell command is unresponsive.
    properties="$(timeout 2s adb -s "$device" shell getprop 2>&1 | tr -d '\r' || true)"
    package_path="$(timeout 5s adb -s "$device" shell cmd package path android 2>&1 | tr -d '\r' || true)"

    boot_ready=false
    api_ready=false
    if [[ "$properties" == *"[sys.boot_completed]: [1]"* ]]; then
      boot_ready=true
    fi
    if [[ "$properties" == *"[ro.build.version.sdk]: [$expected_api]"* ]]; then
      api_ready=true
    fi

    if [[ "$state" == "device" && "$boot_ready" == true && "$api_ready" == true && "$package_path" == package:* ]]; then
      consecutive_samples=$((consecutive_samples + 1))
      printf 'Probe %s/%s: ready (%s/%s consecutive full-property checks; API %s; package manager responsive).\n' \
        "$attempt" "$max_attempts" "$consecutive_samples" "$required_samples" "$expected_api"

      if [[ "$consecutive_samples" -ge "$required_samples" ]]; then
        printf 'Emulator is ready: %s, API %s.\n' "$device" "$expected_api"
        exit 0
      fi
    else
      consecutive_samples=0
      if [[ "$api_ready" == true ]]; then
        api_status="$expected_api"
      else
        api_status="missing-or-unresponsive"
      fi
      if [[ "$boot_ready" == true ]]; then
        boot_status=1
      else
        boot_status="missing-or-unresponsive"
      fi
      if [[ "$properties" == *"ro.build.version.sdk"* ]]; then
        property_status=responsive
      else
        property_status=unresponsive
      fi
      printf 'Probe %s/%s: not ready (state=%s, boot=%s, API=%s, full_getprop=%s, package=%s).\n' \
        "$attempt" "$max_attempts" "${state:-unknown}" "$boot_status" "$api_status" \
        "$property_status" "${package_path:-unresponsive}"
    fi
  fi

  sleep "$delay_seconds"
done

printf 'ERROR: Android API %s emulator did not pass %s consecutive readiness probes within %s attempts.\n' \
  "$expected_api" "$required_samples" "$max_attempts" >&2
adb devices -l >&2 || true
if [[ -n "$device" ]]; then
  printf '\nFinal emulator diagnostics for %s:\n' "$device" >&2
  printf '$ adb shell getprop (full property table; 2-second limit)\n' >&2
  timeout 2s adb -s "$device" shell getprop >&2 || true
  printf '\n$ adb shell cmd package path android\n' >&2
  timeout 5s adb -s "$device" shell cmd package path android >&2 || true
  printf '\nLast 100 logcat lines:\n' >&2
  timeout 10s adb -s "$device" logcat -d -v threadtime -t 100 >&2 || true
fi
exit 1
