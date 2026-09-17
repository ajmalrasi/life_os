# LifeOS Trips

Trips is phone-first: the itinerary, attachments, expenses, GPS samples, and visits live in a dedicated Android Room database and remain usable when the Jetson is unreachable.

## Importing an itinerary

Give the planning agent `trip-v1.schema.json` and ask it to return schema-version 1 JSON. Unknown facts must remain `null` and should also appear in `open_questions`. In LifeOS, open **Trips**, choose **Import itinerary**, paste the JSON or select the JSON file, review warnings, and save.

`example-trip.json` demonstrates fixed times, terminal-dependent alternatives, unresolved places, and a personal reminder.

## Google map setup

Create an Android-restricted Google Maps Platform key for package `com.lifeos.app`, enable Maps SDK for Android, and put this unquoted line in the untracked `android/gradle.properties` or the user Gradle properties file:

```properties
MAPS_API_KEY=your_android_restricted_key
```

Without a key the app still builds and the itinerary works, but the embedded map will not load. Directions and nearby-essential searches are handed to the installed maps app.

## Tracking and backup

Tracking begins only after **Start tracking** and Android location permission. A visible notification provides Pause. Samples with accuracy worse than 100 metres are discarded; an unconfirmed stop is created after at least eight minutes within about 150 metres and closes after moving over 200 metres away.

**Back up to LifeOS** sends an idempotent, revision-guarded snapshot to the configured backend. Selected photos and documents are copied into app-private storage, then uploaded with a 15 MB per-file limit. The phone remains the working copy if backup is unavailable.
