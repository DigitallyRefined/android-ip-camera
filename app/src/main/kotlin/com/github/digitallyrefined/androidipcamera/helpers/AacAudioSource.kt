package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import androidx.preference.PreferenceManager
import java.io.IOException
import kotlin.math.abs

/**
 * Shared microphone capture for the RTSP audio track: one AudioRecord feeding one AAC-LC encoder,
 * whose access units ([onAccessUnit], with presentation time in µs on System.nanoTime) go to every
 * session playing audio. Honours the same audio_gain control as the HTTP audio routes.
 */
internal class AacAudioSource(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onAccessUnit: (ByteArray, Long) -> Unit
) : RtspAudioSource {
    companion object {
        val FORMAT = AacFormat(sampleRate = 44100, channels = 1)
        private const val BIT_RATE = 64_000
        private const val SAMPLES_PER_FRAME = 1024
        /** Re-anchor the sample-count clock if it drifts this far from the system clock. */
        private const val MAX_DRIFT_NS = 100_000_000L
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start() {
        running = true
        thread = Thread({ captureLoop() }, "RtspAudio").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        try { thread?.join(1_000) } catch (_: InterruptedException) {}
        thread = null
    }

    private fun captureLoop() {
        var record: AudioRecord? = null
        var codec: MediaCodec? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                FORMAT.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw IOException("Invalid AudioRecord buffer size: $minBuffer")
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                FORMAT.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, SAMPLES_PER_FRAME * 2 * 4)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) throw IOException("AudioRecord could not be initialized")

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, FORMAT.sampleRate, FORMAT.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLES_PER_FRAME * 2 * 4)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            record.startRecording()

            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val pcm = ByteArray(SAMPLES_PER_FRAME * 2)
            val info = MediaCodec.BufferInfo()
            var anchorNs = 0L
            var samples = 0L
            while (running) {
                val read = record.read(pcm, 0, pcm.size)
                if (read < 0) throw IOException("AudioRecord.read failed: $read")
                if (read == 0) continue
                val count = read / 2

                // Timestamps from the sample count stay evenly spaced (AAC frames 1024 samples
                // apart); the clock only re-anchors them when capture stalls or drifts.
                val capturedNs = System.nanoTime() - count * 1_000_000_000L / FORMAT.sampleRate
                val expectedNs = anchorNs + samples * 1_000_000_000L / FORMAT.sampleRate
                if (samples == 0L || abs(expectedNs - capturedNs) > MAX_DRIFT_NS) {
                    anchorNs = capturedNs
                    samples = 0
                }
                val ptsUs = (anchorNs + samples * 1_000_000_000L / FORMAT.sampleRate) / 1000
                samples += count

                val gain = prefs.getString("audio_gain", "1.0")?.toFloatOrNull() ?: 1.0f
                if (gain != 1.0f) applyGain(pcm, read, gain)

                val input = codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val buffer = codec.getInputBuffer(input)
                    if (buffer != null) {
                        buffer.clear()
                        buffer.put(pcm, 0, read)
                        codec.queueInputBuffer(input, 0, read, ptsUs, 0)
                    } else {
                        codec.queueInputBuffer(input, 0, 0, ptsUs, 0)
                    }
                }
                drain(codec, info)
            }
        } catch (e: SecurityException) {
            onLog("RTSP audio unavailable: microphone permission not granted")
        } catch (e: Exception) {
            if (running) onLog("RTSP audio stopped: ${e.message}")
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            try { record?.release() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index < 0) continue // output format / buffers changed
            try {
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val buffer = codec.getOutputBuffer(index)
                if (!isConfig && buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val accessUnit = ByteArray(info.size)
                    buffer.get(accessUnit)
                    onAccessUnit(accessUnit, info.presentationTimeUs)
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }
    }

    /** In-place gain on 16-bit little-endian PCM, clamped to avoid wrap-around. */
    private fun applyGain(buffer: ByteArray, length: Int, gain: Float) {
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)).toShort()
            val amplified = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = amplified.toByte()
            buffer[i + 1] = (amplified shr 8).toByte()
            i += 2
        }
    }
}
