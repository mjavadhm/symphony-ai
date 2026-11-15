# Mood Session (experimental)

Discovery → **Mood Session · Experimental** opens a local, adaptive listening session.
Choose and order 1–5 analyzed library songs (three recommended). All selected songs
play first. The generated tail keeps up to five suggestions ahead; explicit/manual
queue entries and the current track are preserved when feedback changes the tail.

## Feedback and controls

- **Good for now / Not now** are available in the session and the main player. The
  session history allows a positive reaction after a quick skip. Tap again to clear.
- Direct feedback replaces behavioral evidence for that recording, rather than
  merely adding a larger weight. Clearing it exposes the stored behavior again.
- A negative reaction records the opinion without skipping the current track.
- Initial choices have weight 0.6, direct reactions ±1. Behavior is weak and capped
  at +0.25 / −0.1; repeated visits are averaged, never summed without limit.
- Normal playback exits the mode while keeping the queue and playback position.
  Ordinary shuffle/repeat/flow ordering is unavailable while a mood owns the queue.
  Radio/Autoplay recommendation algorithms have not been changed.

## Duration-aware behavioral evidence

The player measures actual monotonic playing time separately from unique covered
media intervals. Pause/preparation do not count; seeks and replaying the same part
cannot manufacture full-track coverage. Playback speed affects media coverage,
not elapsed listening time.

Positive evidence = `0.25 × min(actualSeconds / 120, 1) × (0.35 + 0.65 × coverage)`.
An intentional Next/jump before `min(20 seconds, 20% of duration)` and before 50%
coverage instead yields a negative fading from −0.1 to zero across that interval.
Unknown duration is neutral. Stops/errors are never interpreted as negative skips.
Examples: 16/20 seconds is about +0.029; 120/180 seconds about +0.196; 180/1200
seconds followed by Next about +0.112. These are pilot constants, not calibrated
probabilities. Only whole-track embeddings are used; section selection and
section-specific recommendations are explicitly deferred.

## Discovery and persistence

- Discovery defaults to 50%; familiar window defaults to seven days (3/7/14).
  Familiar means at least min(30 seconds, half the track) heard in that window.
  Cohort quota uses a rolling ten suggestions with fallback if a cohort is scarce.
- The session profile does not decay with wall-clock age. Historical listening is
  used for familiarity, not to import unrelated tastes into this session.
- SQLite/Room database `mood_sessions` is separate from the app's main database,
  with no destructive-migration fallback. Profiles, runs, reactions and visit IDs
  persist locally. URI/path plus unique metadata matching survive song-ID rescans;
  missing/ambiguous recordings are not attached to arbitrary same-title songs.
- Checkpoint every five seconds and on pause/seek/background/exit. Restart restores
  paused at the last checkpoint; it never auto-starts audio.
- **Save mood** names a profile; subsequent learning is automatic. **Continue**
  restores its latest run. **Start fresh / Fresh queue** retains learning but starts
  a new queue of recommendations, with a new run-specific played exclusion set.
- Reactions also tune general recommendations. The latest direct reaction across
  normal feedback and surviving mood profiles wins, excluding contradictory history
  for that recording. General taste half-life remains unchanged. Mood visits use
  their duration-aware score once, not a second legacy interpretation.
- Deleting a mood removes its runs/reactions and their recommendation contribution;
  ordinary listening-history records are retained. No export/cloud synchronization
  is included in this first version.

## Verification and device acceptance

All Gradle builds and dependency-requiring tests run in Codemagic, never locally.
The cloud command runs JVM tests, builds the debug APK and compiles the Android
instrumentation APK. The existing Apple-silicon machine does not execute Android
instrumentation tests; `MoodDatabaseTest` must run on Android.

On a device, check:

1. Seed order, Next from player/notification/headset and natural end-of-track.
2. Quick skip followed by Good for now; complete listening followed by Not now;
   negative reaction must not skip. Change/clear reactions and compare the tail.
3. Very short and long tracks, seeking, replaying a section, pauses and speed changes.
4. Insert a manual queue item during generation; current/seed/manual rows survive.
5. Save/name, close/reopen offline, Continue versus Fresh queue, rescan/remove/move
   a file, and delete a mood. Check no unsolicited playback after process restart.
6. Switch mood or start ordinary playback while suggestions are computing: old
   results cannot overwrite the new owner. Verify radio behavior remains unchanged.
7. Narrow/large-font/RTL layouts and low-storage/write failure messages.

Phone UI and real Room acceptance are separate from a successful JVM/cloud build.
