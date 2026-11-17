package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.abs

class SubsystemTest {
    enum class Mode { OFF, COLLECTING, EJECTING }

    /** Records every mode it enters, so tests see outputs, not just the reported mode. */
    private open class Roller : Subsystem<Mode>(Mode.OFF) {
        val entered = mutableListOf<Mode>()
        override fun enter(mode: Mode) {
            entered += mode
        }
    }

    /** Eject only from Off, stop from anything, and stop by itself when full. */
    private class GuardedRoller : Roller() {
        var full = false
        override val transitions = transitions {
            Mode.OFF to Mode.COLLECTING
            Mode.OFF to Mode.EJECTING
            anyMode to Mode.OFF
            Mode.COLLECTING to Mode.OFF whenever { full }
        }
    }

    enum class Speed { OFF, SPINNING_UP, READY }

    /** A flywheel with a fake speed sensor and a proportional controller. */
    private class Flywheel : Subsystem<Speed>(Speed.OFF) {
        var rpm = 0.0
        var target = 0.0
        var power = 0.0
        val entered = mutableListOf<Speed>()
        override val transitions = transitions {
            anyMode to Speed.SPINNING_UP
            anyMode to Speed.OFF
            Speed.SPINNING_UP to Speed.READY whenever { abs(target - rpm) < 50 }
        }

        override fun enter(mode: Speed) {
            entered += mode
            if (mode == Speed.OFF) power = 0.0
        }

        override fun loop(mode: Speed) {
            if (mode != Speed.OFF) power = 0.001 * (target - rpm)
        }

        fun spinTo(rpm: Double) = request(Speed.SPINNING_UP) { target = rpm }
    }

    private fun <S> session(create: () -> S): Pair<OpModeSession, S> {
        var subsystem: S? = null
        val session = teleop<Unit, S>("Test", dependencies = { create() }) { subsystem = it }.initialize(Unit)
        session.start()
        @Suppress("UNCHECKED_CAST")
        return session to (subsystem as S)
    }

    @Test
    fun `without transitions any mode can be requested and each entry sets outputs`() {
        val (session, roller) = session { Roller() }
        session.tick()
        roller.request(Mode.COLLECTING).schedule()
        roller.request(Mode.EJECTING).schedule()
        roller.request(Mode.EJECTING).schedule()
        session.tick()
        assertEquals(Mode.EJECTING, roller.mode)
        assertEquals(listOf(Mode.OFF, Mode.COLLECTING, Mode.EJECTING), roller.entered, "re-requesting a mode does not re-enter it")
        session.stop()
    }

    @Test
    fun `a subsystem enters its initial mode at INIT, before anything reads it, and not again at START`() {
        lateinit var roller: Roller
        val session = teleop<Unit, Roller>("Test", dependencies = { Roller() }) { roller = it }.initialize(Unit)
        assertEquals(listOf(Mode.OFF), roller.entered)
        session.tick()
        session.start()
        session.tick()
        assertEquals(listOf(Mode.OFF), roller.entered)
        session.stop()
    }

    @Test
    fun `a move transitions does not allow leaves the mode unchanged`() {
        val (session, roller) = session { GuardedRoller() }
        roller.request(Mode.COLLECTING).schedule()
        roller.request(Mode.EJECTING).schedule()
        session.tick()
        assertEquals(Mode.COLLECTING, roller.mode, "eject is only allowed from Off")

        roller.request(Mode.OFF).schedule()
        roller.request(Mode.EJECTING).schedule()
        session.tick()
        assertEquals(listOf(Mode.OFF, Mode.COLLECTING, Mode.OFF, Mode.EJECTING), roller.entered)
        session.stop()
    }

    @Test
    fun `automatic moves happen on the next update after commands and before program loops`() {
        val events = mutableListOf<String>()
        lateinit var roller: GuardedRoller
        val session = teleop<Unit, GuardedRoller>("Test", dependencies = { GuardedRoller() }) {
            roller = it
            loop { events += "loop sees ${it.mode}" }
        }.initialize(Unit)
        session.start()
        roller.request(Mode.COLLECTING).schedule()
        session.tick()
        roller.full = true
        session.tick()

        // A sequence starts its second step inside Ivy's update; the automatic move must still see it.
        roller.request(Mode.OFF).schedule()
        Command.build().setDone { true }.then(roller.request(Mode.COLLECTING)).schedule()
        session.tick()
        assertEquals(listOf("loop sees COLLECTING", "loop sees OFF", "loop sees OFF"), events)
        assertEquals(listOf(Mode.OFF, Mode.COLLECTING, Mode.OFF, Mode.COLLECTING, Mode.OFF), roller.entered)
        session.stop()
    }

    @Test
    fun `an automatic move cannot be requested by a command`() {
        val (session, roller) = session {
            object : Roller() {
                override val transitions = transitions {
                    Mode.OFF to Mode.COLLECTING
                    Mode.COLLECTING to Mode.EJECTING whenever { false }
                }
            }
        }
        roller.request(Mode.COLLECTING).schedule()
        roller.request(Mode.EJECTING).schedule()
        session.tick()
        assertEquals(Mode.COLLECTING, roller.mode)
        session.stop()
    }

    @Test
    fun `STOP returns to the initial mode even when no declared move leads there`() {
        val (session, roller) = session {
            object : Roller() {
                override val transitions = transitions { Mode.OFF to Mode.COLLECTING }
            }
        }
        roller.request(Mode.COLLECTING).schedule()
        session.tick()
        session.stop()
        assertEquals(Mode.OFF, roller.mode)
        assertEquals(listOf(Mode.OFF, Mode.COLLECTING, Mode.OFF), roller.entered)
    }

    @Test
    fun `subsystems belong to a running session`() {
        assertFailsWith<IllegalStateException> { Roller() }
        val (session, _) = session { Roller() }
        session.stop()
        assertFailsWith<IllegalStateException> { Roller() }
    }

    @Test
    fun `loop runs control after commands each update, and reach waits for the mode`() {
        val (session, flywheel) = session { Flywheel() }
        val spinUp = flywheel.spinTo(3000.0).then(flywheel.reach(Speed.READY))
        spinUp.schedule()
        session.tick()
        assertEquals(Speed.SPINNING_UP, flywheel.mode)
        assertEquals(3.0, flywheel.power, 1e-9, "control ran in the update that requested the mode")

        flywheel.rpm = 2980.0
        session.tick()
        assertEquals(Speed.READY, flywheel.mode)
        assertEquals(0.02, flywheel.power, 1e-9, "control runs after the automatic move")
        assertTrue(spinUp.isScheduled, "reach finishes on the next command pass")
        session.tick()
        assertFalse(spinUp.isScheduled)
        session.stop()
        assertEquals(0.0, flywheel.power)
    }

    @Test
    fun `request data is set only when the move is allowed, and the current mode only updates it`() {
        val (session, flywheel) = session { Flywheel() }
        flywheel.spinTo(3000.0).schedule()
        session.tick()
        flywheel.spinTo(1000.0).schedule()
        flywheel.request(Speed.READY) { flywheel.target = -1.0 }.schedule()
        session.tick()
        assertEquals(1000.0, flywheel.target)
        assertEquals(listOf(Speed.OFF, Speed.SPINNING_UP), flywheel.entered, "the same mode is not re-entered")
        session.stop()
    }
}
