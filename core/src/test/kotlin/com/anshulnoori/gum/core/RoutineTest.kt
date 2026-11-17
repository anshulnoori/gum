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
}
