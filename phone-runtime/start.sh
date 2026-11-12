#!/bin/sh
set -eu
# Matches the installed Android Codex client. Commands have access to this Linux environment.
export TEN_CODEX_SANDBOX=danger-full-access
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec python3 server.py
