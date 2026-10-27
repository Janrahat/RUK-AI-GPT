# RUK AI GPT (Android)

A starter Android AI assistant built with Kotlin and Jetpack Compose. Features include OpenAI-compatible chat/coding, text and PDF import, Android speech recognition and text-to-speech, plus optional image generation where the provider supports the OpenAI-compatible image endpoint.

## Build with GitHub Actions
1. Push the project files to `main`.
2. Open **Actions → Android APK** and run the workflow, or push a commit.
3. Download the `RUK-AI-GPT-debug-apk` artifact from a successful run.

## Requirements
- Android 8.0+ (API 26)
- JDK 17 and Android SDK 35 for local builds
- HTTPS OpenAI-compatible provider endpoint, model ID, and your own API key

## Provider setup
Open Settings in the app and enter the HTTPS API base URL, chat model ID, optional image model ID, and API key. The API key is held in memory only and must be re-entered after the app process restarts. No provider key is included. Free model availability and limits vary by provider.

## Features and limitations
- Chat/coding via `/chat/completions`
- Text and selectable-text PDF import (scanned PDFs/OCR not supported)
- Android speech-to-text and text-to-speech; language support varies by device
- Optional image generation via `/images/generations`; not all providers/models support it
- No cloud history sync

## Security
Never commit API keys or signing credentials. For a public production app, use an authenticated backend rather than distributing a provider API key inside the app. The debug APK is for testing, not Play Store release.

## Local build
With JDK 17, Android SDK platform 35/build-tools 35.0.0 and Gradle 8.9 installed:

```bash
gradle testDebugUnitTest --no-daemon
gradle assembleDebug --no-daemon
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Tests
GitHub Actions runs a basic smoke test and builds the debug APK. A successful build does not verify a live provider API key or model.