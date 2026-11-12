# Song Companion

Implemented on top of `feature/embedded-lyrics-manager` (`21d7766`), which already contains the radio fixes from `36b30ff`. The abandoned recommendation-quality branch is not included. See [the implementation plan](song-companion-plan.md).

## Use

1. Open **Settings → Song Companion**. Configure the existing **AI Provider** (OpenAI-compatible base URL/model/key), enable a non-Off AI mode, and explicitly allow sending context. Choose an explanation/translation language (default: Persian).
2. Open the player lyrics view and switch **Lyrics ↔ About this song**. Meaning, sourced story, draft review/apply and saved correction chat are now inline, without leaving the player. **Versions, manual editor & full chat** opens the advanced dossier with Story / Meaning / Translation / Line notes / Chat tabs. Reading saved content does not contact the model.
3. **Story:** add references first. Optional Wikipedia search returns up to three excerpts; inspect one before adding it. Or enter a reference title, HTTPS URL and a supporting excerpt yourself. This does not fetch arbitrary websites and does not scrape Genius. There is no general web-search service built in.
4. **Generate draft** saves a durable preview. **Apply to saved dossier** accepts it; older accepted versions are kept under **Version history**, with **Restore as a new version**. **Edit manually** works offline and creates a user-origin version.
5. **Translation:** Apply a translation to show it beneath its original lyric lines in the player. Display modes in settings: original, bilingual, translation-only (falls back to original where unavailable). Timing, highlighting, blur and tap-to-seek remain tied to the original line.
6. **Line notes:** long-press a lyric line to open its note scope. An accepted note adds a small marker in the lyrics. Older notes whose words changed remain accessible as “Older lyric line”.
7. **Chat:** first select a section/line, then open Chat. Ask for clarification or a correction. Messages persist. A proposed change is only a draft; a normal answer need not propose an edit. Applying an outdated proposal is blocked if another version has already been accepted.
8. **Settings → Song Companion → Export JSON** backs up dossiers, snapshots, references, all revisions and chats. Import validates the whole file and inserts new dossier IDs transactionally, skipping existing IDs rather than overwriting them. Exports include potentially private chat/lyric text; store them safely.

## Data model and limits

- Separate Room database `song_companion`, version 1, without destructive migration fallback. Existing music/history database schema is untouched.
- Stable recording UUID + validated file-URI alias. Moving/reindexing a file can reattach a dossier only if normalized title/artists, compatible album, duration (±3 seconds) and nonempty lyric fingerprint identify exactly one match. Ambiguous matches prompt for a choice or separate dossier. Imported dossiers can be browsed without an attached audio file; old device URI links are deliberately not imported.
- Identity is heuristic, not an audio fingerprint or MusicBrainz recording ID. Differently tagged or no-lyrics files may need separate dossiers. Do not assume all versions of the same composition are the same recording.
- Line IDs use exact trimmed text + occurrence; lyric fingerprint excludes timestamps. Word/order changes prevent stale translations/notes from being overlaid; timestamp-only changes retain them.
- Documents are keyed by recording, section, target line and response language. Model/user origin, model name, lyric snapshot, base revision and creation time are kept with each revision.
- This feature does **not write to audio files**. Original lyrics embedded by Lyrics Manager stay untouched; translation/notes/story/chat live in the companion database and JSON backup. Export before uninstalling/clearing app data. This backup is separate from the older settings backup.
- Maximum: 400 nonempty lyric lines, 60,000 raw lyric characters, 8,000 characters per line; eight reference excerpts per dossier; 50 MB import/export file. Oversize content is rejected rather than silently truncated into an incorrect translation. Lyrics playback still works independently.
- AI context includes metadata, lyrics, up to eight excerpts (up to 8,000 characters each), saved section content and the last ten scoped messages. One companion AI/edit operation runs at a time. Existing provider timeouts and model context limits apply. Stop prevents a late model response from being persisted, but the shared HTTP client can take until its timeout to finish cancellation.
- References give provenance, **not fact verification**. Wikipedia can be incomplete or about a different recording. User excerpts can be wrong. The app rejects invented source IDs and missing/duplicate translation lines; it cannot prove that a model's cited paragraph is entailed by the excerpt. Interpretations are labelled as such, not as established artist intent. No provider bill or network request is triggered merely by opening a dossier.

## Validation

- 80 JVM tests passed: 37 previous radio/lyrics tests and 43 new core/backup/revision-policy tests. The policy tests exercise the real DAO apply method with an in-memory adapter; they are not a replacement for SQLite transaction tests.
- New services and Compose pages plus lyric renderer compiled with Kotlin 2.1 / Compose compiler against Android 35, Compose 1.7.5 / Material3 1.3.1 and Room 2.6.1 libraries, with isolated app-boundary stubs.
- Room 2.6.1 KSP processor successfully validated the actual new entities/DAO SQL and generated the database implementation in an isolated Gradle project; the resulting Kotlin and generated Java compiled successfully. The generated `apply` method uses Room `withTransaction`.
- Seven Android instrumentation tests are included in `CompanionDatabaseTest`: draft-only behavior, version history, optimistic conflicts, changed/retimed lyric guards, URI aliases, and persisted chat proposal links. These require a connected device/emulator and have **not** been run here.
- No full app APK, emulator/device UI test or live paid LLM request has been run for this feature. Use Codemagic to build `feature/song-companion` and run the device checklist below.

## Device acceptance checklist

- Build release/minified APK; configure provider; verify Off/no-consent blocks requests.
- Add a reference, generate story; check sources and claims; apply; force-stop and reopen offline.
- Generate translation, check every line including repeated chorus and Persian RTL; Apply; verify live bilingual timing/seek/long-press. Switch tracks rapidly, change language and display modes.
- Change only LRC timings: translation should stay aligned. Change words: old translation must not overlay current lyrics.
- Chat clarification without proposal; chat correction with proposal; leave and return before applying; reject an outdated draft, create a fresh one; restore a previous version; test offline manual edits.
- Export/import into a clean installation; compare versions/messages; repeat import (skip); malformed import must leave DB unchanged. Check moved songs and ambiguous matching explicitly.
- Test network timeout/cancellation, long lyrics, background/foreground transitions and source-search failures. Missing story evidence must stay missing, not become invented history.
