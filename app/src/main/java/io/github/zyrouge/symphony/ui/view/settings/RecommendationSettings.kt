package io.github.zyrouge.symphony.ui.view.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import io.github.zyrouge.symphony.services.llm.LlmClient
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationSettings(context: ViewContext) {
    val engine = context.symphony.recommendation

    var discoveryRatio by remember { mutableStateOf(engine.discoveryRatio) }
    var halfLife by remember { mutableStateOf(engine.recencyHalfLifeDays.toFloat()) }
    var mixSize by remember { mutableStateOf(engine.dailyMixSize.toFloat()) }
    var mixCount by remember { mutableStateOf(engine.dailyMixCount.toFloat()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Settings") },
                navigationIcon = {
                    IconButton(onClick = { context.navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
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
            LlmSettingsSection(context)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlmSettingsSection(context: ViewContext) {
    val llm = context.symphony.llm
    val coroutineScope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(llm.baseUrl) }
    var apiKey by remember { mutableStateOf(llm.apiKey) }
    var model by remember { mutableStateOf(llm.model) }
    var mode by remember { mutableStateOf(llm.usageMode) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI Provider (LLM)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Optional. Generates mix prompts and names your daily mixes. " +
                    "Works with any OpenAI-compatible API " +
                    "(OpenAI, OpenRouter, Groq, DeepSeek, local Ollama…). " +
                    "Everything is saved on device and the app stays fully offline when unset.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; llm.baseUrl = it },
            label = { Text("Base URL") },
            placeholder = { Text("https://openrouter.ai/api/v1") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; llm.apiKey = it },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; llm.model = it },
            label = { Text("Model") },
            placeholder = { Text("gpt-4o-mini") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                LlmClient.UsageMode.Off,
                LlmClient.UsageMode.Manual,
                LlmClient.UsageMode.Auto,
            ).forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { mode = m; llm.usageMode = m },
                    label = { Text(m.name) },
                )
            }
        }
        Text(
            "Off: never used · Manual: only when you press ✨ · " +
                    "Auto: also names Daily Mixes (sends song titles to the provider)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !isTesting,
                onClick = {
                    coroutineScope.launch {
                        isTesting = true
                        testResult = null
                        testResult = when (val r = llm.testConnection()) {
                            is LlmClient.Result.Success -> "✅ Connected"
                            is LlmClient.Result.Error -> "❌ ${r.message}"
                        }
                        isTesting = false
                    }
                },
            ) { Text(if (isTesting) "Testing…" else "Test connection") }
            Spacer(modifier = Modifier.width(12.dp))
            testResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
