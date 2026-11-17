package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import com.pedropathing.ivy.behaviors.EndCondition
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.RestrictsSuspension
import kotlin.coroutines.createCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.CancellationException

/**
 * An Ivy command written as sequential code:
 *
 * ```
 * routine {
 *     await(robot.intake.collect())
 *     waitSeconds(0.5)
 *     await(robot.flywheel.spinTo(3000.0))
 * }
 * ```
 *
 * It runs on the OpMode thread inside Ivy's update, with Kotlin's built-in coroutines: no threads,
 * no dispatcher. Each suspension resumes on a later update.
 *
 * Like Ivy's own groups, the routine owns everything it runs for its whole duration: it reserves
 * [requirements], and [Routine.await] fails for a command that needs anything else, because awaited
 * commands run inside the routine rather than through Ivy's scheduler.
 *
 * When Ivy interrupts or cancels it, the command it is awaiting ends as interrupted, and the routine
 * resumes with a [CancellationException], so its `finally` blocks run. An exception from the body
 * propagates out of Ivy's update and stops the OpMode; it carries a [RoutineCreation] that shows
 * where the routine was created.
 *
 * Its outcome is [Outcome.FINISHED] when the body returns and [Outcome.INTERRUPTED] when Ivy ends it
 * first. Give it a limit with [withTimeout].
 */
fun routine(vararg requirements: Any, body: suspend Routine.() -> Unit): Command {
    val routine = Routine(requirements.toSet(), RoutineCreation())
    val command = Reported()
    return command
        .requiring(*requirements)
        .setStart { command.outcome = Outcome.FINISHED; routine.begin(body) }
        .setExecute { routine.step() }
        .setDone { routine.finished && !routine.interrupted }
        .setEnd {
            routine.end(it)
            if (routine.interrupted) command.outcome = Outcome.INTERRUPTED
        }
}

/**
 * Where a routine was created. Gum attaches it to an error the routine's body throws, as a
 * suppressed exception, so the error shows the `routine { }` call as well as the failing line.
 */
class RoutineCreation internal constructor() : Throwable("The routine that failed was created here") {
    init {
        // Start the trace at the caller of routine(), not inside Gum.
        stackTrace = stackTrace.dropWhile { it.className in GUM_FRAMES }.toTypedArray()
    }

    private companion object {
        val GUM_FRAMES = setOf(RoutineCreation::class.java.name, "com.anshulnoori.gum.core.RoutineKt")
    }
}

@RestrictsSuspension
class Routine internal constructor(private val requirements: Set<Any>, private val creation: RoutineCreation) {
    private var continuation: Continuation<Unit>? = null
    private var child: Command? = null
    private var condition: (() -> Boolean)? = null
    internal var finished = false
        private set

    // Set when Ivy ends the routine before its body returns. The routine then never reports done:
    // Ivy 1.1.1's Scheduler.execute can reach an interrupted command again in the same pass, and it
    // ends a command that reports done there a second time, which removes the new owner's requirements.
    internal var interrupted = false
        private set

    /**
     * Runs [command] to completion inside this routine and returns how it ended: [Outcome.FINISHED],
     * [Outcome.REFUSED] for a refused request, [Outcome.TIMED_OUT] for a [withTimeout] that ran out,
     * or [Outcome.INTERRUPTED], such as for a `hold` whose state the subsystem left. The routine
     * continues whatever the outcome; check it before a step that needs the last one to succeed.
     * [command] may need only the routine's requirements.
     */
    suspend fun await(command: Command): Outcome {
        val missing = command.requirements() - requirements
        check(missing.isEmpty()) { "Add $missing to routine(...) to await this command" }
        command.start()
        if (command.done()) {
            command.end(EndCondition.NATURALLY)
            return command.outcome
        }
        suspendCoroutine { child = command; continuation = it }
        return command.outcome
    }

    /** Waits until [condition] is true, checking once per update. Returns at once if it already is. */
    suspend fun waitUntil(condition: () -> Boolean) {
        if (condition()) return
        suspendCoroutine { this.condition = condition; continuation = it }
    }

    /** Waits at least [seconds]. */
    suspend fun waitSeconds(seconds: Double) {
        val deadline = System.nanoTime() + (seconds * 1e9).toLong()
        waitUntil { System.nanoTime() >= deadline }
    }

    /** Waits for the next update. */
    suspend fun nextUpdate() {
        suspendCoroutine { condition = { true }; continuation = it }
    }

    internal fun begin(body: suspend Routine.() -> Unit) {
        finished = false
        interrupted = false
        child = null
        condition = null
        body.createCoroutine(this, Completion()).resume(Unit)
    }

    internal fun step() {
        val command = child
        if (command != null) {
            command.execute()
            if (!command.done()) return
            command.end(EndCondition.NATURALLY)
            child = null
        } else {
            val ready = condition ?: return
            if (!ready()) return
            condition = null
        }
        continuation!!.also { continuation = null }.resume(Unit)
    }

    internal fun end(reason: EndCondition) {
        if (finished) return
        // Ivy resumes a suspended command later without starting it again; keep its place.
        if (reason == EndCondition.SUSPENDED) {
            child?.end(EndCondition.SUSPENDED)
            return
        }
        child?.end(reason)
        child = null
        condition = null
        continuation?.also { continuation = null }
            ?.resumeWithException(CancellationException("Routine ended: $reason"))
        finished = true
        interrupted = true
    }

    private inner class Completion : Continuation<Unit> {
        override val context: CoroutineContext get() = EmptyCoroutineContext

        override fun resumeWith(result: Result<Unit>) {
            finished = true
            result.exceptionOrNull()?.let {
                if (it is CancellationException) return
                it.addSuppressed(creation)
                throw it
            }
        }
    }
}
