package io.github.zyrouge.symphony.services.lyrics

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import io.github.zyrouge.symphony.services.groove.Song
import kotlinx.coroutines.*
import me.zyrouge.symphony.metaphony.AudioMetadataParser
import me.zyrouge.symphony.metaphony.AudioMetadataWriter
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Staged, verified writes with a durable rollback copy before the original is touched. */
class EmbeddedLyricsStore(private val context: Context) {
    data class Recovery(val directory: File, val name: String, val uri: Uri, val sha256: String)
    private val resolver get() = context.contentResolver
    private val recoveryRoot get() = File(context.filesDir, "lyrics-recovery").apply { mkdirs() }
    companion object {
        val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "m4a", "m4b", "ogg", "opus")
        fun supported(filename: String) = filename.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
    }

    fun readLyrics(song: Song): String? {
        val descriptor = resolver.openFileDescriptor(song.uri, "r") ?: throw IOException("Cannot open audio file")
        return descriptor.use {
            val metadata = AudioMetadataParser.parse(song.filename, it.fd)
                ?: throw IOException("Could not read this audio file")
            metadata.lyrics?.takeIf(String::isNotBlank)
        }
    }

    private fun digest(uri: Uri): String = resolver.openInputStream(uri)?.use { stream ->
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) { val n = stream.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    } ?: throw IOException("Cannot read audio file")

    private fun digest(file: File) = digest(Uri.fromFile(file))

    private fun replace(uri: Uri, source: File) {
        val descriptor = resolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Cannot open the song for writing")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            // Seek/truncate is required. Never use append mode or silently accept a pipe.
            output.channel.position(0)
            output.channel.truncate(0)
            source.inputStream().use { it.copyTo(output, 128 * 1024) }
            output.fd.sync()
        }
    }

    suspend fun embed(song: Song, lyrics: String): Boolean = withContext(Dispatchers.IO) {
        require(supported(song.filename)) { "Unsupported audio format" }
        require(lyrics.isNotBlank() && lyrics.toByteArray(Charsets.UTF_8).size <= 1_000_000 && '\u0000' !in lyrics)
        // Probe permissions without truncating or editing anything.
        resolver.openFileDescriptor(song.uri, "rw")?.close()
            ?: throw IOException("Cannot open this music folder for writing")
        val available = StatFs(context.filesDir.absolutePath).availableBytes
        if (song.size <= 0 || song.size > (available - 16L * 1024 * 1024) / 3) {
            throw IOException("Not enough free space for a safe backup and staged copy")
        }
        val directory = File(recoveryRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val original = File(directory, "original.${song.filename.substringAfterLast('.')}")
        val staged = File(directory, "staged.${song.filename.substringAfterLast('.')}")
        var journalCreated = false
        try {
            currentCoroutineContext().ensureActive()
            resolver.openInputStream(song.uri)?.use { input ->
                FileOutputStream(original).use { output -> input.copyTo(output); output.fd.sync() }
            } ?: throw IOException("Cannot back up audio file")
            currentCoroutineContext().ensureActive()
            val originalHash = digest(original)
            val originalMetadata = ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY).use {
                AudioMetadataParser.parse(song.filename, it.fd)
            } ?: throw IOException("Cannot verify original metadata")
            original.copyTo(staged)
            val code = ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_WRITE).use {
                AudioMetadataWriter.writeLyrics(song.filename, it.fd, lyrics.toByteArray(Charsets.UTF_8))
            }
            if (code == AudioMetadataWriter.ALREADY_HAS_LYRICS) return@withContext false
            if (code != AudioMetadataWriter.WRITTEN) throw IOException("This file's tag format cannot be written safely")
            val verified = ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY).use {
                AudioMetadataParser.parse(song.filename, it.fd)
            } ?: throw IOException("Staged metadata verification failed")
            if (verified.lyrics != lyrics) throw IOException("Staged lyrics verification failed; original was not changed")
            if (originalMetadata.copy(lyrics = null, pictures = emptyList()) !=
                verified.copy(lyrics = null, pictures = emptyList()) ||
                originalMetadata.pictures.size != verified.pictures.size ||
                originalMetadata.pictures.zip(verified.pictures).any { (a, b) ->
                    a.pictureType != b.pictureType || a.mimeType != b.mimeType || !a.data.contentEquals(b.data)
                }) throw IOException("Staged tags/artwork changed unexpectedly; original was not changed")
            val stagedHash = digest(staged)
            currentCoroutineContext().ensureActive()
            if (digest(song.uri) != originalHash) throw IOException("The song changed during preparation; retry")
            // The journal is durable before any destructive write. It survives process death.
            val journal = JSONObject().put("name", song.filename).put("uri", song.uri.toString())
                .put("sha256", originalHash).put("backup", original.name)
            FileOutputStream(File(directory, "recovery.json")).use {
                it.write(journal.toString().toByteArray(Charsets.UTF_8)); it.fd.sync()
            }
            journalCreated = true
            // Cancellation must never interrupt copying or rolling back the original.
            withContext(NonCancellable) {
                try {
                    replace(song.uri, staged)
                    if (digest(song.uri) != stagedHash) throw IOException("Written file verification failed")
                    directory.deleteRecursively()
                    journalCreated = false
                } catch (failure: Exception) {
                    try {
                        replace(song.uri, original)
                        if (digest(song.uri) != originalHash) throw IOException("Rollback verification failed")
                        directory.deleteRecursively()
                        journalCreated = false
                    } catch (rollback: Exception) {
                        throw IOException("Write failed; recovery backup retained. Use Restore in Lyrics Manager.", rollback)
                    }
                    throw IOException("Write failed; the original file was restored", failure)
                }
            }
            true
        } finally {
            if (!journalCreated) directory.deleteRecursively()
        }
    }

    fun recoveries(): List<Recovery> = recoveryRoot.listFiles().orEmpty().mapNotNull { directory ->
        runCatching {
            val json = JSONObject(File(directory, "recovery.json").readText())
            Recovery(directory, json.getString("name"), Uri.parse(json.getString("uri")), json.getString("sha256"))
        }.getOrNull()
    }

    suspend fun restore(recovery: Recovery) = withContext(Dispatchers.IO + NonCancellable) {
        val json = JSONObject(File(recovery.directory, "recovery.json").readText())
        val backup = File(recovery.directory, json.getString("backup"))
        require(backup.canonicalFile.parentFile == recovery.directory.canonicalFile)
        if (digest(backup) != recovery.sha256) throw IOException("Backup integrity check failed")
        replace(recovery.uri, backup)
        if (digest(recovery.uri) != recovery.sha256) throw IOException("Restore verification failed; backup retained")
        recovery.directory.deleteRecursively()
        Unit
    }
}
