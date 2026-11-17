package com.anshulnoori.gum.core

/** One part of Gum's update, in the order it runs. */
enum class UpdatePhase(val label: String) {
    /** The host's work before the session's update, such as clearing the bulk cache and taking the newest gamepad packet. */
    INPUT("Input"),

    /** Applying button changes and running their actions. */
    BUTTONS("Buttons"),

    /** Ivy's `Scheduler.execute`: every running command, including routines. Only after START. */
    IVY("Ivy"),

    /** Every subsystem's `beforeCommands`, automatic transitions, and `during`. Only after START. */
    SUBSYSTEMS("Subsystems"),

    /** The program's own `initLoop`, `onStart`, or `loop` callbacks. */
    PROGRAM("Program"),

    /** Sending telemetry to the Driver Station. */
    TELEMETRY("Telemetry"),

    /** Sending telemetry and the field to Panels. */
    PANELS("Panels"),
}

/**
 * Times each [UpdatePhase] of every update and summarizes them once per [windowNanos]. It allocates
 * nothing per update: one clock read per phase, added to fixed counters.
 *
 * The host calls [begin] at the start of each update, then [end] as each phase finishes. Time
 * between the last phase of one update and the start of the next, such as the SDK's own checks,
 * counts as "other". Call it only from the session's thread.
 */
class UpdateTiming(
    private val windowNanos: Long = 1_000_000_000,
    private val clock: () -> Long = System::nanoTime,
) {
    /** One window's averages, in milliseconds. */
    class Report internal constructor(
        /** Updates the window holds. */
        val updates: Int,
        /** Average time from the start of one update to the start of the next. */
        val periodMillis: Double,
        /** Longest time from the start of one update to the start of the next. */
        val maxPeriodMillis: Double,
        private val phases: DoubleArray,
        /** Average time per update outside every phase. */
        val otherMillis: Double,
    ) {
        /** Updates per second. */
        val hertz: Double get() = if (periodMillis > 0) 1000 / periodMillis else 0.0

        /** Average time per update in [phase]. */
        fun millis(phase: UpdatePhase): Double = phases[phase.ordinal]
    }

    /** The latest complete window; null until the first one ends. A new object each window. */
    var report: Report? = null
        private set

    private val phaseNanos = LongArray(UpdatePhase.entries.size)
    private var otherNanos = 0L
    private var periodNanos = 0L
    private var maxPeriodNanos = 0L
    private var updates = 0
    private var windowStart = NONE
    private var updateStart = NONE
    private var lastMark = NONE

    /** Starts an update, which also ends the previous one; ends the window once it is long enough. */
    fun begin() {
        val now = clock()
        if (updateStart != NONE) {
            val period = now - updateStart
            periodNanos += period
            if (period > maxPeriodNanos) maxPeriodNanos = period
            otherNanos += now - lastMark
            updates++
        } else {
            windowStart = now
        }
        if (updates > 0 && now - windowStart >= windowNanos) {
            report = Report(
                updates,
                millis(periodNanos),
                maxPeriodNanos / 1e6,
                DoubleArray(phaseNanos.size) { millis(phaseNanos[it]) },
                millis(otherNanos),
            )
            phaseNanos.fill(0)
            otherNanos = 0
            periodNanos = 0
            maxPeriodNanos = 0
            updates = 0
            windowStart = now
        }
        updateStart = now
        lastMark = now
    }

    /** Ends [phase]: the time since the previous phase ended, or since [begin], counts toward it. */
    fun end(phase: UpdatePhase) {
        if (lastMark == NONE) return
        val now = clock()
        phaseNanos[phase.ordinal] += now - lastMark
        lastMark = now
    }

    private fun millis(total: Long) = total / 1e6 / updates

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
