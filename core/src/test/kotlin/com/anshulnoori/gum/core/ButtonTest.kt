package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import java.util.function.LongSupplier
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

class DerivedButtonTest {
    @Test
    fun `and, or, and not follow every reported change in arrival order, even between updates`() {
        val events = mutableListOf<String>()
        lateinit var a: Button
        lateinit var b: Button
        lateinit var both: Button
        lateinit var either: Button
        val session = teleop<Unit, Unit>("Driver", dependencies = { Unit }) {
            a = Button.reported()
            b = Button.reported()
            both = a and b
            either = a or b
            a.onPress { events += "a press" }
            both.onPress { events += "both press" }
            both.onRelease { events += "both release" }
            either.onPress { events += "either press" }
            either.onRelease { events += "either release" }
            (!a).onPress { events += "not a press" }
            (!a).onRelease { events += "not a release" }
        }.initialize(Unit)
        session.start()

        // One update sees all of it: both is down only between b's press and a's release.
        val callback = Thread {
            a.post(true)
            b.post(true)
            a.post(false)
        }
        callback.start()
        callback.join()
        session.tick()

        assertEquals(
            listOf("a press", "either press", "not a release", "both press", "both release", "not a press"),
            events,
        )
        assertTrue(!both.isDown && both.wasPressed && both.wasReleased)
        assertTrue(either.isDown && either.wasPressed && !either.wasReleased, "b still holds it")

        events.clear()
        b.post(false)
        session.tick()
        assertEquals(listOf("either release"), events)
        assertFailsWith<IllegalStateException> { both.post(true) }
        session.stop()
    }

    @Test
    fun `derived buttons start from their sources, so a held source presses only after a release`() {
        var down = true
        val events = mutableListOf<String>()
        val session = teleop<Unit, Unit>("Driver", dependencies = { Unit }) {
            val held = Button { down }
            val other = Button { true }
            (held and other).onPress { events += "both press" }
            held.toggled().onPress { events += "toggle press" }
        }.initialize(Unit)
        session.start()
        session.tick()
        assertEquals(emptyList(), events, "already down at creation: no press, no flip")
        down = false
        session.tick()
        down = true
        session.tick()
        session.stop()
        assertEquals(listOf("both press", "toggle press"), events)
    }

    @Test
    fun `debounce waits for the source to stay changed, restarting on each reversal`() {
        var time = 0L
        val ms = 1_000_000L
        val events = mutableListOf<String>()
        lateinit var source: Button
        lateinit var debounced: Button
        val session = teleop<Unit, Unit>("Driver", dependencies = { Unit }) {
            clock = LongSupplier { time }
            source = Button.reported()
            debounced = source.debounce(pressSeconds = 0.05, releaseSeconds = 0.1)
            debounced.onPress { events += "press at ${time / ms}" }
            debounced.onRelease { events += "release at ${time / ms}" }
        }.initialize(Unit)
        session.start()

        fun at(millis: Long, down: Boolean? = null) {
            time = millis * ms
            down?.let { source.post(it) }
            session.tick()
        }

        at(0, true)
        at(30, false)
        at(40, true)
        at(60)
        assertTrue(!debounced.isDown, "the press at 40 restarted the 50 ms wait")
        at(89)
        assertTrue(!debounced.isDown)
        at(90)
        assertTrue(debounced.isDown && debounced.wasPressed)

        at(100, false)
        at(150)
        at(160, true)
        at(200, false)
        at(299)
        assertTrue(debounced.isDown, "the press at 160 cancelled the release at 100")
        at(300)
        session.stop()
        assertEquals(listOf("press at 90", "release at 300"), events)
    }

    @Test
    fun `a zero debounce passes changes through in the same update`() {
        lateinit var source: Button
        lateinit var debounced: Button
        val session = teleop<Unit, Unit>("Driver", dependencies = { Unit }) {
            source = Button.reported()
            debounced = source.debounce(pressSeconds = 0.0)
        }.initialize(Unit)
        session.start()
        source.post(true)
        session.tick()
        assertTrue(debounced.isDown && debounced.wasPressed)
        session.stop()
    }

    @Test
    fun `multiPress counts presses within the window from the first, including taps between updates`() {
        var time = 0L
        val ms = 1_000_000L
        val events = mutableListOf<String>()
        lateinit var a: Button
        lateinit var b: Button
        val session = teleop<Unit, Unit>("Driver", dependencies = { Unit }) {
            clock = LongSupplier { time }
            a = Button.reported()
            b = Button.reported()
            val double = a.doublePress(0.3)
            double.onPress { events += "double at ${time / ms}" }
            double.onRelease { events += "double release at ${time / ms}" }
            b.multiPress(3, 0.3).onPress { events += "triple at ${time / ms}" }
        }.initialize(Unit)
        session.start()

        fun tap(button: Button, millis: Long) {
            time = millis * ms
            button.post(true)
            button.post(false)
            session.tick()
        }

        // Two taps that reach one update count as a double press.
        repeat(2) { a.post(true); a.post(false) }
        session.tick()
        // The next press starts a new count; it does not repeat the double press.
        tap(a, 100)
        tap(a, 600)
        tap(a, 800)
        // Presses 0.5 s apart are singles.
        tap(a, 2000)
        tap(a, 2500)

        // 350 is within 300 ms of 200 but not of the first press at 0, so it starts a new count.
        tap(b, 10_000)
        tap(b, 10_200)
        tap(b, 10_350)
        tap(b, 10_400)
        tap(b, 10_500)
        session.stop()

        assertEquals(
            listOf("double at 0", "double release at 0", "double at 800", "double release at 800", "triple at 10500"),
            events,
        )
    }

    private enum class State { OFF, ON }

    private class Light : Subsystem<State>(State.OFF) {
        override fun onEntry(state: State) {}
        fun on() = hold(State.ON)
        fun flash() = request(State.ON)
    }

    @Test
    fun `toggled latches each press, and toggleOnPress cancels a running command or starts a fresh one`() {
        var down = false
        var flashes = 0
        lateinit var light: Light
        lateinit var slow: Button
        lateinit var other: Light
        val session = teleop<Unit, Pair<Light, Light>>("Driver", dependencies = { Light() to Light() }) {
            light = it.first
            other = it.second
            val button = Button { down }
            slow = button.toggled()
            button.toggleOnPress { light.on() }
            button.toggleOnPress { flashes++; other.flash() }
        }.initialize(Unit)
        session.start()

        fun tap() {
            down = true
            session.tick()
            down = false
            session.tick()
        }

        tap()
        assertTrue(slow.isDown)
        assertEquals(State.ON, light.state, "first press starts the hold")
        tap()
        assertTrue(!slow.isDown)
        assertEquals(State.OFF, light.state, "second press cancels it")
        tap()
        assertEquals(State.ON, light.state)
        assertEquals(3, flashes, "flash ends by itself, so every press starts a fresh one")
        session.stop()
    }
}
