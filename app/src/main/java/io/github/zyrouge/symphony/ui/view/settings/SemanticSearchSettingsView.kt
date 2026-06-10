package io.github.zyrouge.symphony.ui.view.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Psychology
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.zyrouge.symphony.ui.components.AdaptiveSnackbar
import io.github.zyrouge.symphony.ui.components.IconButtonPlaceholder
import io.github.zyrouge.symphony.ui.components.TopAppBarMinimalTitle
import io.github.zyrouge.symphony.ui.components.settings.SettingsSideHeading
import io.github.zyrouge.symphony.ui.components.settings.SettingsSimpleTile
import io.github.zyrouge.symphony.ui.components.settings.SettingsSwitchTile
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object SemanticSearchSettingsViewRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemanticSearchSettingsView(context: ViewContext) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    
    val isSemanticSearchEnabled by context.symphony.settings.isSemanticSearchEnabled.flow.collectAsState()

    val textModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Importing text model...")
                val res = context.symphony.semanticSearch.importModel(uri, false)
                if (res.isSuccess) {
                    snackbarHostState.showSnackbar("Text model imported successfully")
                } else {
                    snackbarHostState.showSnackbar("Failed to import text model")
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
                    snackbarHostState.showSnackbar("Audio model imported successfully")
                } else {
                    snackbarHostState.showSnackbar("Failed to import audio model")
                }
            }
        }
    }

    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Importing JSON database...")
                val res = context.symphony.semanticSearch.importJsonDatabase(uri)
                if (res.isSuccess) {
                    val count = res.getOrNull() ?: 0
                    snackbarHostState.showSnackbar("Successfully imported $count tracks")
                } else {
                    snackbarHostState.showSnackbar("Failed to import JSON: ${res.exceptionOrNull()?.message}")
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(snackbarHostState) {
                AdaptiveSnackbar(it)
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    TopAppBarMinimalTitle {
                        Text("${context.symphony.t.Settings} - AI Search")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(
                        onClick = {
                            context.navController.popBackStack()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButtonPlaceholder()
                },
            )
        },
        content = { contentPadding ->
            Box(
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxSize()
            ) {
                Column(modifier = Modifier.verticalScroll(scrollState)) {
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
                        
                        SettingsSimpleTile(
                            icon = {
                                Icon(Icons.Filled.Psychology, null)
                            },
                            title = {
                                Text("Import Text Model (.onnx)")
                            },
                            onClick = {
                                textModelLauncher.launch(arrayOf("*/*"))
                            }
                        )
                        
                        HorizontalDivider()
                        SettingsSimpleTile(
                            icon = {
                                Icon(Icons.Filled.Psychology, null)
                            },
                            title = {
                                Text("Import Audio Model (.onnx)")
                            },
                            onClick = {
                                audioModelLauncher.launch(arrayOf("*/*"))
                            }
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
                    }
                }
            }
        }
    )
}
