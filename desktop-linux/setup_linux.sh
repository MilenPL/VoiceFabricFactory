#!/usr/bin/env bash
# Sets up the Linux environment for VoiceForge (no root required).
set -euo pipefail
cd "$(dirname "$0")"

# 1. Install uv (manages a standalone Python 3.11, since system Python is 3.14)
if ! command -v uv >/dev/null 2>&1; then
    curl -LsSf https://astral.sh/uv/install.sh | sh
fi
export PATH="$HOME/.local/bin:$PATH"

# 2. Create venv with Python 3.11 (Coqui TTS does not support 3.14)
if [ ! -d .venv ]; then
    uv venv --python 3.11 .venv
fi

# 3. PyTorch (CPU build) + Coqui TTS (XTTS-v2) + audio helpers
uv pip install --python .venv/bin/python torch --index-url https://download.pytorch.org/whl/cpu
uv pip install --python .venv/bin/python coqui-tts soundfile

echo "LINUX SETUP DONE"
.venv/bin/python -c "import TTS, torch; print('TTS', TTS.__version__, '| torch', torch.__version__)"
