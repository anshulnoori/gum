package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.abs

class SubsystemTest {
    enum class State { OFF, COLLECTING, EJECTING }

    /** Records every state it enters, so tests see outputs, not just the reported state. */
    private open class Roller : Subsystem<State>(State.OFF) {
        val entered = mutableListOf<String>()
        var full = false
        override val automatic = automatic {
            State.COLLECTING to State.OFF whenever { full } effect { entered += "full" }
        }

        override fun onEntry(state: State) {
            entered += state.name
        }

        fun collect() = request(State.COLLECTING)
        fun eject() = request(State.EJECTING)
        fun stop() = request(State.OFF)
        fun ejectFromOff() = request(State.EJECTING, from = State.OFF) { entered += "eject effect" }
        fun collecting() = hold(State.COLLECTING)
    }

    enum class Speed { OFF, SPINNING_UP, READY }

    /** A flywheel with a fake speed sensor and a proportional controller. */
    private class Flywheel : Subsystem<Speed>(Speed.OFF) {
        var rpm = 0.0
        var target = 0.0
        var power = 0.0
        val entered = mutableListOf<Speed>()
        override val automatic = automatic {
            Speed.SPINNING_UP to Speed.READY whenever { abs(target - rpm) < 50 }
        }

        override fun onEntry(state: Speed) {
            entered += state
            if (state == Speed.OFF) power = 0.0
        }

        override fun during(state: Speed) {
            if (state != Speed.OFF) power = 0.001 * (target - rpm)
        }

        fun spinTo(rpm: Double) = request(Speed.SPINNING_UP) { target = rpm }
        fun boost(rpm: Double) = request(Speed.READY, from = setOf(Speed.SPINNING_UP)) { target = rpm }
        fun spinUp() = spinTo(3000.0).then(reach(Speed.READY))
    }

    private val refusals = mutableListOf<String>()

    private fun <S> session(create: () -> S): Pair<OpModeSession, S> {
        var subsystem: S? = null
        val session = teleop<Unit, S>("Test", dependencies = { create() }) {
            subsystem = it
            onRefused { refusals += it }
        }.initialize(Unit)
        session.start()
        @Suppress("UNCHECKED_CAST")
        return session to (subsystem as S)
    }

    @Test
    fun `any state can be requested and each entry sets outputs`() {
        val (session, roller) = session { Roller() }
        session.tick()
        roller.collect().schedule()
        roller.eject().schedule()
        roller.eject().schedule()
        session.tick()
        assertEquals(State.EJECTING, roller.state)
        assertEquals(listOf("OFF", "COLLECTING", "EJECTING"), roller.entered, "re-requesting a state does not re-enter it")
        assertEquals(emptyList(), refusals)
        session.stop()
    }

    @Test
    fun `a subsystem enters its initial state at INIT, before anything reads it, and not again at START`() {
        lateinit var roller: Roller
        val session = teleop<Unit, Roller>("Test", dependencies = { Roller() }) { roller = it }.initialize(Unit)
        assertEquals(listOf("OFF"), roller.entered)
        session.tick()
        session.start()
        session.tick()
        assertEquals(listOf("OFF"), roller.entered)
        session.stop()
    }

    @Test
    fun `a request from a state it does not name is refused and reported`() {
        val (session, roller) = session { Roller() }
        roller.collect().schedule()
        roller.ejectFromOff().schedule()
        session.tick()
        assertEquals(State.COLLECTING, roller.state, "eject is valid only from Off")
        assertEquals(listOf("Roller refused EJECTING: it is COLLECTING, and EJECTING is requested only from OFF"), refusals)

        roller.stop().schedule()
        roller.ejectFromOff().schedule()
        session.tick()
        assertEquals(listOf("OFF", "COLLECTING", "OFF", "eject effect", "EJECTING"), roller.entered, "the effect runs only when taken, before entry")
        assertEquals(1, refusals.size)
        session.stop()
    }

    @Test
    fun `automatic transitions happen on the next update after commands and before program loops`() {
        val events = mutableListOf<String>()
        lateinit var roller: Roller
        val session = teleop<Unit, Roller>("Test", dependencies = { Roller() }) {
            roller = it
            loop { events += "loop sees ${it.state}" }
        }.initialize(Unit)
        session.start()
        roller.collect().schedule()
        session.tick()
        roller.full = true
        session.tick()

        // A sequence starts its second step inside Ivy's update; the automatic transition must still see it.
        roller.stop().schedule()
        Command.build().setDone { true }.then(roller.collect()).schedule()
        session.tick()
        assertEquals(listOf("loop sees COLLECTING", "loop sees OFF", "loop sees OFF"), events)
        assertEquals(listOf("OFF", "COLLECTING", "full", "OFF", "COLLECTING", "full", "OFF"), roller.entered)
        session.stop()
    }

    @Test
    fun `the first declared automatic transition that matches wins, one per update`() {
        val (session, roller) = session {
            object : Roller() {
                override val automatic = automatic {
                    State.COLLECTING to State.EJECTING whenever { full }
                    anyState to State.OFF whenever { full }
                    State.COLLECTING to State.OFF whenever { true }
                }
            }
        }
        roller.full = true
        roller.collect().schedule()
        session.tick()
        assertEquals(State.EJECTING, roller.state, "declared first, so it wins over the later matches")
        session.tick()
        assertEquals(State.OFF, roller.state, "the next update takes the next transition from the new state")
        assertEquals(listOf("OFF", "COLLECTING", "EJECTING", "OFF"), roller.entered)
        session.stop()
    }

    @Test
    fun `an automatic transition without a condition fails when the subsystem is created`() {
        assertFailsWith<IllegalStateException> {
            session {
                object : Roller() {
                    override val automatic = automatic { State.COLLECTING to State.OFF }
                }
            }
        }
    }

    @Test
    fun `a hold ends when an automatic transition leaves its state, and otherwise returns to the initial state`() {
        val (session, roller) = session { Roller() }
        val first = roller.collecting()
        first.schedule()
        session.tick()
        assertTrue(first.isScheduled)
        roller.full = true
        session.tick()
        assertEquals(State.OFF, roller.state)
        session.tick()
        assertFalse(first.isScheduled, "the automatic transition ended the hold")

        roller.full = false
        roller.eject().schedule()
        session.tick()
        val second = roller.collecting()
        second.schedule()
        session.tick()
        assertEquals(State.COLLECTING, roller.state)
        second.cancel()
        assertEquals(State.OFF, roller.state, "a cancelled hold returns to the initial state")
        session.stop()
    }

    @Test
    fun `STOP returns to the initial state even from a state no command leaves`() {
        val (session, roller) = session { Roller() }
        roller.ejectFromOff().schedule()
        session.tick()
        session.stop()
        assertEquals(State.OFF, roller.state)
        assertEquals(listOf("OFF", "eject effect", "EJECTING", "OFF"), roller.entered)
    }

    @Test
    fun `subsystems belong to a running session`() {
        assertFailsWith<IllegalStateException> { Roller() }
        val (session, _) = session { Roller() }
        session.stop()
        assertFailsWith<IllegalStateException> { Roller() }
    }

    @Test
    fun `during runs control after commands each update, and reach waits for the state`() {
        val (session, flywheel) = session { Flywheel() }
        val spinUp = flywheel.spinUp()
        spinUp.schedule()
        session.tick()
        assertEquals(Speed.SPINNING_UP, flywheel.state)
        assertEquals(3.0, flywheel.power, 1e-9, "control ran in the update that requested the state")

        flywheel.rpm = 2980.0
        session.tick()
        assertEquals(Speed.READY, flywheel.state)
        assertEquals(0.02, flywheel.power, 1e-9, "control runs after the automatic transition")
        assertTrue(spinUp.isScheduled, "reach finishes on the next command pass")
        session.tick()
        assertFalse(spinUp.isScheduled)
        session.stop()
        assertEquals(0.0, flywheel.power)
    }

    @Test
    fun `an effect runs only when its transition is taken, and the current state only runs it`() {
        val (session, flywheel) = session { Flywheel() }
        flywheel.boost(-1.0).schedule()
        session.tick()
        assertEquals(0.0, flywheel.target, "boost from Off is refused, so its effect does not run")
        flywheel.spinTo(3000.0).schedule()
        session.tick()
        flywheel.spinTo(1000.0).schedule()
        session.tick()
        assertEquals(1000.0, flywheel.target)
        assertEquals(listOf(Speed.OFF, Speed.SPINNING_UP), flywheel.entered, "the same state is not re-entered")
        session.stop()
    }
}
