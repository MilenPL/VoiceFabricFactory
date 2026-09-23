"""App storage: voice samples and generated MP3s live in the app's data folder.

Layout (XDG compliant):
    ~/.local/share/voiceforge/
        voices/   <id>.mp3   + index.json   (uploaded/recorded voice samples)
        saved/    <id>.mp3   + index.json   (generated files kept in the library)
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import time
import uuid
from pathlib import Path

APP_NAME = "voiceforge"

DATA_DIR = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share")) / APP_NAME
VOICES_DIR = DATA_DIR / "voices"
SAVED_DIR = DATA_DIR / "saved"
VOICES_INDEX = VOICES_DIR / "index.json"
SAVED_INDEX = SAVED_DIR / "index.json"

# Small persisted settings file (UI language, loudness normalization, …).
CONFIG_DIR = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / APP_NAME
CONFIG_FILE = CONFIG_DIR / "config.json"
DEFAULT_CONFIG: dict = {"lang": "en", "normalize": False}

# XTTS needs at least ~6 s of reference audio to build a speaker embedding.
MIN_SAMPLE_SECONDS = 6.0
# The GUI targets 2–5 minute samples; longer ones are trimmed on import.
MAX_SAMPLE_SECONDS = 600.0


def ensure_dirs() -> None:
    VOICES_DIR.mkdir(parents=True, exist_ok=True)
    SAVED_DIR.mkdir(parents=True, exist_ok=True)


# --------------------------------------------------------------------------- #
# App configuration (~/.config/voiceforge/config.json)
# --------------------------------------------------------------------------- #
def load_config() -> dict:
    """Read the persisted app configuration (missing/broken file → defaults)."""
    config = dict(DEFAULT_CONFIG)
    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if isinstance(data, dict):
            config.update({k: v for k, v in data.items() if k in DEFAULT_CONFIG})
    except (json.JSONDecodeError, OSError, TypeError):
        pass
    config["lang"] = "pl" if config.get("lang") == "pl" else "en"
    config["normalize"] = bool(config.get("normalize"))
    return config


def save_config(config: dict) -> None:
    """Write the app configuration atomically (creating the file if needed)."""
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    tmp = CONFIG_FILE.with_suffix(".tmp")
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(config, fh, ensure_ascii=False, indent=2)
    tmp.replace(CONFIG_FILE)


def set_config(key: str, value) -> dict:
    """Update a single configuration key and persist it."""
    config = load_config()
    config[key] = value
    save_config(config)
    return config


def _load_index(path: Path) -> list:
    if not path.exists():
        return []
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, list) else []
    except (json.JSONDecodeError, OSError):
        return []


def _save_index(path: Path, items: list) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(items, fh, ensure_ascii=False, indent=2)
    tmp.replace(path)


def probe_duration(path: Path | str) -> float:
    """Return media duration in seconds (0.0 when it cannot be determined)."""
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
            capture_output=True, text=True, timeout=30,
        )
        return float(out.stdout.strip())
    except Exception:
        return 0.0


def convert_to_mp3(src: Path | str, dst: Path, *, trim_to: float | None = None,
                   bitrate: str = "192k") -> None:
    """Convert any ffmpeg-readable audio file into an MP3 inside the storage."""
    cmd = ["ffmpeg", "-y", "-v", "error", "-i", str(src)]
    if trim_to:
        cmd += ["-t", f"{trim_to:.3f}"]
    cmd += ["-vn", "-acodec", "libmp3lame", "-b:a", bitrate, str(dst)]
    subprocess.run(cmd, check=True, capture_output=True, timeout=600)


# --------------------------------------------------------------------------- #
# Voice samples
# --------------------------------------------------------------------------- #
def import_voice(src: Path | str, name: str) -> dict:
    """Import an uploaded audio file (mp3/wav/m4a/ogg/flac) as a voice sample."""
    ensure_dirs()
    duration = probe_duration(src)
    if duration <= 0:
        raise ValueError("Could not read that audio file.")
    if duration < MIN_SAMPLE_SECONDS:
        raise ValueError(
            f"The sample is too short ({duration:.1f} s). "
            f"XTTS needs at least {MIN_SAMPLE_SECONDS:.0f} s of clean speech."
        )
    vid = uuid.uuid4().hex[:12]
    target = VOICES_DIR / f"{vid}.mp3"
    convert_to_mp3(src, target, trim_to=MAX_SAMPLE_SECONDS)
    entry = {
        "id": vid,
        "name": name.strip() or "Untitled voice",
        "file": target.name,
        "created": time.time(),
        "duration": probe_duration(target),
        "source": "upload",
    }
    idx = _load_index(VOICES_INDEX)
    idx.append(entry)
    _save_index(VOICES_INDEX, idx)
    return entry


def add_recorded_voice(wav_path: Path | str, name: str, source: str = "record") -> dict:
    """Store a freshly recorded sample. The WAV is validated then stored as MP3."""
    ensure_dirs()
    duration = probe_duration(wav_path)
    if duration < MIN_SAMPLE_SECONDS:
        raise ValueError(
            f"The recording is too short ({duration:.1f} s). "
            f"Record at least {MIN_SAMPLE_SECONDS:.0f} s."
        )
    vid = uuid.uuid4().hex[:12]
    target = VOICES_DIR / f"{vid}.mp3"
    convert_to_mp3(wav_path, target)
    entry = {
        "id": vid,
        "name": name.strip() or "Recorded voice",
        "file": target.name,
        "created": time.time(),
        "duration": probe_duration(target),
        "source": source,
    }
    idx = _load_index(VOICES_INDEX)
    idx.append(entry)
    _save_index(VOICES_INDEX, idx)
    return entry


def list_voices() -> list[dict]:
    items = _load_index(VOICES_INDEX)
    # Drop entries whose file disappeared.
    alive = [v for v in items if (VOICES_DIR / v["file"]).exists()]
    if len(alive) != len(items):
        _save_index(VOICES_INDEX, alive)
    return sorted(alive, key=lambda v: v["created"])


def voice_path(entry: dict) -> Path:
    return VOICES_DIR / entry["file"]


def playback_copy(path: Path) -> Path:
    """Return a playable file for the media player.

    The GStreamer build on this system crashes when decoding MP3
    (gstdecodebin3 assertion), so we lazily create an OGG copy next to the
    MP3 and play that instead. WAV/OGG inputs are returned unchanged.
    """
    path = Path(path)
    if path.suffix.lower() != ".mp3" or not path.exists():
        return path
    ogg = path.with_suffix(".ogg")
    if ogg.exists() and ogg.stat().st_mtime >= path.stat().st_mtime:
        return ogg
    try:
        subprocess.run(
            ["ffmpeg", "-y", "-v", "error", "-i", str(path),
             "-c:a", "libvorbis", str(ogg)],
            check=True, capture_output=True, timeout=600,
        )
    except Exception:
        return path
    return ogg


def rename_voice(voice_id: str, new_name: str) -> None:
    idx = _load_index(VOICES_INDEX)
    for item in idx:
        if item["id"] == voice_id:
            item["name"] = new_name.strip() or item["name"]
    _save_index(VOICES_INDEX, idx)


def delete_voice(voice_id: str) -> None:
    idx = _load_index(VOICES_INDEX)
    keep = []
    for item in idx:
        if item["id"] == voice_id:
            (VOICES_DIR / item["file"]).unlink(missing_ok=True)
            (VOICES_DIR / Path(item["file"]).with_suffix(".ogg")).unlink(
                missing_ok=True)
        else:
            keep.append(item)
    _save_index(VOICES_INDEX, keep)


# --------------------------------------------------------------------------- #
# Saved generations
# --------------------------------------------------------------------------- #
def add_saved(src: Path | str, *, name: str, text: str = "", mood: str = "",
              voice_name: str = "", copy: bool = True,
              settings: dict | None = None) -> dict:
    """Copy a generated MP3 into the app storage (the 'Saved' library).

    `settings` is a snapshot of the delivery settings used for the file
    (mood/pitch/speed/gain/normalize/language/voice) so the entry can be
    regenerated later with exactly the same parameters.
    """
    ensure_dirs()
    sid = uuid.uuid4().hex[:12]
    suffix = 1
    base = name.strip() or "Generated speech"
    existing = {e["name"] for e in _load_index(SAVED_INDEX)}
    final = base
    while final in existing:
        suffix += 1
        final = f"{base} ({suffix})"
    target = SAVED_DIR / f"{sid}.mp3"
    if copy:
        shutil.copy2(src, target)
    else:
        Path(src).rename(target)
    entry = {
        "id": sid,
        "name": final,
        "file": target.name,
        "created": time.time(),          # date when it was saved
        "text": text[:300],
        "mood": mood,
        "voice_name": voice_name,
        "settings": settings,
        "duration": probe_duration(target),
    }
    idx = _load_index(SAVED_INDEX)
    idx.append(entry)
    _save_index(SAVED_INDEX, idx)
    return entry


def list_saved(*, sort: str = "date_desc", query: str = "") -> list[dict]:
    items = [e for e in _load_index(SAVED_INDEX) if (SAVED_DIR / e["file"]).exists()]
    q = query.strip().lower()
    if q:
        items = [e for e in items
                 if q in e["name"].lower() or q in e.get("text", "").lower()
                 or q in e.get("voice_name", "").lower()]
    if sort == "date_asc":
        items.sort(key=lambda e: e["created"])
    elif sort == "name":
        items.sort(key=lambda e: e["name"].lower())
    else:  # date_desc (newest first)
        items.sort(key=lambda e: -e["created"])
    return items


def saved_path(entry: dict) -> Path:
    return SAVED_DIR / entry["file"]


def delete_saved(saved_id: str) -> None:
    idx = _load_index(SAVED_INDEX)
    keep = []
    for item in idx:
        if item["id"] == saved_id:
            (SAVED_DIR / item["file"]).unlink(missing_ok=True)
            (SAVED_DIR / Path(item["file"]).with_suffix(".ogg")).unlink(
                missing_ok=True)
        else:
            keep.append(item)
    _save_index(SAVED_INDEX, keep)


def export_saved(saved_id: str, dest: Path) -> None:
    """'Download' a saved file to a location chosen by the user."""
    for item in _load_index(SAVED_INDEX):
        if item["id"] == saved_id:
            shutil.copy2(SAVED_DIR / item["file"], dest)
            return
    raise FileNotFoundError("Saved file no longer exists.")


def format_duration(seconds: float) -> str:
    seconds = int(round(seconds))
    return f"{seconds // 60}:{seconds % 60:02d}"


def format_date(ts: float) -> str:
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(ts))
