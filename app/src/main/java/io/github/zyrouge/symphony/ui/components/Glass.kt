package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * The HazeState shared between the dynamic background (source) and every glass surface (child).
 * It is created and provided inside HomeView.
 */
val LocalHazeState = compositionLocalOf { HazeState() }

/**
 * Padding for Home's top/bottom bars. Instead of hard padding it is handed to the lists'
 * contentPadding, so content scrolls underneath the bars.
 */
val LocalHomeContentPadding = compositionLocalOf { PaddingValues(0.dp) }

/**
 * A genuine glass surface (blurring what is behind it) for Telegram-style pills and circles.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    border: Boolean = true,
    tintAlpha: Float = 0.25f,
    blurRadius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val hazeState = LocalHazeState.current
    val surface = MaterialTheme.colorScheme.surface
    // In the light theme the glass has to be milkier so dark text on top stays readable
    val effectiveTintAlpha = when {
        surface.luminance() > 0.5f -> (tintAlpha + 0.2f).coerceAtMost(1f)
        else -> tintAlpha
    }

    Box(
        modifier = modifier
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = surface,
                    tint = HazeTint(surface.copy(alpha = effectiveTintAlpha)),
                    blurRadius = blurRadius,
                ),
            )
            .let { base ->
                if (border) base.border(
                    1.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
                    shape,
                ) else base
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
