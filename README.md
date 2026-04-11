# one sec — Android

A minimal, privacy-first app that interrupts mindless app opening with a breathing pause.

No tracking. No ads. No internet permission. All data stays on-device.

---

## How it works

1. You add apps you want to guard (Instagram, TikTok, etc.)
2. Whenever you open one, a full-screen breathing countdown appears (5 seconds)
3. After the breath, you decide: go back, or open anyway
4. Stats show how many times you paused vs. opened anyway

Uses Android's **AccessibilityService** to detect foreground app changes — same
mechanism as the original one sec. Zero battery drain (event-driven, not polling).

---

## Build Instructions

### Requirements
- Android Studio Hedgehog or newer  **OR** JDK 17 + Android SDK (API 34)
- Android device / emulator running Android 8.0+ (API 26+)

### Option A — Android Studio (easiest)
```
1. Open Android Studio
2. File → Open → select the `onesec/` folder
3. Wait for Gradle sync
4. Build → Build Bundle(s) / APK(s) → Build APK(s)
5. APK lands at:  app/build/outputs/apk/debug/app-debug.apk
```

### Option B — Command line
```bash
# Make sure ANDROID_HOME is set, e.g.:
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools

cd onesec
chmod +x gradlew

# Debug build (install directly)
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk

# Release build (smaller, optimised)
./gradlew assembleRelease
# APK at: app/build/outputs/apk/release/app-release-unsigned.apk
# Sign it with apksigner or jarsigner before installing
```

### Signing a release APK
```bash
# Generate a keystore (one-time)
keytool -genkey -v -keystore onesec.jks -alias onesec \
  -keyalg RSA -keysize 2048 -validity 10000

# Sign
$ANDROID_HOME/build-tools/34.0.0/apksigner sign \
  --ks onesec.jks --ks-key-alias onesec \
  --out app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## First-time setup on device

1. Install the APK
2. Open **one sec**
3. Tap **Enable Accessibility** → find "One Sec – App Watcher" → toggle on
4. Tap **Enable Overlay** → toggle on for one sec
5. Tap **+ Add** → pick the apps you want to guard
6. Done. Try opening one of them.

---

## Project structure

```
app/src/main/
├── java/com/onesec/app/
│   ├── AppPrefs.kt              # All data (SharedPreferences, no DB)
│   ├── AppWatcherService.kt     # AccessibilityService — detects app opens
│   ├── InterventionActivity.kt  # Breathing + decision screen
│   ├── MainActivity.kt          # Setup + guarded apps list
│   ├── AppPickerActivity.kt     # Searchable app selector
│   ├── GuardedAppsAdapter.kt    # RecyclerView adapter
│   └── BootReceiver.kt          # Reboot persistence
├── res/
│   ├── layout/                  # XML layouts
│   ├── drawable/                # Vector drawables
│   ├── values/                  # Colors, strings, themes, dimens
│   ├── anim/                    # Slide/fade animations
│   └── xml/                     # Accessibility service config
└── AndroidManifest.xml
```

## Size targets

| Build     | Estimated APK size |
|-----------|--------------------|
| Debug     | ~4–5 MB            |
| Release   | ~2–3 MB            |

Zero external dependencies beyond AndroidX essentials.
All logic is on-device. No network calls ever made.

---

## Permissions used

| Permission | Why |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | Detect when a guarded app opens |
| `SYSTEM_ALERT_WINDOW` | Show intervention over other apps |
| `VIBRATE` | Haptic pulse during breathing |
| `RECEIVE_BOOT_COMPLETED` | Persist across reboots |
| `FOREGROUND_SERVICE` | Keep service alive on some OEMs |

**No INTERNET permission. Your data never leaves the device.**
