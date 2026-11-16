package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import ru.nsk.kstatemachine.event.Event
import ru.nsk.kstatemachine.state.DefaultState
import ru.nsk.kstatemachine.state.addInitialState
import ru.nsk.kstatemachine.state.addState
import ru.nsk.kstatemachine.state.onEntry
import ru.nsk.kstatemachine.state.transition
import ru.nsk.kstatemachine.statemachine.StateMachine
import ru.nsk.kstatemachine.statemachine.createStdLibStateMachine
import ru.nsk.kstatemachine.statemachine.processEventBlocking

/**
 * A mechanism that is in one mode at a time.
 *
 * A subclass lists its modes as an enum, sets its outputs in [enter], and exposes commands built
 * with [request] and [reach]. It can restrict moves between modes and add automatic moves with
 * [transitions], and run feedback control in [loop]. Gum builds the state machine; on every update
 * after commands run, it takes automatic moves and then calls [loop]. STOP returns the subsystem
 * to [initial].
 *
 * Construct a subsystem while an OpMode runs, on its thread; it joins that session.
 */
abstract class Subsystem<M : Enum<M>>(private val initial: M) {
    init {
        val session = checkNotNull(ProgramScope.current.get()) {
            "Create ${javaClass.simpleName} while an OpMode runs, on the OpMode thread"
        }
        session.register(this)
    }

    /** Allowed moves. Without this, any mode can be requested from any other. */
    protected open val transitions: Transitions<M>? = null

    /** Sets outputs for [mode]. Runs each time the subsystem enters a mode, including [initial]. */
    protected abstract fun enter(mode: M)

    /**
     * Runs on every update after START, after commands and automatic moves, for control that
     * depends on sensors, such as holding a flywheel speed. Does nothing unless overridden.
     */
    protected open fun loop(mode: M) {}

    /** The current mode. */
    val mode: M
        get() {
            machine
            return current
        }

    /**
     * A command that moves to [mode] and finishes at once; the subsystem stays in [mode] afterward.
     * It reserves this subsystem. A move [transitions] does not allow leaves the mode unchanged.
     *
     * [set] runs when the request is allowed, before [enter], to set data the mode uses:
     * `request(Mode.SPINNING) { targetRpm = rpm }`. Requesting the current mode runs [set] only.
     */
    fun request(mode: M, set: () -> Unit = {}): Command = Command.build()
        .requiring(this)
        .setStart {
            val from = this.mode
            if (mode == from) {
                set()
            } else if (from to mode in edges) {
                set()
                machine.processEventBlocking(Request(mode))
            }
        }
        .setDone { true }

    /**
     * A command that moves to [mode] like [request] and keeps running until it is cancelled or
     * interrupted, then returns the subsystem to its initial mode if it is still in [mode].
     */
    fun hold(mode: M, set: () -> Unit = {}): Command {
        val enter = request(mode, set)
        return Command.build()
            .requiring(this)
            .setStart { enter.start() }
            .setDone { false }
            .setEnd { if (this.mode == mode && mode != initial) machine.processEventBlocking(Reset) }
    }

    /** A command that reserves this subsystem and finishes once it is in [mode]. */
    fun reach(mode: M): Command = Command.build()
        .requiring(this)
        .setDone { this.mode == mode }

    /** Declares the moves between modes. */
    protected fun transitions(block: TransitionsBuilder<M>.() -> Unit): Transitions<M> =
        Transitions(TransitionsBuilder<M>().apply(block).rules)

    private var current = initial

    // Built on first use, after the subclass has finished constructing its outputs.
    private val machine: StateMachine by lazy { build() }

    private val automatic: List<Rule<M>> by lazy { transitions?.rules.orEmpty().filter { it.condition != null } }

    internal fun update() {
        val mode = mode
        val rule = automatic.firstOrNull { (it.from == null || it.from == mode) && it.to != mode && it.condition!!() }
        if (rule != null) machine.processEventBlocking(Automatic(rule))
        loop(current)
    }

    internal fun reset() {
        machine.processEventBlocking(Reset)
    }

    private val modes: List<M> by lazy { initial.declaringJavaClass.enumConstants.toList() }

    // Moves a command may request.
    private val edges: Set<Pair<M, M>> by lazy {
        val requestable = transitions?.rules?.filter { it.condition == null } ?: listOf(Rule(null, null))
        requestable.flatMap { rule ->
            (rule.from?.let(::listOf) ?: modes).flatMap { from ->
                (rule.to?.let(::listOf) ?: modes).filter { it != from }.map { from to it }
            }
        }.toSet()
    }

    private fun build(): StateMachine {
        return createStdLibStateMachine(javaClass.simpleName) {
            val states = modes.associateWith { DefaultState(it.name) }
            for ((mode, state) in states) {
                if (mode == initial) addInitialState(state) else addState(state)
                state.onEntry {
                    current = mode
                    enter(mode)
                }
            }
            for ((from, to) in edges) {
                states.getValue(from).transition<Request> {
                    guard = { event.mode == to }
                    targetState = states.getValue(to)
                }
            }
            for (rule in automatic) {
                for (from in rule.from?.let(::listOf) ?: modes.filter { it != rule.to }) {
                    states.getValue(from).transition<Automatic> {
                        guard = { event.rule === rule }
                        targetState = states.getValue(rule.to!!)
                    }
                }
            }
            // STOP always returns to the initial mode, whatever the declared moves allow.
            transition<Reset> { targetState = states.getValue(initial) }
        }
    }

    private class Request(val mode: Any) : Event
    private class Automatic(val rule: Rule<*>) : Event
    private object Reset : Event
}

/** Moves between a subsystem's modes, built with [Subsystem.transitions]. */
class Transitions<M : Enum<M>> internal constructor(internal val rules: List<Rule<M>>)

/** One declared move. A `null` [from] means any mode; a `null` [to] is internal and means any mode. */
class Rule<M : Enum<M>> internal constructor(internal val from: M?, internal val to: M?) {
    internal var condition: (() -> Boolean)? = null
}

/** Marks a move that can start from any mode. */
object AnyMode

class TransitionsBuilder<M : Enum<M>> internal constructor() {
    internal val rules = mutableListOf<Rule<M>>()

    /** Every mode, as the start of a move. */
    val anyMode = AnyMode

    /** A command may request this move. */
    infix fun M.to(target: M): Rule<M> = Rule(this, target).also { rules += it }

    /** A command may request [target] from any mode. */
    infix fun AnyMode.to(target: M): Rule<M> = Rule<M>(null, target).also { rules += it }

    /** Makes this move automatic: Gum takes it on an update when [condition] is true. Commands cannot request it. */
    infix fun Rule<M>.whenever(condition: () -> Boolean) {
        this.condition = condition
    }
}
