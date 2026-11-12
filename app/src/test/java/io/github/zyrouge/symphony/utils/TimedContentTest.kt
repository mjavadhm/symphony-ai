package io.github.zyrouge.symphony.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TimedContentTest {
    @Test fun `LRC fractional seconds are scaled correctly`() {
        val parsed = TimedContent.fromLyrics("[00:01.2] a\n[00:02.12] b\n[00:03.123] c")
        assertEquals(listOf(1200L, 2120L, 3123L), parsed.pairs.map { it.first })
    }
    @Test fun `timestamps without fractions work`() {
        assertEquals(62_000L, TimedContent.fromLyrics("[01:02] test").pairs.single().first)
    }
    @Test fun `repeated lines can carry multiple timestamps`() {
        assertEquals(listOf(1000L to "test", 3000L to "test"), TimedContent.fromLyrics("[00:01][00:03] test").pairs)
    }
    @Test fun `metadata is not shown as lyric text`() {
        assertEquals(listOf(1000L to "test"), TimedContent.fromLyrics("[ar:Artist]\n[ti:Title]\n[00:01] test").pairs)
    }
    @Test fun `plain lyrics stay unsynchronized`() {
        val parsed = TimedContent.fromLyrics("first\r\nsecond")
        assertFalse(parsed.isSynced)
        assertEquals(2, parsed.pairs.size)
    }
    @Test fun `positive offset advances line display`() {
        assertEquals(900L, TimedContent.fromLyrics("[offset:+100]\n[00:01] test").pairs.single().first)
    }
}
