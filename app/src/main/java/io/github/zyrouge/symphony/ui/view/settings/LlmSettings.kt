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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.llm.LlmClient
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object LlmSettingsRoute

@OptIn(ExperimentalMaterial3Api::class)
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
    var discoverTpl by remember { mutableStateOf(llm.discoverChatSystem) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Provider") },
                navigationIcon = {
                    IconButton(onClick = { context.navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
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
                testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            Divider()

            Text("Task prompts (advanced)", style = MaterialTheme.typography.titleMedium)
            Text(
                "These system prompts are sent to the model for each task. " +
                        "Edit them to change the style of the results. " +
                        "Keep the JSON instruction, or parsing will fail.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            OutlinedTextField(
                value = discoverTpl,
                onValueChange = { discoverTpl = it; llm.discoverChatSystem = it },
                label = { Text("Discover chat") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
            )
            TextButton(onClick = {
                llm.discoverChatSystem = ""
                discoverTpl = llm.discoverChatSystem
            }) { Text("Reset to default") }
        }
    }
}
