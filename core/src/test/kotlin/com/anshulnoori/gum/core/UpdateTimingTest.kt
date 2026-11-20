package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class UpdateTimingTest {
    private var clockNanos = 0L
    private fun at(millis: Int) {
        clockNanos = millis * 1_000_000L
    }

    @Test
    fun `each window reports per-update averages, the longest update, and time outside every phase`() {
        val timing = UpdateTiming(windowNanos = 10_000_000) { clockNanos }

        at(0); timing.begin()
        at(1); timing.end(UpdatePhase.INPUT)
        at(4); timing.end(UpdatePhase.PROGRAM)
        at(6); timing.end(UpdatePhase.PANELS)
        at(7); timing.begin()
        at(10); timing.end(UpdatePhase.INPUT)
        at(11); timing.end(UpdatePhase.PROGRAM)
        at(13); timing.end(UpdatePhase.PANELS)
        assertNull(timing.report, "no report before the window ends")
        at(17); timing.begin()

        val first = timing.report!!
        assertEquals(2, first.updates)
        assertEquals(8.5, first.periodMillis)
        assertEquals(10.0, first.maxPeriodMillis)
        assertEquals(1000 / 8.5, first.hertz)
        assertEquals(2.0, first.millis(UpdatePhase.INPUT))
        assertEquals(2.0, first.millis(UpdatePhase.PROGRAM))
        assertEquals(2.0, first.millis(UpdatePhase.PANELS))
        assertEquals(0.0, first.millis(UpdatePhase.IVY))
        assertEquals(2.5, first.otherMillis)

        // The next window starts empty, including its maximum.
        at(18); timing.end(UpdatePhase.INPUT)
        at(20); timing.begin()
        assertSame(first, timing.report, "the report stays until the next window ends")
        at(21); timing.end(UpdatePhase.INPUT)
        at(27); timing.begin()

        val second = timing.report!!
        assertNotSame(first, second)
        assertEquals(2, second.updates)
        assertEquals(5.0, second.periodMillis)
        assertEquals(7.0, second.maxPeriodMillis)
        assertEquals(1.0, second.millis(UpdatePhase.INPUT))
        assertEquals(0.0, second.millis(UpdatePhase.PROGRAM))
        assertEquals(4.0, second.otherMillis)
    }

    enum class State { ON }

    private inner class Slow : Subsystem() {
        init {
            region(State.ON) { during { clockNanos += 5_000_000 } }
        }
    }

    @Test
    fun `a session's tick charges buttons, Ivy, and subsystems to their own phases`() {
        val timing = UpdateTiming(windowNanos = 1) { clockNanos }
        val session = teleop<Unit, Unit>("Timed", dependencies = { Slow() }) {
            Button { clockNanos += 2_000_000; false }
            onStart {
                Command.build().setExecute { clockNanos += 3_000_000 }.setDone { false }.schedule()
            }
            loop { clockNanos += 7_000_000 }
        }.initialize(Unit, timing)
        session.start()

        at(0); timing.begin()
        session.tick()
        timing.end(UpdatePhase.PROGRAM)
        timing.begin()
        session.stop()

        val report = timing.report!!
        assertEquals(2.0, report.millis(UpdatePhase.BUTTONS))
        assertEquals(3.0, report.millis(UpdatePhase.IVY))
        assertEquals(5.0, report.millis(UpdatePhase.SUBSYSTEMS))
        assertEquals(7.0, report.millis(UpdatePhase.PROGRAM))
        assertEquals(17.0, report.periodMillis)
        assertEquals(0.0, report.otherMillis)
    }
}
