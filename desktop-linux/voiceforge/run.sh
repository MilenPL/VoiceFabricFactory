#!/usr/bin/env bash
# Launch VoiceForge (Linux/GTK).
set -euo pipefail
cd "$(dirname "$0")"
export PATH="$HOME/.local/bin:$PATH"
if [ ! -x ../.venv/bin/python ]; then
    echo "Environment missing — run ../setup_linux.sh first." >&2
    exit 1
fi
exec ../.venv/bin/python -m voiceforge "$@"
