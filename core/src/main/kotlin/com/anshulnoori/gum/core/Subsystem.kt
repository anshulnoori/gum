package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import com.pedropathing.ivy.behaviors.EndCondition
import ru.nsk.kstatemachine.event.Event
import ru.nsk.kstatemachine.state.ChildMode
import ru.nsk.kstatemachine.state.DefaultState
import ru.nsk.kstatemachine.state.HistoryType
import ru.nsk.kstatemachine.state.IState
import ru.nsk.kstatemachine.state.addInitialState
import ru.nsk.kstatemachine.state.addState
import ru.nsk.kstatemachine.state.historyState
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
 * A mechanism, made of one or more [Region]s that are each in one state at a time, all at once.
 * An intake arm has an arm region (raised, lowered) and a roller region (spinning, stopped), so
 * it can be raised and spinning, raised and stopped, and so on:
 *
 * ```
 * val arm = region(Arm.LOWERED) { onEntry { servo.position = it.position } }
 * val roller = region(Roller.STOPPED) {
 *     onEntry { motor.power = it.power }
 *     automatic { Roller.SPINNING to Roller.STOPPED whenever { arm.isIn(Arm.RAISED) } }
 * }
 * fun collect() = roller.request(Roller.SPINNING, guard = { arm.isIn(Arm.LOWERED) })
 * ```
 *
 * Each region is its own Ivy requirement, so a command for the arm and one for the roller run at
 * the same time; a command that uses both, such as an Ivy group of one command for each, requires
 * both. Named commands built with [request], [hold], and [reach] are the subsystem's whole API.
 * A state can sit inside a parent state ([Nested]); a region can remember the last child of each
 * parent ([History]).
 *
 * The subsystem is one KStateMachine machine whose regions are the children of its parallel root.
 * Gum builds it; every region enters its initial state at INIT, once setup has run, or on the
 * subsystem's first update if it is created later. On every update after START, Gum calls
 * [beforeCommands] before button actions and commands; after commands run, it takes at most one
 * automatic transition per region, then calls each region's `during`. STOP returns every region
 * to its initial state.
 *
 * Construct a subsystem while an OpMode runs, on its thread; it joins that session.
 */
abstract class Subsystem {
    init {
        checkNotNull(ProgramScope.current.get()) {
            "Create ${javaClass.simpleName} while an OpMode runs, on the OpMode thread"
        }.register(this)
    }

    private val regions = ArrayList<Region<*>>()

    /**
     * Runs on every update after START, before button actions and commands, for reads that commands
     * decide from in the same update, such as a localizer's pose. Does nothing unless overridden.
     */
    protected open fun beforeCommands() {}

    /**
     * Declares a region that starts in [initial]; [block] sets its entry, exit, and `during`
     * actions and its automatic transitions. Declare regions as properties, so commands and other
     * regions' conditions can name them. With [history], requesting a parent state returns to the
     * child the region was last in instead of the parent's first child.
     */
    protected fun <S : Enum<S>> region(
        initial: S,
        history: History = History.NONE,
        block: RegionBuilder<S>.() -> Unit = {},
    ): Region<S> {
        val region = Region(this, initial, history)
        RegionBuilder(region).block()
        regions += region
        return region
    }

    /**
     * A command that transitions this region to [state] and finishes at once; the region stays in
     * [state] afterward. It requires this region.
     *
     * [state] may be a parent, which enters its first child, or with [History] the child the region
     * was last in. The request is refused, and the state stays, when [from] is given and the region
     * is in none of those states, or when [guard] returns false; [guard] can read other regions:
     * `guard = { arm.isIn(Arm.LOWERED) }`. Requesting a state the region is already in runs
     * [effect] only, without re-entering, if [guard] allows it.
     *
     * [effect] runs when the transition is taken, before any `onExit` and `onEntry`, to set data
     * the state uses: `request(State.SPINNING) { targetRpm = rpm }`.
     */
    protected fun <S : Enum<S>> Region<S>.request(
        state: S,
        from: S? = null,
        guard: (() -> Boolean)? = null,
        effect: () -> Unit = {},
    ): Command = requestCommand(state, from?.let(::setOf), guard, effect)

    /** [request] that is valid from any state in [from]. */
    protected fun <S : Enum<S>> Region<S>.request(
        state: S,
        from: Set<S>,
        guard: (() -> Boolean)? = null,
        effect: () -> Unit = {},
    ): Command = requestCommand(state, from, guard, effect)

    /**
     * A command that transitions this region to [state] like [request] and keeps running until it
     * is cancelled or interrupted, then returns the region to its initial state if it is still in
     * [state]. It also ends, leaving the state alone, when the region leaves [state] by an automatic
     * transition, or when [from] or [guard] refuses the request. A hold set to
     * `InterruptedBehavior.SUSPEND` keeps its place instead, and takes [state] back when Ivy resumes it.
     */
    protected fun <S : Enum<S>> Region<S>.hold(
        state: S,
        from: S? = null,
        guard: (() -> Boolean)? = null,
        effect: () -> Unit = {},
    ): Command = holdCommand(state, from?.let(::setOf), guard, effect)

    /** [hold] that is valid from any state in [from]. */
    protected fun <S : Enum<S>> Region<S>.hold(
        state: S,
        from: Set<S>,
        guard: (() -> Boolean)? = null,
        effect: () -> Unit = {},
    ): Command = holdCommand(state, from, guard, effect)

    /**
     * A command that requires this region and finishes once it is in [state], or in a child of it.
     * It can wait forever; limit it with Ivy, such as `reach(State.READY).raceWith(waitMs(2000.0))`.
     */
    protected fun <S : Enum<S>> Region<S>.reach(state: S): Command = reachCommand(state)

    // Updates since START; a suspended hold resumes only on a later one.
    internal var updates = 0L
        private set

    // Built at INIT, or on first use for a subsystem created later, after the subclass has
    // finished constructing its regions.
    private var machine: StateMachine? = null

    // The machine exists before it starts, so a request that an initial state's onEntry makes
    // reaches the machine instead of building another one.
    private fun machine(): StateMachine = machine ?: build().also {
        machine = it
        it.startBlocking()
    }

    private fun build(): StateMachine {
        check(regions.isNotEmpty()) { "${javaClass.simpleName} declares no region" }
        return createStdLibStateMachine(javaClass.simpleName, childMode = ChildMode.PARALLEL, start = false) {
            for (region in regions) region.build(addState(DefaultState(region.name)))
        }
    }

    // KStateMachine queues an event sent while it handles another, such as a request an onEntry
    // makes, and handles it after the current transition finishes.
    internal fun send(event: Event) {
        machine().processEventBlocking(event)
    }

    internal fun enterInitialState() {
        machine()
    }

    internal fun updateBeforeCommands() = beforeCommands()

    internal fun update() {
        machine()
        updates++
        var index = 0
        while (index < regions.size) regions[index++].takeAutomatic()
        index = 0
        while (index < regions.size) regions[index++].during()
    }

    /** Sets every region's outputs again, after a hub that lost them reconnects. */
    internal fun reapply() {
        if (machine == null) return
        var index = 0
        while (index < regions.size) regions[index++].reapply()
    }

    internal fun reset() {
        var index = 0
        while (index < regions.size) regions[index++].reset()
    }
}

/**
 * One part of a [Subsystem] that is in one state of [S] at a time, alongside the subsystem's other
 * regions. It is the Ivy requirement for its commands. Build one with `Subsystem.region`.
 */
class Region<S : Enum<S>> internal constructor(
    private val owner: Subsystem,
    private val initial: S,
    private val history: History,
) {
    internal val name: String = initial.declaringJavaClass.simpleName

    /**
     * The innermost state the region is in. In a parent state's `onEntry`, it is that parent until
     * its child is entered.
     */
    var state: S = initial
        private set

    /** Whether the region is in [state] or in a state inside it. */
    fun isIn(state: S): Boolean {
        var current: S? = this.state
        while (current != null) {
            if (current == state) return true
            current = parentOf(current)
        }
        return false
    }

    internal var entryAction: (S) -> Unit = {}
    internal var exitAction: (S) -> Unit = {}
    internal var duringAction: (S) -> Unit = {}
    internal val automatic = ArrayList<AutomaticTransition<S>>()

    // Where a request for each state goes: the state, or for a parent with history, its history state.
    private val targets = HashMap<S, IState>()

    @Suppress("UNCHECKED_CAST")
    private fun parentOf(state: S): S? = (state as? Nested<*>)?.parent as S?

    internal fun build(region: IState) {
        val states = initial.declaringJavaClass.enumConstants
        val children = states.groupBy(::parentOf)
        val initialPath = generateSequence(initial, ::parentOf).toSet()
        fun add(container: IState, parent: S?) {
            val kids = children[parent].orEmpty()
            val first = kids.firstOrNull { it in initialPath } ?: kids.first()
            for (state in kids) {
                val node = DefaultState(state.name)
                if (state == first) container.addInitialState(node) else container.addState(node)
                node.onEntry {
                    this@Region.state = state
                    entryAction(state)
                }
                node.onExit { exitAction(state) }
                targets[state] = if (state in children) {
                    add(node, state)
                    when (history) {
                        History.NONE -> node
                        History.SHALLOW -> node.historyState(historyType = HistoryType.SHALLOW)
                        History.DEEP -> node.historyState(historyType = HistoryType.DEEP)
                    }
                } else {
                    node
                }
            }
        }
        add(region, null)
        // Requests, automatic transitions, and STOP all send Go. The event reaches every region of
        // the subsystem; only its own takes it, and only when `from` and `guard` allow it.
        region.transitionConditionally<Go> {
            direction = {
                val go = event
                @Suppress("UNCHECKED_CAST")
                if (go.region !== this@Region || !allows(go.from as Set<S>?, go.guard)) noTransition()
                else targetState(targets.getValue(go.to as S))
            }
        }.onTriggered { it.event.effect() }
    }

    private fun allows(from: Set<S>?, guard: (() -> Boolean)?): Boolean =
        (from == null || from.any(::isIn)) && guard?.invoke() != false

    private fun go(to: S, from: Set<S>? = null, guard: (() -> Boolean)? = null, effect: () -> Unit = {}) {
        owner.send(Go(this, to, from, guard, effect))
    }

    /** Requests [to]; in [to] already, only the effect runs. */
    private fun take(to: S, from: Set<S>?, guard: (() -> Boolean)?, effect: () -> Unit) {
        if (!isIn(to)) go(to, from, guard, effect) else if (guard?.invoke() != false) effect()
    }

    internal fun takeAutomatic() {
        var index = 0
        while (index < automatic.size) {
            val transition = automatic[index++]
            val from = transition.from
            if ((from == null || isIn(from)) && !isIn(transition.to) && transition.condition!!()) {
                go(transition.to, effect = transition.effect)
                return
            }
        }
    }

    internal fun during() = duringAction(state)

    /** Runs `onEntry` again for every state the region is in, outermost first. */
    internal fun reapply() {
        val path = generateSequence(state, ::parentOf).toList()
        for (index in path.indices.reversed()) entryAction(path[index])
    }

    internal fun reset() = go(initial)

    // Each command below reports done only until it ends. Ivy 1.1.1's Scheduler.execute can reach a
    // command that was already interrupted in the same pass; a command that reports done there is
    // ended again, and Ivy then removes this region from the command that took it over.
    // Ivy#10 fixes this upstream; the `ended` flags go once Gum builds against a release with it.
    internal fun requestCommand(to: S, from: Set<S>?, guard: (() -> Boolean)?, effect: () -> Unit): Command {
        var ended = false
        return Command.build()
            .requiring(this)
            .setStart { ended = false; take(to, from, guard, effect) }
            .setDone { !ended }
            .setEnd { ended = it != EndCondition.SUSPENDED }
    }

    // A hold that Ivy suspends keeps its place: Ivy resumes it later without starting it again, so
    // it takes its state back on its first update after the suspension. Ivy 1.1.1 can also run a
    // command it just suspended once more in the same update, which must not take the state back
    // from the command that took over; a suspension therefore waits for the subsystem's next update.
    internal fun holdCommand(to: S, from: Set<S>?, guard: (() -> Boolean)?, effect: () -> Unit): Command {
        var ended = false
        var suspendedAt = NOT_SUSPENDED
        return Command.build()
            .requiring(this)
            .setStart { ended = false; suspendedAt = NOT_SUSPENDED; take(to, from, guard, effect) }
            .setExecute {
                if (suspendedAt != NOT_SUSPENDED && owner.updates > suspendedAt) {
                    suspendedAt = NOT_SUSPENDED
                    take(to, from, guard, effect)
                }
            }
            .setDone { !ended && suspendedAt == NOT_SUSPENDED && !isIn(to) }
            .setEnd {
                if (it == EndCondition.SUSPENDED) {
                    suspendedAt = owner.updates
                } else {
                    ended = true
                    if (isIn(to) && to != initial) go(initial)
                }
            }
    }

    internal fun reachCommand(to: S): Command {
        var ended = false
        return Command.build()
            .requiring(this)
            .setStart { ended = false }
            .setDone { !ended && isIn(to) }
            .setEnd { ended = it != EndCondition.SUSPENDED }
    }

    override fun toString() = "$name region of ${owner.javaClass.simpleName}"

    private class Go(
        val region: Region<*>,
        val to: Any,
        val from: Set<*>?,
        val guard: (() -> Boolean)?,
        val effect: () -> Unit,
    ) : Event

    private companion object {
        const val NOT_SUSPENDED = -1L
    }
}

/**
 * A state enum whose states can sit inside a parent state, which must be declared before them:
 *
 * ```
 * enum class Arm(override val parent: Arm? = null) : Nested<Arm> {
 *     LOWERED, RAISED, RAISED_LOW(RAISED), RAISED_HIGH(RAISED)
 * }
 * ```
 *
 * A region is always in a state without children: entering a parent enters its first declared
 * child, or the child that leads to the region's initial state. A parent's `onEntry` runs before
 * its child's, and its `onExit` after. Requests, holds, automatic transitions, `from`, and `reach`
 * on a parent cover every state inside it.
 */
interface Nested<S : Enum<S>> {
    val parent: S?
}

/** What requesting a parent state enters: its first child, or the child the region was last in. */
enum class History {
    /** The parent's first child, every time. */
    NONE,

    /** The parent's child the region was last in, then that child's first child. */
    SHALLOW,

    /** The innermost state the region was last in inside the parent. */
    DEEP,
}

/** Declares one region's actions and automatic transitions, in `Subsystem.region { }`. */
class RegionBuilder<S : Enum<S>> internal constructor(private val region: Region<S>) {
    /**
     * Sets outputs for a state. Runs each time the region enters a state, including its initial
     * state and every parent on the way in, and again after a hub reconnects, so outputs reach the
     * hub again.
     */
    fun onEntry(action: (state: S) -> Unit) {
        region.entryAction = action
    }

    /**
     * Runs each time the region leaves a state, after the transition's effect and before the next
     * state's `onEntry`, for cleanup that belongs to the state, such as stopping a timer it started.
     */
    fun onExit(action: (state: S) -> Unit) {
        region.exitAction = action
    }

    /**
     * Runs on every update after START, after commands and every region's automatic transitions,
     * for control that depends on sensors, such as holding a flywheel speed.
     */
    fun during(action: (state: S) -> Unit) {
        region.duringAction = action
    }

    /**
     * Declares automatic transitions: `A to B whenever { condition } effect { … }`. Each update,
     * after commands, Gum takes the first declared transition whose source the region is in, whose
     * target it is not in, and whose condition is true, at most one per region per update.
     * Conditions must only read, and can read other regions: Gum may evaluate any of them on any update.
     */
    fun automatic(block: AutomaticBuilder<S>.() -> Unit) {
        val transitions = AutomaticBuilder<S>().apply(block).transitions
        for (transition in transitions) {
            checkNotNull(transition.condition) {
                "${region.name}: the automatic transition to ${transition.to} needs `whenever { condition }`"
            }
        }
        region.automatic += transitions
    }
}

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

    /** A transition from this state, or any state inside it, to [target]; give it a condition with [whenever]. */
    infix fun S.to(target: S): AutomaticTransition<S> = AutomaticTransition(this, target).also { transitions += it }

    /** A transition to [target] from any other state; give it a condition with [whenever]. */
    infix fun AnyState.to(target: S): AutomaticTransition<S> = AutomaticTransition<S>(null, target).also { transitions += it }

    /** Gum takes this transition on an update when [condition] is true. [condition] must only read. */
    infix fun AutomaticTransition<S>.whenever(condition: () -> Boolean): AutomaticTransition<S> = apply {
        this.condition = condition
    }

    /** Runs [effect] when Gum takes this transition, before any `onExit` and `onEntry`. */
    infix fun AutomaticTransition<S>.effect(effect: () -> Unit) {
        this.effect = effect
    }
}
