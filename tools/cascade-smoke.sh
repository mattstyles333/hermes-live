#!/usr/bin/env bash
# One Bill smoke call on the emulator (spec v2 §11b, cut down): the debug app, engine cascade,
# the real OpenRouter with the key from OPENROUTER_API_KEY (never written anywhere), the fake
# Hermes on the host, the request spoken by TTS and fed through the real sherpa pipeline.
#
#   OPENROUTER_API_KEY=… tools/cascade-smoke.sh [out-dir]
#   DRY_RUN=1 tools/cascade-smoke.sh [out-dir]    # no key, no OpenRouter request: plumbing only
#
# Keeps the debug app installed (install -r: the speech models stay). Writes bill.json (timings,
# no transcripts), the fake's prompt count, a mid-task screenshot and a 45 s screen recording
# into out-dir (default ~/reports/assets/hermes-cascade-smoke).
set -euo pipefail
if [[ -n ${DRY_RUN:-} ]]; then KEYARGS=(-e dryRun true); else
  : "${OPENROUTER_API_KEY:?set OPENROUTER_API_KEY (it is passed to the test, never printed), or DRY_RUN=1}"
  KEYARGS=(-e openrouterKey "$OPENROUTER_API_KEY")
fi
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUT=${1:-$HOME/reports/assets/hermes-cascade-smoke}
export JAVA_HOME=${JAVA_HOME:-/tmp/folio-toolchain/java} ANDROID_HOME=${ANDROID_HOME:-/tmp/folio-toolchain/android-sdk}
ADB=("$ANDROID_HOME/platform-tools/adb" -s "${SERIAL:-emulator-5554}")
PORT=${FAKE_HERMES_PORT:-18119}
mkdir -p "$OUT"

started=""
if ! curl -fsS "http://127.0.0.1:$PORT/__fake/health" >/dev/null 2>&1; then
  "$ROOT/tools/fake-hermes/run.sh" "$PORT" -- --quiet >"$OUT/fake-hermes.log" 2>&1 &
  started=$!
  for _ in $(seq 50); do curl -fsS "http://127.0.0.1:$PORT/__fake/health" >/dev/null 2>&1 && break; sleep 0.2; done
fi
trap '[[ -n $started ]] && kill $started 2>/dev/null || true' EXIT
if [[ -n $started ]]; then
  curl -fsS -X POST "http://127.0.0.1:$PORT/__fake/reset" >/dev/null
else
  echo "Using the fake Hermes already on :$PORT (not reset; its prompt counts include earlier runs)." >&2
fi

(cd "$ROOT" && ./gradlew -q :live:assembleDebug :live:assembleDebugAndroidTest)
"${ADB[@]}" install -r -t "$ROOT/live/build/outputs/apk/debug/live-debug.apk" >/dev/null
"${ADB[@]}" install -r -t "$ROOT/live/build/outputs/apk/androidTest/debug/live-debug-androidTest.apk" >/dev/null

"${ADB[@]}" shell screenrecord --time-limit 45 /sdcard/Download/bill-smoke.mp4 >/dev/null 2>&1 &
rec=$!
set +e
"${ADB[@]}" shell am instrument -w -e class com.folio.hermeslive.smoke.BillSmokeTest \
  "${KEYARGS[@]}" com.folio.hermeslive.test/androidx.test.runner.AndroidJUnitRunner >"$OUT/instrument.txt" 2>&1
status=$?
set -e
wait $rec 2>/dev/null || true

D=/sdcard/Android/data/com.folio.hermeslive/files
"${ADB[@]}" pull "$D/smoke/bill.json" "$OUT/bill.json" >/dev/null 2>&1 || true
"${ADB[@]}" pull /sdcard/Download/bill-smoke-midtask.png "$OUT/bill-smoke-midtask.png" >/dev/null 2>&1 || true
"${ADB[@]}" pull /sdcard/Download/bill-smoke.mp4 "$OUT/bill-smoke.mp4" >/dev/null 2>&1 || true
curl -fsS "http://127.0.0.1:$PORT/__fake/assert/prompts" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps({"prompts": d.get("count")}))' >"$OUT/fake-prompts.json"
curl -fsS "http://127.0.0.1:$PORT/__fake/assert/double-submits" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps({"no_double_submits": d.get("ok")}))' >"$OUT/fake-doubles.json"
grep -E "OK \(|FAILURES|Tests run" "$OUT/instrument.txt" || true
cat "$OUT/bill.json" "$OUT/fake-prompts.json" "$OUT/fake-doubles.json" 2>/dev/null || true
exit $status
