#!/usr/bin/env python3
"""Fake Hermes dashboard + gateway for end-to-end tests. Python 3 stdlib only.

    python3 -B fake_hermes.py --port 18119 --scenario scenarios/default.json --data-dir /tmp/fh

Prints `FAKE_HERMES_LISTENING port=<n>` once it accepts connections. See README.md.
"""

import argparse
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fakehermes.server import serve  # noqa: E402


def main() -> None:
    here = os.path.dirname(os.path.abspath(__file__))
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--bind", default="127.0.0.1", help="address to listen on (the emulator reaches the host's 127.0.0.1 as 10.0.2.2)")
    p.add_argument("--port", type=int, default=18119, help="0 picks a free port")
    p.add_argument("--scenario", default=os.path.join(here, "scenarios", "default.json"))
    p.add_argument("--data-dir", default=None, help="sessions, tokens and the received log persist here (default: a new temp dir)")
    p.add_argument("--user", default="fake")
    p.add_argument("--password", default="fake-password")
    p.add_argument("--tls-cert", default=None, help="serve https with this PEM certificate")
    p.add_argument("--tls-key", default=None)
    p.add_argument("--quiet", action="store_true")
    args = p.parse_args()
    data_dir = args.data_dir or tempfile.mkdtemp(prefix="fake-hermes-")
    serve(args, data_dir)


if __name__ == "__main__":
    main()
