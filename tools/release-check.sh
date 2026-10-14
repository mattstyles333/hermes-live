#!/usr/bin/env bash
# Tier 2, once before a release (test-shape plan): three lanes at once, then pass/fail per lane.
#
#   OPENROUTER_API_KEY=… tools/release-check.sh [out-dir]
#
# 1. jev:     the live Jev eval (LiveJevEvalTest): harmful <= 3% on both sets; friction reported.
# 2. staging: the staging (R8) build installed and launched on the emulator, with an STT round-trip.
# 3. smoke:   one Bill smoke call (tools/cascade-smoke.sh: corpus speech -> Jev -> fake Hermes -> Bill),
#             after lane 2, on the same emulator, once the staging app is stopped (its speech models
#             would otherwise still hold the emulator's CPU and skew the smoke's timings). Leaves
#             bill-smoke-midtask.png in out-dir. Its timings count from the VAD's end of speech.
# Lane 1 needs only the network and runs beside lanes 2 -> 3. The APKs are built first, in one
# Gradle run, so the lanes never wait on each other's builds. The fake Hermes is started here on a
# free port and stopped at the end. The key is read from the environment only: it is passed to the
# test JVM and the instrumentation, never written or printed.
#
# Env: SERIAL (default emulator-5554), JAVA_HOME / ANDROID_HOME (default /tmp/folio-toolchain/...).
set -uo pipefail
: "${OPENROUTER_API_KEY:?set OPENROUTER_API_KEY (read from the environment, never printed)}"
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUT=${1:-$HOME/reports/assets/hermes-cascade-smoke}
export JAVA_HOME=${JAVA_HOME:-/tmp/folio-toolchain/java} ANDROID_HOME=${ANDROID_HOME:-/tmp/folio-toolchain/android-sdk}
SERIAL=${SERIAL:-emulator-5554}
ADB=("$ANDROID_HOME/platform-tools/adb" -s "$SERIAL")
WORK=$(mktemp -d -t release-check-XXXXXX)
mkdir -p "$OUT"
now() { date +%s.%N; }
secs() { python3 -c "import sys; print('%.0f' % (float(sys.argv[2]) - float(sys.argv[1])))" "$1" "$2"; }

started=$(now)
"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "release-check: no device $SERIAL (start ~/hub-tools/android-emulator/run.sh)" >&2; exit 2; }

# ── the fake Hermes on a free port ─────────────────────────────────────────────────────────
"$ROOT/tools/fake-hermes/run.sh" 0 "$ROOT/tools/fake-hermes/scenarios/default.json" "$WORK/fake-data" -- --quiet >"$WORK/fake-hermes.log" 2>&1 &
fake=$!
cleanup() { kill "$fake" 2>/dev/null || true; }
trap cleanup EXIT
PORT=""
for _ in $(seq 100); do
  PORT=$(grep -o 'FAKE_HERMES_LISTENING port=[0-9]*' "$WORK/fake-hermes.log" 2>/dev/null | head -1 | cut -d= -f2)
  [[ -n $PORT ]] && break
  sleep 0.1
done
[[ -n $PORT ]] || { echo "release-check: the fake Hermes did not start" >&2; cat "$WORK/fake-hermes.log" >&2; exit 2; }

# ── build everything once ──────────────────────────────────────────────────────────────────
b0=$(now)
if ! (cd "$ROOT" && ./gradlew -q :live:assembleDebug :live:assembleDebugAndroidTest :live:assembleStaging :hermes:compileDebugUnitTestKotlin) >"$WORK/build.log" 2>&1; then
  echo "release-check: build FAILED (see $WORK/build.log)" >&2
  exit 1
fi
build_s=$(secs "$b0" "$(now)")

# ── lane 1: the live Jev eval ──────────────────────────────────────────────────────────────
lane_jev() {
  local t0; t0=$(now)
  local ok=0
  (cd "$ROOT" && ./gradlew --no-configuration-cache -q :hermes:testDebugUnitTest --tests '*LiveJevEvalTest*' --rerun) >"$WORK/jev.log" 2>&1 || ok=1
  local xml="$ROOT/hermes/build/test-results/testDebugUnitTest/TEST-com.folio.hermes.cascade.routing.LiveJevEvalTest.xml"
  python3 - "$xml" >"$WORK/jev.summary" 2>&1 <<'PY' || ok=1
import sys, xml.etree.ElementTree as E
r = E.parse(sys.argv[1]).getroot()
out = r.find('system-out').text or ''
if int(r.get('skipped')) or int(r.get('failures')) or int(r.get('errors')):
    print('the eval was skipped or failed'); sys.exit(1)
lines = [l.strip() for l in out.splitlines() if l.startswith('==') or 'harmful:' in l or 'friction:' in l or 'gate (' in l or 'latency' in l]
print('\n'.join(l.split('   ')[0] if ('harmful:' in l or 'friction:' in l) else l for l in lines))
gates = [l for l in lines if l.startswith('gate (')]
sys.exit(0 if len(gates) == 2 and all(': PASS' in g for g in gates) else 1)
PY
  echo "$ok $(secs "$t0" "$(now)")" >"$WORK/jev.result"
}

# ── lane 2: staging (R8) install, launch and an STT round-trip ─────────────────────────────
lane_staging() {
  local t0; t0=$(now)
  local ok=0 pkg=com.folio.hermeslive.staging
  local ext=/sdcard/Android/data/$pkg/files
  {
    "${ADB[@]}" install -r -t "$ROOT/live/build/outputs/apk/staging/live-staging.apk" >/dev/null
    # One corpus WAV and the manifest (kept from earlier runs if present).
    if ! "${ADB[@]}" shell "test -f $ext/stt-corpus/e01-sarah.wav"; then
      "${ADB[@]}" shell "mkdir -p $ext/stt-corpus"
      "${ADB[@]}" push "$HOME/reports/assets/stt-corpus/e01-sarah.wav" "$HOME/reports/assets/stt-corpus/manifest.jsonl" "$ext/stt-corpus/" >/dev/null
      "${ADB[@]}" shell "chmod -R o+rX $ext/stt-corpus"
    fi
    run_once() {
      "${ADB[@]}" shell rm -f "$ext/smoke/result.json"
      "${ADB[@]}" shell am force-stop "$pkg"
      "${ADB[@]}" shell am start -W -n "$pkg/com.folio.hermeslive.smoke.StagingSmokeActivity" --es wav e01-sarah.wav >/dev/null
      for _ in $(seq 180); do "${ADB[@]}" shell "test -f $ext/smoke/result.json" && break; sleep 1; done
      "${ADB[@]}" pull "$ext/smoke/result.json" "$WORK/staging.json" >/dev/null
    }
    run_once
    # Speech models not installed in the staging app yet: copy the debug app's (it is debuggable) and run again.
    if grep -q '"models not installed"' "$WORK/staging.json" 2>/dev/null; then
      "${ADB[@]}" exec-out run-as com.folio.hermeslive tar -C no_backup/stt/models -cf - zipformer-en-2023-06-26 parakeet-tdt-110m >"$WORK/models.tar"
      "${ADB[@]}" shell "mkdir -p $ext/stt-models" && "${ADB[@]}" push "$WORK/models.tar" "$ext/stt-models/models.tar" >/dev/null
      "${ADB[@]}" shell "cd $ext/stt-models && tar -xf models.tar && rm models.tar && chmod -R o+rX $ext/stt-models"
      run_once
    fi
    python3 -c 'import json,sys; sys.exit(0 if json.load(open(sys.argv[1])).get("pass") else 1)' "$WORK/staging.json"
  } >"$WORK/staging.log" 2>&1 || ok=1
  # The smoke lane runs next on this emulator: nothing of the staging app may still be running.
  "${ADB[@]}" shell am force-stop "$pkg" >/dev/null 2>&1 || true
  echo "$ok $(secs "$t0" "$(now)")" >"$WORK/staging.result"
}

# ── lane 3: the Bill smoke call (after lane 2 and its app stopped, on the same emulator) ────
lane_smoke() {
  local t0; t0=$(now)
  local ok=0
  SKIP_BUILD=1 FAKE_HERMES_PORT=$PORT SERIAL=$SERIAL "$ROOT/tools/cascade-smoke.sh" "$OUT" >"$WORK/smoke.log" 2>&1 || ok=1
  echo "$ok $(secs "$t0" "$(now)")" >"$WORK/smoke.result"
}

lane_jev &
jev=$!
( lane_staging; lane_smoke ) &
device=$!
wait "$jev" "$device"

# ── report ─────────────────────────────────────────────────────────────────────────────────
verdict() { [[ $(cut -d' ' -f1 "$WORK/$1.result" 2>/dev/null) == 0 ]] && echo PASS || echo FAIL; }
took() { cut -d' ' -f2 "$WORK/$1.result" 2>/dev/null || echo "?"; }
fail=0
echo "release-check ($(date -u +%FT%TZ)), fake Hermes on :$PORT, build ${build_s}s"
for lane in jev staging smoke; do
  v=$(verdict "$lane"); [[ $v == PASS ]] || fail=1
  printf '  %-8s %s  %ss\n' "$lane" "$v" "$(took "$lane")"
done
echo "  jev:"; sed 's/^/    /' "$WORK/jev.summary" 2>/dev/null
echo "  staging: $(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print({k: d.get(k) for k in ("pass", "models_ready", "load_ms", "stt_ms", "fast_wer", "final_wer", "error") if k in d})' "$WORK/staging.json" 2>/dev/null)"
echo "  smoke: $(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print({k: d.get(k) for k in ("pass", "speech_end_to_stock_line_ms", "jev_latency_ms", "speech_end_to_task_started_ms", "clip_end_to_stock_line_ms", "vad_end_minus_clip_end_ms", "task_statuses", "error") if k in d})' "$OUT/bill.json" 2>/dev/null)"
echo "  screenshot: $OUT/bill-smoke-midtask.png"
echo "  total $(secs "$started" "$(now)")s; logs in $WORK"
exit $fail
