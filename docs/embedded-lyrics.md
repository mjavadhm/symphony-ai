# Embedded Lyrics Manager

Open **Settings → Lyrics Manager**. This feature searches for missing lyrics and writes them **inside the original audio files**, not only to Symphony's cache or to separate `.lrc` files.

## Workflow

1. Keep a separate backup of your music before a large batch.
2. Grant **write access** by choosing the same music folder used by the library. The existing library tree permission may be read-only. No all-files permission is requested.
3. The manager performs a **local-only inventory** on entry. **Rescan locally** refreshes it; neither action contacts a provider. Existing embedded lyrics and available sidecar `.lrc` files are preserved and are not searched online. Read errors are shown as errors, not falsely classified as missing lyrics.
4. Filter by title/artist/album, an optional album filter, and Missing / Ready / Errors / All. Tick individual checkboxes or **Select visible**. The selected count explicitly includes hidden selected rows.
5. Choose **Search N selected**, or **Search this song**. Search is separate from scanning and writing. Review the actual matched lyric text and recording metadata, then **Embed N** selected ready matches or **Embed this song**. No unselected file is written.
6. For one track, use its menu → **Find missing lyrics**, or **Find lyrics** in the player. For an album, use the album menu → **Find missing lyrics · album**. These routes scan only their own scope and preselect missing tracks, but never start an online search automatically.
7. A track currently loaded in the player is skipped for safe writing. Play a different track before retrying that file.

**Provider & access** holds the advanced base URL/public app ID settings and folder write permission. Keep a separate backup before a batch. Timed lyrics still embed as LRC text in the original lyrics field, not as a new sidecar.

The manager keeps session progress while navigating within the running app. It is not a persistent background worker and does not resume a queue after process death. Stop cancels searches/countdowns; an in-progress file write finishes safely first. Completed search results are cached across restarts: positive matches for seven days, no-match results for six hours. **Refresh selected online** ignores the result cache but never bypasses the provider cooldown. The cache is limited to 1,000 entries / 20 MB in app cache storage, so Android can evict it.

## Provider configuration

The provider implements the **LRCLIB API contract**, not a universal lyrics API. The default root is `https://lrclib.net`. You can enter an HTTPS proxy/service root such as `https://lyrics.example/proxy` (a trailing `/api` is also accepted).

The client calls:

- `GET {root}/api/get?track_name=…&artist_name=…&album_name=…&duration=…`
- `GET {root}/api/search?track_name=…&artist_name=…`

Responses use LRCLIB's `trackName`, `artistName`, `albumName`, `duration`, `instrumental`, `syncedLyrics` and `plainLyrics` fields. No API key is required for the default provider. APIs with different authentication, endpoints or response schemas need a separate adapter; changing the URL alone will not make them compatible.

Searching sends track metadata (not the audio) to the configured server. Requests identify Symphony and run one at a time, with at least 1.5 seconds **after the previous response** before the next request. HTTP 429/503 establishes a cooldown honoring `Retry-After` plus positive jitter. The UI counts down and retries the current song once; a second throttle pauses the remaining queue for explicit retry. Cooldown deadlines are persisted per provider root, so restarting/retrying does not bypass them. Failed/time-out/rate-limited responses are never cached as "no lyrics". Instrumentals and uncertain matches are not written.

LRCLIB remains the free default with both synchronized and plain lyrics, but coverage is not guaranteed and limits still apply to shared IPs/VPNs. The current [official documentation](https://lrclib.net/docs) distinguishes anonymous access from registered applications. An optional registered **public** `Lrclib-App-Id` can be entered; the header is only sent to `lrclib.net`. This is not a server API key. Do not embed server-side secrets in the app, invent an app ID or rotate hosts/identities to evade rate limits.

Automatic matching requires a normalized title and artist match and a duration difference of at most three seconds. Live/remix qualifiers are not stripped. Synchronized matches are preferred; a plain exact lookup still triggers a search for a synchronized match before using plain lyrics.

## Embedded formats and playback

Supported containers: MP3, FLAC, M4A/M4B, Ogg Vorbis and Opus. Other formats are reported as unsupported, not converted.

- MP3: ID3v2 **USLT** lyrics frame, retaining an existing ID3v2.3/2.4 version.
- FLAC/Ogg/Opus: `LYRICS` Vorbis-comment field.
- M4A/M4B: MP4 `©lyr` atom.

Synchronized lyrics are embedded as **LRC text containing timestamps in that lyrics field**. This is not a binary ID3 SYLT implementation or word-level karaoke. Symphony parses the timestamps; other players may show raw timestamps or only plain text. Existing embedded lyrics (including existing MP3 SYLT frames) are never replaced.

Only the lyrics field is modified. Audio is not transcoded. The library's external `.lrc` priority is unchanged. Newly embedded lyrics update Symphony's lyrics cache and refresh the current lyrics view.

## Safety and recovery

Each file is copied to private staging storage. The native writer edits the staged copy, which is read back to verify the exact lyrics. Before changing the original, the app checks that it has not changed, flushes a backup and recovery journal, then copies the staged file through the authorized document URI. The final bytes are SHA-256 verified. This is a verified copy with rollback, **not an atomic filesystem rename** across document providers.

A failed commit attempts to restore the original backup and verifies it. If rollback fails or the process dies during a commit, the journal and original backup remain in the app's private `files/lyrics-recovery` storage. The manager displays **Restore original** and blocks new work until recovery. Do not clear app data or uninstall before restoring. Keep an independent backup: no application-level write can guarantee recovery from device failure or a broken storage provider.

## Tests

- `./gradlew :app:testDebugUnitTest`: provider request/response behavior, conservative matching and LRC parsing, plus existing radio session tests.
- `./gradlew :metaphony:connectedDebugAndroidTest`: native lyric roundtrip, original metadata/artwork, overwrite protection, Unicode and caller descriptor ownership on bundled MP3/FLAC fixtures.

Development validation also exercised the native writer against generated MP3/FLAC/M4A/Ogg/Opus files and compared copied audio-stream hashes before/after writing. Full Android APK/device permission-flow testing is still required before release.
