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

```bash
# Lint + unit tests (once the wrapper jar exists)
./gradlew test
# Assemble a debug APK
./gradlew assembleDebug
```

## Architecture (clean architecture)

```
app/src/main/java/com/lifeos/app/
├── domain/            Pure models + repository interfaces (no Android deps)
│   ├── model/
│   └── repository/
├── data/              Implementations: the only layer touching frameworks
│   ├── remote/        Retrofit API, DTOs, dynamic base-URL interceptor
│   ├── local/         Room database, DAOs, entities (offline cache)
│   ├── health/        HealthConnectManager (Health Connect SDK)
│   ├── repository/    Repository implementations
│   └── Mappers.kt
├── di/                Hilt modules (Network, App/DB, Repository bindings)
└── presentation/      Compose UI (Material 3) + ViewModels
    ├── dashboard/  mood/  insights/  chat/  settings/
    ├── components/  theme/
    └── LifeOsApp.kt   bottom-nav scaffold + NavHost
```

- **Repository pattern**: ViewModels depend on interfaces in `domain/repository`.
- **Offline-first**: scores/states/insights/mood are cached in Room and observed
  via `Flow`; `refresh()`/`recompute()` reconcile with the backend.
- **Retrofit** for API, **Room** for cache, **Health Connect SDK** for health,
  **Hilt** for DI, **DataStore** for the configurable backend URL.

## Health Connect

Phase 1 requests read access to: sleep (+stages), heart rate, resting HR, HRV,
SpO2, steps, exercise, calories, distance, VO2 max, weight. Permissions are
declared in `AndroidManifest.xml` and requested at runtime from the dashboard.

> The device needs the **Health Connect** app (built into Android 14+, or
> installable from Play on Android 13).

## Tests

Unit tests live in `app/src/test/` (JUnit4 + MockK + Turbine + coroutines-test):
mapper round-trips and `ChatViewModel` behavior. Run with `./gradlew test`.
