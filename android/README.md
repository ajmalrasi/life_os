# LifeOS Android App

Offline-first Jetpack Compose client. Reads Health Connect, lets you log a
daily mood check-in and execution, shows your scores/state/insights, and chats
with the grounded OpenClaw-backed assistant.

## Build & run

This module needs **Android Studio (Ladybug or newer)** and the Android SDK.

1. Open `android/` in Android Studio. It will provision the Gradle wrapper jar
   automatically. (CLI alternative: `gradle wrapper --gradle-version 8.11.1`
   once, then use `./gradlew`.)
2. Set the backend address: launch the app → **Settings** → enter your Jetson's
   LAN URL, e.g. `http://192.168.1.50:8000/`.
   - On the **emulator**, the default `http://10.0.2.2:8000/` reaches a backend
     running on your dev machine.
3. On the **Today** tab tap **Sync health & analyze** to grant Health Connect
   permissions, upload data, and recompute insights.

## Build, Test & ADB Run (Mac CLI)

Always point `JAVA_HOME` to Android Studio's bundled JBR:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
```

### Commands
```bash
# 1. Run unit tests
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest

# 2. Build Debug APK
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# 3. Install on connected phone (e.g. Samsung S24)
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

# 4. Launch app
~/Library/Android/sdk/platform-tools/adb shell am start -n com.lifeos.app/.MainActivity

# 5. Capture screenshot to verify UI
~/Library/Android/sdk/platform-tools/adb exec-out screencap -p > /tmp/lifeos_screen.png

# 6. Run connected Room database tests on device
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew connectedDebugAndroidTest

# 7. Inspect SQLite database directly on device
~/Library/Android/sdk/platform-tools/adb shell "run-as com.lifeos.app cat databases/lifeos-trips.db" > /tmp/lifeos-trips.db
~/Library/Android/sdk/platform-tools/adb shell "run-as com.lifeos.app cat databases/lifeos-trips.db-wal" > /tmp/lifeos-trips.db-wal
sqlite3 /tmp/lifeos-trips.db "SELECT id, title FROM trips;"
```

## Architecture

```
app/src/main/java/com/lifeos/app/
├── data/
│   ├── remote/        Retrofit API, DTOs, dynamic base-URL interceptor (LifeOsApi.kt)
│   ├── local/         Room database for metrics & mood (lifeos.db)
│   └── repository/    Repository implementations
├── trips/             Trips feature module
│   ├── data/          Separate Room DB (lifeos-trips.db), TripDao, TripRepository, TripFileStore
│   ├── importer/      JSON import validator (trip-v1.schema.json) & scoped bundle builder
│   ├── location/      TripLocationService (foreground GPS recording & stop detection)
│   └── ui/            TripsScreen, TripsViewModel, TripDetailScreen (Map, Plan, Details, Journal)
├── di/                Hilt modules (AppModule, Network, Database)
└── presentation/      Compose UI (Material 3) + ViewModels
    ├── dashboard/  trends/  money/  chat/  settings/
    └── LifeOsApp.kt   Scaffold, navigation drawer, bottom bar & NavHost
```
