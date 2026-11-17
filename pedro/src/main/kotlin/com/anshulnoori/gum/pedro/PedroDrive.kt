package com.anshulnoori.gum.pedro

import com.anshulnoori.gum.core.Subsystem
import com.bylazar.field.PanelsField
import com.pedropathing.follower.Follower
import com.pedropathing.ivy.Command
import com.pedropathing.ivy.Scheduler
import com.pedropathing.ivy.behaviors.BlockedBehavior
import com.pedropathing.ivy.behaviors.EndCondition
import com.pedropathing.ivy.behaviors.InterruptedBehavior
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
 * follower's own mode. Its commands reserve this subsystem, so a newer drive command interrupts an
 * older one. STOP stops the drivetrain.
 *
 * After START, it draws the robot and the path it follows on the Panels field, in Pedro's
 * coordinates, on updates when the Panels field's `shouldUpdateCanvas` is true. The field is sent
 * later in the same update, so a send that starts as the flag turns true goes without the robot.
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

    // Each command below reports done only until it ends, as Gum's own commands do: Ivy 1.1.1 can
    // reach an interrupted command again in the same update and would end it a second time.

    /**
     * Follows [path] and finishes at its end; the follower then holds the end pose. If it is
     * cancelled or interrupted first, the follower holds the pose the robot is at.
     */
    fun follow(path: Path): Command {
        var ended = false
        return Command.build()
            .requiring(this)
            .setStart { ended = false; follower.follow(path) }
            .setDone { !ended && follower.atParametricEnd() }
            .setEnd {
                if (it == EndCondition.INTERRUPTED) follower.hold(follower.pose())
                ended = it != EndCondition.SUSPENDED
            }
    }

    /** Holds [pose] and finishes at once; the follower keeps holding it. */
    fun hold(pose: Pose): Command = holdCommand { pose }

    /** Holds the pose the robot is at when the command starts. */
    fun holdHere(): Command = holdCommand { follower.pose() }

    private fun holdCommand(pose: () -> Pose): Command {
        var ended = false
        return Command.build()
            .requiring(this)
            .setStart { ended = false; follower.hold(pose()) }
            .setDone { !ended }
            .setEnd { ended = it != EndCondition.SUSPENDED }
    }

    /**
     * Drives from [forward], [lateral], and [turn], read every update, as the drive's default
     * command: `drive({ -gamepad1.leftStickY }, { -gamepad1.leftStickX }, { -gamepad1.rightStickX })`.
     *
     * It runs at the lowest priority and Ivy suspends it, rather than ending it, when another drive
     * command takes over; it resumes once that command ends. Scheduled while another drive command
     * runs, it starts when that one ends. When it is cancelled, it stops the drivetrain.
     */
    fun drive(forward: () -> Double, lateral: () -> Double, turn: () -> Double): Command {
        var ended = false
        var suspended = false
        lateinit var command: Command
        // False once it ends, and while it waits in Ivy's suspended list: Ivy 1.1.1 can run a command
        // it just suspended once more in the same update, and cancelling it there must not stop the
        // command that took over.
        val owns = { !ended && !(suspended && command.isScheduled && !Scheduler.isRunning(command)) }
        val set = { follower.manual(forward(), lateral(), turn()) }
        command = Command.build()
            .requiring(this)
            .setPriority(Int.MIN_VALUE)
            .setInterruptedBehavior(InterruptedBehavior.SUSPEND)
            .setBlockedBehavior(BlockedBehavior.QUEUE)
            .setStart { ended = false; suspended = false; set() }
            .setExecute {
                if (owns()) {
                    suspended = false
                    set()
                }
            }
            .setEnd {
                val stop = owns()
                ended = it != EndCondition.SUSPENDED
                suspended = it == EndCondition.SUSPENDED
                if (stop) follower.stop()
            }
        return command
    }
}
