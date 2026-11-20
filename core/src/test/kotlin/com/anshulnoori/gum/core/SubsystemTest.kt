package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import com.pedropathing.ivy.CommandBuilder
import com.pedropathing.ivy.behaviors.InterruptedBehavior
import com.pedropathing.ivy.commands.Commands.instant
import com.pedropathing.ivy.commands.Commands.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.abs

class SubsystemTest {
    enum class State { OFF, COLLECTING, EJECTING }

    /** Records every state it enters, so tests see outputs, not just the reported state. */
    private class Roller(rules: (AutomaticBuilder<State>.() -> Unit)? = null) : Subsystem() {
        val entered = mutableListOf<String>()
        var full = false
        var enter: (State) -> Unit = { entered += it.name }
        var exit: (State) -> Unit = {}
        val roller = region(State.OFF) {
            onEntry { enter(it) }
            onExit { exit(it) }
            automatic(rules ?: { State.COLLECTING to State.OFF whenever { full } effect { entered += "full" } })
        }
        val state get() = roller.state

        fun collect() = roller.request(State.COLLECTING)
        fun eject() = roller.request(State.EJECTING)
        fun stop() = roller.request(State.OFF)
        fun ejectFromOff() = roller.request(State.EJECTING, from = State.OFF) { entered += "eject effect" }
        fun collecting() = roller.hold(State.COLLECTING)
    }

    enum class Speed { OFF, SPINNING_UP, READY }

    /** A flywheel with a fake speed sensor and a proportional controller. */
    private class Flywheel : Subsystem() {
        var rpm = 0.0
        var target = 0.0
        var power = 0.0
        val entered = mutableListOf<Speed>()
        val wheel = region(Speed.OFF) {
            automatic { Speed.SPINNING_UP to Speed.READY whenever { abs(target - rpm) < 50 } }
            onEntry {
                entered += it
                if (it == Speed.OFF) power = 0.0
            }
            during { if (it != Speed.OFF) power = 0.001 * (target - rpm) }
        }
        val state get() = wheel.state

        fun spinTo(rpm: Double) = wheel.request(Speed.SPINNING_UP) { target = rpm }
        fun boost(rpm: Double) = wheel.request(Speed.READY, from = setOf(Speed.SPINNING_UP)) { target = rpm }
        fun spinUp() = spinTo(3000.0).then(wheel.reach(Speed.READY))
    }

    private fun <S> session(create: () -> S): Pair<OpModeSession, S> {
        var subsystem: S? = null
        val session = teleop<Unit, S>("Test", dependencies = { create() }) {
            subsystem = it
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
    fun `a request from a state it does not name leaves the state`() {
        val (session, roller) = session { Roller() }
        roller.collect().schedule()
        roller.ejectFromOff().schedule()
        session.tick()
        assertEquals(State.COLLECTING, roller.state, "eject is valid only from Off")

        roller.stop().schedule()
        roller.ejectFromOff().schedule()
        session.tick()
        assertEquals(listOf("OFF", "COLLECTING", "OFF", "eject effect", "EJECTING"), roller.entered, "the effect runs only when taken, before entry")
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
        var full = false
        val (session, roller) = session {
            Roller {
                State.COLLECTING to State.EJECTING whenever { full }
                anyState to State.OFF whenever { full }
                State.COLLECTING to State.OFF whenever { true }
            }
        }
        full = true
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
            session { Roller { State.COLLECTING to State.OFF } }
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

    // Ivy 1.1.1's Scheduler.execute walks a copy of its running commands. When an earlier command
    // schedules one that interrupts a later one, the loop still reaches the interrupted command; if
    // it reports done, Ivy ends it again and removes the roller from the command that took it over.
    @Test
    fun `an interrupted request never strips the roller from the command that took it over`() {
        var go = false
        var down = false
        lateinit var roller: Roller
        lateinit var collecting: Command
        val session = teleop<Unit, Roller>("Teleop", dependencies = { Roller() }) { r ->
            roller = r
            collecting = roller.collecting()
            val scheduler = waitUntil { go }.then(instant { collecting.schedule() })
            onStart { scheduler.schedule() }
            Button { down }.onPress { roller.eject().schedule() }
        }.initialize(Unit)
        session.start()
        session.tick()
        go = true
        down = true
        session.tick()
        assertTrue(collecting.isScheduled, "hold(COLLECTING) took the roller")
        val stop = roller.stop()
        stop.schedule()
        assertFalse(collecting.isScheduled, "stop interrupts hold(COLLECTING), so one command owns the roller")
        session.stop()
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
    fun `reads before commands run after START, before button actions and commands see them`() {
        val events = mutableListOf<String>()
        var down = false

        class Sensor : Subsystem() {
            var reading = 0
            init {
                region(State.OFF) { during { events += "during sees $reading" } }
            }
            override fun beforeCommands() { reading++ }
        }

        lateinit var sensor: Sensor
        val session = teleop<Unit, Sensor>("Test", dependencies = { Sensor() }) {
            sensor = it
            Button { down }.onPress {
                events += "press sees ${sensor.reading}"
                Command.build().setStart { events += "command sees ${sensor.reading}" }.setDone { true }.schedule()
            }
        }.initialize(Unit)
        session.tick()
        assertEquals(0, sensor.reading, "INIT updates do not read")

        session.start()
        down = true
        session.tick()
        assertEquals(listOf("press sees 1", "command sees 1", "during sees 1"), events)
        session.stop()
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

    @Test
    fun `onEntry can read the state it entered, including the initial state at INIT`() {
        val seen = mutableListOf<State>()
        val (session, roller) = session { Roller().apply { enter = { seen += state } } }
        roller.collect().schedule()
        session.tick()
        assertEquals(listOf(State.OFF, State.COLLECTING), seen)
        session.stop()
    }

    @Test
    fun `a transition runs its effect, then the old state's onExit, then the new state's onEntry`() {
        val events = mutableListOf<String>()
        val (session, roller) = session {
            Roller().apply {
                enter = { events += "enter $it" }
                exit = { events += "exit $it" }
            }
        }
        roller.ejectFromOff().schedule()
        session.tick()
        session.stop()
        assertEquals(listOf("enter OFF", "exit OFF", "enter EJECTING", "exit EJECTING", "enter OFF"), events)
    }

    @Test
    fun `a request made from onEntry runs after the current transition finishes`() {
        val (session, roller) = session {
            Roller().apply {
                enter = {
                    entered += it.name
                    if (it == State.EJECTING) stop().schedule()
                }
            }
        }
        roller.eject().schedule()
        session.tick()
        assertEquals(State.OFF, roller.state)
        assertEquals(listOf("OFF", "EJECTING", "OFF"), roller.entered)
        session.stop()
    }

    @Test
    fun `a suspended hold keeps its state's place and takes it back when Ivy resumes it`() {
        val (session, roller) = session { Roller() }
        val collecting = (roller.collecting() as CommandBuilder).setInterruptedBehavior(InterruptedBehavior.SUSPEND)
        collecting.schedule()
        session.tick()
        roller.eject().schedule()
        assertEquals(State.EJECTING, roller.state, "the suspension does not return to the initial state")
        session.tick()
        session.tick()
        assertEquals(State.COLLECTING, roller.state, "resumed once eject released the roller")
        assertTrue(collecting.isScheduled)
        assertEquals(listOf("OFF", "COLLECTING", "EJECTING", "COLLECTING"), roller.entered)
        session.stop()
    }

    @Test
    fun `reapplying outputs runs the current state's onEntry again`() {
        val (session, roller) = session { Roller() }
        roller.collect().schedule()
        session.tick()
        session.reapplyOutputs()
        assertEquals(listOf("OFF", "COLLECTING", "COLLECTING"), roller.entered)
        session.stop()
    }

    /** An intake arm: the arm and the roller are regions, so their states combine. */
    private class IntakeArm(history: History = History.NONE, initial: Arm = Arm.LOWERED) : Subsystem() {
        enum class Arm(override val parent: Arm? = null) : Nested<Arm> {
            LOWERED, RAISED, RAISED_LOW(RAISED), RAISED_HIGH(RAISED)
        }
        enum class Roller { STOPPED, SPINNING }

        val events = mutableListOf<String>()
        var lowerOnRaised = false
        val arm = region(initial, history) {
            onEntry {
                events += "enter $it"
                if (lowerOnRaised && it == Arm.RAISED) lower().schedule()
            }
            onExit { events += "exit $it" }
        }
        val roller = region(Roller.STOPPED) {
            onEntry { events += "roller $it" }
            automatic { Roller.SPINNING to Roller.STOPPED whenever { arm.isIn(Arm.RAISED) } }
        }

        fun raise() = arm.request(Arm.RAISED)
        fun raiseLow() = arm.request(Arm.RAISED_LOW)
        fun raiseHigh() = arm.request(Arm.RAISED_HIGH)
        fun lower(): Command = arm.request(Arm.LOWERED)
        fun collect() = roller.request(Roller.SPINNING, guard = { arm.isIn(Arm.LOWERED) })
        fun spin() = roller.request(Roller.SPINNING)
        fun armUp() = arm.hold(Arm.RAISED)
        fun armHigh() = arm.hold(Arm.RAISED_HIGH)
        fun reachRaised() = arm.reach(Arm.RAISED)
    }

    @Test
    fun `regions of one subsystem are separate requirements, so their commands run together`() {
        val (session, intake) = session { IntakeArm() }
        val up = intake.armUp()
        up.schedule()
        intake.spin().schedule()
        assertTrue(up.isScheduled, "the roller's request does not interrupt the arm's hold")
        session.tick()
        assertEquals(IntakeArm.Arm.RAISED_LOW to IntakeArm.Roller.STOPPED, intake.arm.state to intake.roller.state,
            "spinning while raised stops on the next update")
        intake.lower().schedule()
        assertFalse(up.isScheduled, "a command for the same region interrupts the hold")
        intake.collect().schedule()
        session.tick()
        assertEquals(IntakeArm.Arm.LOWERED to IntakeArm.Roller.SPINNING, intake.arm.state to intake.roller.state)
        session.stop()
        assertEquals(IntakeArm.Arm.LOWERED to IntakeArm.Roller.STOPPED, intake.arm.state to intake.roller.state)
    }

    @Test
    fun `a guard can read another region, and a refused request runs no effect`() {
        val (session, intake) = session { IntakeArm() }
        intake.raise().schedule()
        intake.collect().schedule()
        session.tick()
        assertEquals(IntakeArm.Roller.STOPPED, intake.roller.state, "collecting is refused while raised")
        assertEquals(listOf("enter LOWERED", "roller STOPPED", "exit LOWERED", "enter RAISED", "enter RAISED_LOW"), intake.events)
        session.stop()
    }

    @Test
    fun `a parent enters its first child, its entry runs first and its exit last, and isIn covers its children`() {
        val (session, intake) = session { IntakeArm() }
        val raised = intake.reachRaised()
        intake.raiseHigh().schedule()
        assertEquals(IntakeArm.Arm.RAISED_HIGH, intake.arm.state)
        assertTrue(intake.arm.isIn(IntakeArm.Arm.RAISED))
        raised.schedule()
        session.tick()
        assertFalse(raised.isScheduled, "reach of a parent finishes in any child")
        intake.raise().schedule()
        assertEquals(IntakeArm.Arm.RAISED_HIGH, intake.arm.state, "requesting a parent it is in changes nothing")
        intake.raiseLow().schedule()
        intake.lower().schedule()
        assertEquals(
            listOf("enter LOWERED", "roller STOPPED", "exit LOWERED", "enter RAISED", "enter RAISED_HIGH",
                "exit RAISED_HIGH", "enter RAISED_LOW", "exit RAISED_LOW", "exit RAISED", "enter LOWERED"),
            intake.events,
        )
        intake.raise().schedule()
        assertEquals(IntakeArm.Arm.RAISED_LOW, intake.arm.state, "without history, the first child again")
        session.stop()
    }

    @Test
    fun `with history, requesting a parent returns to the child the region was last in`() {
        val (session, intake) = session { IntakeArm(History.SHALLOW) }
        intake.raiseHigh().schedule()
        intake.lower().schedule()
        intake.raise().schedule()
        assertEquals(IntakeArm.Arm.RAISED_HIGH, intake.arm.state)
        session.stop()
    }

    @Test
    fun `reapplying outputs enters every state the region is in again, outermost first`() {
        val (session, intake) = session { IntakeArm() }
        intake.raiseHigh().schedule()
        intake.events.clear()
        session.reapplyOutputs()
        assertEquals(listOf("enter RAISED", "enter RAISED_HIGH", "roller STOPPED"), intake.events)
        session.stop()
    }

    enum class Claw { OPEN, CLOSED }
    enum class Gate { CLOSED, OPEN }

    @Test
    fun `regions can share an enum and state names`() {
        class Claws : Subsystem() {
            val left = region(Claw.OPEN)
            val right = region(Claw.OPEN)
            val gate = region(Gate.CLOSED)
            fun closeLeft() = left.request(Claw.CLOSED)
            fun openGate() = gate.request(Gate.OPEN)
        }
        val (session, claws) = session { Claws() }
        claws.closeLeft().schedule()
        claws.openGate().schedule()
        assertEquals(listOf(Claw.CLOSED, Claw.OPEN, Gate.OPEN), listOf(claws.left.state, claws.right.state, claws.gate.state))
        session.stop()
    }

    enum class Lift(override val parent: Lift? = null) : Nested<Lift> {
        DOWN, UP, SCORING(UP), LOW(SCORING), HIGH(SCORING)
    }

    @Test
    fun `deep history returns to the innermost state, even after a move between grandchildren`() {
        class Lifter : Subsystem() {
            val lift = region(Lift.DOWN, History.DEEP)
            fun go(state: Lift) = lift.request(state)
        }
        val (session, lifter) = session { Lifter() }
        lifter.go(Lift.HIGH).schedule()
        lifter.go(Lift.LOW).schedule()
        lifter.go(Lift.DOWN).schedule()
        lifter.go(Lift.UP).schedule()
        assertEquals(Lift.LOW, lifter.lift.state)
        session.stop()
    }

    @Test
    fun `cancelling a suspended hold leaves the region to the command that took it`() {
        val (session, roller) = session { Roller() }
        val collecting = (roller.collecting() as CommandBuilder).setInterruptedBehavior(InterruptedBehavior.SUSPEND)
        collecting.schedule()
        session.tick()
        roller.eject().then(waitUntil { false }).schedule()
        session.tick()
        collecting.cancel()
        assertEquals(State.EJECTING, roller.state)
        session.stop()
    }

    @Test
    fun `releasing a hold and STOP return to the initial state itself, not to where history points`() {
        val (session, intake) = session { IntakeArm(History.SHALLOW, initial = IntakeArm.Arm.RAISED) }
        assertEquals(IntakeArm.Arm.RAISED_LOW, intake.arm.state, "an initial parent starts in its first child")
        val high = intake.armHigh()
        high.schedule()
        assertEquals(IntakeArm.Arm.RAISED_HIGH, intake.arm.state)
        high.cancel()
        assertEquals(IntakeArm.Arm.RAISED_LOW, intake.arm.state)
        intake.raiseHigh().schedule()
        session.stop()
        assertEquals(IntakeArm.Arm.RAISED_LOW, intake.arm.state)
    }

    @Test
    fun `a request made while outputs are reapplied runs after every state is entered again`() {
        val (session, intake) = session { IntakeArm() }
        intake.raiseHigh().schedule()
        session.tick()
        intake.events.clear()
        intake.lowerOnRaised = true
        session.reapplyOutputs()
        assertEquals(
            listOf("enter RAISED", "enter RAISED_HIGH", "roller STOPPED", "exit RAISED_HIGH", "exit RAISED", "enter LOWERED"),
            intake.events,
        )
        session.stop()
    }
}
