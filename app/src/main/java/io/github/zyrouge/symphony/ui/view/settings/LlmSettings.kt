package io.github.zyrouge.symphony.ui.view.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.services.llm.LlmClient
import io.github.zyrouge.symphony.ui.components.GlassChip
import io.github.zyrouge.symphony.ui.components.GlassSettingsScaffold
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.components.LocalHazeState
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object LlmSettingsRoute

@Composable
fun LlmSettingsView(context: ViewContext) {
    val llm = context.symphony.llm
    val coroutineScope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(llm.baseUrl) }
    var apiKey by remember { mutableStateOf(llm.apiKey) }
    var model by remember { mutableStateOf(llm.model) }
    var mode by remember { mutableStateOf(llm.usageMode) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var mixPromptsTpl by remember { mutableStateOf(llm.mixPromptsSystem) }
    var nameMixTpl by remember { mutableStateOf(llm.nameMixSystem) }
    var chatBehaviorTpl by remember { mutableStateOf(llm.chatBehavior) }
    var chatStructureTpl by remember { mutableStateOf(llm.chatStructure) }

    GlassSettingsScaffold(
        context,
        title = "AI Provider",
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = LocalHazeState.current, zIndex = 1f)
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
            Text(
                "Optional. Generates mix prompts and names your daily mixes. " +
                        "Works with any OpenAI-compatible API (OpenAI, OpenRouter, " +
                        "Groq, DeepSeek, Google Gemini, local Ollama…). " +
                        "The app stays fully offline when unset.",
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
                placeholder = { Text("gemini-2.0-flash") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    LlmClient.UsageMode.Off,
                    LlmClient.UsageMode.Manual,
                    LlmClient.UsageMode.Auto,
                ).forEach { m ->
                    GlassChip(
                        selected = mode == m,
                        onClick = { mode = m; llm.usageMode = m },
                    ) {
                        Text(m.name)
                    }
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
                testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

                }
            }
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Chat personality", style = MaterialTheme.typography.titleMedium)
            Text(
                "Safe to edit: tone, verbosity, opinions, language. " +
                        "Format rules live in the advanced section below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = chatBehaviorTpl,
                onValueChange = { chatBehaviorTpl = it; llm.chatBehavior = it },
                label = { Text("Chat personality") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )
            TextButton(onClick = {
                llm.chatBehavior = ""
                chatBehaviorTpl = llm.chatBehavior
            }) { Text("Reset to default") }

                }
            }
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("⚠️ Advanced — output formats", style = MaterialTheme.typography.titleMedium)
            Text(
                "These define the exact structure the app parses. Breaking them won't " +
                        "crash anything — chat falls back to plain text and tasks may fail — " +
                        "but features stop working until you fix or reset them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = chatStructureTpl,
                onValueChange = { chatStructureTpl = it; llm.chatStructure = it },
                label = { Text("Chat output format") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
            )
            TextButton(onClick = {
                llm.chatStructure = ""
                chatStructureTpl = llm.chatStructure
            }) { Text("Reset to default") }
            OutlinedTextField(
                value = mixPromptsTpl,
                onValueChange = { mixPromptsTpl = it; llm.mixPromptsSystem = it },
                label = { Text("Mix prompt generation") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
            )
            TextButton(onClick = {
                llm.mixPromptsSystem = ""
                mixPromptsTpl = llm.mixPromptsSystem
            }) { Text("Reset to default") }
            OutlinedTextField(
                value = nameMixTpl,
                onValueChange = { nameMixTpl = it; llm.nameMixSystem = it },
                label = { Text("Daily Mix naming") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            TextButton(onClick = {
                llm.nameMixSystem = ""
                nameMixTpl = llm.nameMixSystem
            }) { Text("Reset to default") }
                }
            }
        }
    }
}
