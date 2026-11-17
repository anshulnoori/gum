package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ButtonTest {
    @Test
    fun `INIT polls edges, actions start after START, and a held button waits for a release`() {
        var down = false
        val events = mutableListOf<String>()
        lateinit var button: Button
        val session = teleop<Unit, Unit>("Driver", dependencies = { Unit }) {
            button = Button { down }
            button.onPress { events += "press" }
            button.onRelease { events += "release" }
            initLoop { if (button.wasPressed) events += "init saw press" }
        }.initialize(Unit)

        down = true
        session.tick()
        session.tick()
        session.start()
        session.tick()
        assertEquals(listOf("init saw press"), events, "INIT presses are polled, not acted on; held through START is no press")

        down = false
        session.tick()
        down = true
        session.tick()
        session.tick()
        session.stop()
        assertEquals(listOf("init saw press", "release", "press"), events)
    }

    @Test
    fun `a command scheduled by a press starts and runs before program loops`() {
        var down = false
        val events = mutableListOf<String>()
        val session = teleop<Unit, Unit>("Driver", dependencies = { Unit }) {
            Button { down }.onPress {
                Command.build()
                    .setStart { events += "start" }
                    .setExecute { events += "execute" }
                    .schedule()
            }
            loop { events += "loop" }
        }.initialize(Unit)
        session.start()
        down = true
        session.tick()
        session.stop()

        assertEquals(listOf("start", "execute", "loop"), events)
    }

    @Test
    fun `STOP from an action skips the remaining actions`() {
        var down = false
        val events = mutableListOf<String>()
        lateinit var session: OpModeSession
        session = teleop<Unit, Unit>("Driver", dependencies = { Unit }) {
            val button = Button { down }
            button.onPress { events += "first"; session.stop() }
            button.onPress { events += "second" }
            Button { down }.onPress { events += "other button" }
            loop { events += "loop" }
        }.initialize(Unit)
        session.start()
        down = true
        session.tick()

        assertEquals(listOf("first"), events)
    }

    @Test
    fun `buttons need a session and bind only during setup`() {
        assertFailsWith<IllegalStateException> { Button { false } }
        lateinit var button: Button
        val session = teleop<Unit, Unit>("Driver", dependencies = { Unit }) { button = Button { false } }.initialize(Unit)
        val error = assertFailsWith<IllegalStateException> { button.onPress { } }
        assertTrue("setup" in error.message.orEmpty())
        session.stop()
    }

    private enum class State { OFF, COLLECTING, EJECTING }

    private class Roller : Subsystem<State>(State.OFF) {
        val entered = mutableListOf<State>()
        override fun onEntry(state: State) { entered += state }
        fun collecting() = hold(State.COLLECTING)
        fun ejecting() = hold(State.EJECTING)
    }

    @Test
    fun `whileHeld holds a state while down, and another held button takes over`() {
        var collect = false
        var eject = false
        lateinit var roller: Roller
        val session = teleop<Unit, Roller>("Driver", dependencies = { Roller() }) {
            roller = it
            Button { collect }.whileHeld { roller.collecting() }
            Button { eject }.whileHeld { roller.ejecting() }
        }.initialize(Unit)
        session.start()

        collect = true
        session.tick()
        session.tick()
        assertEquals(State.COLLECTING, roller.state, "held across updates")
        collect = false
        session.tick()
        assertEquals(State.OFF, roller.state, "release returns to the initial state")

        collect = true
        session.tick()
        eject = true
        session.tick()
        assertEquals(State.EJECTING, roller.state, "the newer hold interrupts the older one")
        collect = false
        session.tick()
        assertEquals(State.EJECTING, roller.state, "releasing the interrupted button changes nothing")
        eject = false
        session.tick()
        session.stop()
        assertEquals(
            listOf(State.OFF, State.COLLECTING, State.OFF, State.COLLECTING, State.OFF, State.EJECTING, State.OFF),
            roller.entered,
        )
    }

    @Test
    fun `reported changes apply at the next update in arrival order, so a tap between updates counts`() {
        val events = mutableListOf<String>()
        lateinit var a: Button
        lateinit var b: Button
        val session = teleop<Unit, Unit>("Driver", dependencies = { Unit }) {
            a = Button.reported()
            b = Button.reported()
            a.onPress { events += "a press" }
            a.onRelease { events += "a release" }
            b.onPress { events += "b press" }
        }.initialize(Unit)
        session.start()

        val callback = Thread {
            b.post(true)
            a.post(true)
            a.post(true)
            a.post(false)
        }
        callback.start()
        callback.join()
        assertEquals(emptyList(), events, "nothing runs until the session updates")
        session.tick()

        assertEquals(listOf("b press", "a press", "a release"), events)
        assertTrue(a.wasPressed && a.wasReleased && !a.isDown)
        session.tick()
        assertTrue(!a.wasPressed && !a.wasReleased && b.isDown, "edges last one update")
        session.stop()
    }

    @Test
    fun `a full change buffer still ends each button in its latest reported state`() {
        lateinit var a: Button
        val session = teleop<Unit, Unit>("Driver", dependencies = { Unit }) { a = Button.reported() }.initialize(Unit)
        session.start()

        // 301 alternating changes ending down; the buffer holds 256, whose last one is a release.
        val callback = Thread { for (change in 0..300) a.post(change % 2 == 0) }
        callback.start()
        callback.join()
        session.tick()

        assertTrue(a.isDown, "the latest reported state wins after an overflow")
        session.tick()
        assertTrue(a.isDown && !a.wasPressed && !a.wasReleased, "the catch-up happens once")
        session.stop()
    }
}
