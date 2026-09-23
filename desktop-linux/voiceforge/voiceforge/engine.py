"""XTTS-v2 synthesis engine.

The model (Coqui XTTS-v2, 17 languages including Polish) is loaded lazily on
the first generation. Delivery effects — mood, loudness, speed, pitch — are
applied with ffmpeg after synthesis so the controls are instant and predictable.
"""
from __future__ import annotations

import os
import subprocess
import tempfile
import threading
import wave
from pathlib import Path

# --------------------------------------------------------------------------- #
# Mood presets: they simply preset the pitch / speed / loudness controls,
# which the user can still fine-tune afterwards.
# --------------------------------------------------------------------------- #
MOODS: dict[str, dict[str, float]] = {
    "Neutral":  {"pitch": 0.0, "speed": 1.00, "gain": 0.0},
    "Happy":    {"pitch": 1.0, "speed": 1.08, "gain": 1.5},
    "Sad":      {"pitch": -1.0, "speed": 0.90, "gain": -1.5},
    "Angry":    {"pitch": 0.5, "speed": 1.12, "gain": 2.5},
    "Calm":     {"pitch": -0.5, "speed": 0.93, "gain": -1.0},
    "Excited":  {"pitch": 1.5, "speed": 1.16, "gain": 2.0},
    "Whisper":  {"pitch": 0.0, "speed": 0.95, "gain": -5.0},
    "Serious":  {"pitch": -0.5, "speed": 0.97, "gain": 0.5},
}

# XTTS-v2 languages (Polish first — the app's default).
LANGUAGES: list[tuple[str, str]] = [
    ("Polski (Polish)", "pl"),
    ("English", "en"),
    ("Deutsch (German)", "de"),
    ("Español (Spanish)", "es"),
    ("Français (French)", "fr"),
    ("Italiano", "it"),
    ("Português", "pt"),
    ("Русский", "ru"),
    ("Türkçe", "tr"),
    ("Nederlands", "nl"),
    ("Čeština", "cs"),
    ("Magyar", "hu"),
    ("中文 (Chinese)", "zh"),
    ("日本語 (Japanese)", "ja"),
    ("한국어 (Korean)", "ko"),
    ("हिन्दी (Hindi)", "hi"),
    ("العربية (Arabic)", "ar"),
]

XTTS_MODEL = "tts_models/multilingual/multi-dataset/xtts_v2"


class EngineError(RuntimeError):
    pass


def _wav_sample_rate(path: Path) -> int:
    with wave.open(str(path), "rb") as fh:
        return fh.getframerate()


def delivery_filters(pitch: float, speed: float, gain: float,
                     sr: int, *, normalize: bool = False) -> list[str]:
    """Build the ffmpeg filter chain applying the delivery settings.

    Order: pitch (asetrate) → tempo (atempo) → EBU R128 loudness
    normalization → user volume gain (an offset applied *after*
    normalization) → final resample.
    """
    filters: list[str] = []
    if abs(pitch) > 1e-3:
        shifted = sr * (2.0 ** (pitch / 12.0))
        filters.append(f"asetrate={shifted:.2f}")
        filters.append(f"aresample={sr}")
    if abs(speed - 1.0) > 1e-3:
        filters.append(f"atempo={max(0.5, min(2.0, speed)):.4f}")
    if normalize:
        filters.append("loudnorm=I=-16:TP=-1.5:LRA=11")
    if abs(gain) > 1e-3:
        filters.append(f"volume={gain:+.2f}dB")
    filters.append("aresample=44100")
    return filters


def _run_ffmpeg(cmd: list[str]) -> None:
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=1800)
    if proc.returncode != 0:
        raise EngineError(f"ffmpeg failed: {proc.stderr.strip()[-500:]}")


class TTSEngine:
    """Thread-safe singleton wrapper around the XTTS-v2 model."""

    def __init__(self) -> None:
        self._model = None
        self._lock = threading.Lock()
        self.device = "cpu"

    # ------------------------------------------------------------------ #
    def _get_model(self, status=None):
        with self._lock:
            if self._model is None:
                if status:
                    status("Loading XTTS-v2 model — first run downloads ~2.5 GB…")
                try:
                    import torch
                    torch.set_num_threads(os.cpu_count() or 4)
                    from TTS.api import TTS
                    self._model = TTS(XTTS_MODEL).to(self.device)
                except Exception as exc:  # noqa: BLE001
                    raise EngineError(
                        f"Could not load the XTTS-v2 model:\n{exc}"
                    ) from exc
            return self._model

    # ------------------------------------------------------------------ #
    def synthesize(
        self,
        text: str,
        speaker_wav: Path | str,
        out_mp3: Path | str,
        *,
        language: str = "pl",
        pitch: float = 0.0,        # semitones
        speed: float = 1.0,        # tempo multiplier
        gain: float = 0.0,         # loudness in dB
        normalize: bool = False,   # EBU R128 loudness normalization first
        status=None,
    ) -> Path:
        """Generate speech from `text` in the cloned voice and write an MP3."""
        text = " ".join((text or "").split())
        if not text:
            raise EngineError("The text is empty.")
        out_mp3 = Path(out_mp3)
        out_mp3.parent.mkdir(parents=True, exist_ok=True)

        say = status or (lambda _msg: None)

        # 1. Normalize the reference sample to mono WAV (what XTTS expects).
        say("Preparing the voice sample…")
        tmpdir = Path(tempfile.mkdtemp(prefix="voiceforge_"))
        ref_wav = tmpdir / "reference.wav"
        raw_wav = tmpdir / "raw.wav"
        _run_ffmpeg([
            "ffmpeg", "-y", "-v", "error", "-i", str(speaker_wav),
            "-vn", "-ac", "1", "-ar", "22050", str(ref_wav),
        ])

        # 2. Synthesize.
        say("Synthesizing speech (this can take a minute on CPU)…")
        model = self._get_model(status)
        try:
            model.tts_to_file(
                text=text,
                speaker_wav=str(ref_wav),
                language=language,
                file_path=str(raw_wav),
                split_sentences=True,
            )
        except Exception as exc:  # noqa: BLE001
            raise EngineError(f"Synthesis failed:\n{exc}") from exc

        # 3. Delivery: pitch (asetrate), speed (atempo), loudness
        #    (optional EBU R128 normalization, then the user's volume gain).
        say("Applying mood, loudness, speed and pitch…")
        sr = _wav_sample_rate(raw_wav)
        filters = delivery_filters(pitch, speed, gain, sr, normalize=normalize)

        say("Encoding MP3…")
        cmd = ["ffmpeg", "-y", "-v", "error", "-i", str(raw_wav)]
        if filters:
            cmd += ["-af", ",".join(filters)]
        cmd += ["-codec:a", "libmp3lame", "-b:a", "192k", str(out_mp3)]
        _run_ffmpeg(cmd)

        # Cleanup temp files.
        for p in tmpdir.glob("*"):
            p.unlink(missing_ok=True)
        tmpdir.rmdir()
        return out_mp3


# One engine per process — the model is expensive to load.
ENGINE = TTSEngine()
