package io.github.zyrouge.symphony

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.diagnostics.Diagnostics
import io.github.zyrouge.symphony.services.diagnostics.DiagnosticPrivacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs separately so a dead main-thread looper cannot prevent report export. No Symphony/Room dependency. */
class ErrorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = intent?.getStringExtra(KEY_ERROR_MESSAGE) ?: "Unexpected error"
        val stack = intent?.getStringExtra(KEY_ERROR_STACK_TRACE) ?: "Open Diagnostics after restarting to inspect retained reports."
        setContent {
            MaterialTheme {
                val clipboard = LocalClipboardManager.current
                val scope = rememberCoroutineScope()
                var status by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                    if (uri != null) scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) {
                                contentResolver.openOutputStream(uri)?.use { Diagnostics.export(it) } ?: error("Cannot open file")
                            }
                            status = "Report exported"
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { status = "Could not export. Check file access and storage space." }
                        finally { busy = false }
                    }
                }
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Something went wrong", style = MaterialTheme.typography.headlineMedium)
                        Text("A report was saved if local diagnostics are enabled and storage is available. No screenshot is needed.")
                        Text(message, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString("$message\n$stack")); status = "Details copied" }) { Text("Copy details") }
                            TextButton(onClick = { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "$message\n$stack"), "Share crash details")) }) { Text("Share") }
                        }
                        Button(enabled = !busy, onClick = { export.launch("symphony-crash-report.zip") }) { Text("Export full report") }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (status.isNotBlank()) Text(status)
                        Text(stack, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    companion object {
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_ERROR_STACK_TRACE = "error_stack_trace"
        fun start(context: Context, error: Throwable) {
            val intent = Intent(context, ErrorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(KEY_ERROR_MESSAGE, error.javaClass.name)
                .putExtra(KEY_ERROR_STACK_TRACE, DiagnosticPrivacy.throwable(error))
            context.startActivity(intent)
        }
    }
}
