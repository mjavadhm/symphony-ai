package me.zyrouge.symphony.metaphony

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioMetadataWriterTest {
    @Test fun embeddedLyricsPreserveMetadataAndDoNotCloseTheCallersDescriptor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (filename in listOf("audio.mp3", "audio-id3v2.3.mp3", "audio-id3v2.4.mp3", "audio.flac")) {
            val copy = File.createTempFile("lyrics-test-", ".${filename.substringAfterLast('.')}", instrumentation.targetContext.cacheDir)
            try {
                instrumentation.context.assets.open(filename).use { input -> copy.outputStream().use { input.copyTo(it) } }
                val lyrics = "[00:01.20] متن آزمایشی 🎵\n[00:02.345] Native roundtrip"
                ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                    val before = AudioMetadataParser.parse(filename, descriptor.fd)!!
                    assertTrue(descriptor.fileDescriptor.valid())
                    assertEquals(AudioMetadataWriter.WRITTEN, AudioMetadataWriter.writeLyrics(filename, descriptor.fd, lyrics.toByteArray()))
                    assertTrue(descriptor.fileDescriptor.valid())
                    val after = AudioMetadataParser.parse(filename, descriptor.fd)!!
                    assertEquals(lyrics, after.lyrics)
                    assertEquals(before.title, after.title)
                    assertEquals(before.artists, after.artists)
                    assertEquals(before.album, after.album)
                    assertEquals(before.genres, after.genres)
                    assertEquals(before.lengthInSeconds, after.lengthInSeconds)
                    assertEquals(before.pictures.size, after.pictures.size)
                    before.pictures.zip(after.pictures).forEach { (a, b) -> assertArrayEquals(a.data, b.data) }
                    assertEquals(AudioMetadataWriter.ALREADY_HAS_LYRICS,
                        AudioMetadataWriter.writeLyrics(filename, descriptor.fd, "must not replace".toByteArray()))
                }
            } finally { copy.delete() }
        }
    }
}
