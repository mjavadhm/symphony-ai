package io.github.zyrouge.symphony.services.search.ml

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class ModelManager(private val context: Context) {

    private val modelsDir = File(context.filesDir, "models").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    val audioModelFile = File(modelsDir, "clap_audio_encoder_int8.onnx")
    val textModelFile = File(modelsDir, "clap_text_encoder_int8.onnx")

    /**
     * بررسی می‌کند که آیا هر دو مدل نصب شده‌اند یا خیر.
     */
    fun areModelsImported(): Boolean {
        return audioModelFile.exists() && textModelFile.exists()
    }

    /**
     * فایل انتخاب شده توسط کاربر را در حافظه داخلی کپی می‌کند.
     */
    fun importModel(uri: Uri, isAudio: Boolean): Result<Unit> {
        return try {
            val targetFile = if (isAudio) audioModelFile else textModelFile
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return Result.failure(Exception("Cannot open input stream for the selected file."))
            
            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
