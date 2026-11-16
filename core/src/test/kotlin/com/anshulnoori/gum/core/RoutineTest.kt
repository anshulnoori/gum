package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutineTest {
    private enum class Mode { OFF, ON }

    private class Roller : Subsystem<Mode>(Mode.OFF) {
        override fun enter(mode: Mode) = Unit
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
                await(roller.request(Mode.ON))
                events += "on ${roller.mode}"
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

    @Test
    fun `awaiting a command that needs more than the routine reserves fails`() {
        lateinit var roller: Roller
        val error = assertFailsWith<IllegalStateException> {
            session { r ->
                roller = r
                onStart { routine { await(roller.request(Mode.ON)) }.schedule() }
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
