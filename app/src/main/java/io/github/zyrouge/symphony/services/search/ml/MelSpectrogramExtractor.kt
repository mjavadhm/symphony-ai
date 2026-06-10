package io.github.zyrouge.symphony.services.search.ml

import kotlin.math.*

/**
 * استخراج‌کننده Mel-Spectrogram — پیاده‌سازی خالص کاتلین (بدون C++/JNI).
 *
 * پارامترها دقیقاً منطبق با preprocessor_config.json مدل CLAP هستند:
 *   - sampleRate = 48000
 *   - nFft = 1024
 *   - hopLength = 480
 *   - nMels = 64
 *   - fMin = 50 Hz
 *   - fMax = 14000 Hz
 *   - maxFrames = 1001 (معادل ۱۰ ثانیه صدا)
 *
 * خروجی: FloatArray با ابعاد [1 × 1 × 1001 × 64] (فلت‌شده) آماده تغذیه به ONNX.
 */
class MelSpectrogramExtractor {

    // --- پارامترهای DSP منطبق با مدل CLAP ---
    private val sampleRate = 48000
    private val nFft = 1024
    private val hopLength = 480
    private val nMels = 64
    private val fMin = 50.0
    private val fMax = 14000.0
    private val maxFrames = 1001

    // ماتریس Mel Filterbank — یک بار محاسبه شده و کش می‌شود
    private val melFilterbank: Array<FloatArray> = buildMelFilterbank()

    // پنجره Hann (Hanning Window) از پیش‌محاسبه‌شده
    private val hannWindow: FloatArray = FloatArray(nFft) { i ->
        (0.5 * (1 - cos(2.0 * PI * i / nFft))).toFloat()
    }

    /**
     * تبدیل آرایه نمونه‌های صوتی خام (PCM float، مونو، 48kHz)
     * به FloatArray با ابعاد فلت‌شده [1, 1, maxFrames, nMels].
     *
     * @param audioSamples نمونه‌های صوتی مونو با مقادیر بین [-1, 1]
     * @return FloatArray آماده برای ClapModelRunner.getAudioEmbedding()
     */
    fun extract(audioSamples: FloatArray): FloatArray {
        // --- مرحله ۱: اعمال STFT ---
        val spectrogram = stft(audioSamples)

        // --- مرحله ۲: محاسبه Power Spectrogram (مربع مقدار مطلق) ---
        val powerSpec = Array(spectrogram.size) { frame ->
            FloatArray(spectrogram[frame].size) { bin ->
                spectrogram[frame][bin] // قبلاً power شده در stft
            }
        }

        // --- مرحله ۳: ضرب در ماتریس Mel Filterbank ---
        val melSpec = applyMelFilterbank(powerSpec)

        // --- مرحله ۴: تبدیل به مقیاس لگاریتمی (Log-Mel) ---
        val logMelSpec = logScale(melSpec)

        // --- مرحله ۵: Padding/Truncation به 1001 فریم ---
        val paddedMel = padOrTruncate(logMelSpec)

        // --- مرحله ۶: فلت کردن به آرایه یک‌بعدی [1 × 1 × 1001 × 64] ---
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
     * خروجی: آرایه‌ای از فریم‌ها، هر فریم شامل (nFft/2 + 1) مقدار Power.
     */
    private fun stft(samples: FloatArray): Array<FloatArray> {
        val numBins = nFft / 2 + 1
        val numFrames = (samples.size - nFft) / hopLength + 1
        if (numFrames <= 0) {
            return Array(1) { FloatArray(numBins) }
        }

        return Array(numFrames) { frameIdx ->
            val offset = frameIdx * hopLength

            // استخراج فریم و اعمال پنجره Hann
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

            // محاسبه Power Spectrum = real^2 + imag^2
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
     * ضرب Power Spectrogram در ماتریس Mel Filterbank.
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
     * تبدیل به مقیاس لگاریتمی (power_to_db): 10 * log10(max(value, 1e-10))
     */
    private fun logScale(melSpec: Array<FloatArray>): Array<FloatArray> {
        return Array(melSpec.size) { t ->
            FloatArray(nMels) { m ->
                10f * log10(maxOf(melSpec[t][m], 1e-10f))
            }
        }
    }

    /**
     * Repeat-Padding یا Truncation به تعداد maxFrames فریم.
     * (مطابق استراتژی "repeatpad" در preprocessor_config.json مدل CLAP)
     */
    private fun padOrTruncate(melSpec: Array<FloatArray>): Array<FloatArray> {
        if (melSpec.size >= maxFrames) {
            // Truncate
            return Array(maxFrames) { melSpec[it] }
        }

        // Repeat-pad: فریم‌های موجود را تکرار می‌کنیم تا به maxFrames برسیم
        return Array(maxFrames) { t ->
            melSpec[t % melSpec.size].copyOf()
        }
    }

    // ===================== Mel Filterbank Builder =====================

    /**
     * ساخت ماتریس فیلتربانک Mel مطابق با فرمول librosa.
     * خروجی: آرایه‌ای [nMels × (nFft/2+1)]
     */
    private fun buildMelFilterbank(): Array<FloatArray> {
        val numBins = nFft / 2 + 1

        // محاسبه نقاط Mel برای fMin و fMax
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        // (nMels + 2) نقطه‌ی با فاصله یکسان روی مقیاس Mel
        val melPoints = FloatArray(nMels + 2) { i ->
            (melMin + i * (melMax - melMin) / (nMels + 1)).toFloat()
        }

        // تبدیل نقاط Mel به فرکانس Hz و سپس به اندیس FFT
        val fftBins = FloatArray(nMels + 2) { i ->
            val hz = melToHz(melPoints[i].toDouble())
            ((nFft + 1) * hz / sampleRate).toFloat()
        }

        // ساخت فیلترهای مثلثی
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

            // نرمال‌سازی Slaney (مطابق librosa)
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
