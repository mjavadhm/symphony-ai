package io.github.zyrouge.symphony.ui.view

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.ui.components.GenericSongListDropdown
import io.github.zyrouge.symphony.ui.components.GlassDetailScaffold
import io.github.zyrouge.symphony.ui.components.IconTextBody
import io.github.zyrouge.symphony.ui.components.SongList
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.serialization.Serializable

@Serializable
data class GenreViewRoute(val genreName: String)

@Composable
fun GenreView(context: ViewContext, route: GenreViewRoute) {
    val allGenreNames by context.symphony.groove.genre.all.collectAsState()
    val allSongIds by context.symphony.groove.song.all.collectAsState()
    val genre by remember(allGenreNames) {
        derivedStateOf { context.symphony.groove.genre.get(route.genreName) }
    }
    val songIds by remember(genre, allSongIds) {
        derivedStateOf { genre?.getSongIds(context.symphony) ?: listOf() }
    }
    val isViable by remember(allGenreNames) {
        derivedStateOf { allGenreNames.contains(route.genreName) }
    }
    var showOptionsMenu by remember { mutableStateOf(false) }
    val backgroundImage = remember(songIds) {
        songIds.firstOrNull()
            ?.let { context.symphony.groove.song.get(it) }
            ?.createArtworkImageRequest(context.symphony)
            ?.build()
    }

    GlassDetailScaffold(
        context = context,
        title = genre?.name ?: context.symphony.t.Genre,
        backgroundImage = backgroundImage,
        topBarActions = {
            IconButton(
                modifier = Modifier.size(44.dp),
                onClick = { showOptionsMenu = !showOptionsMenu },
            ) {
                Icon(Icons.Filled.MoreVert, null)
                GenericSongListDropdown(
                    context,
                    songIds = songIds,
                    expanded = showOptionsMenu,
                    onDismissRequest = {
                        showOptionsMenu = false
                    }
                )
            }
        },
    ) {
        when {
            isViable -> SongList(context, songIds = songIds)
            else -> UnknownGenre(context, route.genreName)
        }
    }
}

@Composable
private fun UnknownGenre(context: ViewContext, genre: String) {
    IconTextBody(
        icon = { modifier ->
            Icon(
                Icons.Filled.Tune,
                null,
                modifier = modifier
            )
        },
        content = {
            Text(context.symphony.t.UnknownGenreX(genre))
        }
    )
}
