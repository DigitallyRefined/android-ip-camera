package com.github.digitallyrefined.androidipcamera.helpers

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * Read/delete access to the recordings folder (Movies/AndroidIPCamera) that [LocalRecorder]
 * writes to. Mirrors its storage split: MediaStore on API 29+ (scoped storage), plain file
 * I/O below. Only files directly inside that folder are exposed — no subfolder browsing.
 */
object RecordingsHelper {

    private const val TAG = "RecordingsHelper"

    data class RecordingFile(val name: String, val sizeBytes: Long, val lastModifiedMs: Long)

    /** Opened recording, ready to be streamed to a client. */
    class OpenRecording(val stream: InputStream, val sizeBytes: Long)

    enum class DeleteResult { DELETED, NOT_FOUND, FAILED }

    val relativePath = "${Environment.DIRECTORY_MOVIES}/${LocalRecorder.SUBDIR}"

    /**
     * Accepts only plain names that resolve inside the recordings folder: no separators,
     * no traversal, no control characters (which would also break response headers).
     */
    fun isValidFileName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

    fun list(context: Context): List<RecordingFile> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listMediaStore(context) else listLegacy()

    /** Returns null when the file does not exist in the recordings folder. */
    fun open(context: Context, name: String): OpenRecording? {
        if (!isValidFileName(name)) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) openMediaStore(context, name) else openLegacy(name)
    }

    fun delete(context: Context, name: String): DeleteResult {
        if (!isValidFileName(name)) return DeleteResult.NOT_FOUND
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) deleteMediaStore(context, name) else deleteLegacy(name)
    }

    // ---- API 29+ (MediaStore / scoped storage) ----

    private fun mediaStoreCollection(): Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private fun listMediaStore(context: Context): List<RecordingFile> {
        val files = mutableListOf<RecordingFile>()
        // LIKE + exact filter afterwards: RELATIVE_PATH trailing-slash handling has varied
        // across Android releases, and this also lets us drop subfolder entries here.
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING}=0"
        try {
            context.contentResolver.query(
                mediaStoreCollection(),
                arrayOf(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.RELATIVE_PATH
                ),
                selection,
                arrayOf("$relativePath%"),
                null
            )?.use { c ->
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                while (c.moveToNext()) {
                    if (c.getString(pathCol)?.trimEnd('/') != relativePath) continue
                    val name = c.getString(nameCol) ?: continue
                    files.add(RecordingFile(name, c.getLong(sizeCol), c.getLong(modifiedCol) * 1000L))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "list failed: ${e.message}")
        }
        return files.sortedByDescending { it.lastModifiedMs }
    }

    private data class MediaRecording(val id: Long, val sizeBytes: Long)

    /**
     * Resolves a recording to its MediaStore row. Uses the same tolerant lookup as
     * [listMediaStore] instead of an exact `RELATIVE_PATH = ?` selection: some builds
     * store the path with a trailing slash, which silently turns equality matches empty.
     */
    private fun findMediaRecording(context: Context, name: String): MediaRecording? {
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING}=0"
        return try {
            context.contentResolver.query(
                mediaStoreCollection(),
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.RELATIVE_PATH
                ),
                selection,
                arrayOf("$relativePath%"),
                null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                while (c.moveToNext()) {
                    if (c.getString(pathCol)?.trimEnd('/') != relativePath) continue
                    if (c.getString(nameCol) == name) {
                        return MediaRecording(c.getLong(idCol), c.getLong(sizeCol))
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "find($name) failed: ${e.message}")
            null
        }
    }

    private fun openMediaStore(context: Context, name: String): OpenRecording? {
        val recording = findMediaRecording(context, name) ?: return null
        val uri = ContentUris.withAppendedId(mediaStoreCollection(), recording.id)
        return try {
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            OpenRecording(stream, if (recording.sizeBytes > 0) recording.sizeBytes else stream.available().toLong())
        } catch (e: Exception) {
            Log.w(TAG, "open($name) failed: ${e.message}")
            null
        }
    }

    private fun deleteMediaStore(context: Context, name: String): DeleteResult {
        val recording = findMediaRecording(context, name) ?: return DeleteResult.NOT_FOUND
        val uri = ContentUris.withAppendedId(mediaStoreCollection(), recording.id)
        return try {
            if (context.contentResolver.delete(uri, null, null) > 0) DeleteResult.DELETED
            else DeleteResult.FAILED
        } catch (e: Exception) {
            // Scoped storage refuses deletes of items this app does not own
            Log.w(TAG, "delete($name) failed: ${e.message}")
            DeleteResult.FAILED
        }
    }

    // ---- API 24-28 (legacy file I/O) ----

    @Suppress("DEPRECATION")
    private fun legacyDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), LocalRecorder.SUBDIR)

    private fun legacyFile(name: String): File? = File(legacyDir(), name).takeIf { it.isFile }

    private fun listLegacy(): List<RecordingFile> = try {
        legacyDir().listFiles()
            ?.filter { it.isFile }
            ?.map { RecordingFile(it.name, it.length(), it.lastModified()) }
            ?.sortedByDescending { it.lastModifiedMs }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun openLegacy(name: String): OpenRecording? = try {
        val file = legacyFile(name) ?: return null
        OpenRecording(file.inputStream(), file.length())
    } catch (_: Exception) {
        null
    }

    private fun deleteLegacy(name: String): DeleteResult = try {
        val file = legacyFile(name) ?: return DeleteResult.NOT_FOUND
        if (file.delete()) DeleteResult.DELETED else DeleteResult.FAILED
    } catch (_: Exception) {
        DeleteResult.FAILED
    }
}
