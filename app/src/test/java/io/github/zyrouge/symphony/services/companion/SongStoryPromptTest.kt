package io.github.zyrouge.symphony.services.companion

import io.github.zyrouge.symphony.utils.ReadingDirection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SongStoryPromptTest {
    @Test fun `brief substitutes song data but never recursively expands it`() {
        assertEquals("{artist} / TOOL / Album / فارسی", SongStoryPrompt.render("{title} / {artist} / {album} / {language}", "{artist}", "TOOL", "Album", "فارسی"))
    }
    @Test fun `unknown template placeholders stay intact`() {
        assertEquals("{other}", SongStoryPrompt.render("{other}", "", "", "", ""))
    }
    @Test fun `oversized and empty briefs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SongStoryPrompt.render("", "", "", "", "") }
        assertThrows(IllegalArgumentException::class.java) { SongStoryPrompt.render("x".repeat(16001), "", "", "", "") }
    }
    @Test fun `old source artifacts are hidden without changing surrounding prose`() {
        assertEquals("متن  ادامه", SongStoryPrompt.readable("متن [منبع: c266d42d-4fd0-4d02-8788-2865f9bb5d60] ادامه"))
        assertEquals("Story", SongStoryPrompt.readable("Story [source: 123]"))
    }
    @Test fun `multiline legacy source labels are hidden without deleting paragraph text`() {
        assertEquals("Story  continues", SongStoryPrompt.readable("Story [منبع:\n12345678-1234-1234-1234-123456789abc] continues"))
    }
    @Test fun `default asks for the story behind the song, not trivia`() {
        assertTrue(SongStoryPrompt.DEFAULT.contains("what was happening in the artist's life"))
        assertTrue(SongStoryPrompt.DEFAULT.contains("who or what it is about"))
        assertTrue(SongStoryPrompt.DEFAULT.contains("2–4 short paragraphs"))
        assertFalse(SongStoryPrompt.DEFAULT.contains("Wikipedia", ignoreCase = true))
    }
    @Test fun `a stored copy of an old built-in default is upgraded`() {
        SongStoryPrompt.LEGACY_DEFAULTS.forEach { assertEquals(SongStoryPrompt.DEFAULT, SongStoryPrompt.effective(it)) }
        assertEquals(SongStoryPrompt.DEFAULT, SongStoryPrompt.effective("  " + SongStoryPrompt.LEGACY_DEFAULTS.first() + "\n"))
    }
    @Test fun `a prompt the user wrote is kept`() {
        assertEquals("Tell it like a radio DJ", SongStoryPrompt.effective("Tell it like a radio DJ"))
    }
    @Test fun `no stored prompt means the default`() {
        assertEquals(SongStoryPrompt.DEFAULT, SongStoryPrompt.effective(null))
        assertEquals(SongStoryPrompt.DEFAULT, SongStoryPrompt.effective("   "))
    }
    @Test fun `Persian prose starting with English title reads RTL`() {
        assertTrue(ReadingDirection.isRtl("TOOL این آهنگ دربارهٔ تغییر و رشد انسان است و داستانی شنیدنی دارد"))
    }
    @Test fun `original and translated lyric lines get independent directions`() {
        assertFalse(ReadingDirection.isRtl("Learn and love and to cry"))
        assertTrue(ReadingDirection.isRtl("یاد بگیرم و عشق بورزم و گریه کنم"))
        assertFalse(ReadingDirection.isRtl("1234 …"))
    }
}
