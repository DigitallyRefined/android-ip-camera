package com.github.digitallyrefined.androidipcamera.helpers

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records H.264 access units from the streaming encoder into a local MP4 file.
 *
 * Thread safety: [feedFrame] runs on the encoder drain thread; [stop] runs on the
 * main/service thread. All muxer access is serialized via [muxerLock], and a volatile
 * [released] flag provides a fast-path exit for [feedFrame] after [stop] completes.
 *
 * Storage: Uses MediaStore on API 29+ (scoped storage, no permissions needed) and
 * legacy file I/O on API 24–28 (requires WRITE_EXTERNAL_STORAGE).
 */
class LocalRecorder(
    private val context: Context,
    val width: Int,
    val height: Int
) {
    companion object {
        private const val TAG = "LocalRecorder"
        private const val SUBDIR = "AndroidIPCamera"
    }

    // --- Public state (read from any thread) ---
    @Volatile var isRecording: Boolean = false
        private set
    @Volatile var recordingStartTimeMs: Long = 0L
        private set

    // --- Muxer state (guarded by muxerLock) ---
    private val muxerLock = Any()
    @Volatile private var released = false
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false

    // --- Output references ---
    private var mediaStoreUri: Uri? = null
    private var outputFile: File? = null
    private var pfd: ParcelFileDescriptor? = null

    // --- Timestamp tracking ---
    /** First presentation timestamp from the encoder, used to normalize PTS to start at 0. */
    private var firstPtsUs = Long.MIN_VALUE
    /** Last written presentation timestamp, enforcing strict monotonicity for MediaMuxer. */
    private var lastPtsUs = -1L

    /**
     * Create the output file and MediaMuxer. The muxer track is NOT added here — it
     * requires the first keyframe's SPS/PPS, which arrives in [feedFrame].
     */
    fun start() {
        synchronized(muxerLock) {
            check(!isRecording) { "Already recording" }
            released = false
            firstPtsUs = Long.MIN_VALUE
            lastPtsUs = -1L
            val filename = generateFilename()

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // API 29+: MediaStore (scoped storage)
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$SUBDIR")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                    ) ?: throw IllegalStateException("MediaStore insert failed")
                    val fd = context.contentResolver.openFileDescriptor(uri, "rw")
                        ?: throw IllegalStateException("Failed to open file descriptor for $uri")
                    mediaStoreUri = uri
                    pfd = fd
                    muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                } else {
                    // API 24-28: Legacy file I/O
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        SUBDIR
                    )
                    dir.mkdirs()
                    val file = File(dir, filename)
                    outputFile = file
                    muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                }

                isRecording = true
                recordingStartTimeMs = System.currentTimeMillis()
                Log.i(TAG, "Recording initialized: ${recordingLocation()}")
            } catch (e: Exception) {
                cleanupOutput()
                throw e
            }
        }
    }

    /**
     * Feed one H.264 access unit from the encoder drain loop.
     *
     * Called on the encoder thread at frame rate (~30 fps). The first keyframe initializes
     * the muxer track (SPS/PPS extraction). Non-keyframes before the first keyframe are dropped.
     *
     * @param annexB  Annex-B byte array (4-byte start codes) from H264ParameterSetCache
     * @param isKeyframe  true if this access unit contains an IDR slice
     * @param ptsUs  presentation timestamp in microseconds from MediaCodec.BufferInfo
     */
    fun feedFrame(annexB: ByteArray, isKeyframe: Boolean, ptsUs: Long) {
        if (released) return                        // fast volatile check, no lock
        synchronized(muxerLock) {
            if (released) return                    // double-check after acquiring lock
            if (!muxerStarted) {
                if (!isKeyframe) return             // wait for the first keyframe
                if (!initTrack(annexB)) return     // wait for a keyframe that contains SPS/PPS
            }
            writeFrame(annexB, isKeyframe, ptsUs)
        }
    }

    /**
     * Stop recording, finalize the MP4 file, and make it visible in Gallery/Files.
     * Safe to call from any thread. Blocks until any in-flight [feedFrame] completes.
     * If stopped before any keyframe was written, cleans up the empty file.
     */
    fun stop() {
        var wasMuxerStarted = false
        synchronized(muxerLock) {
            released = true                         // blocks future feedFrame() calls
            wasMuxerStarted = muxerStarted
            if (muxerStarted) {
                try { muxer?.stop() } catch (e: Exception) {
                    Log.e(TAG, "muxer.stop(): ${e.message}")
                }
            }
            try { muxer?.release() } catch (e: Exception) {
                Log.e(TAG, "muxer.release(): ${e.message}")
            }
            muxer = null
            muxerStarted = false
            trackIndex = -1
            isRecording = false
        }

        if (wasMuxerStarted) {
            finalizeOutput()
            Log.i(TAG, "Recording stopped & saved: ${recordingLocation()}")
        } else {
            cleanupOutput()
            Log.w(TAG, "Recording stopped before any keyframe arrived; deleted empty output.")
        }
    }

    /** Returns a display-friendly location (content:// URI on API 29+, file path on older). */
    fun recordingLocation(): String = when {
        mediaStoreUri != null -> mediaStoreUri.toString()
        outputFile != null -> outputFile!!.absolutePath
        else -> ""
    }

    /** JSON representation of the current recording status. */
    fun statusJson(): String = JSONObject().apply {
        val loc = recordingLocation()
        put("recording", isRecording)
        put("uri", loc)
        put("durationMs", if (isRecording) System.currentTimeMillis() - recordingStartTimeMs else 0)
        put("width", width)
        put("height", height)
    }.toString()

    // ---- Private implementation ----

    /**
     * Extract SPS/PPS from the first keyframe, build a MediaFormat, and start the muxer.
     * Must be called inside synchronized(muxerLock).
     * Returns true if track was successfully initialized, false if SPS/PPS missing.
     */
    private fun initTrack(annexB: ByteArray): Boolean {
        val ranges = H264Bitstream.nalRanges(annexB)
        val spsRange = ranges.firstOrNull { it.type == 7 }
        val ppsRange = ranges.firstOrNull { it.type == 8 }

        if (spsRange == null || ppsRange == null) {
            Log.w(TAG, "Keyframe missing SPS or PPS (SPS=${spsRange != null}, PPS=${ppsRange != null}); waiting for next keyframe")
            return false
        }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)

        // csd-0 (SPS) and csd-1 (PPS) include their Annex-B start codes
        val spsBytes = annexB.copyOfRange(spsRange.startCodeStart, spsRange.end)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(spsBytes))

        val ppsBytes = annexB.copyOfRange(ppsRange.startCodeStart, ppsRange.end)
        format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsBytes))

        val m = muxer ?: return false
        trackIndex = m.addTrack(format)
        m.start()
        muxerStarted = true
        Log.i(TAG, "Muxer track added: ${width}x${height}, SPS=${spsBytes.size}B, PPS=${ppsBytes.size}B")
        return true
    }

    /**
     * Convert one access unit from Annex-B to AVCC and write it to the muxer.
     * Strips SPS/PPS/AUD NALs from the frame data (they belong only in csd-0/csd-1).
     * Must be called inside synchronized(muxerLock).
     */
    private fun writeFrame(annexB: ByteArray, isKeyframe: Boolean, ptsUs: Long) {
        val m = muxer ?: return
        if (trackIndex < 0) return

        // Normalize PTS so the MP4 starts at 0
        if (firstPtsUs == Long.MIN_VALUE) firstPtsUs = ptsUs
        var normalizedPts = maxOf(0L, ptsUs - firstPtsUs)

        // Strict monotonicity check for MediaMuxer stability
        if (normalizedPts <= lastPtsUs) {
            normalizedPts = lastPtsUs + 1_000L // Bump by 1 ms
        }
        lastPtsUs = normalizedPts

        // Strip non-VCL NALs (SPS/PPS/AUD) but keep Annex-B start codes intact.
        // MediaMuxer handles Annex-B → AVCC conversion internally for MP4.
        val stripped = stripNonVclNals(annexB)
        if (stripped.isEmpty()) return

        val buf = ByteBuffer.wrap(stripped)
        val info = MediaCodec.BufferInfo().apply {
            offset = 0
            size = stripped.size
            presentationTimeUs = normalizedPts
            flags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        }

        try {
            m.writeSampleData(trackIndex, buf, info)
        } catch (e: Exception) {
            Log.e(TAG, "writeSampleData: ${e.message}")
        }
    }

    /**
     * Strip SPS (7), PPS (8), and AUD (9) NALs from Annex-B data, keeping start codes intact.
     * Only VCL NALs (types 1–5) and SEI (type 6) are retained for sample data.
     * SPS/PPS belong in the track's MediaFormat csd-0/csd-1, not in sample buffers.
     * MediaMuxer handles the Annex-B → AVCC conversion internally when writing MP4.
     */
    private fun stripNonVclNals(annexB: ByteArray): ByteArray {
        val ranges = H264Bitstream.nalRanges(annexB)
        // Keep only VCL slices (1–5) and SEI (6)
        val vclRanges = ranges.filter { it.type in 1..6 }
        if (vclRanges.isEmpty()) return ByteArray(0)

        // If all NALs are VCL, return the input as-is (common fast path)
        if (vclRanges.size == ranges.size) return annexB

        val out = ByteArrayOutputStream(annexB.size)
        for (range in vclRanges) {
            // Include the Annex-B start code + NAL data
            out.write(annexB, range.startCodeStart, range.end - range.startCodeStart)
        }
        return out.toByteArray()
    }

    /** Make the completed MP4 visible in Gallery / file managers. */
    private fun finalizeOutput() {
        try {
            pfd?.close()
            pfd = null
        } catch (e: Exception) {
            Log.e(TAG, "close pfd: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Clear IS_PENDING to make the file visible
            mediaStoreUri?.let { uri ->
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    context.contentResolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "finalize MediaStore: ${e.message}")
                }
            }
        } else {
            // Scan the file so it appears in Gallery
            outputFile?.let { file ->
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
            }
        }
    }

    /** Clean up and delete incomplete or aborted files/URIs. */
    private fun cleanupOutput() {
        try {
            pfd?.close()
            pfd = null
        } catch (e: Exception) {
            Log.e(TAG, "close pfd in cleanup: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreUri?.let { uri ->
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "cleanup MediaStore: ${e.message}")
                }
            }
        } else {
            outputFile?.let { file ->
                try {
                    if (file.exists()) file.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "cleanup legacy file: ${e.message}")
                }
            }
        }
        mediaStoreUri = null
        outputFile = null
    }

    private fun generateFilename(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "ipcam_${sdf.format(Date())}.mp4"
    }
}
