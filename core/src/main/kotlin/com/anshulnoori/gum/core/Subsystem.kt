package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import com.pedropathing.ivy.behaviors.EndCondition
import ru.nsk.kstatemachine.event.Event
import ru.nsk.kstatemachine.state.DefaultState
import ru.nsk.kstatemachine.state.addInitialState
import ru.nsk.kstatemachine.state.addState
import ru.nsk.kstatemachine.state.onEntry
import ru.nsk.kstatemachine.state.transition
import ru.nsk.kstatemachine.statemachine.StateMachine
import ru.nsk.kstatemachine.statemachine.createStdLibStateMachine
import ru.nsk.kstatemachine.statemachine.processEventBlocking
import ru.nsk.kstatemachine.transition.onTriggered

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

    /** Sets outputs for [state]. Runs each time the subsystem enters a state, including [initial]. */
    protected abstract fun onEntry(state: S)

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
    val state: S
        get() {
            machine
            return current
        }

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

    private fun holdCommand(state: S, from: Set<S>?, effect: () -> Unit): Command {
        var ended = false
        return Command.build()
            .requiring(this)
            .setStart { ended = false; take(state, from, effect) }
            .setDone { !ended && this.state != state }
            .setEnd {
                ended = it != EndCondition.SUSPENDED
                if (this.state == state && state != initial) machine.processEventBlocking(Reset)
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

    // Built on first use, after the subclass has finished constructing its outputs.
    private val machine: StateMachine by lazy { build() }

    private val automaticTransitions: List<AutomaticTransition<S>> by lazy { automatic?.transitions.orEmpty() }

    /** Requests [state]; the state stays when [from] does not allow it. */
    private fun take(state: S, from: Set<S>?, effect: () -> Unit) {
        if (state == this.state) effect() else machine.processEventBlocking(Request(state, from, effect))
    }

    internal fun enterInitialState() {
        machine
    }

    internal fun updateBeforeCommands() = beforeCommands()

    internal fun update() {
        val state = state
        val transition = automaticTransitions.firstOrNull {
            (it.from == null || it.from == state) && it.to != state && it.condition!!()
        }
        if (transition != null) machine.processEventBlocking(Taken(transition))
        during(current)
    }

    internal fun reset() {
        machine.processEventBlocking(Reset)
    }

    private val allStates: List<S> by lazy { initial.declaringJavaClass.enumConstants.toList() }

    private fun build(): StateMachine {
        val transitions = automaticTransitions
        return createStdLibStateMachine(javaClass.simpleName) {
            val nodes = allStates.associateWith { DefaultState(it.name) }
            for ((state, node) in nodes) {
                if (state == initial) addInitialState(node) else addState(node)
                node.onEntry {
                    current = state
                    this@Subsystem.onEntry(state)
                }
            }
            // Any state can be requested from any other; a request's own `from` is the only limit.
            // A request no transition accepts is ignored, so the state stays.
            for ((from, node) in nodes) {
                for (to in allStates) {
                    if (to == from) continue
                    node.transition<Request> {
                        guard = { event.state == to && event.from?.contains(from) != false }
                        targetState = nodes.getValue(to)
                    }.onTriggered { it.event.effect() }
                }
            }
            for (automatic in transitions) {
                for (from in automatic.from?.let(::listOf) ?: allStates.filter { it != automatic.to }) {
                    nodes.getValue(from).transition<Taken> {
                        guard = { event.transition === automatic }
                        targetState = nodes.getValue(automatic.to)
                    }.onTriggered { automatic.effect() }
                }
            }
            // STOP always returns to the initial state, whatever the subsystem's commands allow.
            transition<Reset> { targetState = nodes.getValue(initial) }
        }
    }

    private class Request(val state: Any, val from: Set<*>?, val effect: () -> Unit) : Event
    private class Taken(val transition: AutomaticTransition<*>) : Event
    private object Reset : Event
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
