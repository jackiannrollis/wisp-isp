#!/usr/bin/env bash
# Use the same credential loading and strict callback/ledger assertions on every OS.
set -euo pipefail
if ! command -v pwsh >/dev/null 2>&1; then
  echo 'PowerShell 7 (pwsh) is required for the Daraja sandbox check.' >&2
  exit 1
fi
exec pwsh -NoProfile -File "$(dirname "$0")/daraja-sandbox.ps1" "$@"
