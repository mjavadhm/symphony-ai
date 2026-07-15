package io.github.zyrouge.symphony.ui.view

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.groove.AlbumArtist
import io.github.zyrouge.symphony.ui.components.AlbumArtistDropdownMenu
import io.github.zyrouge.symphony.ui.components.AlbumRow
import io.github.zyrouge.symphony.ui.components.GenericGrooveBanner
import io.github.zyrouge.symphony.ui.components.GlassDetailScaffold
import io.github.zyrouge.symphony.ui.components.IconTextBody
import io.github.zyrouge.symphony.ui.components.SongList
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.serialization.Serializable

@Serializable
data class AlbumArtistViewRoute(val albumArtistName: String)

@Composable
fun AlbumArtistView(context: ViewContext, route: AlbumArtistViewRoute) {
    val allAlbumArtistNames by context.symphony.groove.albumArtist.all.collectAsState()
    val allSongIds by context.symphony.groove.song.all.collectAsState()
    val allAlbumIds = context.symphony.groove.album.all
    val albumArtist by remember(allAlbumArtistNames) {
        derivedStateOf { context.symphony.groove.albumArtist.get(route.albumArtistName) }
    }
    val songIds by remember(albumArtist, allSongIds) {
        derivedStateOf { albumArtist?.getSongIds(context.symphony) ?: listOf() }
    }
    val albumIds by remember(albumArtist, allAlbumIds) {
        derivedStateOf { albumArtist?.getAlbumIds(context.symphony) ?: listOf() }
    }
    val isViable by remember(albumArtist) {
        derivedStateOf { albumArtist != null }
    }
    val backgroundImage = remember(albumArtist) {
        albumArtist?.createArtworkImageRequest(context.symphony)?.build()
    }

    GlassDetailScaffold(
        context = context,
        title = albumArtist?.name ?: context.symphony.t.AlbumArtist,
        backgroundImage = backgroundImage,
    ) {
        if (isViable) {
            SongList(
                context,
                songIds = songIds,
                leadingContent = {
                    item {
                        AlbumArtistHero(context, albumArtist!!)
                    }
                    if (albumIds.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            AlbumRow(context, albumIds)
                            Spacer(modifier = Modifier.height(4.dp))
                            HorizontalDivider()
                        }
                    }
                }
            )
        } else UnknownAlbumArtist(context, route.albumArtistName)
    }
}

@Composable
private fun AlbumArtistHero(context: ViewContext, albumArtist: AlbumArtist) {
    GenericGrooveBanner(
        image = albumArtist.createArtworkImageRequest(context.symphony).build(),
        options = { expanded, onDismissRequest ->
            AlbumArtistDropdownMenu(
                context,
                albumArtist,
                expanded = expanded,
                onDismissRequest = onDismissRequest
            )
        },
        content = {
            Text(albumArtist.name)
        }
    )
}

@Composable
private fun UnknownAlbumArtist(context: ViewContext, artistName: String) {
    IconTextBody(
        icon = { modifier ->
            Icon(
                Icons.Filled.PriorityHigh,
                null,
                modifier = modifier
            )
        },
        content = {
            Text(context.symphony.t.UnknownArtistX(artistName))
        }
    )
}
