package io.github.zyrouge.symphony.services.search.ml

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

data class ImportedModelInfo(
    val fileName: String,
    val fileSizeBytes: Long,
    val importedAt: Long,
)

class ModelManager(private val context: Context) {

    private val modelsDir = File(context.filesDir, "models").apply {
        if (!exists()) mkdirs()
    }
    private val prefs = context.getSharedPreferences("ai_model_info", Context.MODE_PRIVATE)

    val audioModelFile = File(modelsDir, "clap_audio_encoder_int8.onnx")
    val textModelFile = File(modelsDir, "clap_text_encoder_int8.onnx")

    fun areModelsImported(): Boolean {
        return audioModelFile.exists() && textModelFile.exists()
    }

    fun getModelInfo(isAudio: Boolean): ImportedModelInfo? {
        val target = if (isAudio) audioModelFile else textModelFile
        if (!target.exists()) return null
        val key = if (isAudio) "audio" else "text"
        return ImportedModelInfo(
            fileName = prefs.getString("${key}_name", null) ?: target.name,
            fileSizeBytes = prefs.getLong("${key}_size", target.length()),
            importedAt = prefs.getLong("${key}_at", 0L),
        )
    }

    fun deleteModel(isAudio: Boolean) {
        (if (isAudio) audioModelFile else textModelFile).delete()
        val key = if (isAudio) "audio" else "text"
        prefs.edit()
            .remove("${key}_name").remove("${key}_size").remove("${key}_at")
            .apply()
    }

    fun importModel(uri: Uri, isAudio: Boolean): Result<ImportedModelInfo> {
        val tempFile = File(modelsDir, "import_temp.onnx")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: return Result.failure(Exception("Cannot open the selected file."))

            // ✅ Validation 1: is this actually a valid ONNX file?
            val inputNames: Set<String> = try {
                OrtEnvironment.getEnvironment()
                    .createSession(tempFile.absolutePath)
                    .use { it.inputNames }
            } catch (e: Throwable) {
                tempFile.delete()
                return Result.failure(Exception("Not a valid ONNX model file."))
            }

            // ✅ Validation 2: is the right model going into the right slot?
            // The text encoder has tokenizer inputs (input_ids/attention_mask); the audio one does not.
            android.util.Log.d("ModelManager", "ONNX inputs: $inputNames")
            val looksLikeText = inputNames.any {
                it.contains("input_ids", true) || it.contains("attention", true)
            }
            if (isAudio && looksLikeText) {
                tempFile.delete()
                return Result.failure(Exception("This looks like the TEXT encoder. Import it as Text model."))
            }
            if (!isAudio && !looksLikeText) {
                tempFile.delete()
                return Result.failure(Exception("This looks like the AUDIO encoder. Import it as Audio model."))
            }

            val targetFile = if (isAudio) audioModelFile else textModelFile
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val info = ImportedModelInfo(
                fileName = queryDisplayName(uri) ?: targetFile.name,
                fileSizeBytes = targetFile.length(),
                importedAt = System.currentTimeMillis(),
            )
            val key = if (isAudio) "audio" else "text"
            prefs.edit()
                .putString("${key}_name", info.fileName)
                .putLong("${key}_size", info.fileSizeBytes)
                .putLong("${key}_at", info.importedAt)
                .apply()
            Result.success(info)
        } catch (e: Throwable) {
            tempFile.delete()
            e.printStackTrace()
            Result.failure(Exception(e))
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }
}
