package io.github.zyrouge.symphony.ui.view.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Waves
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.zyrouge.symphony.ui.components.AdaptiveSnackbar
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.ui.components.GlassSettingsScaffold
import io.github.zyrouge.symphony.ui.components.IconButtonPlaceholder
import io.github.zyrouge.symphony.ui.components.LocalHazeState
import io.github.zyrouge.symphony.ui.components.TopAppBarMinimalTitle
import io.github.zyrouge.symphony.ui.components.settings.SettingsSideHeading
import io.github.zyrouge.symphony.ui.components.settings.SettingsSwitchTile
import io.github.zyrouge.symphony.ui.components.settings.SettingsSimpleTile
import io.github.zyrouge.symphony.ui.components.ModelStatusCard
import androidx.compose.runtime.mutableStateOf
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import androidx.compose.foundation.layout.fillMaxWidth

@Serializable
object SemanticSearchSettingsViewRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemanticSearchSettingsView(context: ViewContext) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    
    val isSemanticSearchEnabled by context.symphony.settings.isSemanticSearchEnabled.flow.collectAsState()

    var audioInfo by remember { mutableStateOf(context.symphony.semanticSearch.getModelInfo(true)) }
    var textInfo by remember { mutableStateOf(context.symphony.semanticSearch.getModelInfo(false)) }

    val textModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Importing text model...")
                val res = context.symphony.semanticSearch.importModel(uri, false)
                if (res.isSuccess) {
                    textInfo = context.symphony.semanticSearch.getModelInfo(false)
                    snackbarHostState.showSnackbar("Text model imported successfully")
                    context.symphony.semanticSearch.initializeEngine()
                } else {
                    snackbarHostState.showSnackbar("Failed: ${res.exceptionOrNull()?.message}")
                }
            }
        }
    }

    val audioModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Importing audio model...")
                val res = context.symphony.semanticSearch.importModel(uri, true)
                if (res.isSuccess) {
                    audioInfo = context.symphony.semanticSearch.getModelInfo(true)
                    snackbarHostState.showSnackbar("Audio model imported successfully")
                    context.symphony.semanticSearch.initializeEngine()
                } else {
                    snackbarHostState.showSnackbar("Failed: ${res.exceptionOrNull()?.message}")
                }
            }
        }
    }

    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) context.symphony.semanticSearch.startJsonImport(uri)
    }

    val jsonImportState by context.symphony.semanticSearch.jsonImportState.collectAsState()
    val jsonExportState by context.symphony.semanticSearch.jsonExportState.collectAsState()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { context.symphony.semanticSearch.startJsonExport(it) }
    }

    GlassSettingsScaffold(
        context = context,
        title = "${context.symphony.t.Settings} - AI Search",
        snackbarHost = {
            SnackbarHost(snackbarHostState) {
                AdaptiveSnackbar(it)
            }
        },
        content = { contentPadding ->
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = LocalHazeState.current, zIndex = 1f)
                        .verticalScroll(scrollState)
                        .padding(contentPadding)
                ) {
                    SettingsSideHeading("Symphony Search (AI)")
                    
                    SettingsSwitchTile(
                        icon = {
                            Icon(Icons.Filled.AutoAwesome, null)
                        },
                        title = {
                            Text("Enable AI Search")
                        },
                        value = isSemanticSearchEnabled,
                        onChange = { value ->
                            context.symphony.settings.isSemanticSearchEnabled.setValue(value)
                            if (value) {
                                context.symphony.semanticSearch.initializeEngine()
                            }
                        }
                    )
                    
                    if (isSemanticSearchEnabled) {
                        HorizontalDivider()
                        SettingsSideHeading("Models & Data")
                        
                        ModelStatusCard(
                            title = "Audio Encoder (CLAP)",
                            info = audioInfo,
                            onImport = { audioModelLauncher.launch(arrayOf("*/*")) },
                            onDelete = {
                                context.symphony.semanticSearch.deleteModel(true)
                                audioInfo = null
                            },
                        )
                        ModelStatusCard(
                            title = "Text Encoder (RoBERTa)",
                            info = textInfo,
                            onImport = { textModelLauncher.launch(arrayOf("*/*")) },
                            onDelete = {
                                context.symphony.semanticSearch.deleteModel(false)
                                textInfo = null
                            },
                        )
                        
                        HorizontalDivider()
                        SettingsSimpleTile(
                            icon = {
                                Icon(Icons.Filled.DataObject, null)
                            },
                            title = {
                                Text("Import music_embeddings.json")
                            },
                            onClick = {
                                jsonLauncher.launch(arrayOf("application/json", "*/*"))
                            }
                        )
                        SettingsSimpleTile(
                            icon = { Icon(Icons.Filled.Upload, null) },
                            title = { Text("Export AI Index") },
                            onClick = {
                                exportLauncher.launch("symphony-ai-index.json")
                            }
                        )
                        if (jsonExportState.isActive || jsonExportState.text.isNotEmpty()) {
                            androidx.compose.material3.Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                androidx.compose.foundation.layout.Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    if (jsonExportState.isActive) {
                                        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(12.dp))
                                    }
                                    Text(jsonExportState.text, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        if (jsonImportState.isActive || jsonImportState.text.isNotEmpty()) {
                            androidx.compose.material3.Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    if (jsonImportState.isActive) {
                                        androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                                        Text("Imported: ${jsonImportState.count} tracks")
                                    }
                                    Text(
                                        jsonImportState.text,
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        val isSemanticSearchReady by context.symphony.semanticSearch.isReady.collectAsState()
                        if (isSemanticSearchReady) {
                            HorizontalDivider()
                            SettingsSimpleTile(
                                icon = {
                                    Icon(Icons.Filled.AutoAwesome, null)
                                },
                                title = {
                                    Text("Manage AI Index")
                                },
                                onClick = {
                                    context.navController.navigate(io.github.zyrouge.symphony.ui.view.settings.IndexSongsSettingsRoute)
                                }
                            )
                            androidx.compose.material3.ListItem(
                                modifier = Modifier.clickable {
                                    context.navController.navigate(LibraryStatsRoute)
                                },
                                leadingContent = { Text("📊") },
                                headlineContent = { Text("Library Stats") },
                                supportingContent = { Text("Indexed songs, Flow data, history and more") },
                            )
                        }
                    }

                    HorizontalDivider()
                    SettingsSideHeading("Flow Transitions")
                    val flowScanState by context.symphony.flow.scanState.collectAsState()
                    SettingsSimpleTile(
                        icon = { Icon(Icons.Filled.Waves, null) },
                        title = {
                            Text(
                                if (flowScanState.isActive)
                                    "Analyzing… ${flowScanState.done}/${flowScanState.total} (tap to cancel)"
                                else "Analyze songs for Flow"
                            )
                        },
                        onClick = {
                            if (flowScanState.isActive) context.symphony.flow.stopScan()
                            else context.symphony.flow.startScan()
                        }
                    )
                    if (flowScanState.isActive || flowScanState.text.isNotEmpty()) {
                        androidx.compose.material3.Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                if (flowScanState.isActive) {
                                    androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                                }
                                Text(
                                    flowScanState.text,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}
