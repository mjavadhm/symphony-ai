package io.github.zyrouge.symphony.ui.view.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.zyrouge.symphony.ui.components.GlassSettingsScaffold
import io.github.zyrouge.symphony.ui.components.GlassSurface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.serialization.Serializable

@Serializable
object RecommendationSettingsRoute

@Composable
fun RecommendationSettings(context: ViewContext) {
    val engine = context.symphony.recommendation

    var discoveryRatio by remember { mutableStateOf(engine.discoveryRatio) }
    var halfLife by remember { mutableStateOf(engine.recencyHalfLifeDays.toFloat()) }
    var mixSize by remember { mutableStateOf(engine.dailyMixSize.toFloat()) }
    var mixCount by remember { mutableStateOf(engine.dailyMixCount.toFloat()) }

    GlassSettingsScaffold(
        context = context,
        title = "AI Settings",
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
            SettingSliderItem(
                title = "Discovery ratio",
                description = "How much new, unheard music gets mixed into your mixes. Higher means more discovery.",
                valueLabel = "${(discoveryRatio * 100).toInt()}%",
                value = discoveryRatio,
                valueRange = 0f..1f,
                steps = 9,
                onChange = {
                    discoveryRatio = it
                    engine.discoveryRatio = it
                },
            )
            SettingSliderItem(
                title = "Taste memory (half-life)",
                description = "How fast older listens lose their influence on your taste profile.",
                valueLabel = "${halfLife.toInt()} days",
                value = halfLife,
                valueRange = 1f..30f,
                steps = 28,
                onChange = {
                    halfLife = it
                    engine.recencyHalfLifeDays = it.toInt()
                },
            )
            SettingSliderItem(
                title = "Songs per mix",
                description = "How many songs each mix contains.",
                valueLabel = "${mixSize.toInt()}",
                value = mixSize,
                valueRange = 10f..100f,
                steps = 8,
                onChange = {
                    mixSize = it
                    engine.dailyMixSize = it.toInt()
                },
            )
            SettingSliderItem(
                title = "Daily Mix count",
                description = "How many Daily Mixes to generate. Needs enough listening history.",
                valueLabel = "${mixCount.toInt()}",
                value = mixCount,
                valueRange = 1f..3f,
                steps = 1,
                onChange = {
                    mixCount = it
                    engine.dailyMixCount = it.toInt()
                },
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    engine.resetToDefaults()
                    discoveryRatio = engine.discoveryRatio
                    halfLife = engine.recencyHalfLifeDays.toFloat()
                    mixSize = engine.dailyMixSize.toFloat()
                    mixCount = engine.dailyMixCount.toFloat()
                },
            ) {
                Text("Reset to defaults")
            }
                }
            }
        }
    }
}

@Composable
private fun SettingSliderItem(
    title: String,
    description: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueLabel,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}


