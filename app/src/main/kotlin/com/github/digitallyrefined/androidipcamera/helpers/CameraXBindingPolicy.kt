package com.github.digitallyrefined.androidipcamera.helpers

/** One CameraX bind attempt: which optional use cases are included. ImageAnalysis is always bound. */
internal data class CameraXBinding(
    val display: Boolean,
    val encoder: Boolean,
    val still: Boolean,
)

/**
 * Bind attempts in order: the full request first, then progressively shed optional use cases.
 *
 * The H.264 encoder surface is kept for as long as possible — the CPU-fed YUV fallback cannot
 * sustain full-HD frame rates on most phones. The on-phone preview is kept before the
 * full-resolution still use case, whose snapshots fall back to the analysis frame; devices with a
 * concurrent-stream limit can then still show the preview while streaming or recording.
 */
internal object CameraXBindingPolicy {
    fun candidates(display: Boolean, encoder: Boolean, still: Boolean): List<CameraXBinding> {
        val encoderFirst = listOf(true, false)
        val displayFirst = listOf(true, false)
        val stillFirst = listOf(true, false)
        return encoderFirst.flatMap { keepEncoder ->
            displayFirst.flatMap { keepDisplay ->
                stillFirst.map { keepStill ->
                    CameraXBinding(
                        display = display && keepDisplay,
                        encoder = encoder && keepEncoder,
                        still = still && keepStill,
                    )
                }
            }
        }.distinct()
    }
}
