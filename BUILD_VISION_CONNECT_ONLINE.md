# Build VISION CONNECT APK with GitHub Actions

VISION CONNECT is a separate Android app from the VISION desktop program. Its source is consolidated into one Kotlin file:

- `VisionConnect.kt` — the single app source file at the repository root (easy to copy/review).
- `android/VisionConnect/app/build.gradle.kts` and `app/src/main/AndroidManifest.xml` — the minimum Android build/permission configuration required by Android; these cannot be replaced by a Kotlin source file alone.

## GitHub build

1. Create a GitHub repository for your VISION project.
2. Upload the project contents, including root `VisionConnect.kt`, the hidden `.github` folder and `android/VisionConnect` build-configuration folder. The workflow automatically copies the one Kotlin file into the Android source path; Android still needs the manifest and Gradle configuration to produce an APK.
3. Open **Actions → Build VISION CONNECT Android APK → Run workflow**.
4. When the workflow finishes, open its run page and download the `VISION-CONNECT-Android-APK` artifact.
5. Extract the artifact ZIP and install `app-debug.apk` on your Android phone. Allow installation from your browser/file manager when Android asks.

The project targets Android 10+ (API 29 and newer), including Android 10 and Android 11. It is built with JDK 17, Gradle 8.9, and Android SDK 35.

## One-scan same-Wi-Fi setup

1. Start VISION on the PC and open **PHONE CONNECT / VISION CONNECT**.
2. Keep the PC and phone on the same Wi-Fi network. Press **GENERATE ONE-SCAN QR** on the PC.
3. Open the **VISION CONNECT** app on Android and tap **SCAN VISION QR**.
4. The app reads the PC LAN address, short-lived one-time pairing code, and TLS certificate fingerprint from the QR; it validates the certificate, exchanges the one-time code, stores a long-lived token in Android encrypted preferences, and starts reconnecting automatically.

No USB, ADB, Wireless Debugging, manually typed address, or typed pairing code is used for normal connection. Android's camera permission is required to scan the QR. Accessibility and notification access are optional system permissions and can only be granted by the phone owner.

## Network and privacy notes

- The desktop dashboard remains on `127.0.0.1:8765`. A separate TLS listener exposes only the VISION CONNECT phone-control API on TCP `8766` to the private LAN.
- The local TLS private key and certificate are generated once and stored under VISION's `data` directory. The QR contains the SHA-256 certificate fingerprint; Vision Connect pins this exact certificate.
- Allow Python/VISION through Windows Firewall for **Private networks** only. Guest Wi-Fi, client isolation, VPN routing, or different networks may prevent connection.
- WhatsApp and Instagram integration is read-only notification preview, optional and filtered to chat names selected on the phone. WhatsApp previews require an explicit non-group notification flag. No auto-reply, auto-send, or chat-send command exists.
- SMS opens a draft; phone calls open the dialer. The user confirms these operations on the phone.
- Accessibility can operate visible labelled controls and the focused text field when the owner enables it. It cannot reliably control every custom-drawn/protected control in every app.
