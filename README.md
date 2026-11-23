<div align="center">

<img src="assets/heast-music-logo.svg" width="120" alt="Heast Music logo" />

# Heast Music

**Music, without the noise.**

A lightweight Android music player with a focused dark interface, built with Kotlin, Jetpack Compose and AndroidX Media3.

**Developer · xyrcodex**

![Android](https://img.shields.io/badge/Android-8.0%2B-111713?style=flat-square&logo=android&logoColor=BDF477)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-111713?style=flat-square&logo=jetpackcompose&logoColor=BDF477)
![License](https://img.shields.io/badge/License-GPL--3.0-111713?style=flat-square)

</div>

## About

Heast Music is a dark-first music player focused on fast navigation, clear typography and restrained visual detail. The interface uses a near-black graphite base with a soft lime signal accent, flat surfaces and compact controls to keep visual overhead low on entry-level Android devices.

## Features

- Native Kotlin YouTube Music backend ported from the Sonara backend flow
- Music search, search suggestions, home recommendations, explore and trending shelves
- Native stream resolution with Media3 playback, queue controls and background playback
- Synced and plain lyrics through LRCLIB, plus the existing translation and disk-cache pipeline
- Favorites, playlists, listening history and local recommendations
- Offline downloads and home-screen playback widgets
- Audio output detection, sleep timer and configurable quality
- Dark, light and system theme options

## Tech stack

- Kotlin + Jetpack Compose
- AndroidX Media3 / ExoPlayer
- Native YouTube Music InnerTube client (Kotlin + OkHttp)
- NewPipeExtractor for native audio stream extraction
- Coroutines, StateFlow and Coil
- Rust native core

## Requirements

- Android Studio with JDK 17
- Android SDK 34
- Rust stable and `cargo-ndk` for the native core
- Android NDK installed through SDK Manager

## Build locally

```bash
git clone https://github.com/USERNAME/REPOSITORY.git
cd REPOSITORY
chmod +x ./gradlew
cargo install cargo-ndk
./gradlew assembleDebug
```

For a complete release build including the Rust libraries, install the Rust Android targets and run:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo ndk -t arm64-v8a -t armeabi-v7a -o app/src/main/jniLibs build --release --manifest-path kuro-core/Cargo.toml
./gradlew assembleRelease
```

APK output: `app/build/outputs/apk/`.

## GitHub Actions

The workflow in `.github/workflows/build-apk.yml` builds the Rust native core and Android APKs. It does not require repository secrets or a manually uploaded keystore. The release variant uses Android's automatically generated debug signing configuration so the APK can be installed directly.

Because GitHub-hosted runners may generate a different debug certificate on a later run, Android can reject an update over an APK signed by a previous run. If that happens, uninstall the old Heast Music build before installing the new one. For stable in-place updates and Play Store distribution, use a persistent private release keystore.

## Credits

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) by Team NewPipe and contributors.
- [AndroidX Media3](https://developer.android.com/media/media3).
- [Jetpack Compose](https://developer.android.com/compose).

## License

This project is distributed under the GNU General Public License v3.0 (GPL-3.0).
