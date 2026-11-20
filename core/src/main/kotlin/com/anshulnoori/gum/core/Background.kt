package com.anshulnoori.gum.core

import java.util.concurrent.locks.LockSupport

/**
 * Reads [read] over and over on its own thread, from now until STOP, waiting [periodMillis]
 * between reads. Use it for slow inputs, such as I2C sensors, so the OpMode loop does not wait for
 * each read: `private val distance = background { sensor.getDistance(DistanceUnit.MM) }`. A read
 * can still delay a bulk read, because the SDK sends to both hubs on a portal under one lock.
 * Vision processors and the Limelight already deliver results on their own threads; do not poll them here.
 *
 * [read] must only read. Outputs belong to subsystems on the OpMode thread. It runs on another
 * thread, so it cannot use `hardware` or `robot.battery`; get the device first.
 * A failed read stops the OpMode the next time the loop reads [Latest.value].
 * Call it while an OpMode runs, on its thread, such as in a subsystem's constructor.
 */
fun <T : Any> background(periodMillis: Long = 10, read: () -> T): Latest<T> {
    val session = checkNotNull(ProgramScope.current.get()) {
        "Start background reads while an OpMode runs, on the OpMode thread"
    }
    session.checkThread()
    return Latest(periodMillis, read).also(session::startBackground)
}

/** The most recent result of a [background] read and its age. */
class Latest<T : Any> internal constructor(private val periodMillis: Long, private val read: () -> T) {
    private class Sample<T>(val value: T, val nanos: Long)

    @Volatile private var sample: Sample<T>? = null
    @Volatile private var failure: Throwable? = null
    @Volatile private var running = true
    private val thread = Thread({ run() }, "Gum background read").apply { isDaemon = true }

    /** The latest result, or null before the first read finishes. */
    val value: T?
        get() {
            failure?.let { throw IllegalStateException("A background read failed", it) }
            return sample?.value
        }

    /** Seconds since the latest result was read; infinite before the first read finishes. */
    val ageSeconds: Double
        get() = sample?.let { (System.nanoTime() - it.nanos) / 1e9 } ?: Double.POSITIVE_INFINITY

    internal fun start() = thread.start()

    /** Asks the reader to stop after its current read. */
    internal fun requestStop() {
        running = false
        LockSupport.unpark(thread)
    }

    /** Waits until the reader has stopped or [deadline], on [System.nanoTime], has passed. */
    internal fun awaitStop(deadline: Long) {
        val left = deadline - System.nanoTime()
        if (left > 0) thread.join((left + 999_999) / 1_000_000)
    }

    private fun run() {
        while (running) {
            try {
                sample = Sample(read(), System.nanoTime())
            } catch (error: Throwable) {
                if (running) failure = error
                return
            }
            if (periodMillis > 0) LockSupport.parkNanos(periodMillis * 1_000_000)
        }
    }
}
