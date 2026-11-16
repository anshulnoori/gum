package com.anshulnoori.gum.core

import java.util.concurrent.locks.LockSupport

/**
 * Reads [read] over and over on its own thread, from now until STOP, waiting [periodMillis]
 * between reads. Use it for slow inputs, such as I2C sensors or vision, so the OpMode loop never
 * waits for them: `private val distance = background { sensor.getDistance(DistanceUnit.MM) }`.
 *
 * [read] must only read. Outputs belong to subsystems on the OpMode thread.
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

    /** Stops reading and waits briefly for an in-progress read to finish. */
    internal fun stop() {
        running = false
        LockSupport.unpark(thread)
        thread.join(250)
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
