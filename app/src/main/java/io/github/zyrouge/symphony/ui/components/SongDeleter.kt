package io.github.zyrouge.symphony.ui.components

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.zyrouge.symphony.services.groove.Groove
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.utils.Logger

private const val LOG_TAG = "SongDeleter"

private sealed interface DeleteOutcome {
    /** The file is gone. */
    data object Deleted : DeleteOutcome

    /** The OS owns the file; the user has to approve deletion in a system dialog. */
    data class NeedsSystemConfirmation(val intentSender: IntentSender) : DeleteOutcome

    data class Failed(val message: String) : DeleteOutcome
}

/**
 * Symphony indexes media through SAF tree URIs, but the granted permission is
 * usually read-only, so deleting has to fall back to MediaStore. Look the track
 * up by file name (and size when available) to find its MediaStore row.
 */
private fun findMediaStoreUri(activity: Activity, song: Song): Uri? {
    val collection = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }
    activity.contentResolver.query(
        collection,
        arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.SIZE,
        ),
        MediaStore.Audio.Media.DISPLAY_NAME + " = ?",
        arrayOf(song.filename),
        null,
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val sizeColumn = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
        var fallback: Uri? = null
        while (cursor.moveToNext()) {
            val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
            if (fallback == null) {
                fallback = uri
            }
            if (sizeColumn < 0 || song.size <= 0L || cursor.getLong(sizeColumn) == song.size) {
                return uri
            }
        }
        return fallback
    }
    return null
}

private fun deleteSongFile(context: ViewContext, song: Song): DeleteOutcome {
    val activity = context.activity
    val resolver = activity.contentResolver

    // 1. Direct SAF delete, works when the tree grant includes write access.
    try {
        if (DocumentsContract.isDocumentUri(activity, song.uri) &&
            DocumentsContract.deleteDocument(resolver, song.uri)
        ) {
            return DeleteOutcome.Deleted
        }
    } catch (err: Exception) {
        Logger.warn(LOG_TAG, "deleting document uri failed, falling back to media store", err)
    }

    // 2. MediaStore delete, which may require an explicit user confirmation.
    val mediaUri = runCatching { findMediaStoreUri(activity, song) }.getOrNull()
        ?: return DeleteOutcome.Failed("فایل این آهنگ در حافطه پیدا نشد")

    return try {
        when {
            resolver.delete(mediaUri, null, null) > 0 -> DeleteOutcome.Deleted
            else -> DeleteOutcome.Failed("فایل این آهنگ در حافطه پیدا نشد")
        }
    } catch (err: SecurityException) {
        val sender = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                MediaStore.createDeleteRequest(resolver, listOf(mediaUri)).intentSender

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                (err as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender

            else -> null
        }
        when (sender) {
            null -> DeleteOutcome.Failed(err.localizedMessage ?: err.toString())
            else -> DeleteOutcome.NeedsSystemConfirmation(sender)
        }
    } catch (err: Exception) {
        Logger.error(LOG_TAG, "deleting song failed", err)
        DeleteOutcome.Failed(err.localizedMessage ?: err.toString())
    }
}

/** Drops the song from the in-memory library so the row disappears right away. */
private fun reindexAfterDelete(context: ViewContext) {
    runCatching {
        context.symphony.groove.fetch(Groove.FetchOptions(resetInMemoryCache = true))
    }.onFailure {
        Logger.warn(LOG_TAG, "re-indexing after delete failed", it)
    }
}

/**
 * Confirmation dialog for permanently removing a song file from the device.
 * Handles the Android 10/11+ scoped-storage confirmation flow transparently.
 */
@Composable
fun DeleteSongFromDeviceDialog(
    context: ViewContext,
    song: Song,
    onDismissRequest: () -> Unit,
) {
    var isDeleting by remember { mutableStateOf(false) }

    val toast: (String) -> Unit = remember(context) {
        { message -> Toast.makeText(context.activity, message, Toast.LENGTH_SHORT).show() }
    }

    val systemConfirmation = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        isDeleting = false
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                reindexAfterDelete(context)
                toast(song.title + " از گوشی حذف شد")
            }

            else -> toast("حذف انجام نشد")
        }
        onDismissRequest()
    }

    AlertDialog(
        onDismissRequest = {
            if (!isDeleting) {
                onDismissRequest()
            }
        },
        title = { Text("حذف دائمی از گوشی") },
        text = {
            Text(
                "«" + song.title + "» از حافطه‌ی گوشی پاک می‌شود و این کار " +
                        "قابل بازگشت نیست."
            )
        },
        confirmButton = {
            TextButton(
                enabled = !isDeleting,
                onClick = {
                    isDeleting = true
                    when (val outcome = deleteSongFile(context, song)) {
                        is DeleteOutcome.Deleted -> {
                            reindexAfterDelete(context)
                            toast(song.title + " از گوشی حذف شد")
                            isDeleting = false
                            onDismissRequest()
                        }

                        is DeleteOutcome.NeedsSystemConfirmation -> {
                            runCatching {
                                systemConfirmation.launch(
                                    IntentSenderRequest.Builder(outcome.intentSender).build()
                                )
                            }.onFailure { failure ->
                                Logger.error(LOG_TAG, "launching delete request failed", failure)
                                toast("حذف انجام نشد")
                                isDeleting = false
                                onDismissRequest()
                            }
                        }

                        is DeleteOutcome.Failed -> {
                            toast("حذف نشد: " + outcome.message)
                            isDeleting = false
                            onDismissRequest()
                        }
                    }
                },
            ) {
                Text("حذف دائمی")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isDeleting,
                onClick = onDismissRequest,
            ) {
                Text("انصراف")
            }
        },
    )
}
