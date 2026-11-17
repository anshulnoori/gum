package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import com.pedropathing.ivy.CommandBuilder
import com.pedropathing.ivy.behaviors.EndCondition

/** How a command ended, as [Routine.await] returns it. */
enum class Outcome {
    /** It did its work: a request entered its state, a `reach` arrived, a routine's body returned. */
    FINISHED,

    /** It ended before doing its work, such as a `hold` whose state the subsystem left by itself. */
    INTERRUPTED,

    /** Its subsystem refused the request: the state stayed. */
    REFUSED,

    /** Its [withTimeout] ran out first, and Gum ended it as interrupted. */
    TIMED_OUT,
}

/**
 * An Ivy command that knows its outcome. Gum's own commands are these; any other command that
 * finishes reports [Outcome.FINISHED].
 */
internal open class Reported : CommandBuilder() {
    var outcome = Outcome.FINISHED
}

internal val Command.outcome: Outcome get() = (this as? Reported)?.outcome ?: Outcome.FINISHED

/**
 * This command, ended as interrupted if it has not finished [seconds] after it starts:
 * `await(robot.flywheel.spinUp().withTimeout(2.0))`. The interrupted command runs its own
 * interrupt handling, such as a routine's `finally` blocks, and its outcome is [Outcome.TIMED_OUT].
 * A command that finishes in time keeps its own outcome.
 *
 * It has this command's requirements, priority, and behaviors, so Ivy schedules it the same way.
 * Gum checks the time once per update, so it ends on the first update after [seconds].
 */
fun Command.withTimeout(seconds: Double): Command {
    require(seconds >= 0) { "A timeout cannot be negative: $seconds" }
    val inner = this
    val nanos = (seconds * 1e9).toLong()
    var deadline = 0L
    // Reports done only until it ends, like Gum's other commands: Ivy 1.1.1 can reach an
    // interrupted command again in the same pass and would end it a second time.
    var ended = false
    val command = Reported()
    command.requiring(inner.requirements())
        .setPriority(inner.priority())
        .setInterruptedBehavior(inner.interruptedBehavior())
        .setConflictBehavior(inner.conflictBehavior())
        .setBlockedBehavior(inner.blockedBehavior())
        .setStart {
            ended = false
            deadline = System.nanoTime() + nanos
            inner.start()
        }
        .setExecute { if (!ended) inner.execute() }
        .setDone { !ended && (inner.done() || System.nanoTime() - deadline >= 0) }
        .setEnd { reason ->
            if (reason == EndCondition.SUSPENDED) {
                inner.end(EndCondition.SUSPENDED)
                return@setEnd
            }
            ended = true
            when {
                reason == EndCondition.INTERRUPTED -> {
                    inner.end(EndCondition.INTERRUPTED)
                    command.outcome = Outcome.INTERRUPTED
                }
                inner.done() -> {
                    inner.end(EndCondition.NATURALLY)
                    command.outcome = inner.outcome
                }
                else -> {
                    inner.end(EndCondition.INTERRUPTED)
                    command.outcome = Outcome.TIMED_OUT
                }
            }
        }
    return command
}
