# VoiceFabricFactory

**Local, offline AI voice cloning text-to-speech for Linux desktop — by MilenPL.**

VoiceFabricFactory is a GTK4/libadwaita application (Python) that turns a short
voice sample (2–5 minutes of MP3/WAV, minimum 6 seconds) into a reusable voice
and reads any text aloud in that voice — entirely on your machine, powered by
Coqui **XTTS-v2**. Polish is supported and is the default language
(XTTS-v2 speaks 17 languages).

---

## About

- **Voice cloning from a sample** — upload an MP3/WAV file or record it
  in-app through your microphone.
- **Runs locally** — synthesis happens on your CPU (or GPU if you build one);
  no server, no account, no cloud.
- **Full control over delivery** — mood presets, loudness/speed/pitch sliders,
  automatic loudness normalization (EBU R128), 17-language selector.
- **Library management** — save results, search/sort them, re-generate with the
  same settings, export the audio.

## Features

- **Voice cloning** from a 2–5 min (min. 6 s) MP3/WAV sample — upload **or**
  record the sample in-app (PulseAudio/PipeWire microphone).
- **Voice list** with preview, rename and delete for every stored voice.
- **Text box** for whatever you want spoken.
- **Mood presets**: Neutral, Happy, Sad, Angry, Calm, Excited, Whisper, Serious.
- **Sliders**: loudness, speed and pitch, plus **auto loudness normalization
  (EBU R128)**.
- **Language selector — 17 languages, Polish first/default.**
- **Result player** with waveform, play/pause and seek.
- **Save to library** + **Export** of the generated audio.
- **Saved tab**: search, sort by date or name, play, download, delete and
  *regenerate with the same settings*.
- **Batch queue**: add several texts, generate them all, import a plain `.txt`.
- **English/Polish UI switcher** (live, from the Compose screen).
- **4 preloaded demo voices**: *Google TTS – Polski / English / Deutsch /
  Français* (so you can try the app before recording your own sample).

## Screenshots

| Light | Dark |
|-------|------|
| ![Desktop light](screenshots/desktop-light.png) | ![Desktop dark](screenshots/desktop-dark.png) |

## Requirements

- A fairly recent **Arch/Debian-ish Linux** with **GTK 4** and
  **libadwaita** installed.
- **ffmpeg** (used for audio decode/encode and normalization).
- **PulseAudio or PipeWire** (for recording a sample and playback).
- **~6 GB RAM** recommended (the XTTS-v2 model is large).
- **CPU-only works** — expect roughly **~60 s to generate one sentence** on a
  normal CPU. A GPU is optional (and needs a PyTorch build with CUDA instead of
  the CPU wheel).

## Installation

Quick start (no root required):

```bash
git clone <this-repository> VoiceFabricFactory
cd VoiceFabricFactory
./setup_linux.sh          # installs uv + Python 3.11 venv + torch (CPU) + coqui-tts
```

`setup_linux.sh` installs [uv](https://github.com/astral-sh/uv), creates a
Python **3.11** virtualenv (Coqui TTS does not support newer interpreters),
installs the **CPU build of PyTorch**, **coqui-tts** and **soundfile**.

Then launch it:

```bash
./linux/run.sh
```

- **First run downloads XTTS-v2 (~1.9 GB, about ~15 minutes)** into the local
  Coqui model cache, then loads it — subsequent runs start from cache.
- Alternatively double-click the **`VoiceFabricFactory.desktop`** shortcut:
  `chmod +x VoiceFabricFactory.desktop`, right-click it in the file manager →
  **Allow Launching** (GNOME/Nautilus), then double-click.
  The shortcut ships with absolute `Exec=` and `Icon=` paths — **adjust them in
  a text editor** if your checkout lives somewhere else.

## How to use

1. **Pick a voice** in the *Głos / Voice* section — or use
   **Upload sample…** / **Record sample…** to clone your own
   (2–5 min of clean speech, at least 6 s).
2. **Type the text** you want spoken.
3. **Choose the content language**, then the **mood**, and adjust
   **loudness / speed / pitch**; enable *auto loudness normalize (EBU R128)*
   if you want consistent output levels.
4. Click **Generate mowę / Generate** and wait for the result.
5. **Play** it in the result player (waveform, play/pause, seek).
6. **Save to library** (or **Export**) the audio.
7. Open the **Zapisane / Saved** tab to search, sort (date/name), play,
   download, delete or **regenerate with the same settings**.
8. For longer narration open the **Kolejka / Batch queue**, add several texts
   (or import a `.txt`) and **Generate all**.
9. Switch the **UI language (English ⇄ Polish)** live from the Compose screen.

## Data locations

XDG-compliant, nothing hidden:

| What | Where |
|------|-------|
| Library (voices, saved audio) | `~/.local/share/voiceforge/` |
| Configuration | `~/.config/voiceforge/config.json` |
| XTTS-v2 model cache | Coqui/TTS cache (`~/.local/share/tts/…`) |
| Python environment | `./.venv` (created by `setup_linux.sh`) |

Deleting `~/.local/share/voiceforge/` resets the library;
deleting `~/.config/voiceforge/config.json` resets settings.

## Model & privacy

- **Everything runs locally.** Speech is synthesized on your machine; your
  samples, texts and generated audio are never sent anywhere.
- Internet is needed **only** for `setup_linux.sh` (packages) and the
  **first-run model download (~1.9 GB)**. After that the app works fully
  offline.

## Licenses

- **License: Freeware — free of charge for personal and non-commercial use,
  © 2026 MilenPL (see the `LICENSE` file with the full text).**
- **XTTS-v2 model weights © Coqui AI — [CPML license](https://coqui.ai/cpml),
  non-commercial use only.** The model this app downloads and relies upon is
  covered by CPML.
- **Demo voice samples** are synthesized Google Translate (gTTS) clips used for
  demonstration purposes.

> ⚠️ **Do not use this app for commercial purposes** while the CPML-licensed
> model is included or relied upon.

## Credits

- [Coqui XTTS-v2](https://github.com/coqui-ai/TTS) / [coqui-tts](https://github.com/coqui-ai/TTS)
  — the voice-cloning model and Python API.
- [PyTorch](https://pytorch.org/) — inference backend (CPU build by default).
- [GTK 4](https://www.gtk.org/) + [libadwaita](https://gnome.pages.gitlab.gnome.org/libadwaita/doc/)
  — the desktop UI.
- [uv](https://github.com/astral-sh/uv) — Python 3.11 environment bootstrap.
- Google Translate (gTTS) — demo voice samples.

---

*by MilenPL*
