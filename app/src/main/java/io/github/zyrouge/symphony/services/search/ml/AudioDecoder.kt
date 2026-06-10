package io.github.zyrouge.symphony.services.search.ml

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.floor

class AudioDecoder(private val context: Context) {

    data class AudioChunk(
        val floatArray: FloatArray,
        val offsetSeconds: Int
    )

    fun extractChunks(uri: Uri): List<AudioChunk> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            Log.e("AudioDecoder", "Failed to set data source for $uri", e)
            return emptyList()
        }

        var trackIndex = -1
        var sampleRate = 48000
        var channelCount = 1
        var durationUs = 0L

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                durationUs = format.getLong(MediaFormat.KEY_DURATION)
                break
            }
        }

        if (trackIndex < 0) {
            extractor.release()
            return emptyList()
        }

        val durationSeconds = (durationUs / 1000000).toInt()
        val chunkLength = 30
        val offsets = mutableListOf<Int>()
        for (i in 0 until durationSeconds step chunkLength) {
            offsets.add(i)
        }

        if (offsets.size > 20) {
            Log.d("AudioDecoder", "Song has ${offsets.size} parts. Limiting to first 20.")
            offsets.retainAll(offsets.take(20).toSet())
        }

        val results = mutableListOf<AudioChunk>()

        for (offset in offsets) {
            if (durationSeconds - offset < 10 && offsets.size > 1) {
                continue
            }

            Log.d("AudioDecoder", "Decoding offset $offset for $chunkLength seconds")
            val chunkFloats = decodeChunk(extractor, trackIndex, offset, chunkLength, channelCount)
            if (chunkFloats != null && chunkFloats.isNotEmpty()) {
                val resampled = resampleTo48k(chunkFloats, sampleRate)
                results.add(AudioChunk(resampled, offset))
            }
        }

        extractor.release()
        return results
    }
    
    fun getDurationSeconds(uri: Uri): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    extractor.release()
                    return (durationUs / 1000000).toInt()
                }
            }
        } catch (e: Exception) {
            Log.e("AudioDecoder", "Failed to get duration", e)
        }
        extractor.release()
        return 0
    }

    private fun decodeChunk(
        extractor: MediaExtractor,
        trackIndex: Int,
        offsetSeconds: Int,
        durationSeconds: Int,
        channelCount: Int
    ): FloatArray? {
        extractor.selectTrack(trackIndex)
        extractor.seekTo(offsetSeconds * 1000000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

        val codec: MediaCodec
        try {
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            Log.e("AudioDecoder", "Failed to configure codec", e)
            extractor.unselectTrack(trackIndex)
            return null
        }

        val outputFloats = mutableListOf<Float>()
        var isEOS = false
        var outputEOS = false
        val bufferInfo = MediaCodec.BufferInfo()
        val endTimeUs = (offsetSeconds + durationSeconds) * 1000000L

        try {
            while (!outputEOS) {
                if (!isEOS) {
                    val inputBufferId = codec.dequeueInputBuffer(10000)
                    if (inputBufferId >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferId)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            val presentationTimeUs = extractor.sampleTime

                            if (sampleSize < 0 || presentationTimeUs >= endTimeUs) {
                                codec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEOS = true
                            } else {
                                codec.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferId >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferId)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val floatsThisBuffer = FloatArray(shortBuffer.remaining() / channelCount)

                        for (i in floatsThisBuffer.indices) {
                            var sum = 0f
                            for (c in 0 until channelCount) {
                                val sample = shortBuffer.get()
                                sum += sample / 32768.0f
                            }
                            floatsThisBuffer[i] = sum / channelCount
                        }

                        outputFloats.addAll(floatsThisBuffer.toList())
                    }
                    codec.releaseOutputBuffer(outputBufferId, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEOS = true
                    }
                } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Ignore format changes for now
                } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Wait
                }
            }
        } catch (e: Exception) {
            Log.e("AudioDecoder", "Error during decoding", e)
        } finally {
            codec.stop()
            codec.release()
            extractor.unselectTrack(trackIndex)
        }

        return outputFloats.toFloatArray()
    }

    private fun resampleTo48k(input: FloatArray, originalSampleRate: Int): FloatArray {
        if (originalSampleRate == 48000) return input

        val ratio = 48000.0 / originalSampleRate
        val outLen = (input.size * ratio).toInt()
        val output = FloatArray(outLen)

        for (i in 0 until outLen) {
            val position = i / ratio
            val index = floor(position).toInt()
            val fraction = (position - index).toFloat()

            if (index >= input.size - 1) {
                output[i] = input.last()
            } else {
                output[i] = input[index] * (1 - fraction) + input[index + 1] * fraction
            }
        }
        return output
    }
}
