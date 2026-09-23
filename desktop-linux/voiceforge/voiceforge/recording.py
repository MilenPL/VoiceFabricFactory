"""Microphone recording via ffmpeg + PulseAudio/PipeWire."""
from __future__ import annotations

import signal
import subprocess
import time
from pathlib import Path

MAX_RECORD_SECONDS = 600  # 10 minutes hard cap


def list_sources() -> list[tuple[str, str]]:
    """Return [(name, human label)] of capture devices (monitors excluded)."""
    try:
        out = subprocess.run(
            ["pactl", "list", "short", "sources"],
            capture_output=True, text=True, timeout=10,
        ).stdout
    except Exception:
        return [("default", "Default microphone")]
    sources: list[tuple[str, str]] = []
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        name = parts[1].strip()
        if ".monitor" in name:
            continue
        label = name.split(".")[-1] if name else "Microphone"
        sources.append((name, label))
    sources.append(("default", "Default microphone"))
    return sources


class Recorder:
    """Records a single mono WAV from a PulseAudio source."""

    def __init__(self, source: str, out_path: Path) -> None:
        self.source = source
        self.out_path = Path(out_path)
        self.proc: subprocess.Popen | None = None
        self.started_at: float = 0.0

    @property
    def running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    @property
    def elapsed(self) -> float:
        if not self.running:
            return 0.0
        return time.time() - self.started_at

    def start(self) -> None:
        if self.running:
            return
        self.out_path.parent.mkdir(parents=True, exist_ok=True)
        self.proc = subprocess.Popen(
            [
                "ffmpeg", "-y", "-v", "error",
                "-f", "pulse", "-i", self.source,
                "-ac", "1", "-ar", "44100",
                "-t", str(MAX_RECORD_SECONDS),
                str(self.out_path),
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        self.started_at = time.time()

    def stop(self) -> float:
        """Stop recording and return the duration in seconds."""
        if self.proc is None:
            return 0.0
        if self.proc.poll() is None:
            # SIGINT lets ffmpeg finalize the WAV header cleanly.
            try:
                self.proc.send_signal(signal.SIGINT)
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=5)
        duration = max(0.0, time.time() - self.started_at)
        self.proc = None
        return duration

    def cancel(self) -> None:
        if self.proc is not None:
            self.stop()
        self.out_path.unlink(missing_ok=True)
