package io.github.zyrouge.symphony.ui.view

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.services.groove.Groove
import io.github.zyrouge.symphony.ui.components.AlbumArtistDropdownMenu
import io.github.zyrouge.symphony.ui.components.AlbumDropdownMenu
import io.github.zyrouge.symphony.ui.components.AnimatedNowPlayingBottomBar
import io.github.zyrouge.symphony.ui.components.ArtistDropdownMenu
import io.github.zyrouge.symphony.ui.components.GenericGrooveCard
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.components.HomeDynamicBackground
import io.github.zyrouge.symphony.ui.components.IconTextBody
import io.github.zyrouge.symphony.ui.components.LocalHazeState
import io.github.zyrouge.symphony.ui.components.PlaylistDropdownMenu
import io.github.zyrouge.symphony.ui.components.SongCard
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.utils.joinToStringIfNotEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

private data class SearchResult(
    val songIds: List<String>,
    val artistNames: List<String>,
    val albumIds: List<String>,
    val albumArtistNames: List<String>,
    val genreNames: List<String>,
    val playlistIds: List<String>,
)

@Serializable
data class SearchViewRoute(val initialChip: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchView(context: ViewContext, route: SearchViewRoute) {
    val coroutineScope = rememberCoroutineScope()
    var terms by rememberSaveable { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<SearchResult?>(null) }
    val initialChip = remember {
        route.initialChip?.let { enumValueOf<Groove.Kind>(it) }
    }
    var selectedChip by rememberSaveable {
        mutableStateOf(initialChip)
    }
    val isSemanticSearchEnabled by context.symphony.settings.isSemanticSearchEnabled.flow.collectAsState()
    val isSemanticSearchReady by context.symphony.semanticSearch.isReady.collectAsState()
    val showAiSearchTab = isSemanticSearchEnabled && isSemanticSearchReady
    var isAiSearchSelected by rememberSaveable { mutableStateOf(false) }

    fun isChipSelected(kind: Groove.Kind) =
        (selectedChip == null && !isAiSearchSelected) || selectedChip == kind

    var currentTermsRoutine: Job? = null
    fun setTerms(nTerms: String) {
        terms = nTerms
        isSearching = true
        currentTermsRoutine?.cancel()
        currentTermsRoutine = coroutineScope.launch {
            withContext(Dispatchers.Default) {
                delay(250)
                val songIds = mutableListOf<String>()
                val artistNames = mutableListOf<String>()
                val albumIds = mutableListOf<String>()
                val albumArtistNames = mutableListOf<String>()
                val genreNames = mutableListOf<String>()
                val playlistIds = mutableListOf<String>()

                if (isAiSearchSelected) {
                    val aiResults = context.symphony.semanticSearch.search(terms)

                    // aiResults contains file paths or filenames. We need to map them to song IDs.
                    // Groove.Song has path and filename.
                    val allSongs = context.symphony.groove.song.values()
                    for (path in aiResults) {
                        val matchedSong = allSongs.find {
                            it.path == path || it.filename == path || it.path.endsWith(path)
                        }
                        if (matchedSong != null) {
                            songIds.add(matchedSong.id)
                        }
                    }
                } else {
                    if (isChipSelected(Groove.Kind.SONG)) {
                        songIds.addAll(
                            context.symphony.groove.song
                                .search(context.symphony.groove.song.ids(), terms)
                                .map { it.entity }
                        )
                    }
                    if (isChipSelected(Groove.Kind.ARTIST)) {
                        artistNames.addAll(
                            context.symphony.groove.artist
                                .search(context.symphony.groove.artist.ids(), terms)
                                .map { it.entity }
                        )
                    }
                    if (isChipSelected(Groove.Kind.ALBUM)) {
                        albumIds.addAll(
                            context.symphony.groove.album
                                .search(context.symphony.groove.album.ids(), terms)
                                .map { it.entity }
                        )
                    }
                    if (isChipSelected(Groove.Kind.ALBUM_ARTIST)) {
                        albumArtistNames.addAll(
                            context.symphony.groove.albumArtist
                                .search(context.symphony.groove.albumArtist.ids(), terms)
                                .map { it.entity }
                        )
                    }
                    if (isChipSelected(Groove.Kind.GENRE)) {
                        genreNames.addAll(
                            context.symphony.groove.genre
                                .search(context.symphony.groove.genre.ids(), terms)
                                .map { it.entity }
                        )
                    }
                    if (isChipSelected(Groove.Kind.PLAYLIST)) {
                        playlistIds.addAll(
                            context.symphony.groove.playlist
                                .search(context.symphony.groove.playlist.ids(), terms)
                                .map { it.entity }
                        )
                    }
                }

                results = SearchResult(
                    songIds = songIds.distinct(),
                    artistNames = artistNames,
                    albumIds = albumIds,
                    albumArtistNames = albumArtistNames,
                    genreNames = genreNames,
                    playlistIds = playlistIds,
                )
            }
            isSearching = false
        }
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val textFieldFocusRequester = FocusRequester()
    val chipsScrollState = rememberScrollState()
    var initialScroll = remember { false }
    val hazeState = remember { HazeState() }

    LaunchedEffect(LocalContext.current) {
        textFieldFocusRequester.requestFocus()
        snapshotFlow { configuration.orientation }.collect {
            setTerms(terms)
        }
    }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(modifier = Modifier.fillMaxSize()) {
            HomeDynamicBackground(context)
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                topBar = {
                    Column(
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(top = 10.dp, bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlassSurface(
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                            ) {
                                IconButton(
                                    modifier = Modifier.size(48.dp),
                                    onClick = { context.navController.popBackStack() },
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                                }
                            }
                            GlassSurface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(50),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Search,
                                        null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                    BasicTextField(
                                        value = terms,
                                        onValueChange = { setTerms(it) },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        keyboardOptions = KeyboardOptions(
                                            imeAction = ImeAction.Search,
                                        ),
                                        decorationBox = { innerTextField ->
                                            Box(contentAlignment = Alignment.CenterStart) {
                                                if (terms.isEmpty()) {
                                                    Text(
                                                        context.symphony.t.SearchYourMusic,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                innerTextField()
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(textFieldFocusRequester),
                                    )
                                    if (terms.isNotEmpty()) {
                                        IconButton(
                                            modifier = Modifier.size(28.dp),
                                            onClick = { setTerms("") },
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(chipsScrollState)
                        ) {
                            Spacer(modifier = Modifier.width(6.dp))
                            GlassFilterChip(
                                selected = selectedChip == null && !isAiSearchSelected,
                                label = {
                                    Text(context.symphony.t.All)
                                },
                                onClick = {
                                    selectedChip = null
                                    isAiSearchSelected = false
                                    setTerms(terms)
                                }
                            )
                            if (showAiSearchTab) {
                                GlassFilterChip(
                                    selected = isAiSearchSelected,
                                    label = {
                                        Text("AI Search")
                                    },
                                    onClick = {
                                        selectedChip = null
                                        isAiSearchSelected = true
                                        setTerms(terms)
                                    }
                                )
                            }
                            Groove.Kind.entries.map {
                                GlassFilterChip(
                                    selected = selectedChip == it,
                                    label = {
                                        Text(it.label(context))
                                    },
                                    modifier = Modifier.onGloballyPositioned { coordinates ->
                                        if (!initialScroll && initialChip == it) {
                                            val windowWidth = with(density) {
                                                configuration.screenWidthDp.dp.toPx()
                                            }
                                            val position = coordinates.positionInWindow()
                                            val start = position.x.toInt()
                                            val width = coordinates.size.width
                                            val end = start + width
                                            val scrollTo = when {
                                                width < windowWidth && end > windowWidth -> start + width
                                                start > windowWidth -> start
                                                else -> null
                                            }
                                            scrollTo?.let { v ->
                                                coroutineScope.launch {
                                                    chipsScrollState.animateScrollTo(v)
                                                }
                                            }
                                            initialScroll = true
                                        }
                                    },
                                    onClick = {
                                        selectedChip = it
                                        isAiSearchSelected = false
                                        setTerms(terms)
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                    }
                },
                content = { contentPadding ->
                    results?.run {
                        val hasSongs =
                            (isChipSelected(Groove.Kind.SONG) || isAiSearchSelected) && songIds.isNotEmpty()
                        val hasArtists = isChipSelected(Groove.Kind.ARTIST) && artistNames.isNotEmpty()
                        val hasAlbums = isChipSelected(Groove.Kind.ALBUM) && albumIds.isNotEmpty()
                        val hasAlbumArtists =
                            isChipSelected(Groove.Kind.ALBUM_ARTIST) && albumArtistNames.isNotEmpty()
                        val hasPlaylists =
                            isChipSelected(Groove.Kind.PLAYLIST) && playlistIds.isNotEmpty()
                        val hasGenres = isChipSelected(Groove.Kind.GENRE) && genreNames.isNotEmpty()
                        val hasNoResults =
                            !hasSongs && !hasArtists && !hasAlbums && !hasAlbumArtists && !hasPlaylists && !hasGenres

                        if (terms.isNotEmpty()) {
                            when {
                                isSearching -> {
                                    Box(
                                        modifier = Modifier
                                            .padding(contentPadding)
                                            .fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        IconTextBody(
                                            icon = { modifier ->
                                                Icon(
                                                    Icons.Filled.Search,
                                                    null,
                                                    modifier = modifier
                                                )
                                            },
                                            content = {
                                                Text(context.symphony.t.FilteringResults)
                                            }
                                        )
                                    }
                                }

                                hasNoResults -> {
                                    Box(
                                        modifier = Modifier
                                            .padding(contentPadding)
                                            .fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        IconTextBody(
                                            icon = { modifier ->
                                                Icon(
                                                    Icons.Filled.PriorityHigh,
                                                    null,
                                                    modifier = modifier
                                                )
                                            },
                                            content = {
                                                Text(context.symphony.t.NoResultsFound)
                                            }
                                        )
                                    }
                                }

                                else -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .hazeSource(state = LocalHazeState.current, zIndex = 1f)
                                            .verticalScroll(rememberScrollState())
                                            .padding(contentPadding)
                                    ) {
                                        if (hasSongs) {
                                            SideHeading(context, Groove.Kind.SONG)
                                            songIds.forEach { songId ->
                                                context.symphony.groove.song.get(songId)?.let { song ->
                                                    SongCard(context, song) {
                                                        context.symphony.radio.shorty.playQueue(song.id)
                                                    }
                                                }
                                            }
                                        }
                                        if (hasArtists) {
                                            SideHeading(context, Groove.Kind.ARTIST)
                                            artistNames.forEach { artistName ->
                                                context.symphony.groove.artist.get(artistName)
                                                    ?.let { artist ->
                                                        GenericGrooveCard(
                                                            image = artist
                                                                .createArtworkImageRequest(context.symphony)
                                                                .build(),
                                                            title = {
                                                                Text(artist.name)
                                                            },
                                                            options = { expanded, onDismissRequest ->
                                                                ArtistDropdownMenu(
                                                                    context,
                                                                    artist,
                                                                    expanded = expanded,
                                                                    onDismissRequest = onDismissRequest,
                                                                )
                                                            },
                                                            onClick = {
                                                                context.navController.navigate(
                                                                    ArtistViewRoute(artist.name)
                                                                )
                                                            }
                                                        )
                                                    }
                                            }
                                        }
                                        if (hasAlbums) {
                                            SideHeading(context, Groove.Kind.ALBUM)
                                            albumIds.forEach { albumId ->
                                                context.symphony.groove.album.get(albumId)
                                                    ?.let { album ->
                                                        GenericGrooveCard(
                                                            image = album
                                                                .createArtworkImageRequest(context.symphony)
                                                                .build(),
                                                            title = {
                                                                Text(album.name)
                                                            },
                                                            subtitle = album.artists
                                                                .joinToStringIfNotEmpty()
                                                                ?.let { { Text(it) } },
                                                            options = { expanded, onDismissRequest ->
                                                                AlbumDropdownMenu(
                                                                    context,
                                                                    album,
                                                                    expanded = expanded,
                                                                    onDismissRequest = onDismissRequest,
                                                                )
                                                            },
                                                            onClick = {
                                                                context.navController.navigate(
                                                                    AlbumViewRoute(album.id)
                                                                )
                                                            }
                                                        )
                                                    }
                                            }
                                        }
                                        if (hasAlbumArtists) {
                                            SideHeading(context, Groove.Kind.ALBUM_ARTIST)
                                            albumArtistNames.forEach { albumArtistName ->
                                                context.symphony.groove.albumArtist.get(albumArtistName)
                                                    ?.let { albumArtist ->
                                                        GenericGrooveCard(
                                                            image = albumArtist
                                                                .createArtworkImageRequest(context.symphony)
                                                                .build(),
                                                            title = {
                                                                Text(albumArtist.name)
                                                            },
                                                            options = { expanded, onDismissRequest ->
                                                                AlbumArtistDropdownMenu(
                                                                    context,
                                                                    albumArtist,
                                                                    expanded = expanded,
                                                                    onDismissRequest = onDismissRequest,
                                                                )
                                                            },
                                                            onClick = {
                                                                context.navController.navigate(
                                                                    AlbumArtistViewRoute(albumArtist.name)
                                                                )
                                                            }
                                                        )
                                                    }
                                            }
                                        }
                                        if (hasPlaylists) {
                                            SideHeading(context, Groove.Kind.PLAYLIST)
                                            playlistIds.forEach { playlistId ->
                                                context.symphony.groove.playlist.get(playlistId)
                                                    ?.let { playlist ->
                                                        GenericGrooveCard(
                                                            image = playlist
                                                                .createArtworkImageRequest(context.symphony)
                                                                .build(),
                                                            title = {
                                                                Text(playlist.title)
                                                            },
                                                            options = { expanded, onDismissRequest ->
                                                                PlaylistDropdownMenu(
                                                                    context,
                                                                    playlist,
                                                                    expanded = expanded,
                                                                    onDismissRequest = onDismissRequest,
                                                                )
                                                            },
                                                            onClick = {
                                                                context.navController.navigate(
                                                                    PlaylistViewRoute(playlist.id)
                                                                )
                                                            }
                                                        )
                                                    }
                                            }
                                        }
                                        if (hasGenres) {
                                            SideHeading(context, Groove.Kind.GENRE)
                                            genreNames.forEach { genreName ->
                                                context.symphony.groove.genre.get(genreName)
                                                    ?.let { genre ->
                                                        GenericGrooveCard(
                                                            image = null,
                                                            title = { Text(genre.name) },
                                                            subtitle = {
                                                                Text(
                                                                    context.symphony.t.XSongs(
                                                                        genre.numberOfTracks.toString()
                                                                    )
                                                                )
                                                            },
                                                            options = null,
                                                            onClick = {
                                                                context.navController.navigate(
                                                                    GenreViewRoute(genre.name)
                                                                )
                                                            }
                                                        )
                                                    }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                    }
                                }
                            }
                        }
                    }
                },
                bottomBar = {
                    AnimatedNowPlayingBottomBar(context)
                }
            )
        }
    }
}

@Composable
private fun SideHeading(context: ViewContext, kind: Groove.Kind) {
    SideHeading(kind.label(context))
}

@Composable
private fun SideHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(12.dp, 12.dp, 12.dp, 4.dp)
    )
}

private fun Groove.Kind.label(context: ViewContext) = when (this) {
    Groove.Kind.SONG -> context.symphony.t.Songs
    Groove.Kind.ALBUM -> context.symphony.t.Albums
    Groove.Kind.ARTIST -> context.symphony.t.Artists
    Groove.Kind.ALBUM_ARTIST -> context.symphony.t.AlbumArtists
    Groove.Kind.GENRE -> context.symphony.t.Genres
    Groove.Kind.PLAYLIST -> context.symphony.t.Playlists
}

@Composable
private fun GlassFilterChip(
    selected: Boolean,
    label: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
    ) {
        FilterChip(
            selected = selected,
            label = label,
            onClick = onClick,
            shape = RoundedCornerShape(50),
            border = null,
            colors = FilterChipDefaults.filterChipColors(
                containerColor = Color.Transparent,
            ),
        )
    }
}

