package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection

@Composable
fun MediaSortBarScaffold(
    mediaSortBar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val outerPadding = LocalHomeContentPadding.current
    var height by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalHomeContentPadding provides PaddingValues(
                start = outerPadding.calculateStartPadding(layoutDirection),
                end = outerPadding.calculateEndPadding(layoutDirection),
                top = outerPadding.calculateTopPadding() + with(density) { height.toDp() },
                bottom = outerPadding.calculateBottomPadding(),
            )
        ) {
            content()
        }
        Box(
            modifier = Modifier
                .padding(top = outerPadding.calculateTopPadding())
                .onGloballyPositioned {
                    height = it.size.height
                }
        ) {
            mediaSortBar()
        }
    }
}
