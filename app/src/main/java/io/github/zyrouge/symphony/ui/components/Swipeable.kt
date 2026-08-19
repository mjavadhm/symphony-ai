package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.absoluteValue

fun Modifier.swipeable(
    minimumDragAmount: Float = 50f,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
) = pointerInput(Unit) {
    var offset = Offset.Zero
    detectDragGestures(
        onDrag = { pointer, dragAmount ->
            pointer.consume()
            offset += dragAmount
        },
        onDragEnd = {
            val xAbs = offset.x.absoluteValue
            val yAbs = offset.y.absoluteValue
            when {
                xAbs > minimumDragAmount && xAbs > yAbs -> when {
                    offset.x > 0 -> onSwipeRight?.invoke()
                    else -> onSwipeLeft?.invoke()
                }

                yAbs > minimumDragAmount -> when {
                    offset.y > 0 -> onSwipeDown?.invoke()
                    else -> onSwipeUp?.invoke()
                }
            }
            offset = Offset.Zero
        },
        onDragCancel = {
            offset = Offset.Zero
        }
    )
}

/**
 * Same as [swipeable] but only reacts to (and only consumes) horizontal drags.
 *
 * Use this when an ancestor needs to keep handling vertical gestures, e.g. the
 * now playing artwork keeps next/previous swipes while the container above it
 * uses vertical drags to reveal or hide the lyrics.
 */
fun Modifier.horizontalSwipeable(
    minimumDragAmount: Float = 50f,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
) = pointerInput(Unit) {
    var offsetX = 0f
    detectHorizontalDragGestures(
        onDragStart = {
            offsetX = 0f
        },
        onHorizontalDrag = { pointer, dragAmount ->
            pointer.consume()
            offsetX += dragAmount
        },
        onDragEnd = {
            if (offsetX.absoluteValue > minimumDragAmount) {
                when {
                    offsetX > 0 -> onSwipeRight?.invoke()
                    else -> onSwipeLeft?.invoke()
                }
            }
            offsetX = 0f
        },
        onDragCancel = {
            offsetX = 0f
        },
    )
}
