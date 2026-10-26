package io.github.zyrouge.symphony.utils

data class TimedContent(val pairs: List<Pair<Long, String>>) {
    val isSynced: Boolean get() = pairs.firstOrNull()?.first != pairs.lastOrNull()?.first

    companion object {
        val lrcLineSeparatorRegex = Regex("\\r\\n|\\n|\\r")
        val lrcLineFilterRegex = Regex("""^\[\s*(\d{1,3}):([0-5]\d)(?:\.(\d{1,3}))?\s*](.*)""")
        private val timestampRegex = Regex("""\[\s*(\d{1,3}):([0-5]\d)(?:\.(\d{1,3}))?\s*]""")
        private val metadataRegex = Regex("""^\[(?:ar|al|ti|au|by|re|ve|length):.*]$""", RegexOption.IGNORE_CASE)

        fun fromLyrics(content: String): TimedContent {
            val lines = content.split(lrcLineSeparatorRegex)
            val offset = lines.firstNotNullOfOrNull { line ->
                Regex("""^\[offset:([+-]?\d+)]$""", RegexOption.IGNORE_CASE)
                    .matchEntire(line.trim())?.groupValues?.get(1)?.toLongOrNull()
            }?.coerceIn(-86_400_000, 86_400_000) ?: 0L
            var lastTime = 0L
            val pairs = lines.flatMap { raw ->
                val line = raw.trim()
                if (metadataRegex.matches(line) || line.startsWith("[offset:", true)) return@flatMap emptyList()
                val stamps = timestampRegex.findAll(line).toList()
                if (stamps.firstOrNull()?.range?.first != 0) return@flatMap listOf(lastTime to line)
                val text = line.substring(stamps.last().range.last + 1).trim()
                stamps.map { stamp ->
                    // .12 is 120 ms, not 12 ms; accept 1, 2, or 3 fractional digits.
                    val fraction = stamp.groupValues[3].padEnd(3, '0').toLong()
                    val time = (stamp.groupValues[1].toLong() * 60_000 +
                        stamp.groupValues[2].toLong() * 1000 + fraction - offset).coerceAtLeast(0)
                    lastTime = time
                    time to text
                }
            }.sortedBy { it.first }
            return TimedContent(pairs)
        }
    }
}
