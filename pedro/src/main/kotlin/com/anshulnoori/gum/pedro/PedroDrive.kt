package com.anshulnoori.gum.pedro

import com.anshulnoori.gum.core.Subsystem
import com.bylazar.field.PanelsField
import com.pedropathing.follower.Follower
import com.pedropathing.ivy.Command
import com.pedropathing.ivy.pedro.PedroCommands
import com.pedropathing.math.Pose
import com.pedropathing.paths.Path
import kotlin.math.cos
import kotlin.math.sin

/**
 * A Pedro Pathing [Follower] as a Gum subsystem, the one owner of the drivetrain.
 *
 * ```
 * @Inject
 * @SingleIn(OpModeScope::class)
 * class Drive(follower: Follower = Constants.create(hardware.map)) : PedroDrive(follower)
 * ```
 *
 * Gum updates the follower on every update after START, after commands. [state] mirrors the
 * follower's own mode. Paths run through Ivy's Pedro commands, which this subsystem reserves, so
 * a newer drive command interrupts an older one. STOP stops the drivetrain.
 *
 * After START, it draws the robot and the path it follows on the Panels field, in Pedro's
 * coordinates, only on updates when Panels sends the field.
 */
open class PedroDrive(val follower: Follower) : Subsystem<PedroDrive.State>(State.IDLE) {
    enum class State { IDLE, FOLLOWING, HOLDING, DRIVER }

    // Commands change the follower; Gum transitions to match it on the next update.
    final override val automatic = automatic {
        anyState to State.IDLE whenever { follower.idle() }
        anyState to State.FOLLOWING whenever { follower.following() }
        anyState to State.HOLDING whenever { follower.holding() }
        anyState to State.DRIVER whenever { follower.manual() }
    }

    /** The follower's current pose. */
    val pose: Pose get() = follower.pose()

    override fun onEntry(state: State) {
        if (state == State.IDLE) {
            follower.stop()
            follower.drivetrain.stop()
        }
    }

    override fun during(state: State) {
        follower.update()
        if (PanelsField.field.shouldUpdateCanvas) draw()
    }

    private fun draw() {
        val field = PanelsField.field
        field.setOffsets(PanelsField.presets.PEDRO_PATHING)
        if (state == State.FOLLOWING) follower.currentPath()?.let { path ->
            field.setStyle(PanelsField.TRANSPARENT, PATH_COLOR, 0.75)
            var from = path.get(0.0)
            for (step in 1..PATH_STEPS) {
                val to = path.get(step.toDouble() / PATH_STEPS)
                field.moveCursor(from.x(), from.y())
                field.line(to.x(), to.y())
                from = to
            }
        }
        val pose = follower.pose()
        field.setStyle(PanelsField.TRANSPARENT, ROBOT_COLOR, 0.75)
        field.moveCursor(pose.x(), pose.y())
        field.circle(ROBOT_RADIUS)
        field.line(pose.x() + ROBOT_RADIUS * cos(pose.heading()), pose.y() + ROBOT_RADIUS * sin(pose.heading()))
    }

    private companion object {
        const val ROBOT_RADIUS = 9.0
        const val PATH_STEPS = 32
        const val ROBOT_COLOR = "#3F51B5"
        const val PATH_COLOR = "#4CAF50"
    }

    /** Follows [path] and finishes at its end; the follower then holds the end pose. */
    fun follow(path: Path): Command = PedroCommands.follow(follower, path).requiring(this)

    /** Holds [pose] and finishes at once; the follower keeps holding it. */
    fun hold(pose: Pose): Command = PedroCommands.hold(follower, pose).requiring(this)

    /** Holds the pose the robot is at when the command starts. */
    fun holdHere(): Command = Command.build().requiring(this).setStart { follower.hold(follower.pose()) }.setDone { true }

    /**
     * Drives from [forward], [lateral], and [turn], read every update, until interrupted:
     * `drive({ -gamepad1.leftStickY }, { -gamepad1.leftStickX }, { -gamepad1.rightStickX })`.
     */
    fun drive(forward: () -> Double, lateral: () -> Double, turn: () -> Double): Command {
        val set = { follower.manual(forward(), lateral(), turn()) }
        return Command.build().requiring(this).setStart(set).setExecute(set).setDone { false }
    }
}
