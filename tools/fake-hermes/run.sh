#!/usr/bin/env bash
# Fake Hermes on the host, for end-to-end tests. The emulator reaches it at http://10.0.2.2:<port>
# (hub-emulator uses host networking, so the host's 127.0.0.1 is enough).
#
#   tools/fake-hermes/run.sh [port] [scenario.json] [data-dir] [-- more fake_hermes.py flags]
#
# Defaults: port 18119, scenarios/default.json, a fresh temp data dir. Env FAKE_HERMES_PORT,
# FAKE_HERMES_SCENARIO and FAKE_HERMES_DATA_DIR work too. Login: user "fake", password
# "fake-password" (change with -- --user X --password Y). Admin API: http://127.0.0.1:<port>/__fake/
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PORT=${FAKE_HERMES_PORT:-18119}
SCENARIO=${FAKE_HERMES_SCENARIO:-$HERE/scenarios/default.json}
DATA_DIR=${FAKE_HERMES_DATA_DIR:-}
if [[ $# -gt 0 && $1 != "--" ]]; then PORT=$1; shift; fi
if [[ $# -gt 0 && $1 != "--" ]]; then SCENARIO=$1; shift; fi
if [[ $# -gt 0 && $1 != "--" ]]; then DATA_DIR=$1; shift; fi
if [[ $# -gt 0 && $1 == "--" ]]; then shift; fi
if [[ -z $DATA_DIR ]]; then DATA_DIR=$(mktemp -d -t fake-hermes-XXXXXX); fi
exec python3 -B "$HERE/fake_hermes.py" --port "$PORT" --scenario "$SCENARIO" --data-dir "$DATA_DIR" "$@"
