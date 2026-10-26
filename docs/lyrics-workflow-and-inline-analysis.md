# Lyrics workflow and inline analysis refinement

Based on `feature/song-companion` commit `500e846`, retaining embedded lyrics and radio fixes. No merge of recommendation-quality, no new main database migration and no change to the native audio writer.

## What changed

- The lyrics area in both the main player and full-screen lyrics has a **Lyrics / About this song** switch. Meaning, source-based story, review/apply and scoped saved correction chat are available directly inside About. Advanced versions/manual editing/line notes/translation management remain accessible through the full editor. The accepted bilingual translation rendering is unchanged.
- The fade mask applies only to scrolling lyric text, not to the switch, buttons or analysis cards.
- A local-only inventory runs when entering Lyrics Manager. A pure presence classifier distinguishes embedded lyrics, sidecar lyrics and truly missing lyrics; local read failures stay failures. Scanning has no API client call.
- Missing tracks appear in a filtered checkbox list. Search and embed use only explicit selections. Filters include artist/title/album text, a dedicated album filter, and Missing/Ready/Errors/All. Hidden selections are counted explicitly.
- Song menus and the player have a single-song entry; album menus have an album-scoped entry. Scoped views automatically inventory their own files and preselect missing tracks, never auto-search online.
- LRCLIB stays the default free synced/plain provider, with configurable compatible base URL and an optional registered public client app ID. No fabricated ID, secret API key or rate-limit evasion.
- HTTP requests are serialized through a mutex. Minimum 1.5-second spacing is measured after completion. Retry-After plus jitter is honored, a visible countdown runs before retry, and repeated throttling pauses the selected queue rather than hammering the service. Provider-specific cooldown is persisted across restarts. Shared IP/VPN rate limits can still affect the first request; this cannot be eliminated client-side.
- Disk result cache: complete matches seven days, complete no-match results six hours; failed lookups are never cached as no-match. Cache is provider + recording metadata scoped, bounded to 1,000 entries / 20 MB. Explicit refresh bypasses results, never cooldown.
- File writes still require separate confirmation and preserve native backup/verification/rollback behavior. Sidecar lyrics are counted as available, not automatically copied over existing files.

## Validation

- 104 JVM tests passed, including all 80 previous tests, inventory classification, request serialization, cancellation during cooldown, completion-based pacing, restored deadlines, cache expiry/recording isolation/corruption, official-host-only public app ID headers and edge/non-JSON rate-limit handling.
- Changed services and Compose lyrics-manager/inline-analysis/lyric-rendering code compiled against the project's real Android/Compose/Room libraries with isolated app-boundary stubs. Integration menu/route/renderer call sites were reviewed.
- No full APK build, live provider load test, paid LLM call, emulator run or device UI test was performed for this refinement. Build the new branch in Codemagic.

## Device checks

1. Open Lyrics Manager while offline: inventory must complete without network requests. Check existing embedded lyrics, sidecars, missing files, unsupported formats and unreadable files.
2. Select only two tracks; change filters; confirm hidden selections are counted; search and embed must affect only explicit selected tracks. Preview actual words before embedding.
3. Open a song and an album via their menus; verify no other tracks are scanned/searched. Switch to another track before embedding a loaded file.
4. Stop during a provider cooldown: no request should be sent. Restart the app during cooldown and confirm an explicit new search still waits. Inspect repeated 429/503 handling without intentionally load-testing the public service.
5. Repeat a completed search: cache should avoid requests. Explicit refresh should go online but still obey cooldown.
6. Switch Lyrics/About without navigation. Generate/review/apply explanation; ask for a correction; restart and read offline. Check synced translation, long-press notes and rapid track switching without cross-recording content.
7. Validate small screens, landscape, Persian RTL, long text and chat keyboard/scrolling on physical devices.
