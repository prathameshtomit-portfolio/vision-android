# Build VISION CONNECT on GitHub

1. Extract the ZIP.
2. Upload all extracted contents to the repository root, including `.github/workflows/main.yml`.
3. Remove any older workflow files from `.github/workflows/` so the repository does not run duplicate builds.
4. Commit to `main`.
5. Open **Actions → Build VISION CONNECT Android APK → Run workflow**.
6. Download the `VISION-CONNECT-Android-APK` artifact after the run succeeds.

The workflow uses `android-actions/setup-android@v4` (not v3) so it does not request Google's retired `tools` SDK package. It then explicitly installs Android platform 35 and build-tools 35.0.0, sets up Gradle 8.9, copies the one Kotlin file into Android's required package path, builds `:app:assembleDebug`, verifies the APK exists, and uploads it.
