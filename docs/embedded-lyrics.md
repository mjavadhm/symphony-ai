# Embedded Lyrics Manager

Open **Settings → Lyrics Manager**. This feature searches for missing lyrics and writes them **inside the original audio files**, not only to Symphony's cache or to separate `.lrc` files.

## Workflow

1. Keep a separate backup of your music before a large batch.
2. Grant **write access** by choosing the same music folder used by the library. The existing library tree permission may be read-only. No all-files permission is requested.
3. Choose **Scan & find missing lyrics**. This step only reads the files and performs network lookups; it does not modify music.
4. Review the matched recording's title, artist, album and duration. Embed individual matches, or confirm the batch of ready matches.
5. Stop playback of a song before embedding it. Loaded songs are left ready for a later retry.

The manager keeps session progress while navigating within the running app. It is not a persistent background worker and does not resume network searches after process death. Stop cancels searches; an in-progress file operation finishes safely before stopping. Failed/no-match results can be retried by scanning again.

## Provider configuration

The provider implements the **LRCLIB API contract**, not a universal lyrics API. The default root is `https://lrclib.net`. You can enter an HTTPS proxy/service root such as `https://lyrics.example/proxy` (a trailing `/api` is also accepted).

The client calls:

- `GET {root}/api/get?track_name=…&artist_name=…&album_name=…&duration=…`
- `GET {root}/api/search?track_name=…&artist_name=…`

Responses use LRCLIB's `trackName`, `artistName`, `albumName`, `duration`, `instrumental`, `syncedLyrics` and `plainLyrics` fields. No API key is required for the default provider. APIs with different authentication, endpoints or response schemas need a separate adapter; changing the URL alone will not make them compatible.

Searching sends track metadata (not the audio) to the configured server. Requests identify Symphony, run sequentially, and are throttled. HTTP 429/503 stops the batch and establishes a cooldown honoring `Retry-After`. Instrumentals and uncertain matches are not written.

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
