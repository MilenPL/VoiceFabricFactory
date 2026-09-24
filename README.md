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
The app was made on GNOME, but i will remake it for KDE in upcoming versions.

The model should be running locally only, without internet connection. If it doesn't then please report the bug.

## Android
Important: Up to mobile version 1.2 the AI model will download only if your device has at least 6GB of RAM. in the mobile version 1.3 this will be changed for older devices. 

The Android model is delivered as verified GitHub release assets rather than
embedded in the APK. The model after download will run 100% locally without internet connection (at least it should).

## Licenses

The applications are provided for personal, non-commercial use. XTTS-v2 model
weights remain subject to the Coqui Public Model License (CPML).
