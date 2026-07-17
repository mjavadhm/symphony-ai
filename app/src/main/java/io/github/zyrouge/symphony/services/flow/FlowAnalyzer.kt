package io.github.zyrouge.symphony.services.flow

import android.net.Uri
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.database.entities.TrackFlow
import io.github.zyrouge.symphony.services.search.ml.AudioDecoder
import io.github.zyrouge.symphony.services.search.ml.MelSpectrogramExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Flow — تحلیل آکوستیک ۵ ثانیه اول و آخر هر آهنگ برای پیدا کردن
 * نرمترین ترنزیشن بین آهنگها. بدون مدل AI — فقط DSP خالص.
 */
class FlowAnalyzer(private val symphony: Symphony) {
    data class ScanState(
        val isActive: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val text: String = "",
    )

    val scanState = MutableStateFlow(ScanState())
    private var scanJob: Job? = null
    private val mel = MelSpectrogramExtractor()

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val SEGMENT_SECONDS = 5

        // وزن هر ویژگی در فاصلهی ترنزیشن
        private const val W_CENTROID = 0.35f
        private const val W_ENERGY = 0.30f
        private const val W_ROLLOFF = 0.20f
        private const val W_ONSET = 0.15f
    }

    // ==================== اسکن پسزمینه ====================

    fun startScan() {
        if (scanState.value.isActive) return
        scanJob = symphony.groove.coroutineScope.launch(Dispatchers.IO) {
            try {
                scan()
            } catch (err: Exception) {
                scanState.value = ScanState(text = "Failed: ${err.message}")
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanState.value = ScanState(text = "Cancelled")
    }

    private suspend fun scan() {
        val analyzed = symphony.database.trackFlow.getAnalyzedSongIds().toHashSet()
        val pending = symphony.groove.song.all.value.filter { it !in analyzed }
        if (pending.isEmpty()) {
            scanState.value = ScanState(text = "All songs are already analyzed")
            return
        }
        val decoder = AudioDecoder(symphony.applicationContext)
        var done = 0
        scanState.value = ScanState(isActive = true, total = pending.size)
        for (songId in pending) {
            if (!currentCoroutineContext().isActive) return
            val song = symphony.groove.song.get(songId)
            if (song != null) {
                try {
                    analyzeSong(decoder, song.id, song.uri)?.let {
                        symphony.database.trackFlow.upsert(it)
                    }
                } catch (_: Exception) {
                    // فایل خراب — رد شو
                }
            }
            done++
            scanState.value = ScanState(
                isActive = true,
                done = done,
                total = pending.size,
                text = song?.title ?: "",
            )
        }
        scanState.value = ScanState(done = done, total = pending.size, text = "Done — $done songs analyzed")
    }

    private fun analyzeSong(decoder: AudioDecoder, songId: String, uri: Uri): TrackFlow? {
        val duration = decoder.getDurationSeconds(uri)
        if (duration < 20) return null // خیلی کوتاهه، ترنزیشن معنی نداره

        // ۱۰ ثانیه اول و ۱۲ ثانیه آخر رو decode میکنیم (با حاشیه برای سکوت/fade)
        val headRaw = decoder.decodeRange(uri, 0, 10) ?: return null
        val tailRaw = decoder.decodeRange(uri, maxOf(0, duration - 12), 12) ?: return null

        // بخش «واقعاً شنیدنی» رو جدا میکنیم (ردشدن از سکوت و fade-out)
        val head = audibleSegment(headRaw, fromStart = true) ?: return null
        val tail = audibleSegment(tailRaw, fromStart = false) ?: return null

        val h = fingerprint(head)
        val t = fingerprint(tail)
        return TrackFlow(
            songId = songId,
            headEnergy = h.energy, headCentroid = h.centroid,
            headRolloff = h.rolloff, headOnset = h.onset,
            tailEnergy = t.energy, tailCentroid = t.centroid,
            tailRolloff = t.rolloff, tailOnset = t.onset,
            analyzedAt = System.currentTimeMillis(),
        )
    }

    // ==================== DSP ====================

    /**
     * ۵ ثانیه «شنیدنی» رو برمیگردونه:
     * - fromStart=true: از اولین جای غیرساکت به بعد (ردشدن از سکوت اول)
     * - fromStart=false: تا آخرین جای غیرساکت (ردشدن از fade-out و سکوت آخر)
     */
    private fun audibleSegment(samples: FloatArray, fromStart: Boolean): FloatArray? {
        val win = SAMPLE_RATE / 4 // پنجرههای ۰.۲۵ ثانیهای
        val n = samples.size / win
        if (n == 0) return null
        val rms = FloatArray(n) { i ->
            var sum = 0.0
            for (j in i * win until (i + 1) * win) sum += samples[j] * samples[j]
            sqrt(sum / win).toFloat()
        }
        val maxRms = rms.max()
        if (maxRms < 1e-4f) return null // کل بازه ساکته
        val threshold = maxRms * 0.05f
        val segLen = SAMPLE_RATE * SEGMENT_SECONDS
        return if (fromStart) {
            val first = rms.indexOfFirst { it > threshold }
            if (first < 0) return null
            val start = (first * win).coerceAtMost(maxOf(0, samples.size - segLen))
            samples.copyOfRange(start, minOf(samples.size, start + segLen))
        } else {
            val last = rms.indexOfLast { it > threshold }
            if (last < 0) return null
            val end = ((last + 1) * win).coerceAtLeast(minOf(samples.size, segLen))
            samples.copyOfRange(maxOf(0, end - segLen), end)
        }
    }

    private data class Fingerprint(
        val energy: Float,
        val centroid: Float,
        val rolloff: Float,
        val onset: Float,
    )

    /** چهار ویژگی آکوستیک، همه نرمالشده بین ۰ و ۱ */
    private fun fingerprint(samples: FloatArray): Fingerprint {
        // --- انرژی: مستقیم از خود نمونهها (RMS) ---
        var sq = 0.0
        for (s in samples) sq += s * s
        val rmsVal = sqrt(sq / samples.size)
        val energy = ((log10(rmsVal + 1e-6) + 3.0) / 3.0).toFloat().coerceIn(0f, 1f)

        // --- بقیه از mel spectrogram (از FFT موجود پروژه استفاده میکنیم) ---
        val nMels = 64
        // فقط فریمهای واقعی، نه فریمهای repeat-pad شده
        val realFrames = minOf(1001, maxOf(1, (samples.size - 1024) / 480 + 1))
        val spec = mel.extract(samples) // dB، فلتشده [1001 × 64]

        var centroidNum = 0.0
        var totalPower = 0.0
        var rolloffSum = 0.0
        val framePower = DoubleArray(realFrames)
        for (t in 0 until realFrames) {
            var frameTotal = 0.0
            val linear = DoubleArray(nMels)
            for (m in 0 until nMels) {
                val p = 10.0.pow(spec[t * nMels + m] / 10.0) // dB → power
                linear[m] = p
                frameTotal += p
                centroidNum += m * p
            }
            framePower[t] = frameTotal
            totalPower += frameTotal
            // rolloff: کوچکترین باندی که ۸۵٪ انرژی فریم زیرشه
            var cum = 0.0
            var r = nMels - 1
            for (m in 0 until nMels) {
                cum += linear[m]
                if (cum >= 0.85 * frameTotal) { r = m; break }
            }
            rolloffSum += r
        }
        val centroid = if (totalPower > 0) {
            (centroidNum / totalPower / (nMels - 1)).toFloat().coerceIn(0f, 1f)
        } else 0f
        val rolloff = (rolloffSum / realFrames / (nMels - 1)).toFloat().coerceIn(0f, 1f)

        // --- onset density: تعداد پرشهای ناگهانی انرژی در ثانیه ---
        var onsets = 0
        val avgPower = totalPower / realFrames
        for (t in 1 until realFrames) {
            if (framePower[t] > framePower[t - 1] * 1.5 && framePower[t] > avgPower * 0.2) onsets++
        }
        val seconds = samples.size.toFloat() / SAMPLE_RATE
        val onset = (onsets / seconds / 8f).coerceIn(0f, 1f)

        return Fingerprint(energy, centroid, rolloff, onset)
    }

    // ==================== مرتبسازی ====================

    /** فاصلهی ترنزیشن از «ته A» به «سر B» — کمتر یعنی نرمتر */
    fun transitionDistance(a: TrackFlow, b: TrackFlow): Float =
        W_CENTROID * abs(a.tailCentroid - b.headCentroid) +
                W_ENERGY * abs(a.tailEnergy - b.headEnergy) +
                W_ROLLOFF * abs(a.tailRolloff - b.headRolloff) +
                W_ONSET * abs(a.tailOnset - b.headOnset)

    /** زنجیره حریصانه: هر بار نزدیکترین «سر» به «ته» آهنگ فعلی */
    suspend fun orderByFlow(songIds: List<String>, startSongId: String? = null): List<String> {
        if (songIds.size <= 2) return songIds
        val flows = symphony.database.trackFlow.getAll().associateBy { it.songId }
        val remaining = songIds.toMutableList()
        val result = ArrayList<String>(songIds.size)
        var current = startSongId?.takeIf { remaining.contains(it) } ?: remaining.first()
        remaining.remove(current)
        result.add(current)
        while (remaining.isNotEmpty()) {
            val currentFlow = flows[current]
            val next = if (currentFlow == null) remaining.first()
            else remaining.minByOrNull { id ->
                flows[id]?.let { transitionDistance(currentFlow, it) } ?: 1f
            } ?: remaining.first()
            remaining.remove(next)
            result.add(next)
            current = next
        }
        return result
    }
}
