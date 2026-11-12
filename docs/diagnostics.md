# Local diagnostics and server correlation

## In the app

Open **Settings → Diagnostics & reports** to enable/disable collection, inspect
recent errors/crashes/requests, copy an event, clear reports or export a ZIP via
the Android document picker. The screen displays the 200 newest retained events;
export includes the whole retained journal and a small `device.json` manifest.
The crash screen independently supports copying/sharing safe stack details and
exporting the full ZIP, without needing a screenshot or a working Symphony model.

Reports live under `noBackupFilesDir/diagnostics` (not Android cloud backup).
The cap is **20 MiB of report contents**, with **no age expiry**. Normal segments
rotate first, then older crashes if necessary. Fatal records bypass the bounded
background queue and sync to disk. File locks coordinate the main and separate
crash-report processes. ZIPs stream directly to the selected destination, with no
second private ZIP cache. Exported files belong to the user and are not counted
against the journal cap; ZIP/lock metadata and filesystem overhead are excluded.
Disabling collection stops new events; clear or export old reports separately.
Under bursts, the bounded writer may drop ordinary events instead of blocking UI.

Recorded information: random event/session IDs, time, app version, Android API,
source/category, exception **types and stack frames without messages**, and for
network calls the method, redacted route, HTTP status, elapsed time and request IDs.
The export manifest also has manufacturer/model (no device identifier). Reports
exclude API keys/tokens, authorization headers, query strings, request/response
bodies, arbitrary exception messages, chat text and lyrics. No automatic upload.
Review a ZIP before sharing it; stack traces and device models still reveal some
technical information. This does not redact OS logcat or third-party SDK logs.

## Request IDs

The shared OkHttp client, Spotizer, lyrics and reference clients add a fresh UUID
`X-Request-ID` to each call. Both ordinary and streaming LLM HTTP calls do the
same. HTTP status, connection failures, and a validated server response ID are
recorded without inspecting bodies. Spotizer download HTTP errors also display
the ID (the sent ID is the fallback if a proxy did not return one). An OkHttp
internal redirect/retry within the same call shares its call ID; a new call,
including an application-level retry, gets a fresh ID.

Deploy the matching Spotizer diagnostics update to echo/record these IDs server
side. Copy the ID from an app report and find it in the server's private JSONL
journal. This separates server-side HTTP failures from client-side file/permission
failures. It does not itself repair the pre-existing download/status HTTP 500.

## Coverage and limitations

- Managed uncaught Java/Kotlin exceptions are captured from Application startup,
  before activity initialization, and delegated to Android's default handler.
- Existing safe Logger calls and companion/lyrics/download failures are retained.
- The crash screen runs in `:crash_report` so a dead main looper does not block it.
- Persistence and crash-screen launch are best effort: no guarantee for native
  crashes, ANRs, force-stop, OS kills, exhausted memory, unavailable storage or a
  damaged Android process. This is not a native crash SDK or remote monitoring.
- Logs deliberately omit exception messages, so some investigations need source
  line numbers and server operational categories, not the original raw error text.

## Validation

`DiagnosticStoreTest` / `DiagnosticPrivacyTest` cover persistence, no age expiry,
byte rotation, crash priority, concurrent instances, ZIP contents, clearing,
corrupt records, record bounds, message omission and route/ID redaction.
`DiagnosticInterceptorTest` uses a loopback HTTP server to check fresh IDs, a
correlated HTTP 500, preserved auth headers and an untouched response body.
Android UI/crash delivery still needs a full build and device testing, especially
OEM background-activity restrictions. Isolated compilation with Android/Compose
jars is not an APK build or device test.
