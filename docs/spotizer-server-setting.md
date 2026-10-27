# Unified Spotizer server setting

## Root cause

Home settings wrote `settings / online_service_base_url`. The Home online page read it, but `SpotizerClient`, streaming/downloads and the Spotizer settings About section read a different preference: `spotizer / server_base_url`. The latter defaulted to `https://api.spotizer.javadhm.online`. Thus the old displayed address reflected the real dedicated client's old target, not just a hard-coded text label.

## Fix

- Both settings screens share `SpotizerServerEditor` and the canonical `SpotizerSettings.serverBaseUrl` flow. Home's online service observes the same flow; both clients now use the same server. The legacy app setting remains deprecated only for backup/migration compatibility.
- URLs are edited as drafts and applied with **Save server**, instead of committing every valid intermediate hostname while typing. Active server is shown separately. Trailing slashes/whitespace are normalized; HTTP(S) with custom base paths is supported; embedded credentials, queries, fragments and other schemes are rejected.
- One-time migration imports a valid custom Home value when the dedicated setting is absent/untouched. A dedicated custom setting wins. Old unused defaults move to `https://api.spotizer.room237.site`. A migration marker prevents stale legacy Home values from overriding later saves/restarts.
- Switching backends clears the old backend user ID/client key. An unchanged normalized URL preserves them. Delayed user-resolution responses cannot write an old identity into the new server's settings.
- Existing in-flight playback/downloads are not silently moved to another server. Stop/retry those after a change. New requests use the current setting.

## Validation

Public unauthenticated `GET /v1/search?q=Tool&type=track&limit=1&offset=0` at the new domain returned HTTP 200 JSON with a track result during this change. No live download, stream, account creation or credentialed request was made.

Added JVM URL/migration/model compatibility tests and Android SharedPreferences persistence/identity tests. An isolated harness exercises the real settings and request-URL builders with in-memory preferences. Affected Compose source compilation uses real Compose/Android libraries with app-shell/glass/haze/navigation/image-loader boundaries stubbed. This is not a full Gradle APK build or a physical-device test.
