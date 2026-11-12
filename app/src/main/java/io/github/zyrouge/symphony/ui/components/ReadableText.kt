package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import io.github.zyrouge.symphony.utils.ReadingDirection

fun readingStyle(text: String, style: TextStyle): TextStyle = if (ReadingDirection.isRtl(text))
    style.copy(textDirection = TextDirection.Rtl, textAlign = TextAlign.Right)
else style.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left)

@Composable
fun ReadableText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val resolved = remember(text, style) { readingStyle(text, style) }
    Text(text, modifier.fillMaxWidth(), style = resolved)
}
