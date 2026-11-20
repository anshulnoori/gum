package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import com.pedropathing.ivy.behaviors.EndCondition
import ru.nsk.kstatemachine.event.Event
import ru.nsk.kstatemachine.state.DefaultState
import ru.nsk.kstatemachine.state.addInitialState
import ru.nsk.kstatemachine.state.addState
import ru.nsk.kstatemachine.state.onEntry
import ru.nsk.kstatemachine.state.onExit
import ru.nsk.kstatemachine.state.transitionConditionally
import ru.nsk.kstatemachine.statemachine.StateMachine
import ru.nsk.kstatemachine.statemachine.createStdLibStateMachine
import ru.nsk.kstatemachine.statemachine.processEventBlocking
import ru.nsk.kstatemachine.statemachine.startBlocking
import ru.nsk.kstatemachine.transition.noTransition
import ru.nsk.kstatemachine.transition.onTriggered
import ru.nsk.kstatemachine.transition.targetState

/**
 * A mechanism that is in one state at a time.
 *
 * A subclass lists its states as an enum and sets its outputs in [onEntry]. Its named commands,
 * built with [request], [hold], and [reach], are its whole API. A command that is valid only from
 * certain states says so with `from`. Transitions the subsystem takes by itself go in [automatic],
 * and feedback control goes in [during]. Sensor reads that commands decide from go in
 * [beforeCommands]. Gum builds the state machine; on every update after START it calls
 * [beforeCommands] before button actions and commands, and after commands run, it takes at most
 * one automatic transition and then calls [during]. The subsystem
 * enters [initial] at INIT, once setup has run, or on its first update if it is created later.
 * STOP returns the subsystem to [initial].
 *
 * Construct a subsystem while an OpMode runs, on its thread; it joins that session.
 */
abstract class Subsystem<S : Enum<S>>(private val initial: S) {
    init {
        checkNotNull(ProgramScope.current.get()) {
            "Create ${javaClass.simpleName} while an OpMode runs, on the OpMode thread"
        }.register(this)
    }

    /** Transitions the subsystem takes by itself, built with [automatic]. Commands cannot request them. */
    protected open val automatic: Automatic<S>? = null

    /**
     * Sets outputs for [state]. Runs each time the subsystem enters a state, including [initial],
     * and again after a hub reconnects, so outputs it set reach the hub again.
     */
    protected abstract fun onEntry(state: S)

    /**
     * Runs each time the subsystem leaves [state], after the transition's effect and before the next
     * state's [onEntry], for cleanup that belongs to the state, such as stopping a timer it started.
     * Does nothing unless overridden.
     */
    protected open fun onExit(state: S) {}

    /**
     * Runs on every update after START while in [state], after commands and automatic transitions,
     * for control that depends on sensors, such as holding a flywheel speed. Does nothing unless overridden.
     */
    protected open fun during(state: S) {}

    /**
     * Runs on every update after START, before button actions and commands, for reads that commands
     * decide from in the same update, such as a localizer's pose. Does nothing unless overridden.
     */
    protected open fun beforeCommands() {}

    /** The current state. */
    val state: S get() = current

    /**
     * A command that transitions to [state] and finishes at once; the subsystem stays in [state]
     * afterward. It reserves this subsystem.
     *
     * When [from] is given, the request is valid only from those states; from any other state the
     * state stays. Requesting the current state always runs [effect] only, without re-entering.
     *
     * [effect] runs when the transition is taken, before [onEntry], to set data the state uses:
     * `request(State.SPINNING) { targetRpm = rpm }`.
     */
    protected fun request(state: S, from: S? = null, effect: () -> Unit = {}): Command =
        requestCommand(state, from?.let(::setOf), effect)

    /** [request] that is valid from any state in [from]. */
    protected fun request(state: S, from: Set<S>, effect: () -> Unit = {}): Command =
        requestCommand(state, from, effect)

    // Each command below reports done only until it ends. Ivy 1.1.1's Scheduler.execute can reach a
    // command that was already interrupted in the same pass; a command that reports done there is
    // ended again, and Ivy then removes this subsystem from the command that took it over.
    // Ivy#10 fixes this upstream; the `ended` flags go once Gum builds against a release with it.
    private fun requestCommand(state: S, from: Set<S>?, effect: () -> Unit): Command {
        var ended = false
        return Command.build()
            .requiring(this)
            .setStart { ended = false; take(state, from, effect) }
            .setDone { !ended }
            .setEnd { ended = it != EndCondition.SUSPENDED }
    }

    /**
     * A command that transitions to [state] like [request] and keeps running until it is cancelled
     * or interrupted, then returns the subsystem to its initial state if it is still in [state].
     * It also ends, leaving the state alone, when the subsystem leaves [state] by an automatic
     * transition, or when [from] does not allow the request.
     */
    protected fun hold(state: S, from: S? = null, effect: () -> Unit = {}): Command =
        holdCommand(state, from?.let(::setOf), effect)

    /** [hold] that is valid from any state in [from]. */
    protected fun hold(state: S, from: Set<S>, effect: () -> Unit = {}): Command =
        holdCommand(state, from, effect)

    // A hold that Ivy suspends keeps its place: Ivy resumes it later without starting it again, so
    // it takes its state back on its first update after the suspension. Ivy 1.1.1 can also run a
    // command it just suspended once more in the same update, which must not take the state back
    // from the command that took over; a suspension therefore waits for the subsystem's next update.
    private fun holdCommand(state: S, from: Set<S>?, effect: () -> Unit): Command {
        var ended = false
        var suspendedAt = NOT_SUSPENDED
        return Command.build()
            .requiring(this)
            .setStart { ended = false; suspendedAt = NOT_SUSPENDED; take(state, from, effect) }
            .setExecute {
                if (suspendedAt != NOT_SUSPENDED && updates > suspendedAt) {
                    suspendedAt = NOT_SUSPENDED
                    take(state, from, effect)
                }
            }
            .setDone { !ended && suspendedAt == NOT_SUSPENDED && this.state != state }
            .setEnd {
                if (it == EndCondition.SUSPENDED) {
                    suspendedAt = updates
                } else {
                    ended = true
                    if (this.state == state && state != initial) go(initial)
                }
            }
    }

    /**
     * A command that reserves this subsystem and finishes once it is in [state]. It can wait
     * forever; limit it with Ivy, such as `reach(State.READY).raceWith(waitMs(2000.0))`.
     */
    protected fun reach(state: S): Command {
        var ended = false
        return Command.build()
            .requiring(this)
            .setStart { ended = false }
            .setDone { !ended && this.state == state }
            .setEnd { ended = it != EndCondition.SUSPENDED }
    }

    /**
     * Declares automatic transitions: `A to B whenever { condition } effect { … }`. Each update,
     * after commands, Gum takes the first declared transition whose source is the current state and
     * whose condition is true, at most one per update. Conditions must only read: Gum may evaluate
     * any of them on any update.
     */
    protected fun automatic(block: AutomaticBuilder<S>.() -> Unit): Automatic<S> {
        val transitions = AutomaticBuilder<S>().apply(block).transitions
        for (transition in transitions) {
            checkNotNull(transition.condition) {
                "${javaClass.simpleName}: the automatic transition to ${transition.to} needs `whenever { condition }`"
            }
        }
        return Automatic(transitions)
    }

    private var current = initial

    // Updates since START; a suspended hold resumes only on a later one.
    private var updates = 0L

    // Built at INIT, or on first use for a subsystem created later, after the subclass has
    // finished constructing its outputs.
    private var machine: StateMachine? = null

    private val automaticTransitions: List<AutomaticTransition<S>> by lazy { automatic?.transitions.orEmpty() }

    /** Requests [state]; the state stays when [from] does not allow it. */
    private fun take(state: S, from: Set<S>?, effect: () -> Unit) {
        if (state == current) effect() else go(state, from, effect)
    }

    // KStateMachine queues an event sent while it handles another, such as a request an onEntry
    // schedules, and handles it after the current transition finishes.
    private fun go(to: S, from: Set<S>? = null, effect: () -> Unit = {}) {
        machine().processEventBlocking(Go(to, from, effect))
    }

    internal fun enterInitialState() {
        machine()
    }

    internal fun updateBeforeCommands() = beforeCommands()

    internal fun update() {
        machine()
        updates++
        val state = current
        val transitions = automaticTransitions
        var index = 0
        while (index < transitions.size) {
            val transition = transitions[index++]
            if ((transition.from == null || transition.from == state) && transition.to != state && transition.condition!!()) {
                go(transition.to, effect = transition.effect)
                break
            }
        }
        during(current)
    }

    /** Sets the current state's outputs again, after a hub that lost them reconnects. */
    internal fun reapply() {
        if (machine != null) onEntry(current)
    }

    internal fun reset() {
        go(initial)
    }

    // The machine exists before it starts, so a request that the initial state's onEntry makes
    // reaches the machine instead of building another one.
    private fun machine(): StateMachine = machine ?: build().also {
        machine = it
        it.startBlocking()
    }

    private fun build(): StateMachine {
        val states = initial.declaringJavaClass.enumConstants
        return createStdLibStateMachine(javaClass.simpleName, start = false) {
            val nodes = states.associateWith { DefaultState(it.name) }
            for ((state, node) in nodes) {
                if (state == initial) addInitialState(node) else addState(node)
                node.onEntry {
                    current = state
                    this@Subsystem.onEntry(state)
                }
                node.onExit { this@Subsystem.onExit(state) }
            }
            // Requests, automatic transitions, and STOP all send Go. A request whose `from` does not
            // include the current state takes no transition, so the state stays.
            transitionConditionally<Go> {
                direction = {
                    @Suppress("UNCHECKED_CAST")
                    if (event.from?.contains(current) == false) noTransition() else targetState(nodes.getValue(event.to as S))
                }
            }.onTriggered { it.event.effect() }
        }
    }

    private class Go(val to: Any, val from: Set<*>?, val effect: () -> Unit) : Event

    private companion object {
        const val NOT_SUSPENDED = -1L
    }
}

/** A subsystem's automatic transitions, built with [Subsystem.automatic]. */
class Automatic<S : Enum<S>> internal constructor(internal val transitions: List<AutomaticTransition<S>>)

/** One automatic transition. A `null` [from] means any state other than [to]. */
class AutomaticTransition<S : Enum<S>> internal constructor(internal val from: S?, internal val to: S) {
    internal var condition: (() -> Boolean)? = null
    internal var effect: () -> Unit = {}
}

/** Marks an automatic transition that can start from any state. */
object AnyState

class AutomaticBuilder<S : Enum<S>> internal constructor() {
    internal val transitions = mutableListOf<AutomaticTransition<S>>()

    /** Every state, as the source of a transition. */
    val anyState = AnyState

    /** A transition from this state to [target]; give it a condition with [whenever]. */
    infix fun S.to(target: S): AutomaticTransition<S> = AutomaticTransition(this, target).also { transitions += it }

    /** A transition to [target] from any other state; give it a condition with [whenever]. */
    infix fun AnyState.to(target: S): AutomaticTransition<S> = AutomaticTransition<S>(null, target).also { transitions += it }

    /** Gum takes this transition on an update when [condition] is true. [condition] must only read. */
    infix fun AutomaticTransition<S>.whenever(condition: () -> Boolean): AutomaticTransition<S> = apply {
        this.condition = condition
    }

    /** Runs [effect] when Gum takes this transition, before the target state's `onEntry`. */
    infix fun AutomaticTransition<S>.effect(effect: () -> Unit) {
        this.effect = effect
    }
}
