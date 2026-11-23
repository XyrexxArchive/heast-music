from pathlib import Path
import os
import re

ROOT = Path(__file__).resolve().parents[1]
SKIP_DIRS = {".git", ".gradle", ".idea", "build", ".kotlin"}
TEXT_SUFFIXES = {".kt", ".kts", ".xml", ".toml", ".md", ".yml", ".yaml", ".py", ".properties", ".pro", ".svg", ".txt", ".json", ".sh"}

def replace_text(value):
    value = value.replace("com.ayashii.kuro", "com.xyrcodex.heast")
    value = value.replace("com.ayashii", "com.xyrcodex")
    value = re.sub("ayashii", "xyrcodex", value, flags=re.IGNORECASE)
    value = re.sub("sonara", lambda m: "HEAST" if m.group(0).isupper() else ("Heast" if m.group(0)[0].isupper() else "heast"), value, flags=re.IGNORECASE)
    value = re.sub("kuro", lambda m: "HEAST" if m.group(0).isupper() else ("Heast" if m.group(0)[0].isupper() else "heast"), value, flags=re.IGNORECASE)
    return value

for current, dirs, files in os.walk(ROOT, topdown=True):
    dirs[:] = [d for d in dirs if d not in SKIP_DIRS and d != ".github"]
    for filename in files:
        path = Path(current) / filename
        if path == Path(__file__) or path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        try:
            original = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        updated = replace_text(original)
        if path.name == "build.gradle.kts" and path.parent.name == "app":
            updated = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 69', updated)
            updated = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "4.0.0"', updated)
            updated = re.sub(r'namespace\s*=\s*"[^"]+"', 'namespace = "com.xyrcodex.heast"', updated)
            updated = re.sub(r'applicationId\s*=\s*"[^"]+"', 'applicationId = "com.xyrcodex.heast"', updated)
        if path.name == "settings.gradle.kts":
            updated = re.sub(r'rootProject\.name\s*=\s*"[^"]+"', 'rootProject.name = "Heast-Music"', updated)
        if path.name == "SettingsManager.kt":
            updated = re.sub(
                r'private fun loadThemeMode\(\): ThemeMode \{.*?\n    \}',
                'private fun loadThemeMode(): ThemeMode {\n        val name = prefs.getString(KEY_THEME_MODE, ThemeMode.DARK.name) ?: ThemeMode.DARK.name\n        return try {\n            ThemeMode.valueOf(name)\n        } catch (_: Exception) {\n            ThemeMode.DARK\n        }\n    }',
                updated, count=1, flags=re.DOTALL
            )
        if path.name == "README.md":
            updated = '''<div align="center">

<img src="assets/heast-music-logo.svg" width="120" alt="Heast Music logo" />

# Heast Music

**Music for your moment.**

A lightweight Android music player with an OLED-black interface, lime accents, Kotlin and Jetpack Compose.

**Developer · xyrcodex**

![Android](https://img.shields.io/badge/Android-8.0%2B-111713?style=flat-square&logo=android&logoColor=BDF477)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-111713?style=flat-square&logo=jetpackcompose&logoColor=BDF477)
![License](https://img.shields.io/badge/License-GPL--3.0-111713?style=flat-square)

</div>

## About

Heast Music is a lightweight Android music player focused on music discovery, smooth playback and a personal music library.

## Features

- YouTube Music search, discovery, suggestions and recommendations
- Background playback, queue management and radio mix
- Synced lyrics, plain lyrics and translation
- Personalized home shelves, moods and discovery
- Favorites, playlists and listening history
- Offline downloads and playback controls
- Home-screen widgets and media notifications
- Sleep timer and audio output selection
- OLED-black theme with soft lime accents
- Lightweight image loading and caching

## Technology

Kotlin, Jetpack Compose, AndroidX Media3, OkHttp, NewPipeExtractor, Coil, Coroutines and Rust.

## Credits

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
- [AndroidX Media3](https://developer.android.com/media/media3)
- [Jetpack Compose](https://developer.android.com/compose)

## License

GNU General Public License v3.0 (GPL-3.0).
'''
        if updated != original:
            path.write_text(updated, encoding="utf-8")

for current, dirs, files in os.walk(ROOT, topdown=False):
    current_path = Path(current)
    dirs[:] = [d for d in dirs if d not in SKIP_DIRS and d != ".github"]
    for name in files:
        old = current_path / name
        new_name = name.replace("Kuro", "Heast").replace("kuro", "heast").replace("Ayashii", "Xyrcodex").replace("ayashii", "xyrcodex").replace("Sonara", "Heast").replace("sonara", "heast")
        if new_name != name:
            old.rename(current_path / new_name)
    for name in dirs:
        old = current_path / name
        new_name = name.replace("Kuro", "Heast").replace("kuro", "heast").replace("Ayashii", "Xyrcodex").replace("ayashii", "xyrcodex").replace("Sonara", "Heast").replace("sonara", "heast")
        if new_name != name and old.exists():
            old.rename(current_path / new_name)

Path(__file__).unlink(missing_ok=True)
