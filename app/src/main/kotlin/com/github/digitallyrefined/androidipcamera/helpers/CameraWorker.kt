package com.github.digitallyrefined.androidipcamera.helpers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * One background thread for blocking camera calls, run one at a time in the order they were queued.
 * Callers suspend instead of blocking, so a HAL that takes 20 s to open or close a camera never stalls
 * the main thread, and an open queued after a release always finds the device free.
 */
class CameraWorker(name: String) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }
    /** Work queued or running, so [busy] needs no suspension. */
    private val pending = AtomicInteger()

    /** True while some open, start or release queued here has not finished. */
    val busy: Boolean get() = pending.get() > 0

    /**
     * Runs [block] after everything queued before it and returns its result. A caller cancelled before
     * [block] starts skips it. One cancelled while it runs hands what it returns to [discard], so
     * nothing it opened is leaked. (withContext with another dispatcher drops that result instead.)
     * [discard] always runs on this thread, before anything queued after it, so [awaitIdle] covers it.
     */
    suspend fun <T> call(discard: (T) -> Unit = {}, block: () -> T): T {
        val state = AtomicInteger(QUEUED)
        val outcome = AtomicReference<Result<T>>()
        post {
            if (state.compareAndSet(QUEUED, RUNNING)) {
                outcome.set(runCatching(block))
                // The caller gave up while this ran, so release what it opened here.
                if (!state.compareAndSet(RUNNING, DONE)) outcome.get().onSuccess { discard(it) }
            }
        }
        try {
            while (state.get() != DONE) delay(POLL_MS)
        } catch (e: CancellationException) {
            // Finished just as the caller gave up, so release it on this thread too.
            if (state.getAndSet(ABANDONED) == DONE) outcome.get().onSuccess { post { discard(it) } }
            throw e
        }
        return outcome.get().getOrThrow()
    }

    /** Queues [release] behind everything queued so far. [awaitIdle] waits for it. */
    fun release(release: () -> Unit) = post(release)

    /**
     * Waits, without blocking a thread, until everything queued so far has finished, an open whose
     * caller gave up included. False after [timeoutMs].
     */
    suspend fun awaitIdle(timeoutMs: Long): Boolean {
        if (!busy) return true
        val barrier = executor.submit { }
        var waited = 0L
        while (!barrier.isDone && waited < timeoutMs) {
            delay(POLL_MS)
            waited += POLL_MS
        }
        return barrier.isDone
    }

    private fun post(work: () -> Unit) {
        pending.incrementAndGet()
        executor.execute {
            try { work() } catch (_: Exception) {} finally { pending.decrementAndGet() }
        }
    }

    private companion object {
        const val POLL_MS = 50L
        const val QUEUED = 0
        const val RUNNING = 1
        const val DONE = 2
        const val ABANDONED = 3
    }
}
