# VISION CONNECT — Android companion app

This is the complete standalone Android app project for pairing a phone with the VISION desktop app over the **same Wi-Fi network**.

## One-source-file layout

- `VisionConnect.kt` at the repository root contains all Kotlin application code (UI, QR scanning, secure pairing, reconnect service, optional read-only message previews, accessibility controls).
- `android/VisionConnect/` contains only Android/Gradle build metadata and the manifest/resources required by Android's build system.
- `.github/workflows/main.yml` is the **only** GitHub Actions workflow. It copies the root Kotlin file into its required Android source directory and builds the APK.

An Android APK cannot literally be built from a single `.kt` file alone: Android also requires a manifest, Gradle build configuration and resource XML. Those files are included here, but all app logic remains in one Kotlin source file.

## Replace the older GitHub project

1. Download and extract this ZIP on your PC.
2. In the GitHub repository, remove old/duplicate workflow files under `.github/workflows/` so only the new `main.yml` remains.
3. Replace the repository contents with the contents of this extracted folder. Upload the **contents**, not the ZIP itself. Include the hidden `.github` directory.
4. Commit the files to the `main` branch.
5. Open **Actions → Build VISION CONNECT Android APK → Run workflow**. A push to `main` that changes the app files also triggers the build automatically.
6. When it succeeds, open the run and download `VISION-CONNECT-Android-APK` under **Artifacts**. Extract that artifact ZIP to get `app-debug.apk`.

## Build configuration

- Minimum Android version: Android 10 (API 29)
- Compile/target SDK: Android 15 (API 35)
- Java: 17
- Gradle: 8.9
- Android Gradle Plugin: 8.7.3
- Kotlin: 2.0.21

## Pairing

1. Run the updated VISION desktop application on the PC.
2. Put the PC and phone on the same Wi-Fi network.
3. In VISION desktop, start VISION CONNECT and generate a fresh QR code.
4. Open this app and tap **SCAN VISION QR**.
5. The app reads the HTTPS endpoint, one-time pairing code and certificate fingerprint from the QR, verifies the certificate pin, pairs, stores its token securely, and starts its reconnecting service.

No USB cable, ADB or manual IP entry is needed for this same-Wi-Fi path. The PC must allow VISION/Python through Windows Firewall on the Private network and both devices must be able to communicate over the LAN.

## Permissions and safety

Android will still require the user to approve camera permission for QR scanning and, separately, Accessibility and Notification Access where those optional features are wanted. A QR code cannot silently grant Android permissions.

WhatsApp and Instagram auto-replies and auto-sending are not implemented. Optional message previews are read-only, disabled by default, and limited to manually selected chat names. WhatsApp group/ambiguous notifications are rejected. SMS and calling open the relevant composer/dialer for user confirmation.

Some protected or custom app UI elements cannot be controlled through Android Accessibility. Compatibility must be verified on physical devices from Android 10 onward; the app cannot guarantee every app exposes controllable controls.
