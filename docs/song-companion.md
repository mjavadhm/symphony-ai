# Song Companion

Implemented on top of `feature/embedded-lyrics-manager` (`21d7766`), which already contains the radio fixes from `36b30ff`. The abandoned recommendation-quality branch is not included. See [the implementation plan](song-companion-plan.md).

## Use

The lyrics screen has three tabs: **Lyrics**, **Story** and **Meaning**.

1. **First time:** open Story or Meaning, or tap the translate icon, then tap **Turn on**. This sends the song's title, artist and lyrics to the AI provider set in **Settings → AI Provider** (OpenAI-compatible base URL, model and key; usage mode must not be Off). Choose the language under **Lyrics options → Language & settings** (default: Persian).
2. **Lyrics → translate icon:** translates the whole song by meaning, in natural spoken language, and shows each translation under its original line. The same icon then shows or hides the translation; **Lyrics options → Translate again** rewrites it. Display modes in settings: original, bilingual, translation-only (falls back to original where unavailable). Timing, highlighting, blur and tap-to-seek stay tied to the original line.
3. **Story:** the story behind the song, written from the model's own knowledge: why the artist made it, what was happening in their life, who or what it is about, what they said about it, and the message. A label says **Known background** or **Interpretation from the lyrics**. The model is told to use the second, and say so, when it does not reliably know the song. No Wikipedia lookup or manual references. The tone and focus come from the editable writing style in settings.
4. **Meaning:** Genius-style. A short summary of what the song is about, then the lyrics with the lines that need explaining highlighted. Tap a highlighted line to read what it means.
5. **Writes itself once.** Story and Meaning are written the first time a tab is shown for a song (after a short pause, so skipping through songs with the tab open does not start a request per song). Results are saved and reopen instantly and offline. **Regenerate** (with an optional "what should change?" note) writes a new version; the old one stays in version history. Nothing is sent just because a song plays.
6. **Requests queue.** Only one AI request runs at a time; others wait their turn instead of failing. The same request is never queued twice. **Cancel** stops the running request and the ones waiting behind it.
7. **Lyrics options → Versions, editing & chat:** version history and restore, manual editing, line notes (long-press a lyric line) and chat. A change suggested in chat is only saved once you apply it.
8. **Settings → Export JSON** backs up songs, snapshots, older references, all versions and chats. Import validates the whole file and inserts new songs transactionally, skipping ones already on the device. Exports include private chat and lyric text; store them safely.

### Upgrading from the earlier companion

- The old "Behind the song" article was saved as Meaning text without line explanations. The Meaning tab treats it as missing and writes a line-by-line version. The old article stays in version history.
- Older stories that cited Wikipedia or typed-in reference excerpts still display with their source links. Saved references stay in the database and in backups, but no new ones can be added.
- If the stored story writing prompt is still the old built-in default, the new default is used. A prompt you wrote yourself is kept.

## Data model and limits

- Separate Room database `song_companion`, version 1, without destructive migration fallback. Existing music/history database schema is untouched.
- Stable recording UUID + validated file-URI alias. Moving/reindexing a file can reattach a dossier only if normalized title/artists, compatible album, duration (±3 seconds) and nonempty lyric fingerprint identify exactly one match. Ambiguous matches prompt for a choice or separate dossier. Imported dossiers can be browsed without an attached audio file; old device URI links are deliberately not imported.
- Identity is heuristic, not an audio fingerprint or MusicBrainz recording ID. Differently tagged or no-lyrics files may need separate dossiers. Do not assume all versions of the same composition are the same recording.
- Line IDs use exact trimmed text + occurrence; lyric fingerprint excludes timestamps. Word/order changes prevent stale translations/notes from being overlaid; timestamp-only changes retain them.
- Documents are keyed by recording, section, target line and response language. Model/user origin, model name, lyric snapshot, base revision and creation time are kept with each revision.
- This feature does **not write to audio files**. Original lyrics embedded by Lyrics Manager stay untouched; translation/notes/story/chat live in the companion database and JSON backup. Export before uninstalling/clearing app data. This backup is separate from the older settings backup.
- Maximum: 400 nonempty lyric lines, 60,000 raw lyric characters, 8,000 characters per line; eight reference excerpts per dossier; 50 MB import/export file. Oversize content is rejected rather than silently truncated into an incorrect translation. Lyrics playback still works independently.
- AI context includes title, artists, album, the lyric lines and, for Story, the writing style. The previous version is included only when you ask for a specific change (Regenerate with a note, or chat), plus the last ten scoped chat messages in chat. Companion AI/edit operations run one at a time, in order. Existing provider timeouts and model context limits apply. Stop prevents a late model response from being persisted, but the shared HTTP client can take until its timeout to finish cancellation.
- Stories come from the model's own knowledge and are **not fact-checked**. For famous songs this is usually right; for obscure, very new or local songs the model can still produce a convincing but wrong story. The prompt and the Known background / Interpretation label reduce this but cannot remove it. A story without a basis from the model is labelled as interpretation, never as known. The app rejects missing, duplicate or invented translation and meaning line ids. Playing a song never triggers a request; opening Story/Meaning for a song with nothing saved does.

## Validation

- **Simpler companion (Story / Meaning tabs, new prompts):** written without a build. The machine it was made on had no JDK or Android SDK and a full disk, and GitHub CI was blocked. Unit tests were updated (`CompanionCoreTest`, `SongStoryPromptTest`) but **not run**. Build the branch and run `./gradlew testDebugUnitTest` plus the device checklist below before merging.

The notes below describe the earlier companion release.

- 80 JVM tests passed: 37 previous radio/lyrics tests and 43 new core/backup/revision-policy tests. The policy tests exercise the real DAO apply method with an in-memory adapter; they are not a replacement for SQLite transaction tests.
- New services and Compose pages plus lyric renderer compiled with Kotlin 2.1 / Compose compiler against Android 35, Compose 1.7.5 / Material3 1.3.1 and Room 2.6.1 libraries, with isolated app-boundary stubs.
- Room 2.6.1 KSP processor successfully validated the actual new entities/DAO SQL and generated the database implementation in an isolated Gradle project; the resulting Kotlin and generated Java compiled successfully. The generated `apply` method uses Room `withTransaction`.
- Seven Android instrumentation tests are included in `CompanionDatabaseTest`: draft-only behavior, version history, optimistic conflicts, changed/retimed lyric guards, URI aliases, and persisted chat proposal links. These require a connected device/emulator and have **not** been run here.
- No full app APK, emulator/device UI test or live paid LLM request has been run for this feature. Use Codemagic to build the branch and run the device checklist below.

## Device acceptance checklist

- Build release/minified APK; configure provider; verify Off/no-consent blocks requests.
- Open Story on a famous song whose background you know (e.g. Tool – "H."): check it is labelled Known background and the facts are right. Open it on an obscure song: it should say it is an interpretation and not invent events. Force-stop and reopen offline.
- Open Meaning: summary plus highlighted lines; tap one; check explanations are about that line. Translate a slang-heavy song and a Persian target: check it reads naturally, not word for word.
- Generate translation, check every line including repeated chorus and Persian RTL; Apply; verify live bilingual timing/seek/long-press. Switch tracks rapidly, change language and display modes.
- Change only LRC timings: translation should stay aligned. Change words: old translation must not overlay current lyrics.
- Chat clarification without proposal; chat correction with proposal; leave and return before applying; reject an outdated draft, create a fresh one; restore a previous version; test offline manual edits.
- Export/import into a clean installation; compare versions/messages; repeat import (skip); malformed import must leave DB unchanged. Check moved songs and ambiguous matching explicitly.
- Test network timeout/cancellation, long lyrics and background/foreground transitions. Skip quickly through songs with Story open: no request should start until you stay on a song. Open Story then Meaning quickly: the second should wait, not fail.
