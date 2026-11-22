package com.github.digitallyrefined.androidipcamera.helpers

/** An advertised camera capture-FPS range in whole frames per second. */
data class FpsRange(val lower: Int, val upper: Int)

/**
 * Picks the advertised range that should be requested for [target] FPS.
 *
 * Cameras advertise ranges such as `[7,30]` and `[30,30]`. Picking the widest range (as the
 * previous code did) lets auto-exposure fall all the way to the lower bound in low light, so a
 * request for 30 FPS could record at 7 FPS. Prefer instead the tightest range that contains the
 * target, which for most devices is the fixed `[target,target]` range:
 *
 *  1. the range containing the target with the smallest upper bound,
 *     then the highest lower bound (an exact lock wins);
 *  2. otherwise the highest range below the target;
 *  3. otherwise the lowest range above the target.
 *
 * Only advertised ranges are returned; this never invents an unsupported rate.
 */
object CaptureFps {
    fun select(ranges: List<FpsRange>, target: Int): FpsRange? {
        if (target <= 0) return null
        val valid = ranges
            .filter { it.lower > 0 && it.upper >= it.lower }
            .distinct()
        return valid.filter { target in it.lower..it.upper }
            .minWithOrNull(compareBy({ it.upper }, { -it.lower }))
            ?: valid.filter { it.upper < target }
                .maxWithOrNull(compareBy({ it.upper }, { it.lower }))
            ?: valid.minWithOrNull(compareBy({ it.lower }, { it.upper }))
    }
}
