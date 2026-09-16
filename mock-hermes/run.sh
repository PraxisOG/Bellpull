#!/usr/bin/env bash
# Fixture replay server. See server.py's docstring for the full environment.
#
#   MOCK_HERMES_PORT=8643            listen port
#   MOCK_HERMES_KEY=...              required bearer key (every route returns 401 without it)
#   MOCK_HERMES_SSE_DELAY=0.25       seconds between SSE events, for incremental streaming
#   MOCK_HERMES_FAIL=stream-abort    default fault; per-request use the X-Mock-Fail header
set -euo pipefail
cd "$(dirname "$0")"
exec python3 server.py
