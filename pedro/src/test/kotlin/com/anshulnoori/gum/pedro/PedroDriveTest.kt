package com.anshulnoori.gum.pedro

import com.anshulnoori.gum.core.Button
import com.anshulnoori.gum.core.Outcome
import com.anshulnoori.gum.core.routine
import com.anshulnoori.gum.core.teleop
import com.anshulnoori.gum.core.withTimeout
import com.bylazar.field.Canvas
import com.bylazar.field.Circle
import com.bylazar.field.PanelsField
import com.pedropathing.algorithm.Algorithm
import com.pedropathing.drivetrain.DrivePowers
import com.pedropathing.drivetrain.Drivetrain
import com.pedropathing.follower.Follower
import com.pedropathing.ivy.Command
import com.pedropathing.localization.Localizer
import com.pedropathing.localization.MotionState
import com.pedropathing.math.Pose
import com.pedropathing.paths.AtomicPath
import com.pedropathing.paths.curves.Line
import com.pedropathing.paths.interpolator.Interpolator
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PedroDriveTest {
    private val calls = mutableListOf<String>()
    private var atEnd = false

    private inline fun <reified T> fake(crossinline answer: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> T::class.java.simpleName
                else -> answer(method.name, args ?: emptyArray())
            }
        } as T

    private val localizer = fake<Localizer> { name, _ ->
        when (name) {
            "state" -> MotionState.zero()
            "pose" -> Pose(12.0, 34.0, 0.0)
            "update" -> { calls += "localize"; null }
            else -> null
        }
    }
    private val drivetrain = fake<Drivetrain> { name, args ->
        when (name) {
            "drive" -> { calls += "drive manual=${args[1]}"; null }
            "stop" -> { calls += "stop"; null }
            else -> null
        }
    }
    private val algorithm = fake<Algorithm> { name, args ->
        when (name) {
            "calculateHold" -> { (args[1] as Pose).let { calls += "hold at ${it.x()},${it.y()}" }; DrivePowers.zero() }
            "isBusy" -> true
            "atParametricEnd" -> atEnd
            else -> null
        }
    }

    @Test
    fun `drive and hold move the follower, Gum updates it before commands each loop, and STOP stops the drivetrain`() {
        lateinit var drive: PedroDrive
        var down = false
        val session = teleop<Unit, PedroDrive>("Drive", dependencies = { PedroDrive(Follower(localizer, drivetrain, algorithm)) }) {
            drive = it
            Button { down }.onPress { drive.drive({ 1.0 }, { 0.0 }, { 0.0 }).schedule() }
        }.initialize(Unit)
        session.start()
        assertEquals(PedroDrive.State.IDLE, drive.state)
        calls.clear()

        down = true
        session.tick()
        assertEquals(PedroDrive.State.DRIVER, drive.state, "the state follows the command in the same update")
        assertEquals(listOf("localize", "stop"), calls, "the follower updates once, before the command runs")
        calls.clear()
        session.tick()
        assertEquals(listOf("localize", "drive manual=true"), calls, "the command's powers reach the drivetrain on the next update")
        val robot = PanelsField.field.canvas.items.filterIsInstance<Circle>().last()
        assertEquals(listOf(12.0, 34.0), listOf(robot.x, robot.y), "draws the robot at its pose on the Panels field")

        drive.hold(Pose(10.0, 0.0, 0.0)).schedule()
        session.tick()
        assertEquals(PedroDrive.State.HOLDING, drive.state, "a newer drive command interrupts the stick drive")

        calls.clear()
        session.stop()
        assertEquals(PedroDrive.State.IDLE, drive.state)
        assertEquals(listOf("stop"), calls)
    }

    @Test
    fun `a cancelled drive stops the drivetrain, and a cancelled follow holds where the robot is`() {
        val path = AtomicPath(Line(Pose(0.0, 0.0, 0.0), Pose(24.0, 0.0, 0.0)), Interpolator.constant(0.0), emptyList())
        lateinit var drive: PedroDrive
        val session = teleop<Unit, PedroDrive>("Drive", dependencies = { PedroDrive(Follower(localizer, drivetrain, algorithm)) }) {
            drive = it
        }.initialize(Unit)
        session.start()

        val sticks = drive.drive({ 1.0 }, { 0.0 }, { 0.0 })
        sticks.schedule()
        session.tick()
        assertEquals(PedroDrive.State.DRIVER, drive.state)
        calls.clear()
        sticks.cancel()
        session.tick()
        assertEquals(PedroDrive.State.IDLE, drive.state)
        assertEquals("stop", calls.last(), "the follower stops the drivetrain on its next update")
        assertFalse("drive manual=true" in calls, "the cancelled drive no longer sends its powers")

        val follow = drive.follow(path)
        follow.schedule()
        session.tick()
        assertEquals(PedroDrive.State.FOLLOWING, drive.state)
        calls.clear()
        follow.cancel()
        session.tick()
        assertEquals(PedroDrive.State.HOLDING, drive.state)
        assertEquals(listOf("localize", "hold at 12.0,34.0", "drive manual=false"), calls, "holds the pose the robot was at, not the path")
        session.stop()
        PanelsField.field.canvas = Canvas()
    }

    @Test
    fun `the stick drive gives way to a path and resumes after it, and only a cancel while it runs stops the drivetrain`() {
        val path = AtomicPath(Line(Pose(0.0, 0.0, 0.0), Pose(24.0, 0.0, 0.0)), Interpolator.constant(0.0), emptyList())
        var go = false
        lateinit var drive: PedroDrive
        lateinit var sticks: Command
        lateinit var follow: Command
        val session = teleop<Unit, PedroDrive>("Drive", dependencies = { PedroDrive(Follower(localizer, drivetrain, algorithm)) }) {
            drive = it
            follow = drive.follow(path)
            sticks = drive.drive({ 1.0 }, { 0.0 }, { 0.0 })
            // Earlier in Ivy's list than the drive, so the path interrupts it inside Ivy's update.
            val auto = routine { waitUntil { go }; follow.schedule() }
            onStart { auto.schedule(); sticks.schedule() }
        }.initialize(Unit)
        session.start()
        session.tick()
        assertEquals(PedroDrive.State.DRIVER, drive.state)

        go = true
        calls.clear()
        session.tick()
        assertEquals(PedroDrive.State.FOLLOWING, drive.state, "the suspended drive does not run again in that update")
        assertTrue(sticks.isScheduled, "Ivy keeps the drive to resume")
        calls.clear()
        session.tick()
        assertFalse("drive manual=true" in calls, "the suspended drive sends no powers while the path runs")

        atEnd = true
        session.tick()
        session.tick()
        assertEquals(PedroDrive.State.DRIVER, drive.state, "the drive resumes once the path finishes")

        atEnd = false
        follow.schedule()
        session.tick()
        sticks.cancel()
        session.tick()
        assertEquals(PedroDrive.State.FOLLOWING, drive.state, "cancelling the suspended drive leaves the path running")

        follow.cancel()
        sticks.schedule()
        session.tick()
        assertEquals(PedroDrive.State.DRIVER, drive.state)
        calls.clear()
        sticks.cancel()
        session.tick()
        assertEquals(PedroDrive.State.IDLE, drive.state)
        assertEquals("stop", calls.last())
        session.stop()
        PanelsField.field.canvas = Canvas()
    }

    @Test
    fun `a hold keeps the stick drive suspended until it is cancelled, and then the stick drive resumes`() {
        lateinit var drive: PedroDrive
        lateinit var sticks: Command
        val session = teleop<Unit, PedroDrive>("Drive", dependencies = { PedroDrive(Follower(localizer, drivetrain, algorithm)) }) {
            drive = it
            sticks = drive.drive({ 1.0 }, { 0.0 }, { 0.0 })
            onStart { sticks.schedule() }
        }.initialize(Unit)
        session.start()
        session.tick()
        assertEquals(PedroDrive.State.DRIVER, drive.state)

        val hold = drive.holdHere()
        hold.schedule()
        repeat(3) { session.tick() }
        assertEquals(PedroDrive.State.HOLDING, drive.state, "the hold outlasts one update")
        assertTrue(hold.isScheduled)
        assertTrue(sticks.isScheduled, "Ivy keeps the stick drive to resume")

        hold.cancel()
        session.tick()
        session.tick()
        assertEquals(PedroDrive.State.DRIVER, drive.state, "the stick drive resumes once the hold ends")
        session.stop()
        PanelsField.field.canvas = Canvas()
    }

    @Test
    fun `an auto limits a hold with withTimeout, and the follower keeps holding after it`() {
        lateinit var drive: PedroDrive
        var outcome: Outcome? = null
        val session = teleop<Unit, PedroDrive>("Auto", dependencies = { PedroDrive(Follower(localizer, drivetrain, algorithm)) }) {
            drive = it
            val auto = routine(drive) { outcome = await(drive.hold(Pose(10.0, 0.0, 0.0)).withTimeout(0.05)) }
            onStart { auto.schedule() }
        }.initialize(Unit)
        session.start()
        session.tick()
        assertEquals(null, outcome, "the hold is still running before its timeout")
        assertEquals(PedroDrive.State.HOLDING, drive.state)

        Thread.sleep(60)
        session.tick()
        assertEquals(Outcome.TIMED_OUT, outcome)
        calls.clear()
        session.tick()
        assertEquals(PedroDrive.State.HOLDING, drive.state)
        assertTrue("hold at 10.0,0.0" in calls, "the follower still holds the pose")
        session.stop()
        PanelsField.field.canvas = Canvas()
    }

    @Test
    fun `a path scheduled at START is followed even when setup never touched the drive`() {
        val path = AtomicPath(Line(Pose(0.0, 0.0, 0.0), Pose(24.0, 0.0, 0.0)), Interpolator.constant(0.0), emptyList())
        lateinit var drive: PedroDrive
        val session = teleop<Unit, PedroDrive>("Auto", dependencies = { PedroDrive(Follower(localizer, drivetrain, algorithm)) }) {
            drive = it
            onStart { drive.follow(path).schedule() }
        }.initialize(Unit)
        session.start()
        session.tick()
        assertEquals(PedroDrive.State.FOLLOWING, drive.state, "entering IDLE at INIT, not on the first update, keeps the path")
        assertSame(path, drive.follower.currentPath())
        session.stop()
        // The Panels field is shared, and the other test counts what is drawn on it.
        PanelsField.field.canvas = Canvas()
    }
}
