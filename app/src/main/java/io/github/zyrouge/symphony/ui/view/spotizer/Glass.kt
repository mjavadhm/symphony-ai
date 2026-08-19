package io.github.zyrouge.symphony.ui.view.spotizer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glassmorphism helpers matching the app's existing frosted-glass style.
 *
 * These use translucent gradients + hairline borders and work standalone.
 * If the fork already uses chris banes' haze library, add
 * `Modifier.hazeEffect(hazeState)` before `glass()` for real backdrop blur
 * (see INTEGRATION.md, section "Glass / haze").
 */

val GlassCornerRadius = 20.dp

@Composable
fun Modifier.glass(
    cornerRadius: Dp = GlassCornerRadius,
    surfaceAlpha: Float = 0.55f,
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    return this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                colors = listOf(
                    surface.copy(alpha = surfaceAlpha + 0.15f),
                    surface.copy(alpha = surfaceAlpha),
                ),
            ),
        )
        .border(
            BorderStroke(
                1.dp,
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.25f),
                        outline.copy(alpha = 0.10f),
                    ),
                ),
            ),
            shape,
        )
}

@Composable
fun Modifier.glassChip(selected: Boolean): Modifier {
    val shape = RoundedCornerShape(50)
    val color = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.40f)
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.60f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)
    }
    return this
        .clip(shape)
        .background(color)
        .border(BorderStroke(1.dp, borderColor), shape)
}
