package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutineTest {
    private enum class State { OFF, ON }

    private class Roller : Subsystem<State>(State.OFF) {
        override fun onEntry(state: State) = Unit
        fun on() = request(State.ON)
    }

    private fun session(setup: ProgramScope<Unit>.(Roller) -> Unit): OpModeSession =
        teleop<Unit, Roller>("Auto", dependencies = { Roller() }, setup = setup).initialize(Unit).also { it.start() }

    @Test
    fun `steps run in order, instant commands cost no update, and waits resume on later updates`() {
        val events = mutableListOf<String>()
        var ready = false
        lateinit var roller: Roller
        lateinit var command: Command
        val session = session { r ->
            roller = r
            command = routine(roller) {
                await(roller.on())
                events += "on ${roller.state}"
                waitUntil { ready }
                events += "ready"
                await(Command.build().setDone { events.size >= 3 }.setExecute { events += "child" })
                events += "done"
            }
            onStart { command.schedule() }
        }
        assertEquals(listOf("on ON"), events, "runs up to its first wait when scheduled")
        session.tick()
        ready = true
        session.tick()
        assertEquals(listOf("on ON", "ready"), events)
        session.tick()
        assertEquals(listOf("on ON", "ready", "child", "done"), events)
        assertFalse(command.isScheduled, "Ivy ends the routine once its body returns")
        session.stop()
    }

    @Test
    fun `cancelling ends the awaited command as interrupted and runs finally blocks`() {
        val events = mutableListOf<String>()
        lateinit var command: Command
        val session = session {
            command = routine {
                try {
                    await(Command.build().setDone { false }.setEnd { events += "child end $it" })
                } finally {
                    events += "finally"
                }
            }
            onStart { command.schedule() }
        }
        session.tick()
        command.cancel()
        assertEquals(listOf("child end INTERRUPTED", "finally"), events)
        session.stop()
    }

    private enum class Roll { OFF, ON, EJECT }

    private class TwoWay : Subsystem<Roll>(Roll.OFF) {
        override fun onEntry(state: Roll) = Unit
        fun on() = hold(Roll.ON)
        fun eject() = hold(Roll.EJECT)
        fun ejectOnce() = request(Roll.EJECT)
    }

    // Ivy 1.1.1's Scheduler.execute walks a copy of its running commands. When an earlier command
    // schedules one that interrupts a later one, the loop still reaches the interrupted command; if
    // it reports done, Ivy ends it again and removes the roller from the command that took it over.
    @Test
    fun `an interrupted routine or request never strips the roller from the command that took it over`() {
        for (victim in listOf("routine", "button request")) {
            var go = false
            var down = false
            lateinit var roller: TwoWay
            lateinit var on: Command
            val session = teleop<Unit, TwoWay>("Teleop", dependencies = { TwoWay() }) { r ->
                roller = r
                on = roller.on()
                val scheduler = routine { waitUntil { go }; on.schedule() }
                onStart { scheduler.schedule() }
                if (victim == "routine") {
                    val stuck = routine(roller) { waitUntil { false } }
                    onStart { stuck.schedule() }
                } else {
                    Button { down }.onPress { roller.ejectOnce().schedule() }
                }
            }.initialize(Unit)
            session.start()
            session.tick()
            go = true
            down = true
            session.tick()
            assertTrue(on.isScheduled, "$victim: hold(ON) took the roller")
            val eject = roller.eject()
            eject.schedule()
            assertFalse(on.isScheduled, "$victim: hold(EJECT) interrupts hold(ON), so one command owns the roller")
            assertTrue(eject.isScheduled)
            session.stop()
        }
    }

    @Test
    fun `awaiting a command that needs more than the routine reserves fails`() {
        lateinit var roller: Roller
        val error = assertFailsWith<IllegalStateException> {
            session { r ->
                roller = r
                onStart { routine { await(roller.on()) }.schedule() }
            }
        }
        assertTrue("routine(...)" in error.message.orEmpty())
    }

    @Test
    fun `an exception from the body stops the OpMode`() {
        val session = session {
            onStart { routine { nextUpdate(); error("jammed") }.schedule() }
        }
        val error = assertFailsWith<IllegalStateException> { session.tick() }
        assertEquals("jammed", error.message)
        assertEquals(SessionState.STOPPED, session.state)
    }

    private class Gate : Subsystem<Roll>(Roll.OFF) {
        var full = false
        override val automatic = automatic { Roll.ON to Roll.OFF whenever { full } }
        override fun onEntry(state: Roll) = Unit
        fun on() = request(Roll.ON)
        fun ejectOnce() = request(Roll.EJECT, from = Roll.OFF)
        fun ejecting() = hold(Roll.EJECT, from = Roll.OFF)
        fun onHeld() = hold(Roll.ON)
        fun opened() = reach(Roll.ON)
    }

    @Test
    fun `await returns how each command ended`() {
        val outcomes = mutableListOf<Outcome>()
        lateinit var gate: Gate
        val session = teleop<Unit, Gate>("Auto", dependencies = { Gate() }) { g ->
            gate = g
            val steps = routine(gate) {
                outcomes += await(gate.on())
                outcomes += await(gate.ejectOnce().withTimeout(10.0))
                outcomes += await(gate.ejecting())
                outcomes += await(gate.onHeld())
                outcomes += await(gate.opened().withTimeout(0.05))
                outcomes += await(routine(gate) { nextUpdate() }.withTimeout(10.0))
            }
            onStart { steps.schedule() }
        }.initialize(Unit)
        session.start()
        assertEquals(listOf(Outcome.FINISHED, Outcome.REFUSED, Outcome.REFUSED), outcomes)
        assertEquals(Roll.ON, gate.state, "refused requests leave the state alone, and a timeout keeps the refusal")
        session.tick()
        gate.full = true
        session.tick()
        session.tick()
        assertEquals(Outcome.INTERRUPTED, outcomes.last(), "the gate left ON by itself, cutting the hold short")
        assertEquals(Roll.OFF, gate.state)
        session.tick()
        assertEquals(4, outcomes.size, "reach(ON) waits until its timeout")
        Thread.sleep(80)
        session.tick()
        assertEquals(Outcome.TIMED_OUT, outcomes.last())
        session.tick()
        assertEquals(
            listOf(Outcome.FINISHED, Outcome.REFUSED, Outcome.REFUSED, Outcome.INTERRUPTED, Outcome.TIMED_OUT, Outcome.FINISHED),
            outcomes,
            "a command that finishes in time keeps its own outcome",
        )
        session.stop()
    }

    @Test
    fun `a timeout counts from when the command starts and ends it as interrupted`() {
        val events = mutableListOf<String>()
        lateinit var limited: Command
        lateinit var outer: Command
        val session = session {
            val stuck = { name: String ->
                routine {
                    try {
                        waitUntil { false }
                    } finally {
                        events += "$name finally"
                    }
                }
            }
            limited = stuck("scheduled").withTimeout(0.05)
            outer = routine { events += "awaited ${await(stuck("awaited").withTimeout(0.05))}" }
            Thread.sleep(80)
            onStart { limited.schedule(); outer.schedule() }
        }
        session.tick()
        assertTrue(limited.isScheduled, "the time before START does not count")
        assertEquals(emptyList(), events)
        Thread.sleep(80)
        session.tick()
        assertFalse(limited.isScheduled)
        assertFalse(outer.isScheduled)
        assertEquals(listOf("scheduled finally", "awaited finally", "awaited TIMED_OUT"), events)
        session.stop()
    }
}
