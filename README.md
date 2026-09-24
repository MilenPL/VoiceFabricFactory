# VoiceFabricFactory
# Supports linux and android.
VoiceFabricFactory is a local, offline XTTS-v2 voice-cloning project with two
applications:

- **Linux desktop (`1.2-gnome`):** `desktop-linux/main-branch/desktop-linux/`
- **Android (`1.2-android`):** `mobile-android/main-branch/mobile-android/`

Both applications generate speech on the device after the required model assets
are installed. The Linux application uses Coqui XTTS-v2 through PyTorch; the
Android application uses the exported ONNX graphs and ONNX Runtime.

## Linux desktop

```bash
cd desktop-linux/main-branch/desktop-linux
./setup_linux.sh
./voiceforge/run.sh
```

The release archive is built at:

```text
desktop-linux/release/VoiceFabricFactory-1.2-gnome.tar.gz
```

## Android

```bash
cd mobile-android/main-branch/mobile-android/android
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The Android model is delivered as verified GitHub release assets rather than
embedded in the APK. The installer shows a progress bar, percentage and an
estimated time remaining. See `mobile-android/main-branch/mobile-android/README.md`
for device requirements, model installation, hashes and release details.

## Licenses

The applications are provided for personal, non-commercial use. XTTS-v2 model
weights remain subject to the Coqui Public Model License (CPML).
