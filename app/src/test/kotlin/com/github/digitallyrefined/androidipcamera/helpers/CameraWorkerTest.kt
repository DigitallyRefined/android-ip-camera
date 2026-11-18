package com.github.digitallyrefined.androidipcamera.helpers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CameraWorkerTest {
    @Test
    fun `call returns the block's value`() = runBlocking<Unit> {
        val worker = CameraWorker("test")
        assertEquals(42, worker.call { 42 })
    }

    @Test
    fun `call rethrows the block's exception as it was thrown`() = runBlocking<Unit> {
        val worker = CameraWorker("test")
        try {
            worker.call<Unit> { throw IllegalStateException("Unknown camera ID 0") }
            fail("expected the block's exception")
        } catch (e: IllegalStateException) {
            assertEquals("Unknown camera ID 0", e.message)
        }
    }

    @Test
    fun `an open waits for the release queued before it`() = runBlocking<Unit> {
        val worker = CameraWorker("test")
        val order = Collections.synchronizedList(mutableListOf<String>())
        worker.release { Thread.sleep(200); order.add("release") }
        worker.call { order.add("open") }
        assertEquals(listOf("release", "open"), order)
    }

    @Test
    fun `work runs in queue order on the one named thread`() = runBlocking<Unit> {
        val worker = CameraWorker("Camera1")
        val order = Collections.synchronizedList(mutableListOf<String>())
        worker.release { order.add("release A on ${Thread.currentThread().name}") }
        worker.release { order.add("release B on ${Thread.currentThread().name}") }
        worker.call { order.add("open on ${Thread.currentThread().name}") }
        assertEquals(listOf("release A on Camera1", "release B on Camera1", "open on Camera1"), order)
    }

    @Test
    fun `a caller cancelled during the block hands its result to discard`() = runBlocking<Unit> {
        val worker = CameraWorker("test")
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val discarded = Collections.synchronizedList(mutableListOf<String>())
        val thrown = AtomicReference<Throwable>()
        val job = launch(Dispatchers.Default) {
            try {
                worker.call<String>(discard = { discarded.add(it) }) {
                    started.countDown()
                    gate.await()
                    "camera"
                }
            } catch (e: CancellationException) {
                thrown.set(e)
                throw e
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertTrue(thrown.get() is CancellationException)
        assertTrue(discarded.isEmpty())
        gate.countDown()
        // Queued behind the cancelled call, so it ends after the discard ran.
        worker.call { }
        assertEquals(listOf("camera"), discarded)
    }

    @Test
    fun `a caller cancelled while queued never runs its block`() = runBlocking<Unit> {
        val worker = CameraWorker("test")
        val gate = CountDownLatch(1)
        val ran = AtomicBoolean(false)
        val discards = AtomicInteger(0)
        worker.release { gate.await() }
        val job = launch(Dispatchers.Default) {
            worker.call<String>(discard = { discards.incrementAndGet() }) { ran.set(true); "camera" }
        }
        delay(150)
        job.cancel()
        job.join()
        gate.countDown()
        worker.call { }
        assertFalse(ran.get())
        assertEquals(0, discards.get())
    }

    @Test
    fun `awaitIdle waits for the last release`() = runBlocking<Unit> {
        val worker = CameraWorker("test")
        assertTrue(worker.awaitIdle(0))
        assertFalse(worker.busy)
        val gate = CountDownLatch(1)
        worker.release { gate.await() }
        assertTrue(worker.busy)
        assertFalse(worker.awaitIdle(120))
        gate.countDown()
        assertTrue(worker.awaitIdle(2000))
        assertFalse(worker.busy)
    }

    @Test
    fun `awaitIdle covers an open whose caller gave up and the release it queues`() = runBlocking<Unit> {
        // A start cancelled mid-open (the service destroyed): the next start must not bind a camera
        // while the abandoned open is still running, nor before the device it opened is released.
        val worker = CameraWorker("test")
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            worker.call<String>(discard = { Thread.sleep(150); released.set(true) }) {
                started.countDown()
                gate.await()
                "camera"
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertTrue(worker.busy)
        assertFalse(worker.awaitIdle(120))
        gate.countDown()
        assertTrue(worker.awaitIdle(2000))
        assertTrue(released.get())
    }

    @Test
    fun `discard runs on the worker thread`() = runBlocking<Unit> {
        val worker = CameraWorker("Camera1")
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val discardThread = AtomicReference<String>()
        val job = launch(Dispatchers.Default) {
            worker.call<String>(discard = { discardThread.set(Thread.currentThread().name) }) {
                started.countDown()
                gate.await()
                "camera"
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        gate.countDown()
        assertTrue(worker.awaitIdle(2000))
        assertEquals("Camera1", discardThread.get())
    }

    @Test
    fun `a failing release does not break the worker`() = runBlocking<Unit> {
        val worker = CameraWorker("test")
        worker.release { throw RuntimeException("release failed") }
        assertTrue(worker.awaitIdle(2000))
        assertEquals(5, worker.call { 5 })
    }
}
