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
 * HazeState مشترک بین پسزمینهی داینامیک (source) و همهی سطحهای شیشهای (child).
 * توی HomeView ساخته و provide میشه.
 */
val LocalHazeState = compositionLocalOf { HazeState() }

/**
 * پدینگ نوارهای بالا/پایین Home که بهجای padding سفت،
 * به contentPadding لیستها داده میشه تا محتوا از زیر نوارها رد بشه.
 */
val LocalHomeContentPadding = compositionLocalOf { PaddingValues(0.dp) }

/**
 * یه سطح شیشهای واقعی (بلور از پشت) برای پیل/دایرههای سبک تلگرام.
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
    // تو تم روشن شیشه باید شیریتر باشه تا متن تیره روش خوانا بمونه
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
