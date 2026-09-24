package com.github.digitallyrefined.androidipcamera.helpers

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import java.io.File
import java.io.InputStream

/**
 * Read/delete access to the recordings folder that [LocalRecorder] writes to.
 *
 * By default this is Movies/AndroidIPCamera on internal storage (MediaStore on API 29+,
 * plain file I/O below). The user can also pick a custom folder via the settings
 * (Storage Access Framework tree URI, e.g. an SD card) — recordings then go there.
 * Only files directly inside that folder are exposed — no subfolder browsing.
 */
object RecordingsHelper {

    private const val TAG = "RecordingsHelper"

    /** SharedPreferences key storing the tree URI of the user-chosen recording folder. */
    const val PREF_RECORDING_STORAGE_URI = "recording_storage_uri"

    /** Default location inside the public Movies directory. */
    val DEFAULT_RELATIVE_PATH = "${Environment.DIRECTORY_MOVIES}/${LocalRecorder.SUBDIR}"

    /** Backwards-compatible alias used by the web UI and other helpers. */
    val relativePath = DEFAULT_RELATIVE_PATH

    data class RecordingFile(val name: String, val sizeBytes: Long, val lastModifiedMs: Long)

    /** Opened recording, ready to be streamed to a client. */
    class OpenRecording(val stream: InputStream, val sizeBytes: Long)

    enum class DeleteResult { DELETED, NOT_FOUND, FAILED }

    /** Returns the user-chosen folder tree URI, or null when the default location is used. */
    fun customStorageUri(context: Context): Uri? {
        val s = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_RECORDING_STORAGE_URI, null)
        if (s.isNullOrBlank()) return null
        return try { Uri.parse(s) } catch (_: Exception) { null }
    }

    /** Human-readable folder label for the web UI: the custom folder name or the default path. */
    fun folderLabel(context: Context): String {
        val uri = customStorageUri(context) ?: return DEFAULT_RELATIVE_PATH
        val root = try { DocumentFile.fromTreeUri(context, uri) } catch (_: Exception) { null }
        return root?.name ?: DEFAULT_RELATIVE_PATH
    }

    /** Resolves the user-chosen folder to a DocumentFile, or null when the default is used. */
    internal fun customRoot(context: Context): DocumentFile? {
        val uri = customStorageUri(context) ?: return null
        val root = try { DocumentFile.fromTreeUri(context, uri) } catch (_: Exception) { null }
        return root.takeIf { it?.exists() == true }
    }

    /**
     * Accepts only plain names that resolve inside the recordings folder: no separators,
     * no traversal, no control characters (which would also break response headers).
     */
    fun isValidFileName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

    fun list(context: Context): List<RecordingFile> =
        when {
            customRoot(context) != null -> listCustom(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> listMediaStore(context)
            else -> listLegacy()
        }

    /** Returns null when the file does not exist in the recordings folder. */
    fun open(context: Context, name: String): OpenRecording? {
        if (!isValidFileName(name)) return null
        return when {
            customRoot(context) != null -> openCustom(context, name)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> openMediaStore(context, name)
            else -> openLegacy(name)
        }
    }

    fun delete(context: Context, name: String): DeleteResult {
        if (!isValidFileName(name)) return DeleteResult.NOT_FOUND
        return when {
            customRoot(context) != null -> deleteCustom(context, name)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> deleteMediaStore(context, name)
            else -> deleteLegacy(name)
        }
    }

    // ---- Custom folder (Storage Access Framework / SD card) ----

    private fun listCustom(context: Context): List<RecordingFile> {
        val root = customRoot(context) ?: return emptyList()
        return try {
            root.listFiles()
                .filter { it.isFile }
                .mapNotNull { file ->
                    val name = file.name
                    if (name.isNullOrBlank()) null
                    else RecordingFile(name, safeSize(file), safeLastModified(file))
                }
                .sortedByDescending { it.lastModifiedMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findCustom(context: Context, name: String): DocumentFile? {
        val root = customRoot(context) ?: return null
        return try {
            root.listFiles().firstOrNull { it.isFile && it.name == name }
        } catch (_: Exception) {
            null
        }
    }

    private fun openCustom(context: Context, name: String): OpenRecording? = try {
        val file = findCustom(context, name) ?: return null
        val stream = context.contentResolver.openInputStream(file.uri) ?: return null
        OpenRecording(stream, if (safeSize(file) > 0) safeSize(file) else stream.available().toLong())
    } catch (_: Exception) {
        null
    }

    private fun deleteCustom(context: Context, name: String): DeleteResult = try {
        val file = findCustom(context, name) ?: return DeleteResult.NOT_FOUND
        if (file.delete()) DeleteResult.DELETED else DeleteResult.FAILED
    } catch (_: Exception) {
        DeleteResult.FAILED
    }

    private fun safeSize(file: DocumentFile): Long = try {
        file.length()
    } catch (_: Exception) {
        0L
    }

    private fun safeLastModified(file: DocumentFile): Long = try {
        file.lastModified()
    } catch (_: Exception) {
        0L
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
