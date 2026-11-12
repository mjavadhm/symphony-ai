package io.github.zyrouge.symphony.utils

/** Paragraph direction is independent of the application's UI language. */
object ReadingDirection {
    fun isRtl(text: String): Boolean {
        var rtl = 0; var ltr = 0
        text.codePoints().forEach { point ->
            when (Character.getDirectionality(point)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl++
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++
            }
        }
        return rtl > ltr
    }
}
