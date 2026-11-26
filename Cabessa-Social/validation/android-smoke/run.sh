#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME}}"
ADB="$SDK_ROOT/platform-tools/adb"
OUT="$TASK_ROOT/build/android-smoke"
TIMEOUT_SCALE="${CABESSA_SMOKE_TIMEOUT_SCALE:-1}"
python3 "$TASK_ROOT/validation/android-smoke/build.py"
"$ADB" wait-for-device
"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0
"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell wm dismiss-keyguard
# Each build uses a disposable test key; remove only the isolated test package.
"$ADB" uninstall com.dvircab.social.smoke >/dev/null 2>&1 || true
"$ADB" install -r "$OUT/cabessa-framework-smoke.apk"
"$ADB" shell pm clear com.dvircab.social.smoke
set +e
"$ADB" shell am instrument -w -e timeoutScale "$TIMEOUT_SCALE" \
    com.dvircab.social.smoke/com.feurstagram.extension.SmokeInstrumentation > "$OUT/instrumentation.txt" 2>&1
RUN_STATUS=$?
set -e
cat "$OUT/instrumentation.txt"
"$ADB" pull /sdcard/Android/data/com.dvircab.social.smoke/files "$OUT/device-results" || true
"$ADB" logcat -d -s AndroidRuntime:E > "$OUT/android-crashes.txt"
"$ADB" logcat -d -v threadtime -s MediaPlayer:V MediaPlayerNative:V NuPlayer:V GenericSource:V CCodec:V Codec2Client:V AndroidRuntime:E > "$OUT/media-logcat.txt"
validate_result() {
python3 - "$1" "$2" <<'PY'
import json, sys
from pathlib import Path
path = Path(sys.argv[1])
if not path.is_file():
    raise SystemExit('Instrumentation did not produce a result: ' + str(path))
result = json.loads(path.read_text())
print(json.dumps(result, ensure_ascii=False, indent=2))
if result.get('status') != 'passed' or len(result.get('framework_checks', [])) < int(sys.argv[2]):
    raise SystemExit('Android framework smoke checks failed')
PY
}
MAIN_PASSED=0
if validate_result "$OUT/device-results/smoke-result.json" 8 && [ "$RUN_STATUS" -eq 0 ]; then
    MAIN_PASSED=1
else
    RUN_STATUS=1
fi

# Keep the original failure, but collect independent UI evidence in this same
# emulator session. No lifecycle assertion is skipped or reclassified as passed.
if [ "$MAIN_PASSED" -eq 0 ]; then
    if ! "$ADB" shell am instrument -w -e screenshotsOnly true -e suffix ui -e timeoutScale "$TIMEOUT_SCALE" \
        com.dvircab.social.smoke/com.feurstagram.extension.SmokeInstrumentation > "$OUT/ui-recovery.txt" 2>&1; then
        RUN_STATUS=1
    fi
    cat "$OUT/ui-recovery.txt"
    "$ADB" pull /sdcard/Android/data/com.dvircab.social.smoke/files "$OUT/recovery-results" || true
    if ! validate_result "$OUT/recovery-results/smoke-result-ui.json" 0; then RUN_STATUS=1; fi
fi

# A second render uses a genuinely narrow display and large text, not a resized PNG.
trap '"$ADB" shell wm size reset; "$ADB" shell wm density reset' EXIT
"$ADB" shell wm size 960x1920
"$ADB" shell wm density 480
if ! "$ADB" shell am instrument -w -e screenshotsOnly true -e suffix narrow -e largeFont true -e timeoutScale "$TIMEOUT_SCALE" \
    com.dvircab.social.smoke/com.feurstagram.extension.SmokeInstrumentation > "$OUT/narrow-render.txt" 2>&1; then
    RUN_STATUS=1
fi
cat "$OUT/narrow-render.txt"
"$ADB" pull /sdcard/Android/data/com.dvircab.social.smoke/files "$OUT/narrow-results" || true
if ! validate_result "$OUT/narrow-results/smoke-result-narrow.json" 0; then RUN_STATUS=1; fi
exit "$RUN_STATUS"
