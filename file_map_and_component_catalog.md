# Symphony AI — File Map & Component Catalog

> **Purpose**: Quick reference for finding anything in the codebase. "What is where."

---

## 1. Top-Level Directory Map

```
symphony-ai/
├── app/                        ← Main Android application module
│   ├── build.gradle.kts        ← App build config (deps, SDK, signing, splits)
│   ├── proguard-rules.pro      ← R8/ProGuard rules
│   ├── room-schemas/           ← Room database migration schemas (auto-generated)
│   └── src/
│       ├── main/               ← Production source
│       ├── debug/              ← Debug-only overrides
│       ├── canary/             ← Canary variant overrides
│       └── test/               ← Unit tests
│
├── metaphony/                  ← Native C++ audio metadata parser library
│   ├── build.gradle.kts        ← Library build config (CMake integration)
│   ├── consumer-rules.pro      ← ProGuard rules for consumers
│   └── src/
│       ├── main/cpp/           ← C++ source + CMakeLists.txt
│       └── androidTest/        ← Instrumentation tests
│
├── cli/                        ← Node.js CLI tools
│   ├── android/                ← APK output management scripts
│   ├── changelogs/             ← Fastlane changelog scripts
│   ├── git/                    ← Git tag/diff utilities
│   ├── i18n/                   ← Translation summary generator
│   └── version/                ← Version bumping & printing
│
├── i18n/                       ← Translation source files (Phrasey input)
├── .phrasey/                   ← Phrasey build config
├── media/                      ← Marketing assets (banner.png, screenshots.png)
├── metadata/                   ← App store metadata (changelogs, descriptions)
├── secrets/                    ← Signing key config (gitignored)
├── onnx_models/                ← ONNX model files (AI features, WIP)
│
├── build.gradle.kts            ← Root build: plugin declarations
├── settings.gradle.kts         ← Module includes (:app, :metaphony)
├── gradle.properties           ← Gradle JVM settings
├── package.json                ← Node.js deps & scripts (CLI + i18n)
├── tsconfig.json               ← TypeScript config for CLI
├── codemagic.yaml              ← Codemagic CI/CD config
├── .prettierrc                 ← Prettier config for TOML/JSON
└── LICENSE                     ← AGPL-3.0
```

---

## 2. Kotlin Source Map

All Kotlin source lives under:
```
app/src/main/java/io/github/zyrouge/symphony/
```

### Root Files

| File | Purpose |
|------|---------|
| [Symphony.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/Symphony.kt) | **Central ViewModel** — service locator, lifecycle hooks |
| [MainActivity.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/MainActivity.kt) | Activity entry point, splash screen, sets Compose content |
| [ActivityIgnition.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/ActivityIgnition.kt) | Controls splash screen dismiss timing |
| [ErrorActivity.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/ErrorActivity.kt) | Crash reporting UI |

---

### `services/` — Backend Services

| File | Purpose |
|------|---------|
| [Settings.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/services/Settings.kt) | **All app settings** — SharedPreferences with typed reactive entries |
| [AppMeta.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/services/AppMeta.kt) | App version info, update checking |
| [Permissions.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/services/Permissions.kt) | Runtime permission handling |

---

### `services/groove/` — Media Library

| File | Purpose |
|------|---------|
| [Groove.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/services/groove/Groove.kt) | **Groove coordinator** — owns all repos, handles fetch/reset |
| [MediaExposer.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/services/groove/MediaExposer.kt) | **File scanner** — walks SAF trees, parses audio files, manages cache |
| [Song.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/services/groove/Song.kt) | **Song data model** — Room entity, metadata parsing (Metaphony + MMR) |
| [Album.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/services/groove/Album.kt) | Album data model |
| [Artist.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/services/groove/Artist.kt) | Artist data model |
| [AlbumArtist.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/services/groove/AlbumArtist.kt) | Album artist data model |
| [Genre.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/services/groove/Genre.kt) | Genre data model |
| [Playlist.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/services/groove/Playlist.kt) | Playlist data model |

#### `services/groove/repositories/`

| File | Purpose |
|------|---------|
| [SongRepository.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/services/groove/repositories/SongRepository.kt) | Song CRUD, sorting (11 sort modes), fuzzy search, artwork URIs, lyrics |
| AlbumRepository.kt | Album aggregation from songs, sorting, search |
| ArtistRepository.kt | Artist aggregation from songs |
| AlbumArtistRepository.kt | Album artist aggregation |
| GenreRepository.kt | Genre aggregation |
| PlaylistRepository.kt | M3U import, favorites, custom playlists, Room persistence |

**Repository Pattern**: Each repo has:
- `ConcurrentHashMap` cache
- `MutableStateFlow<List<String>>` for IDs (`.all`)
- `MutableStateFlow<Int>` for count
- `onSong(song)` — called by `MediaExposer` during scan
- `sort(ids, by, reverse)` — configurable sorting
- `search(ids, terms)` — fuzzy search
- `reset()` — clear all data

---

### `services/radio/` — Playback Engine

| File | Purpose |
|------|---------|
| [Radio.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/services/radio/Radio.kt) | **Main playback controller** — play, pause, seek, skip, queue management |
| RadioPlayer.kt | Wraps `MediaPlayer` with volume fading, speed/pitch control |
| RadioQueue.kt | Queue state — current index, loop mode, shuffle, serialization |
| RadioShorty.kt | **Convenience API** for UI — `playPause()`, `skip()`, `previous()`, `playQueue()` |
| RadioSession.kt | `MediaSessionCompat` integration for system UI controls |
| RadioNotification.kt | Media notification content builder |
| RadioNotificationManager.kt | Notification channel management |
| RadioNotificationService.kt | Foreground service for persistent playback |
| RadioObservatory.kt | **State bridge** — converts `Radio.Events` into `StateFlow` for Compose |
| RadioFocus.kt | Audio focus request/abandon |
| RadioNativeReceiver.kt | Headphone plug/unplug BroadcastReceiver |
| RadioEffects.kt | Audio effects (equalizer placeholder) |
| RadioArtworkCacher.kt | Notification artwork bitmap caching |

---

### `services/database/` — Persistence

| File | Purpose |
|------|---------|
| [Database.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/services/database/Database.kt) | **Database coordinator** — owns cache DB + persistent DB + file stores |
| CacheDatabase.kt | Room database for song metadata cache |
| PersistentDatabase.kt | Room database for playlists |

#### `services/database/adapters/`
| File | Purpose |
|------|---------|
| FileDatabaseAdapter.kt | File-based key-value storage |
| FileTreeDatabaseAdapter.kt | Tree-structured file storage |
| SQLiteKeyValueDatabaseAdapter.kt | SQLite-backed key-value store |

#### `services/database/store/`
| File | Purpose |
|------|---------|
| SongCacheStore.kt | Song metadata cache (Room DAO) |
| ArtworkCacheStore.kt | Album artwork file cache |
| LyricsCacheStore.kt | Lyrics text cache |
| PlaylistStore.kt | Playlist persistence (Room DAO) |

---

### `services/i18n/` — Internationalization

| File | Purpose |
|------|---------|
| Translator.kt | Loads translations, emits changes |
| Translation.kt | Generated translation interface |
| Translations.kt | Translation registry |
| CommonTranslation.kt | Shared translation utilities |

---

### `utils/` — Utility Library

| File | Purpose |
|------|---------|
| [Eventer.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/utils/Eventer.kt) | Simple pub/sub event bus |
| ActivityUtils.kt | SAF permission helpers |
| DocumentFileX.kt | Extended `DocumentFile` wrapper for SAF |
| DurationUtils.kt | Duration formatting |
| Float.kt | Float extensions |
| Fuzzy.kt | FuzzyWuzzy wrapper for search |
| Http.kt | OkHttp singleton |
| ImagePreserver.kt | Bitmap resizing with quality presets |
| KeyGenerator.kt | Time-incremental unique ID generator |
| List.kt | List extensions (`concurrentSetOf`, etc.) |
| Logger.kt | Logging utilities |
| RangeUtils.kt | Range calculations |
| RoomConvertors.kt | Room type converters (Set, Uri, LocalDate) |
| Run.kt | Coroutine helpers |
| Set.kt | Set extensions |
| SimpleFileSystem.kt | In-memory file tree for browsing |
| SimplePath.kt | Path manipulation utilities |
| StringListUtils.kt | String list sorting |
| StringUtils.kt | String extensions (case handling) |
| TimedContent.kt | Timed lyrics parser (LRC format) |

---

## 3. UI Layer Map

### `ui/theme/` — Design System

| File | Purpose |
|------|---------|
| [Theme.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/ui/theme/Theme.kt) | `SymphonyTheme` composable — wraps `MaterialTheme` with dynamic config |
| [Color.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/ui/theme/Color.kt) | 17 primary colors + neutral palette |
| [ColorScheme.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/ui/theme/ColorScheme.kt) | Light/Dark/Black scheme generators with HSL blending |
| Typography.kt | Font configuration + Google Fonts support |

---

### `ui/helpers/` — UI Utilities

| File | Purpose |
|------|---------|
| [Context.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/ui/helpers/Context.kt) | `ViewContext` data class |
| Assets.kt | Placeholder image URIs, image request builders |
| Transitions.kt | Slide, Scale, Fade transition definitions + durations |
| UserInterface.kt | UI utility functions |
| SimpleFileSystem.kt | UI file system helpers |

---

### `ui/view/` — Screens (17 Routes)

| File | Route | Description |
|------|-------|-------------|
| [Base.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/ui/view/Base.kt) | — | `NavHost` setup, route registration |
| [Home.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/ui/view/Home.kt) | `HomeViewRoute` | Main screen with bottom tabs |
| NowPlaying.kt | `NowPlayingViewRoute` | Full-screen now playing |
| Queue.kt | `QueueViewRoute` | Current queue |
| Search.kt | `SearchViewRoute(kind?)` | Global search |
| Artist.kt | `ArtistViewRoute(name)` | Artist detail |
| Album.kt | `AlbumViewRoute(id)` | Album detail |
| AlbumArtist.kt | `AlbumArtistViewRoute(name)` | Album artist detail |
| Genre.kt | `GenreViewRoute(name)` | Genre detail |
| Playlist.kt | `PlaylistViewRoute(id)` | Playlist detail |
| Lyrics.kt | `LyricsViewRoute` | Full-screen lyrics |
| Settings.kt | `SettingsViewRoute` | Settings hub |

#### `ui/view/home/` — Home Tab Contents (10 tabs)

| File | Tab |
|------|-----|
| ForYou.kt | "For You" — suggested albums/artists |
| Songs.kt | Song list |
| Albums.kt | Album grid |
| Artists.kt | Artist grid |
| AlbumArtists.kt | Album artist grid |
| Genres.kt | Genre grid |
| Playlists.kt | Playlist grid |
| Browser.kt | File browser (flat view) |
| Folders.kt | Folder grid |
| Tree.kt | Hierarchical file tree |

#### `ui/view/settings/` — Settings Sub-pages (7 pages)

| File | Settings Area |
|------|--------------|
| AppearanceSettingsView.kt | Theme, colors, Material You, font, scale |
| GrooveSettingsView.kt | Media folders, filters, cache, metadata engine |
| HomePageSettingsView.kt | Tab visibility, order, bottom bar labels |
| MiniPlayerSettingsView.kt | Mini player controls, marquee |
| NowPlayingSettingsView.kt | Controls layout, additional info, lyrics layout |
| PlayerSettingsView.kt | Audio focus, headphone behavior, fade, gapless |
| UpdateSettingsView.kt | Auto-update check toggle |

#### `ui/view/nowPlaying/` — Now Playing Sub-components

| File | Purpose |
|------|---------|
| AppBar.kt | Now playing top bar |
| Body.kt | Now playing body layout |
| BodyContent.kt | Song info + controls |
| BodyCover.kt | Album artwork display |
| BottomBar.kt | Now playing bottom controls |
| NothingPlaying.kt | Empty state |
| SpeedDialog.kt | Playback speed picker |
| PitchDialog.kt | Playback pitch picker |
| SleepTimerDialog.kt | Sleep timer configuration |

---

### `ui/components/` — Reusable Components (48 files)

#### Data Display Components

| Component | Purpose | Key Props |
|-----------|---------|-----------|
| [SongCard.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/ui/components/SongCard.kt) | **Song list item** — artwork, title, artist, actions menu | `context`, `song`, `highlighted`, `onClick` |
| SongList.kt | Song list with sort bar | `context`, `songIds`, `sortBy`, `sortReverse` |
| SongExplorerList.kt | Song list for file browser | |
| SongTreeList.kt | Hierarchical song tree | |
| AlbumGrid.kt | Album grid layout | |
| AlbumRow.kt | Album horizontal scroll row | |
| AlbumTile.kt | **Album grid item** — artwork + name | |
| ArtistGrid.kt | Artist grid layout | |
| ArtistTile.kt | **Artist grid item** | |
| AlbumArtistGrid.kt | Album artist grid | |
| AlbumArtistTile.kt | Album artist grid item | |
| GenreGrid.kt | Genre grid layout | |
| PlaylistGrid.kt | Playlist grid layout | |
| PlaylistTile.kt | Playlist grid item | |

#### Layout Components

| Component | Purpose |
|-----------|---------|
| GenericGrooveBanner.kt | Full-width banner with artwork + info (Album/Artist detail) |
| GenericGrooveCard.kt | Card wrapper for groove entities |
| SquareGrooveTile.kt | Square tile with artwork (grids) |
| ResponsiveGrid.kt | Adaptive grid column configuration |
| MediaSortBar.kt | Sort controls bar |
| MediaSortBarScaffold.kt | Scaffold with integrated sort bar |
| [NowPlayingBottomBar.kt](file:///e:/New%20folder/symphony-ai/app/src/main/java/io/github/zyrouge/symphony/ui/components/NowPlayingBottomBar.kt) | **Mini player** at bottom of screens |
| LoaderScaffold.kt | Loading state scaffold |

#### Dialog Components

| Component | Purpose |
|-----------|---------|
| AddToPlaylistDialog.kt | Add songs to playlist picker |
| NewPlaylistDialog.kt | Create new playlist |
| RenamePlaylistDialog.kt | Rename playlist |
| PlaylistManageSongsDialog.kt | Manage playlist contents |
| PlaylistInformationDialog.kt | Playlist details |
| SongInformationDialog.kt | Song metadata details |
| ConfirmationDialog.kt | Generic yes/no confirmation |
| InformationDialog.kt | Generic info dialog |
| IntroductoryDialog.kt | First-launch welcome dialog |
| ScaffoldDialog.kt | Base dialog with scaffold layout |

#### Utility Components

| Component | Purpose |
|-----------|---------|
| Slider.kt | Custom slider (playback seek bar) |
| Swipeable.kt | Swipe gesture wrapper |
| LyricsText.kt | Lyrics renderer (plain + synced) |
| TopAppBarMinimalTitle.kt | Compact top bar title |
| SubtleCaptionText.kt | Muted caption text |
| IconButtonPlaceholder.kt | Invisible spacer matching icon button size |
| IconTextBody.kt | Icon + text empty state |
| LongPressCopyableText.kt | Text that copies on long press |
| TimedContentText.kt | Timed/synced text display |
| KeepScreenAwake.kt | Prevents screen dimming |
| GenericSongListDropdown.kt | Dropdown for song list actions |
| ErrorComp.kt | Error display component |
| Snackbar.kt | Snackbar utility |

#### Scroll Components

| Component | Purpose |
|-----------|---------|
| LazyColumnScrollBar.kt | Scroll indicator for LazyColumn |
| LazyGridScrollBar.kt | Scroll indicator for LazyGrid |
| ContentDrawScopeScrollBar.kt | Custom scroll bar drawing |

---

### `ui/components/settings/` — Settings Tile Components

These are the building blocks for all settings pages:

| Component | Purpose | When to Use |
|-----------|---------|-------------|
| Tile.kt | Base settings tile layout | Extend for custom tiles |
| SwitchTile.kt | Toggle switch | Boolean settings |
| OptionTile.kt | Single-select dropdown | Enum settings |
| MultiOptionTile.kt | Multi-select checkboxes | Set\<Enum\> settings |
| SliderTile.kt | Range slider | Float/Int settings |
| TextInputTile.kt | Text field | String settings |
| FloatInputTile.kt | Numeric input | Float settings |
| MultiTextOptionTile.kt | Multi text input | String set settings |
| LinkTile.kt | Clickable link | External URLs |
| SimpleTile.kt | Plain clickable tile | Navigation / actions |
| SideHeading.kt | Section header | Group settings visually |
| MultiGrooveFolderTile.kt | Media folder picker | SAF folder selection |
| MultiSystemFolderTile.kt | System folder picker | Blacklist/whitelist |
| ConsiderContributingTile.kt | Contribution CTA | About section |

---

## 4. Resource Files

```
app/src/main/res/
├── drawable/           ← App icons, vector drawables
├── mipmap-*/           ← Launcher icons (all densities)
├── values/
│   ├── strings.xml     ← App name only (i18n strings are in Kotlin)
│   ├── themes.xml      ← Splash screen theme
│   └── colors.xml      ← Legacy color defs (minimal)
├── xml/
│   ├── backup_rules.xml
│   └── data_extraction_rules.xml
└── raw/                ← (if any) raw audio assets
```

---

## 5. Quick Reference: Where to Find Things

| I want to... | Look in... |
|--------------|-----------|
| Change a color/theme | `ui/theme/Color.kt`, `ColorScheme.kt`, `Theme.kt` |
| Add a setting | `services/Settings.kt` + `ui/view/settings/*.kt` |
| Add a new screen | `ui/view/` + register in `ui/view/Base.kt` |
| Add a home tab | `ui/view/Home.kt` (enum) + `ui/view/home/*.kt` |
| Modify song parsing | `services/groove/Song.kt` |
| Change playback logic | `services/radio/Radio.kt` |
| Add a UI component | `ui/components/*.kt` |
| Modify the mini player | `ui/components/NowPlayingBottomBar.kt` |
| Change the now playing screen | `ui/view/nowPlaying/*.kt` |
| Modify media scanning | `services/groove/MediaExposer.kt` |
| Change database schema | `services/database/` + Room entities |
| Add a translation string | `i18n/` source files + `npm run i18n:build` |
| Modify CI/CD | `.github/workflows/` or `codemagic.yaml` |
| Write CLI tools | `cli/` (TypeScript) |
| Modify native metadata parser | `metaphony/src/main/cpp/` |
