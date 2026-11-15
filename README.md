# one sec — Android

A minimal, privacy-first app that interrupts mindless app opening with a breathing pause.

No tracking. No ads. No internet permission. All data stays on-device.

---

## How it works

1. You add apps you want to guard (Instagram, TikTok, etc.)
2. Whenever you open one, a full-screen breathing countdown appears (5 seconds)
3. After the breath, you decide:
   - **Go back**
   - **Quick check** (2–5 min, set on the main screen): asks "Is it that
     important?" first. From the 3rd quick check of the day "yes" stays locked
     for 10 seconds, from the 5th you also type what you're checking. When the
     time's up you're asked whether you got what you needed.
   - **I just want to scroll**: 30 minutes shared by every guarded app, counted
     in real time even while you're out of them. Before it starts you see your
     own list of things you'd rather do (up to 5) and when the next session
     would unlock; "scroll" stays locked for 5 seconds. Each session is followed
     by a growing cooldown (1h, then 3h, 5h, 7h...). Limits reset at 4 AM.
4. Stats show pauses, quick checks, scroll sessions and links watched

**Links someone sent you.** If a guarded app opens from another app (say a
reel link in WhatsApp) instead of from the home screen, the pause is shorter
and offers **Watch just this one**. The moment you swipe on to the next reel or
scroll past the post, or after 90 seconds, a check-in offers to take you back
to the chat. Choosing **Keep scrolling** sends you through the full pause.

Locking the phone ends a quick check or "just this one"; unlocking back into a
guarded app pauses it again.

There's also a **browser extension** with the same features for Chromium-based
browsers and Firefox: see [`extension/`](extension/README.md).

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
