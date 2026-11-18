package com.github.digitallyrefined.androidipcamera.helpers

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
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
 *
 * Recordings are grouped into `YYYY/MM/DD` subfolders. Paths passed in here are relative
 * to the configured root (for example `2026/11/09/clip.mp4`) and are validated before use.
 */
object RecordingsHelper {

    private const val TAG = "RecordingsHelper"

    /** SharedPreferences key storing the tree URI of the user-chosen recording folder. */
    const val PREF_RECORDING_STORAGE_URI = "recording_storage_uri"

    /** Top-level public directory used for recordings when no custom folder is configured. */
    const val DEFAULT_PUBLIC_DIR = "Movies"

    /** Default location relative to shared storage. */
    val DEFAULT_RELATIVE_PATH = "$DEFAULT_PUBLIC_DIR/${LocalRecorder.SUBDIR}"

    /** Backwards-compatible alias used by the web UI and other helpers. */
    val relativePath = DEFAULT_RELATIVE_PATH

    data class RecordingEntry(
        val name: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val isDirectory: Boolean
    )

    /** Opened recording, ready to be streamed to a client. */
    class OpenRecording(val stream: InputStream, val sizeBytes: Long)

    enum class DeleteResult { DELETED, NOT_FOUND, FAILED }

    /** Space on the drive that holds the recordings folder. */
    data class StorageUsage(val totalBytes: Long, val availableBytes: Long) {
        val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)
        val usedPercent: Double get() = if (totalBytes > 0) usedBytes * 100.0 / totalBytes else 0.0
    }

    /**
     * Bytes that must be freed before [limitPercent] of the drive is in use. Returns 0 while the
     * drive is below the limit. Pure so the policy is easy to test.
     */
    fun bytesToFree(totalBytes: Long, availableBytes: Long, limitPercent: Int): Long {
        if (totalBytes <= 0) return 0
        val limit = limitPercent.coerceIn(1, 99)
        val keepFree = totalBytes * (100 - limit) / 100
        return (keepFree - availableBytes).coerceAtLeast(0L)
    }

    fun shouldTrim(totalBytes: Long, availableBytes: Long, limitPercent: Int): Boolean =
        bytesToFree(totalBytes, availableBytes, limitPercent) > 0

    /** Total and free space of the drive holding the configured recordings folder, or null. */
    fun storageUsage(context: Context): StorageUsage? {
        val root = storageRoot(context) ?: return null
        return try {
            val stat = StatFs(root.absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val available = stat.availableBlocksLong * stat.blockSizeLong
            if (total > 0) StorageUsage(total, available) else null
        } catch (e: Exception) {
            Log.w(TAG, "storageUsage failed: ${e.message}")
            null
        }
    }

    /**
     * Deletes the oldest recordings until the drive is below [limitPercent] used, or until no
     * recordings are left. [activePath] supplies the clip currently being written (if any), which
     * is never deleted. Returns the number of files deleted.
     */
    fun trimOldest(context: Context, limitPercent: Int, activePath: () -> String? = { null }): Int {
        val usage = storageUsage(context) ?: return 0
        if (!shouldTrim(usage.totalBytes, usage.availableBytes, limitPercent)) return 0
        var deleted = 0
        for (file in oldestFiles(context)) {
            if (file.path == activePath()) continue
            // Re-read after every delete: the freed space is what decides when to stop, and it
            // avoids relying on file sizes that some storage providers report as 0.
            val now = storageUsage(context) ?: break
            if (!shouldTrim(now.totalBytes, now.availableBytes, limitPercent)) break
            if (delete(context, file.path) == DeleteResult.DELETED) deleted++
        }
        if (deleted > 0) {
            Log.i(TAG, "Deleted $deleted oldest recording(s); drive was ${"%.1f".format(usage.usedPercent)}% full")
        }
        return deleted
    }

    /** Returns the user-chosen folder tree URI, or null when the default location is used. */
    fun customStorageUri(context: Context): Uri? {
        val s = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_RECORDING_STORAGE_URI, null)
        if (s.isNullOrBlank()) return null
        return try { Uri.parse(s) } catch (_: Exception) { null }
    }

    /** Human-readable root folder label for the web UI: the custom folder name or the default path. */
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

    /**
     * Normalizes a path relative to the recordings root, returning null when it is unsafe.
     * An empty (or absent) path means the root folder itself.
     */
    fun normalizeRelativePath(raw: String?): String? {
        if (raw.isNullOrEmpty()) return ""
        if (raw.startsWith("/") || raw.startsWith("\\") || raw.contains('\\')) return null
        val parts = raw.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." || !isValidFileName(it) }) return null
        return parts.joinToString("/")
    }

    fun list(context: Context, dir: String = ""): List<RecordingEntry> {
        val normalized = normalizeRelativePath(dir) ?: return emptyList()
        return when {
            customRoot(context) != null -> listCustom(context, normalized)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> listMediaStore(context, normalized)
            else -> listLegacy(normalized)
        }
    }

    /** Returns null when the file does not exist in the recordings folder. */
    fun open(context: Context, path: String): OpenRecording? {
        val normalized = normalizeRelativePath(path) ?: return null
        if (normalized.isEmpty()) return null
        return when {
            customRoot(context) != null -> openCustom(context, normalized)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> openMediaStore(context, normalized)
            else -> openLegacy(normalized)
        }
    }

    /** Deletes a file, or recursively deletes a folder and everything below it. */
    fun delete(context: Context, path: String): DeleteResult {
        val normalized = normalizeRelativePath(path) ?: return DeleteResult.NOT_FOUND
        if (normalized.isEmpty()) return DeleteResult.NOT_FOUND
        return when {
            customRoot(context) != null -> deleteCustom(context, normalized)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> deleteMediaStore(context, normalized)
            else -> deleteLegacy(normalized)
        }
    }

    /**
     * Returns (creating when needed) a nested directory below the custom SAF root.
     * Used by [LocalRecorder] for its YYYY/MM/DD output folders.
     */
    fun ensureCustomDirectory(context: Context, relativeDir: String): DocumentFile? {
        val normalized = normalizeRelativePath(relativeDir) ?: return null
        var current = customRoot(context) ?: return null
        if (normalized.isEmpty()) return current
        for (segment in normalized.split('/')) {
            val existing = try { current.findFile(segment) } catch (_: Exception) { null }
            current = existing?.takeIf { it.isDirectory }
                ?: (try { current.createDirectory(segment) } catch (_: Exception) { null })
                ?: return null
        }
        return current
    }

    // ---- Storage housekeeping ----

    private data class StoredFile(val path: String, val sizeBytes: Long, val modifiedMs: Long)

    /** Root path of the volume that holds the recordings (default storage or the SAF volume). */
    private fun storageRoot(context: Context): File? {
        val uri = customStorageUri(context) ?: return Environment.getExternalStorageDirectory()
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot resolve custom folder document id: ${e.message}")
            return null
        }
        val volumeId = docId.substringBefore(':')
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
        val volume = try {
            manager.storageVolumes.firstOrNull { (it.uuid ?: "primary").equals(volumeId, ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot enumerate storage volumes: ${e.message}")
            null
        }
        return volumeRoot(volume)
    }

    @Suppress("DEPRECATION")
    private fun volumeRoot(volume: StorageVolume?): File? {
        if (volume == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return volume.directory
        // StorageVolume.getDirectory() only exists on API 30+; older releases expose getPathFile().
        return try {
            val method = StorageVolume::class.java.getMethod("getPathFile")
            method.invoke(volume) as? File
        } catch (e: Exception) {
            Log.w(TAG, "Cannot resolve storage volume path: ${e.message}")
            null
        }
    }

    private fun oldestFiles(context: Context): List<StoredFile> = when {
        customRoot(context) != null -> customFiles(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> mediaStoreFiles(context)
        else -> legacyFiles()
    }.sortedBy { it.modifiedMs }

    private fun customFiles(context: Context): List<StoredFile> {
        val root = customRoot(context) ?: return emptyList()
        val out = mutableListOf<StoredFile>()

        fun walk(folder: DocumentFile, prefix: String) {
            val children = try {
                folder.listFiles()
            } catch (_: Exception) {
                return
            }
            for (child in children) {
                val name = child.name?.takeIf { isValidFileName(it) } ?: continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    walk(child, path)
                } else if (child.isFile && name.endsWith(".mp4", ignoreCase = true)) {
                    out.add(StoredFile(path, safeSize(child), safeLastModified(child)))
                }
            }
        }

        walk(root, "")
        return out
    }

    private fun mediaStoreFiles(context: Context): List<StoredFile> {
        val root = relativePath.trimEnd('/')
        val out = mutableListOf<StoredFile>()
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
                arrayOf("$root%"),
                null
            )?.use { c ->
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val rowPath = c.getString(pathCol)?.trimEnd('/') ?: continue
                    if (rowPath != root && !rowPath.startsWith("$root/")) continue
                    val name = c.getString(nameCol) ?: continue
                    if (!isValidFileName(name) || !name.endsWith(".mp4", ignoreCase = true)) continue
                    val relDir = rowPath.removePrefix(root).trim('/')
                    val path = if (relDir.isEmpty()) name else "$relDir/$name"
                    out.add(StoredFile(path, c.getLong(sizeCol), c.getLong(modifiedCol) * 1000L))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "oldestFiles failed: ${e.message}")
        }
        return out
    }

    private fun legacyFiles(): List<StoredFile> {
        val out = mutableListOf<StoredFile>()

        fun walk(dir: File, prefix: String) {
            val children = try {
                dir.listFiles()
            } catch (_: Exception) {
                null
            } ?: return
            for (child in children) {
                if (!isValidFileName(child.name)) continue
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) walk(child, path)
                else if (child.isFile && child.name.endsWith(".mp4", ignoreCase = true)) {
                    out.add(StoredFile(path, child.length(), child.lastModified()))
                }
            }
        }

        walk(legacyRoot(), "")
        return out
    }

    // ---- Custom folder (Storage Access Framework / SD card) ----

    private fun listCustom(context: Context, dir: String): List<RecordingEntry> {
        val folder = findCustomDir(context, dir) ?: return emptyList()
        return try {
            val children = folder.listFiles()
            val directories = children
                .filter { it.isDirectory }
                .mapNotNull { file ->
                    file.name?.takeIf { it.isNotBlank() }?.let {
                        RecordingEntry(it, 0L, safeLastModified(file), true)
                    }
                }
                .sortedByDescending { it.name }
            val files = children
                .filter { it.isFile }
                .mapNotNull { file ->
                    file.name?.takeIf { it.isNotBlank() }?.let {
                        RecordingEntry(it, safeSize(file), safeLastModified(file), false)
                    }
                }
                .sortedByDescending { it.lastModifiedMs }
            directories + files
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findCustomDir(context: Context, dir: String): DocumentFile? {
        val root = customRoot(context) ?: return null
        if (dir.isEmpty()) return root
        var current = root
        for (segment in dir.split('/')) {
            current = try {
                current.listFiles().firstOrNull { it.isDirectory && it.name == segment }
            } catch (_: Exception) {
                null
            } ?: return null
        }
        return current
    }

    /** Resolves a file or folder below the custom root. */
    private fun findCustomNode(context: Context, path: String): DocumentFile? {
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val folder = findCustomDir(context, dir) ?: return null
        return try {
            folder.listFiles().firstOrNull { it.name == name }
        } catch (_: Exception) {
            null
        }
    }

    private fun findCustomFile(context: Context, path: String): DocumentFile? =
        findCustomNode(context, path)?.takeIf { it.isFile }

    /** Deletes a SAF file or recursively deletes a folder and everything below it. */
    private fun deleteCustomNode(node: DocumentFile): Boolean {
        if (node.isDirectory) {
            val children = try {
                node.listFiles()
            } catch (_: Exception) {
                return false
            }
            for (child in children) {
                if (!deleteCustomNode(child)) return false
            }
        }
        return try {
            node.delete()
        } catch (_: Exception) {
            false
        }
    }

    private fun openCustom(context: Context, path: String): OpenRecording? = try {
        val file = findCustomFile(context, path) ?: return null
        val stream = context.contentResolver.openInputStream(file.uri) ?: return null
        OpenRecording(stream, if (safeSize(file) > 0) safeSize(file) else stream.available().toLong())
    } catch (_: Exception) {
        null
    }

    private fun deleteCustom(context: Context, path: String): DeleteResult = try {
        val node = findCustomNode(context, path) ?: return DeleteResult.NOT_FOUND
        if (deleteCustomNode(node)) DeleteResult.DELETED else DeleteResult.FAILED
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

    private fun listMediaStore(context: Context, dir: String): List<RecordingEntry> {
        val root = relativePath.trimEnd('/')
        val target = if (dir.isEmpty()) root else "$root/$dir"
        val directories = LinkedHashMap<String, Long>()
        val files = mutableListOf<RecordingEntry>()
        // LIKE + exact filter afterwards: RELATIVE_PATH trailing-slash handling has varied
        // across Android releases, and this also lets us derive the immediate subfolders.
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
                arrayOf("$root%"),
                null
            )?.use { c ->
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val rowPath = c.getString(pathCol)?.trimEnd('/') ?: continue
                    if (rowPath != target && !rowPath.startsWith("$target/")) continue
                    val name = c.getString(nameCol) ?: continue
                    val modifiedMs = c.getLong(modifiedCol) * 1000L
                    if (rowPath == target) {
                        files.add(RecordingEntry(name, c.getLong(sizeCol), modifiedMs, false))
                    } else {
                        val childDir = rowPath.removePrefix("$target/").substringBefore('/')
                        if (childDir.isNotEmpty()) {
                            directories[childDir] = maxOf(directories[childDir] ?: 0L, modifiedMs)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "list($dir) failed: ${e.message}")
        }
        val directoryEntries = directories.map { (name, modified) ->
            RecordingEntry(name, 0L, modified, true)
        }.sortedByDescending { it.name }
        return directoryEntries + files.sortedByDescending { it.lastModifiedMs }
    }

    private data class MediaRecording(val id: Long, val sizeBytes: Long)

    /**
     * Resolves a recording to its MediaStore row. Uses the same tolerant lookup as
     * [listMediaStore] instead of an exact `RELATIVE_PATH = ?` selection: some builds
     * store the path with a trailing slash, which silently turns equality matches empty.
     */
    private fun findMediaRecording(context: Context, path: String): MediaRecording? {
        val root = relativePath.trimEnd('/')
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val target = if (dir.isEmpty()) root else "$root/$dir"
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
                arrayOf("$target%"),
                null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                while (c.moveToNext()) {
                    if (c.getString(pathCol)?.trimEnd('/') != target) continue
                    if (c.getString(nameCol) == name) {
                        return MediaRecording(c.getLong(idCol), c.getLong(sizeCol))
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "find($path) failed: ${e.message}")
            null
        }
    }

    private fun openMediaStore(context: Context, path: String): OpenRecording? {
        val recording = findMediaRecording(context, path) ?: return null
        val uri = ContentUris.withAppendedId(mediaStoreCollection(), recording.id)
        return try {
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            OpenRecording(stream, if (recording.sizeBytes > 0) recording.sizeBytes else stream.available().toLong())
        } catch (e: Exception) {
            Log.w(TAG, "open($path) failed: ${e.message}")
            null
        }
    }

    private fun deleteMediaStore(context: Context, path: String): DeleteResult {
        val recording = findMediaRecording(context, path)
        if (recording != null) {
            val uri = ContentUris.withAppendedId(mediaStoreCollection(), recording.id)
            return try {
                if (context.contentResolver.delete(uri, null, null) > 0) DeleteResult.DELETED
                else DeleteResult.FAILED
            } catch (e: Exception) {
                // Scoped storage refuses deletes of items this app does not own
                Log.w(TAG, "delete($path) failed: ${e.message}")
                DeleteResult.FAILED
            }
        }
        return deleteMediaStoreFolder(context, path)
    }

    /**
     * MediaStore folders are virtual: a folder exists only as the common prefix of its
     * files' `RELATIVE_PATH`. Deleting one means deleting every row at or below it.
     */
    private fun deleteMediaStoreFolder(context: Context, path: String): DeleteResult {
        val target = "${relativePath.trimEnd('/')}/$path"
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING}=0"
        val ids = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                mediaStoreCollection(),
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.RELATIVE_PATH),
                selection,
                arrayOf("$target%"),
                null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val rowPath = c.getString(pathCol)?.trimEnd('/') ?: continue
                    if (rowPath == target || rowPath.startsWith("$target/")) {
                        ids.add(c.getLong(idCol))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "delete folder($path) query failed: ${e.message}")
            return DeleteResult.FAILED
        }
        if (ids.isEmpty()) return DeleteResult.NOT_FOUND

        var failed = false
        for (id in ids) {
            val uri = ContentUris.withAppendedId(mediaStoreCollection(), id)
            try {
                if (context.contentResolver.delete(uri, null, null) <= 0) failed = true
            } catch (e: Exception) {
                Log.w(TAG, "delete folder($path) item $id failed: ${e.message}")
                failed = true
            }
        }
        return if (failed) DeleteResult.FAILED else DeleteResult.DELETED
    }

    // ---- API 24-28 (legacy file I/O) ----

    @Suppress("DEPRECATION")
    private fun legacyRoot(): File =
        File(Environment.getExternalStoragePublicDirectory(DEFAULT_PUBLIC_DIR), LocalRecorder.SUBDIR)

    private fun legacyDir(dir: String): File =
        if (dir.isEmpty()) legacyRoot() else File(legacyRoot(), dir)

    private fun legacyFile(path: String): File? = File(legacyRoot(), path).takeIf { it.isFile }

    private fun listLegacy(dir: String): List<RecordingEntry> {
        val children = try {
            legacyDir(dir).listFiles()
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val directories = children
            .filter { it.isDirectory }
            .map { RecordingEntry(it.name, 0L, it.lastModified(), true) }
            .sortedByDescending { it.name }
        val files = children
            .filter { it.isFile }
            .map { RecordingEntry(it.name, it.length(), it.lastModified(), false) }
            .sortedByDescending { it.lastModifiedMs }
        return directories + files
    }

    private fun openLegacy(path: String): OpenRecording? = try {
        val file = legacyFile(path) ?: return null
        OpenRecording(file.inputStream(), file.length())
    } catch (_: Exception) {
        null
    }

    private fun deleteLegacy(path: String): DeleteResult = try {
        val node = File(legacyRoot(), path)
        when {
            node.isFile -> if (node.delete()) DeleteResult.DELETED else DeleteResult.FAILED
            node.isDirectory -> if (node.deleteRecursively()) DeleteResult.DELETED else DeleteResult.FAILED
            else -> DeleteResult.NOT_FOUND
        }
    } catch (_: Exception) {
        DeleteResult.FAILED
    }
}
