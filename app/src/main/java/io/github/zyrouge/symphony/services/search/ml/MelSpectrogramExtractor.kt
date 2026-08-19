package io.github.zyrouge.symphony.services.search.ml

import kotlin.math.*

/**
 * Mel-Spectrogram extractor — a pure Kotlin implementation (no C++/JNI).
 *
 * The parameters match the CLAP model's preprocessor_config.json exactly:
 *   - sampleRate = 48000
 *   - nFft = 1024
 *   - hopLength = 480
 *   - nMels = 64
 *   - fMin = 50 Hz
 *   - fMax = 14000 Hz
 *   - maxFrames = 1001 (equivalent to 10 seconds of audio)
 *
 * Output: a FloatArray shaped [1 x 1 x 1001 x 64] (flattened), ready to feed into ONNX.
 */
class MelSpectrogramExtractor {

    // --- DSP parameters matching the CLAP model ---
    private val sampleRate = 48000
    private val nFft = 1024
    private val hopLength = 480
    private val nMels = 64
    private val fMin = 50.0
    private val fMax = 14000.0
    private val maxFrames = 1001

    // Mel filterbank matrix — computed once and cached
    private val melFilterbank: Array<FloatArray> = buildMelFilterbank()

    // Pre-computed Hann (Hanning) window
    private val hannWindow: FloatArray = FloatArray(nFft) { i ->
        (0.5 * (1 - cos(2.0 * PI * i / nFft))).toFloat()
    }

    /**
     * Converts raw audio samples (PCM float, mono, 48kHz) into a FloatArray
     * flattened to [1, 1, maxFrames, nMels].
     *
     * @param audioSamples mono audio samples with values between [-1, 1]
     * @return a FloatArray ready for ClapModelRunner.getAudioEmbedding()
     */
    fun extract(audioSamples: FloatArray): FloatArray {
        // --- Step 1: apply the STFT ---
        val spectrogram = stft(audioSamples)

        // --- Step 2: compute the power spectrogram (squared magnitude) ---
        val powerSpec = Array(spectrogram.size) { frame ->
            FloatArray(spectrogram[frame].size) { bin ->
                spectrogram[frame][bin] // already turned into power inside stft
            }
        }

        // --- Step 3: multiply by the mel filterbank matrix ---
        val melSpec = applyMelFilterbank(powerSpec)

        // --- Step 4: convert to a logarithmic scale (log-mel) ---
        val logMelSpec = logScale(melSpec)

        // --- Step 5: pad/truncate to 1001 frames ---
        val paddedMel = padOrTruncate(logMelSpec)

        // --- Step 6: flatten into a 1D array [1 x 1 x 1001 x 64] ---
        val output = FloatArray(1 * 1 * maxFrames * nMels)
        for (t in 0 until maxFrames) {
            for (m in 0 until nMels) {
                output[t * nMels + m] = paddedMel[t][m]
            }
        }
        return output
    }

    /**
     * Short-Time Fourier Transform (STFT)
     * Output: an array of frames, each frame holding (nFft/2 + 1) power values.
     */
    private fun stft(samples: FloatArray): Array<FloatArray> {
        val numBins = nFft / 2 + 1
        val numFrames = (samples.size - nFft) / hopLength + 1
        if (numFrames <= 0) {
            return Array(1) { FloatArray(numBins) }
        }

        return Array(numFrames) { frameIdx ->
            val offset = frameIdx * hopLength

            // Take the frame and apply the Hann window
            val realPart = FloatArray(nFft)
            val imagPart = FloatArray(nFft)
            for (i in 0 until nFft) {
                val sampleIdx = offset + i
                realPart[i] = if (sampleIdx < samples.size) {
                    samples[sampleIdx] * hannWindow[i]
                } else {
                    0f
                }
            }

            // FFT (Cooley-Tukey)
            fft(realPart, imagPart)

            // Power spectrum = real^2 + imag^2
            FloatArray(numBins) { k ->
                realPart[k] * realPart[k] + imagPart[k] * imagPart[k]
            }
        }
    }

    /**
     * FFT Radix-2 (Cooley-Tukey) — in-place
     */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        if (n == 1) return

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                var temp = real[i]; real[i] = real[j]; real[j] = temp
                temp = imag[i]; imag[i] = imag[j]; imag[j] = temp
            }
            var m = n / 2
            while (m >= 1 && j >= m) {
                j -= m
                m /= 2
            }
            j += m
        }

        // Butterfly operations
        var step = 2
        while (step <= n) {
            val halfStep = step / 2
            val angleStep = -2.0 * PI / step
            for (k in 0 until halfStep) {
                val angle = k * angleStep
                val wr = cos(angle).toFloat()
                val wi = sin(angle).toFloat()
                var i = k
                while (i < n) {
                    val jIdx = i + halfStep
                    val tr = wr * real[jIdx] - wi * imag[jIdx]
                    val ti = wr * imag[jIdx] + wi * real[jIdx]
                    real[jIdx] = real[i] - tr
                    imag[jIdx] = imag[i] - ti
                    real[i] += tr
                    imag[i] += ti
                    i += step
                }
            }
            step *= 2
        }
    }

    /**
     * Multiplies the power spectrogram by the mel filterbank matrix.
     */
    private fun applyMelFilterbank(powerSpec: Array<FloatArray>): Array<FloatArray> {
        val numFrames = powerSpec.size
        val numBins = powerSpec[0].size

        return Array(numFrames) { t ->
            FloatArray(nMels) { m ->
                var sum = 0f
                val filter = melFilterbank[m]
                val filterLen = minOf(filter.size, numBins)
                for (k in 0 until filterLen) {
                    sum += filter[k] * powerSpec[t][k]
                }
                sum
            }
        }
    }

    /**
     * Converts to a logarithmic scale (power_to_db): 10 * log10(max(value, 1e-10))
     */
    private fun logScale(melSpec: Array<FloatArray>): Array<FloatArray> {
        return Array(melSpec.size) { t ->
            FloatArray(nMels) { m ->
                10f * log10(maxOf(melSpec[t][m], 1e-10f))
            }
        }
    }

    /**
     * Repeat-padding or truncation to exactly maxFrames frames.
     * (Matches the "repeatpad" strategy in the CLAP model's preprocessor_config.json)
     */
    private fun padOrTruncate(melSpec: Array<FloatArray>): Array<FloatArray> {
        if (melSpec.size >= maxFrames) {
            // Truncate
            return Array(maxFrames) { melSpec[it] }
        }

        // Repeat-pad: repeat the existing frames until maxFrames is reached
        return Array(maxFrames) { t ->
            melSpec[t % melSpec.size].copyOf()
        }
    }

    // ===================== Mel Filterbank Builder =====================

    /**
     * Builds the mel filterbank matrix following librosa's formula.
     * Output: an array shaped [nMels x (nFft/2+1)]
     */
    private fun buildMelFilterbank(): Array<FloatArray> {
        val numBins = nFft / 2 + 1

        // Compute the mel points for fMin and fMax
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        // (nMels + 2) evenly spaced points on the mel scale
        val melPoints = FloatArray(nMels + 2) { i ->
            (melMin + i * (melMax - melMin) / (nMels + 1)).toFloat()
        }

        // Convert the mel points into Hz, then into FFT bin indices
        val fftBins = FloatArray(nMels + 2) { i ->
            val hz = melToHz(melPoints[i].toDouble())
            ((nFft + 1) * hz / sampleRate).toFloat()
        }

        // Build the triangular filters
        return Array(nMels) { m ->
            val filterArr = FloatArray(numBins)
            val fLeft = fftBins[m]
            val fCenter = fftBins[m + 1]
            val fRight = fftBins[m + 2]

            for (k in 0 until numBins) {
                val kf = k.toFloat()
                filterArr[k] = when {
                    kf < fLeft -> 0f
                    kf <= fCenter -> (kf - fLeft) / (fCenter - fLeft + 1e-10f)
                    kf <= fRight -> (fRight - kf) / (fRight - fCenter + 1e-10f)
                    else -> 0f
                }
            }

            // Slaney normalization (as in librosa)
            val enorm = 2.0f / (melToHz(melPoints[m + 2].toDouble()).toFloat() - melToHz(melPoints[m].toDouble()).toFloat())
            for (k in 0 until numBins) {
                filterArr[k] *= enorm
            }

            filterArr
        }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
}
