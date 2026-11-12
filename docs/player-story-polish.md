# Player story / lyrics workflow polish

This update builds on `feature/lyrics-workflow-and-inline-analysis` (`8b5a7c7`). It retains the radio fixes, embedded-lyrics writer, translation overlay and existing companion database.

## Everyday UI

- Transparent player header with small Lyrics / Behind the song text tabs and an options menu, not FilterChips.
- One continuous Behind the song article. Ask/correct opens a bottom sheet; rewrite opens a short instruction dialog. New drafts must be kept explicitly, or discarded. Prior saved text remains untouched until acceptance.
- Reading text has its own direction/alignment, independent of the app locale. Original lyric lines and their translations are laid out separately. A Persian paragraph can start with an English song/artist name and still read RTL.
- The Find lyrics action appears only in the empty-lyrics state, not for songs that already have displayable lyrics.
- Existing technical revision/model labels are absent from the player reading surface. Old source UUID artifacts are removed for display only, not rewritten in storage.

## AI configuration and storage

The shared AI Provider settings and Song stories & translation settings expose the same persisted writing prompt, with reset and `{title}`, `{artist}`, `{album}`, `{language}` placeholders. This is the writing brief; the service separately enforces JSON and scope validation. Prompt changes affect **future** drafts, never rewrite existing documents automatically.

New articles reuse the existing `MEANING` document key. No Room schema change or destructive migration is needed. Earlier `STORY` revisions/sources remain in backup and in the legacy history section, but the source-selection/Wikipedia UI is removed. New article generation/chat sends no source excerpts and performs no live web lookup. It cannot guarantee historical accuracy: the default brief forbids invented events, dates, quotes or research claims and asks the model to acknowledge missing knowledge.

Discarded drafts are retained with `state=discarded`, never applied. Backup import/export accepts that state; existing saved versions and chat proposal links remain valid. Older app versions will not understand backups containing discarded drafts; import those with this version or newer.

## Missing-lyrics inventory

Opening a manager scope prepares the saved list only: **no audio tag reads, no sidecar reads and no provider requests**. An initial local check is explicit. URI + size + modification time identify presence entries across library/album/single-song scopes and process restarts. New or changed entries are checked on **Check changes**; **Recheck all files** forces a complete fresh local check, including sidecars changed outside the music index. Read failures are not persisted as known missing lyrics. Provider results still use the separate positive/negative lookup cache.

Search continues to be explicitly selected, sequential, paced and cancellable via OkHttp cancellation, including cooldown waiting. Cancel is outside the scrolling list. Embedding finishes/rolls back the current file before honoring cancellation, as before. Write failures preserve their candidate for retry, appear in Errors with a visible message and report real success/error counts. Access-denied errors additionally offer the folder grant action; disk-full or player-loaded errors do not misleadingly suggest permissions.

The staged native writer, backup journal, readback/hash checks, rollback, existing-lyrics protection and loaded-song protection are unchanged.

## Validation

- Isolated Kotlin 2.1 / Compose compiler check of the real companion, lyrics, LLM and affected UI sources against Android 15, Compose 1.7.5 / Material3 1.3.1 and Room 2.6.1 libraries. Only app-shell/navigation/glass/haze boundaries are stubbed; this is not a full Gradle APK build or a Room KSP/device test.
- JVM regression suite, including radio, lyric matching/cache/gate, parsing, companion validation/history/backup, persistent inventory, access-error classification, prompt substitution and text direction.
- No live paid AI request, LRCLIB quota test or physical-device visual/write test was performed here.

Device checklist: reopen manager repeatedly without a scan; switch album/library scopes; restart app and reopen; check a new/changed file; cancel a network search/cooldown after scrolling; reject folder access and retry after granting; try disk-full/player-loaded errors; keep/discard a rewritten article; test Persian text beginning with an English name, line translations, large font, dark/light themes and narrow screens. Generate a new article once to evaluate the new brief—old stored prose is intentionally preserved.
