# Spotizer online module — merge guide (Symphony fork)

This drop-in adds the Spotizer online section (search / stream / download) to the
Symphony fork. All new code lives in two new packages, so merging is mostly
copy-paste plus a few wiring points:

```
app/src/main/java/io/github/zyrouge/symphony/
├── services/spotizer/
│   ├── SpotizerModels.kt          # DTOs matching the backend exactly
│   ├── SpotizerClient.kt          # OkHttp client for every /v1 endpoint
│   ├── SpotizerSettings.kt        # local settings (SharedPreferences + StateFlow)
│   ├── SpotizerUserManager.kt     # /users/resolve + bot link-code flow
│   ├── SpotizerDownloadManager.kt # two-phase download queue
│   └── Spotizer.kt                # facade / composition root
└── ui/view/spotizer/
    ├── Glass.kt                   # glassmorphism modifiers (glass / glassChip)
    ├── OnlineSearchState.kt       # debounced search state holder (450ms, paging)
    ├── OnlineSearchView.kt        # Online tab body + track/album/artist rows
    ├── OnlineTrackBottomSheet.kt  # track sheet: play (stream) / download / badges
    ├── OnlineAlbumView.kt         # full album screen + "Download album"
    ├── OnlineArtistView.kt        # artist screen: top tracks, discography, related
    ├── DownloadQueueView.kt       # queue with per-phase progress, cancel/retry
    └── SpotizerSettingsView.kt    # settings section (qualities, toggles, slider)
```

## 1. Gradle dependencies (app/build.gradle.kts)

```kotlin
plugins {
    // add if not present:
    kotlin("plugin.serialization") version "<your kotlin version>"
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.coil-kt:coil-compose:2.6.0") // Symphony already uses coil; keep one version
    implementation("androidx.compose.material:material-icons-extended") // Download/DownloadDone icons
}
```

## 2. Manifest

```xml
<uses-permission android:name="android.permission.INTERNET" />
<!-- Android 9 (P) legacy save path only: -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

## 3. Composition root (Symphony.kt application class)

```kotlin
lateinit var spotizer: Spotizer

override fun onCreate() {
    super.onCreate()
    // ... existing init ...
    spotizer = Spotizer(
        context = this,
        isTrackOnDevice = { track ->
            // dedupe hook: match against the local groove library
            val title = track.title?.trim()?.lowercase()
            val artist = track.artist?.trim()?.lowercase()
            val durationMs = track.durationMs
            groove.song.values().any { song ->
                song.title.trim().lowercase() == title &&
                    song.artists.any { it.trim().lowercase() == artist } &&
                    kotlin.math.abs(song.duration - durationMs) <= 3000
            }
        },
    )
}
```

(Adjust `groove.song.values()` / `song.duration` to the fork's actual song
repository API — tolerance is ±3s as agreed.)

## 4. Search view: Local | Online switch

In `ui/view/SearchView.kt`, add a two-state source selector next to the query
field (same style as the existing chips), keep the existing local results for
`Local`, and render the online body for `Online`:

```kotlin
var searchSource by rememberSaveable { mutableStateOf(SearchSource.Local) } // new enum
val onlineSearch = remember { OnlineSearchState(symphony.spotizer.client, coroutineScope) }

// forward the existing query field into the online state:
LaunchedEffect(terms, searchSource) {
    if (searchSource == SearchSource.Online) onlineSearch.onQueryChanged(terms)
}

when (searchSource) {
    SearchSource.Local -> { /* existing results UI, unchanged */ }
    SearchSource.Online -> OnlineSearchView(
        state = onlineSearch,
        onOpenTrack = { selectedOnlineTrack = it },       // opens OnlineTrackBottomSheet
        onOpenAlbum = { navigateToOnlineAlbum(it.id!!) },
        onOpenArtist = { navigateToOnlineArtist(it.id!!) },
    )
}
```

## 5. Navigation routes

Add two routes to the fork's NavHost (names as in the existing Routes object):

```kotlin
composable("online_album/{albumId}") { entry ->
    OnlineAlbumView(
        spotizer = symphony.spotizer,
        albumId = entry.arguments!!.getString("albumId")!!,
        onOpenTrack = { selectedOnlineTrack = it },
        onOpenArtist = { navController.navigate("online_artist/$it") },
    )
}
composable("online_artist/{artistId}") { entry ->
    OnlineArtistView(
        spotizer = symphony.spotizer,
        artistId = entry.arguments!!.getString("artistId")!!,
        onOpenTrack = { selectedOnlineTrack = it },
        onOpenAlbum = { navController.navigate("online_album/$it") },
        onOpenArtist = { navController.navigate("online_artist/$it") },
    )
}
```

Also add a `DownloadQueueView(symphony.spotizer.downloads)` route (e.g. from a
download icon in the top bar; a badge can use `downloads.items.collectAsState()`
and count `isActive`).

## 6. Streaming playback

`spotizer.streamUrl(track)` returns a plain HTTPS URL with Range/seek support
(server prepares the track on demand; first play of an uncached track can take
a few seconds — show a buffering state).

- If the fork's player is Media3/ExoPlayer: `MediaItem.fromUri(url)` and play.
- If it uses Symphony's RadioPlayer (MediaPlayer-based): it already plays from
  a Uri; pass `Uri.parse(url)`. Wire this in the `onPlay` callback of
  `OnlineTrackBottomSheet`.

## 7. Settings screen

Embed `SpotizerSettingsBody(symphony.spotizer)` as a new "Spotizer" section or
settings sub-page. It covers: download quality, streaming quality (MP3_128 /
MP3_320 / FLAC), skip-existing-tracks toggle, Wi-Fi-only toggle, concurrent
downloads (1–3), and shows the save folder (Music/Spotizer).

## 8. Glass / haze (optional)

`Glass.kt` works standalone (translucent gradient + hairline border, matching
the app's glassy style). For real backdrop blur, add chrisbanes/haze and apply
`Modifier.hazeEffect(hazeState)` before `.glass()` on cards, with
`Modifier.hazeSource(hazeState)` on the background content.

## 9. How the two-phase download works (already implemented)

1. `CheckingLocal` — dedupe against the device library (skippable in settings).
2. `CheckingServer` — `GET /v1/tracks/{id}/status` → `cached` badge.
3. `PreparingOnServer` — `GET /v1/tracks/{id}/download` triggers on-demand
   server prep; 504 responses mean "still preparing" and are retried every 3s
   (up to 40 attempts). UI shows an indeterminate bar.
4. `Downloading` — response body streamed to a temp file with progress;
   interrupted transfers resume with `Range: bytes=N-`.
5. `Saving` — published via MediaStore to `Music/<folder>` (API 29+) or legacy
   path + media scanner on Android 9, so the local library sees it immediately.

Note: the server-side job API (`POST /v1/downloads` + polling + `/v1/files/{token}`)
is intended for the Telegram bots / zip flow; the app deliberately uses the
per-track `/download` endpoint instead, which also logs history via `user_id`.
The client (`SpotizerClient`) still implements the job API in case you want an
"album as zip" option later.

## 10. i18n

UI strings are intentionally hardcoded in English for the first merge. To
localize via Phrasey, replace the literals in `ui/view/spotizer/*.kt` with
`context.symphony.t.*` keys after adding them to `i18n/en.yaml`.
