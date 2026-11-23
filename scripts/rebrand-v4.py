from pathlib import Path
import os
import re

ROOT = Path(__file__).resolve().parents[1]
SKIP_DIRS = {".git", ".gradle", ".idea", "build", ".kotlin"}
TEXT_SUFFIXES = {".kt", ".kts", ".xml", ".toml", ".md", ".yml", ".yaml", ".py", ".properties", ".pro", ".svg", ".txt", ".json", ".sh"}

def replace_text(value):
    value = value.replace("com.ayashii.kuro", "com.xyrcodex.beast")
    value = value.replace("com.ayashii", "com.xyrcodex")
    value = re.sub("ayashiiiyo", "xyrcodex", value, flags=re.IGNORECASE)
    value = re.sub("ayashii", "xyrcodex", value, flags=re.IGNORECASE)
    value = re.sub("sonara", lambda m: "BEAST" if m.group(0).isupper() else ("Beast" if m.group(0)[0].isupper() else "beast"), value, flags=re.IGNORECASE)
    value = re.sub("kuro", lambda m: "BEAST" if m.group(0).isupper() else ("Beast" if m.group(0)[0].isupper() else "beast"), value, flags=re.IGNORECASE)
    value = value.replace("Heast Music", "Beast Music").replace("HEAST", "BEAST")
    return value

for current, dirs, files in os.walk(ROOT, topdown=True):
    current_path = Path(current)
    dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
    for filename in files:
        path = current_path / filename
        if path == Path(__file__):
            continue
        if path.suffix.lower() in TEXT_SUFFIXES:
            try:
                original = path.read_text(encoding="utf-8")
                updated = replace_text(original)
                if path.name == "build.gradle.kts" and path.parent.name == "app":
                    updated = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 69', updated)
                    updated = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "4.0.0"', updated)
                    updated = re.sub(r'namespace\s*=\s*"[^"]+"', 'namespace = "com.xyrcodex.beast"', updated)
                    updated = re.sub(r'applicationId\s*=\s*"[^"]+"', 'applicationId = "com.xyrcodex.beast"', updated)
                if path.name == "settings.gradle.kts":
                    updated = re.sub(r'rootProject\.name\s*=\s*"[^"]+"', 'rootProject.name = "Beast-Music"', updated)
                if path.name == "SettingsManager.kt":
                    updated = re.sub(
                        r'private fun loadThemeMode\(\): ThemeMode \{.*?\n    \}',
                        'private fun loadThemeMode(): ThemeMode {\\n        val name = prefs.getString(KEY_THEME_MODE, ThemeMode.DARK.name) ?: ThemeMode.DARK.name\n        return try {\\n            ThemeMode.valueOf(name)\\n        } catch (_: Exception) {\\n            ThemeMode.DARK\n        }\\n    }',
                        updated,
                        count=1,
                        flags=re.DOTALL
                    )
                if path.name == "README.md":
                    updated = '''<div align="center">

<img src="assets/heast-music-logo.svg" width="120" alt="Beast Music logo" />

# Beast Music

**Music for your moment.**

A lightweight Android music player with a dark-first interface, built with Kotlin, Jetpack Compose and AndroidX Media3.

**Developer · xyrcodex**

![Android](https://img.shields.io/badge/Android-8.0%2B-111713?style=flat-square&logo=android&logoColor=BDF477)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-111713?style=flat-square&logo=jetpackcompose&logoColor=BDF477)
![License](https://img.shields.io/badge/License-GPL--3.0-111713?style=flat-square)

</div>

## About

Beast Music is a fast, dark-first music player designed around a near-black interface, soft lime accents and compact layouts. It brings music discovery, playback and personal library tools together in one lightweight app.

## Features

- Native YouTube Music search, discovery, suggestions and recommendations
- Background playback, queue management and radio mix
- Synced lyrics, plain lyrics and on-demand translation
- Personalized home shelves, moods and music discovery
- Favorites, playlists, listening history and local recommendations
- Offline downloads with selectable audio quality
- Home-screen playback widgets and media notifications
- Sleep timer, audio output detection and playback preferences
- Dark-first graphite theme with lime accents
- Lightweight image caching and responsive layouts for entry-level devices

## Technology

Kotlin, Jetpack Compose, AndroidX Media3, OkHttp, NewPipeExtractor, Coil, Coroutines and a Rust native core.

## Credits

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) by Team NewPipe and contributors.
- [AndroidX Media3](https://developer.android.com/media/media3).
- [Jetpack Compose](https://developer.android.com/compose).

## License

This project is distributed under the GNU General Public License v3.0 (GPL-3.0).
'''
                if updated != original:
                    path.write_text(updated, encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                pass

for current, dirs, files in os.walk(ROOT, topdown=False):
    current_path = Path(current)
    dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
    if any(part in SKIP_DIRS for part in current_path.relative_to(ROOT).parts):
        continue
    for name in files:
        old = current_path / name
        new_name = name.replace("Kuro", "Beast").replace("kuro", "beast").replace("Ayashii", "Xyrcodex").replace("ayashii", "xyrcodex").replace("Sonara", "Beast").replace("sonara", "beast")
        if new_name != name:
            old.rename(current_path / new_name)
    for name in dirs:
        old = current_path / name
        new_name = name.replace("Kuro", "Beast").replace("kuro", "beast").replace("Ayashii", "Xyrcodex").replace("ayashii", "xyrcodex").replace("Sonara", "Beast").replace("sonara", "beast")
        if new_name != name and old.exists():
            old.rename(current_path / new_name)

Path(__file__).unlink(missing_ok=True)
