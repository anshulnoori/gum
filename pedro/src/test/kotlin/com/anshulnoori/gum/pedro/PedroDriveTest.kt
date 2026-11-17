package com.anshulnoori.gum.pedro

import com.anshulnoori.gum.core.teleop
import com.bylazar.field.Canvas
import com.bylazar.field.Circle
import com.bylazar.field.PanelsField
import com.pedropathing.algorithm.Algorithm
import com.pedropathing.drivetrain.DrivePowers
import com.pedropathing.drivetrain.Drivetrain
import com.pedropathing.follower.Follower
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

class PedroDriveTest {
    private val calls = mutableListOf<String>()

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
            "atParametricEnd" -> false
            else -> null
        }
    }

    @Test
    fun `drive and hold move the follower, Gum updates it each loop, and STOP stops the drivetrain`() {
        lateinit var drive: PedroDrive
        val session = teleop<Unit, PedroDrive>("Drive", dependencies = { PedroDrive(Follower(localizer, drivetrain, algorithm)) }) {
            drive = it
        }.initialize(Unit)
        session.start()
        assertEquals(PedroDrive.State.IDLE, drive.state)
        calls.clear()

        drive.drive({ 1.0 }, { 0.0 }, { 0.0 }).schedule()
        session.tick()
        assertEquals(PedroDrive.State.DRIVER, drive.state)
        assertEquals(listOf("localize", "drive manual=true"), calls, "one follower update per loop, after the command")
        val robot = PanelsField.field.canvas.items.filterIsInstance<Circle>().single()
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
