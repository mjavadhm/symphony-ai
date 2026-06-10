package io.github.zyrouge.symphony.services.search.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer

class ClapModelRunner(context: Context) {
    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private val audioSession: OrtSession
    private val textSession: OrtSession

    private val modelManager = ModelManager(context)

    init {
        if (!modelManager.areModelsImported()) {
            throw IllegalStateException("Models are not imported yet.")
        }
        
        // Load ONNX models from internal filesDir
        audioSession = ortEnvironment.createSession(modelManager.audioModelFile.absolutePath, OrtSession.SessionOptions())
        textSession = ortEnvironment.createSession(modelManager.textModelFile.absolutePath, OrtSession.SessionOptions())
    }

    /**
     * Extracts embedding from a pre-processed mel-spectrogram.
     * @param melSpectrogram Flattened float array of shape [1, 1, time_frames, 64]
     * @param timeFrames The number of frames (e.g. 1001 for 10 seconds)
     */
    fun getAudioEmbedding(melSpectrogram: FloatArray, timeFrames: Long = 1001): FloatArray {
        val shape = longArrayOf(1, 1, timeFrames, 64)
        val tensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(melSpectrogram), shape)
        val inputs = mapOf("input_features" to tensor)
        
        audioSession.run(inputs).use { result ->
            val outputTensor = result.get(0) as OnnxTensor
            @Suppress("UNCHECKED_CAST")
            val outputArray = (outputTensor.value as Array<FloatArray>)[0]
            
            return normalize(outputArray)
        }
    }

    /**
     * Extracts embedding from text input IDs and attention mask.
     * @param inputIds LongArray of shape [1, sequenceLength]
     * @param attentionMask LongArray of shape [1, sequenceLength]
     */
    fun getTextEmbedding(inputIds: LongArray, attentionMask: LongArray): FloatArray {
        val shape = longArrayOf(1, inputIds.size.toLong())
        val inputIdsTensor = OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(inputIds), shape)
        val attentionMaskTensor = OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(attentionMask), shape)
        
        val inputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attentionMaskTensor
        )
        
        textSession.run(inputs).use { result ->
            val outputTensor = result.get(0) as OnnxTensor
            @Suppress("UNCHECKED_CAST")
            val outputArray = (outputTensor.value as Array<FloatArray>)[0]
            
            return normalize(outputArray)
        }
    }

    private fun normalize(array: FloatArray): FloatArray {
        var norm = 0f
        for (v in array) {
            norm += v * v
        }
        norm = kotlin.math.sqrt(norm)
        if (norm == 0f) return array
        return FloatArray(array.size) { array[it] / norm }
    }
}
