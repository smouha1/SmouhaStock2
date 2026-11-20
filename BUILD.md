# Smouha Stock — Ubuntu & GitHub Actions APK Build Guide

## 1. Repository Overview
- **App Name:** `Smouha Stock`
- **Package ID:** `com.smouha.stock`
- **Toolbar & Stack:** AGP `8.2.2`, Kotlin `1.9.22`, Gradle Wrapper `8.5`, `compileSdk = 34`, `targetSdk = 34`, `minSdk = 21`
- **CI Runner:** `ubuntu-24.04` with Temurin **JDK 17**

## 2. Optional GitHub Actions Secrets
The workflow (`.github/workflows/build-apk.yml`) automatically generates a signed release keystore at `/tmp/smouha-keys/smouha-release.jks` using default internal credentials if secrets are not configured. To use custom signing credentials, add these repository secrets under **Settings → Secrets and variables → Actions**:
- `SMOUHA_KEYSTORE_PASS`
- `SMOUHA_KEY_PASS`
- `SMOUHA_KEY_ALIAS`

## 3. How to Build & Download the Signed APK on GitHub
1. Push or upload this repository to GitHub (`main` or `master` branch).
2. Open the **Actions** tab in your GitHub repository and select **Build Smouha Stock APK**.
3. Wait for the `Assemble Release APK` job to finish, then scroll to **Artifacts** and download **`SmouhaStock-release-apk`**.

## 4. One-Line Local Ubuntu Build Command
```bash
chmod +x ./gradlew && ./gradlew :app:assembleRelease --no-daemon --stacktrace
```
The signed APK is output to:
`app/build/outputs/apk/release/app-release.apk`
