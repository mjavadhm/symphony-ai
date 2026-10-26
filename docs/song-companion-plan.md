# Song Companion — implementation plan

## Product contract

A persistent, offline-readable dossier per recording: sourced background/story, interpretation, line-bound translations, Genius-style notes, and a saved conversation. Opening a dossier never calls an LLM. Explicit user actions produce durable drafts; only Apply changes the saved version. Previous accepted revisions remain restorable. Audio files and original lyrics are never modified by this feature.

## 1. Persistence and identity

- Add an independent Room database `song_companion` (version 1, no destructive fallback). Keep the existing playlists/history database untouched.
- Store recording UUIDs, file-URI aliases, lyric snapshots, sources, versioned documents, and conversation messages.
- Prefer a validated URI alias. A changed recording at the same URI must not inherit an incompatible dossier. Reattach moved/reindexed tracks only when normalized metadata plus a nonempty lyric fingerprint identify exactly one dossier. Ambiguous matches require an explicit choice; archived dossiers remain browsable.
- Line IDs derive from exact line text + occurrence, not a volatile library ID or timestamp. Lyric fingerprint excludes timing. Changed words mark saved translation/notes stale; do not overlay stale translations.

## 2. LLM and evidence

- Reuse the configured OpenAI-compatible client/model; honor Off mode and require an explicit action. Default response language Persian; user-selectable language.
- Separate schemas for story paragraphs with source IDs, meaning, translations keyed by line IDs, and single-line notes.
- Treat lyrics, source excerpts and chat context as untrusted data, never system instructions. Reject malformed responses, unknown source IDs, unknown/missing translation line IDs, and proposals outside the requested scope.
- Retrieve optional references from Wikipedia's public API; let the user inspect and select a result. Also allow a user-provided HTTPS source and excerpt. Clearly distinguish retrieved references, user-provided excerpts, and model interpretation. A citation is provenance, not proof that an interpretation is true.
- Do not generate a factual construction/history narrative without sources. Display an honest missing-evidence state instead. Do not scrape Genius or invent URLs.

## 3. Interaction and revisions

- Dedicated Song Companion page: Story / Meaning / Translation / Notes / Chat; all load from disk.
- Generate/Rework creates a draft. Apply archives the current accepted revision and selects the new one transactionally. Undo restores a prior revision as a new accepted revision.
- Chat history persists per dossier. A model answer may contain a draft for the selected section/line, but cannot silently apply it. Manual edits are saved as explicit user-origin revisions.
- Bound context/history and request/output sizes; avoid duplicate simultaneous tasks. Navigation must not redirect a late response to a different recording.

## 4. Lyrics UI

- Add a Companion entry point from lyrics, plus long-press on a lyric line. Keep tap-to-seek intact.
- Add Original / Bilingual / Translation display modes. Render accepted translation directly below the corresponding original line using the same timing, highlight, blur and seek target.
- Mark annotated lines; long-press opens the relevant note/chat scope.
- Fix content-keyed scrolling so swapping songs/translations does not keep a coroutine bound to previous lyrics.

## 5. Management and portability

- Add a Settings entry listing saved dossiers, language preference, export/import, and access to the existing LLM settings.
- JSON backup includes sources, lyric snapshots, revisions and chat, never API credentials. Validate bounded imports; skip existing UUIDs rather than overwrite them. Do not blindly import old device URI bindings.
- State clearly: dossiers are local until exported, drafts are not accepted translations, and this is line-level interpretation rather than verified artist intent.

## 6. Validation / acceptance

- Unit tests: stable line IDs, timing-insensitive fingerprints, stale-translation rejection, strict response schemas, source provenance validation, backup validation, draft/apply/version rules.
- Kotlin compilation against real Room/Android/Compose dependencies, isolated app boundaries where a full Android toolchain is unavailable.
- Retain and rerun existing lyrics/radio regression tests. Add Android Room round-trip/revision instrumentation coverage.
- Verify remote commit and all changed files. No merge to main; create `feature/song-companion` from the embedded-lyrics branch.
- Device acceptance still required: grant/network failures, RTL layout, long lyrics, persisted reopen/restart, import/export, source selection, Apply/Undo, and live bilingual seek/highlight. Do not claim an APK/device run unless executed.
