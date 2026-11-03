# VISION CONNECT — standalone Android app source

This repository builds a separate Android companion app for the VISION desktop program.

- **All Kotlin application logic is in one file:** `VisionConnect.kt` at the repository root.
- `android/VisionConnect/` contains only the Gradle project and Android manifest/resources required by the APK build system.
- `.github/workflows/build-vision-connect-apk.yml` copies the root Kotlin file into the Android source path and builds a debug APK with GitHub Actions.

## Build the APK on GitHub

1. Create a GitHub repository and upload the contents of this ZIP (including the hidden `.github` folder).
2. Open **Actions → Build VISION CONNECT Android APK → Run workflow**.
3. Download the **VISION-CONNECT-Android-APK** artifact from the completed run.
4. Extract `app-debug.apk` and install it on the phone.

The app targets Android 10+ (API 29+) and uses the same QR flow on Android 10, 11, and newer. It is a companion to the VISION desktop project; it does not run the PC-side VISION server by itself.

## Pairing

Keep the PC and phone on the same Wi-Fi. In VISION desktop, open **VISION CONNECT** and generate the one-scan QR. Open this app and tap **SCAN VISION QR**. The app verifies the pinned TLS fingerprint, pairs using the one-time code, stores its token securely, and reconnects automatically.

Android will still ask the phone owner to approve camera permission. Accessibility is separately required for visible-button clicks, scrolling, system navigation, and focused-field text entry. These permissions cannot be silently granted by a QR scan.

## Controls and privacy

App/URL launch, YouTube search and video opening, media keys, volume, camera/video-camera launch, dialer/SMS drafts, Android navigation/system panels, visible text clicks, scrolling, and focused-field typing are included. Some custom or protected app controls do not expose accessibility labels and cannot be controlled reliably.

WhatsApp and Instagram automatic messaging/replies are not included. Optional selected-chat notification previews are read-only, disabled by default, and WhatsApp group/ambiguous notifications are rejected. SMS opens a draft; it does not send automatically.
