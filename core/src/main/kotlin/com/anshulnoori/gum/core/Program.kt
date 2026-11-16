package com.anshulnoori.gum.core

import com.pedropathing.ivy.Scheduler
import org.jctools.queues.atomic.SpscAtomicArrayQueue
import java.util.concurrent.atomic.AtomicBoolean

enum class ProgramKind { TELEOP, AUTONOMOUS, UTILITY }

/** Host-facing metadata. It does not affect lifecycle behavior. */
data class ProgramMetadata(
    val name: String,
    val kind: ProgramKind,
    val group: String,
    val description: String,
)

/**
 * A reusable program definition. Dependencies are created only by [initialize].
 * [E] is the environment the host supplies at INIT, such as FTC hardware access.
 *
 * Setup may end with the program to run next, which chains programs: an autonomous that ends
 * with its TeleOp hands off to it. Any other result means no next program.
 */
class Program<E, D> internal constructor(
    val metadata: ProgramMetadata,
    private val dependencies: ProgramScope<E>.() -> D,
    private val setup: ProgramScope<E>.(D) -> Any?,
) {
    val name: String get() = metadata.name

    /**
     * Creates a session on the calling thread and runs dependency creation and setup once.
     * A failure runs registered cleanup before propagating the original error.
     *
     * Every tick first samples each [Button]. An INIT tick then runs [ProgramScope.initLoop] callbacks.
     * An active tick runs button actions, advances Ivy's global scheduler, updates every [Subsystem],
     * and runs the program's own [ProgramScope.loop] callbacks. STOP runs the program's cleanup,
     * returns subsystems to their initial modes, and finally clears the scheduler.
     */
    fun initialize(environment: E): OpModeSession {
        Scheduler.reset()
        val scope = ProgramScope(environment)
        val session = OpModeSession(scope)
        ProgramScope.current.set(scope)
        session.guard {
            scope.onStop { Scheduler.reset() }
            scope.onStop { scope.resetSubsystems() }
            // Runs before subsystems reset: readers stop first.
            scope.onStop { scope.stopBackground() }
            scope.initLoop { scope.updateButtons(dispatch = false) }
            scope.loop { scope.updateButtons(dispatch = true) }
            scope.loop { Scheduler.execute() }
            scope.loop { scope.updateSubsystems() }
            val graph = scope.dependencies()
            session.chained = scope.setup(graph) as? Program<*, *>
        }
        scope.finishSetup()
        return session
    }
}

fun <E, D> teleop(
    name: String,
    group: String = "",
    description: String = "",
    dependencies: ProgramScope<E>.() -> D,
    setup: ProgramScope<E>.(D) -> Any?,
): Program<E, D> = Program(ProgramMetadata(name, ProgramKind.TELEOP, group, description), dependencies, setup)

fun <E, D> autonomous(
    name: String,
    group: String = "",
    description: String = "",
    dependencies: ProgramScope<E>.() -> D,
    setup: ProgramScope<E>.(D) -> Any?,
): Program<E, D> = Program(ProgramMetadata(name, ProgramKind.AUTONOMOUS, group, description), dependencies, setup)

fun <E, D> utility(
    name: String,
    group: String = "",
    description: String = "",
    dependencies: ProgramScope<E>.() -> D,
    setup: ProgramScope<E>.(D) -> Any?,
): Program<E, D> = Program(ProgramMetadata(name, ProgramKind.UTILITY, group, description), dependencies, setup)

/**
 * Registration is permitted only during dependency creation and setup, on the initializing thread.
 * Register resource cleanup immediately after acquisition, including within a dependency factory.
 * Callbacks must not block waiting for physical motion.
 * Schedule Ivy commands from START or later; Ivy starts a command as soon as it is scheduled.
 */
class ProgramScope<out E> internal constructor(
    /** The host environment for this session. Do not retain it beyond the session. */
    val environment: E,
) {
    private val owner = Thread.currentThread()
    private var configuring = true
    internal val initCallbacks = mutableListOf<() -> Unit>()
    internal val startCallbacks = mutableListOf<() -> Unit>()
    internal val tickCallbacks = mutableListOf<() -> Unit>()
    internal val stopCallbacks = mutableListOf<() -> Unit>()
    private val subsystems = mutableListOf<Subsystem<*>>()
    private val buttons = mutableListOf<Button>()
    @Volatile internal var stopped = false

    /** Runs on every update before START, in registration order. */
    fun initLoop(callback: () -> Unit) = register(initCallbacks, callback)

    /** Runs once when the host signals START. */
    fun onStart(callback: () -> Unit) = register(startCallbacks, callback)

    /** Runs on every update after START, in registration order, after commands and subsystems. */
    fun loop(callback: () -> Unit) = register(tickCallbacks, callback)

    /**
     * Runs once on shutdown, in reverse registration order, including after setup failure.
     * Ivy commands still scheduled at STOP are dropped without their `end` callback.
     */
    fun onStop(callback: () -> Unit) = register(stopCallbacks, callback)

    internal fun register(subsystem: Subsystem<*>) {
        subsystems += subsystem
    }

    private val background = mutableListOf<Latest<*>>()

    internal fun startBackground(latest: Latest<*>) {
        check(!stopped) { "The session has stopped" }
        background += latest
        latest.start()
    }

    internal fun stopBackground() {
        for (latest in background) latest.stop()
        background.clear()
    }

    internal fun join(button: Button) {
        checkThread()
        buttons += button
    }

    // Changes the host reports from its one input thread, applied on this thread at the next update.
    // A bounded circular buffer of preallocated changes: reporting allocates nothing.
    private val posted = SpscAtomicArrayQueue<Button.Change>(POSTED_CAPACITY)
    // Set when the buffer was full; the next update then applies each button's latest reported state.
    private val overflowed = AtomicBoolean()
    private val changes = ArrayList<Button>()
    private val pressed = ArrayList<Boolean>()

    internal fun post(button: Button, down: Boolean) {
        if (stopped) return
        button.reported = down
        if (!posted.offer(if (down) button.pressChange else button.releaseChange)) overflowed.set(true)
    }

    /** Applies reported changes in arrival order, then checks condition buttons, then runs actions. */
    internal fun updateButtons(dispatch: Boolean) {
        changes.clear()
        pressed.clear()
        // Buttons created during an update join the list; index iteration picks them up.
        var index = 0
        while (index < buttons.size) buttons[index++].clearEdges()
        val overflow = overflowed.getAndSet(false)
        while (true) {
            val change = posted.poll() ?: break
            change.button.apply(change.down)?.let { changes += change.button; pressed += it }
        }
        index = 0
        while (index < buttons.size) {
            val button = buttons[index++]
            (if (overflow) button.catchUp() else null)?.let { changes += button; pressed += it }
            button.check()?.let { changes += button; pressed += it }
        }
        if (!dispatch) return
        index = 0
        while (!stopped && index < changes.size) {
            changes[index].run(pressed[index]) { !stopped }
            index++
        }
    }

    // Subsystems created during an update join the list; index iteration picks them up.
    internal fun updateSubsystems() {
        var index = 0
        while (index < subsystems.size) subsystems[index++].update()
    }

    internal fun resetSubsystems() {
        var failure: Throwable? = null
        for (subsystem in subsystems.asReversed()) {
            try {
                subsystem.reset()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    internal companion object {
        /** The session running on this thread, from INIT until STOP. */
        val current = ThreadLocal<ProgramScope<*>>()

        /** Reported changes one update can hold, far more than a Driver Station sends between updates. */
        const val POSTED_CAPACITY = 256
    }

    internal fun checkThread() {
        check(Thread.currentThread() === owner) { "Use the thread that initialized this session" }
    }

    internal fun finishSetup() {
        configuring = false
    }

    internal fun checkConfiguring(what: String) {
        checkThread()
        check(configuring) { "$what during dependency creation or setup" }
    }

    private fun register(callbacks: MutableList<() -> Unit>, callback: () -> Unit) {
        checkConfiguring("Register lifecycle callbacks")
        callbacks += callback
    }
}

enum class SessionState { INIT, ACTIVE, STOPPED }

/**
 * Host-driven lifecycle. All access belongs to the thread that called [Program.initialize].
 * The host must call [stop] when it exits, including before START.
 * Callback failures stop the session and propagate, with cleanup failures attached as suppressed errors.
 */
class OpModeSession internal constructor(private val scope: ProgramScope<*>) {
    private var currentState = SessionState.INIT
    private var dispatching = false
    private var started = false
    internal var chained: Program<*, *>? = null

    /**
     * The program setup ended with, for the host to initialize after this session stops.
     * Null until STOP, and null if the session stopped before START.
     */
    val next: Program<*, *>?
        get() {
            scope.checkThread()
            return if (currentState == SessionState.STOPPED && started) chained else null
        }

    val state: SessionState
        get() {
            scope.checkThread()
            return currentState
        }

    internal fun guard(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            try {
                stop()
            } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    /** Repeated START has no effect. Calling START from a lifecycle callback is invalid. */
    fun start() {
        scope.checkThread()
        check(!dispatching) { "Do not call start or tick from lifecycle callbacks" }
        if (state != SessionState.INIT) return
        currentState = SessionState.ACTIVE
        started = true
        dispatch(scope.startCallbacks)
    }

    /** Advances the current phase without creating threads or scheduling work independently. */
    fun tick() {
        scope.checkThread()
        check(!dispatching) { "Do not call start or tick from lifecycle callbacks" }
        when (state) {
            SessionState.INIT -> dispatch(scope.initCallbacks)
            SessionState.ACTIVE -> dispatch(scope.tickCallbacks)
            SessionState.STOPPED -> Unit
        }
    }

    private fun dispatch(callbacks: List<() -> Unit>) {
        dispatching = true
        try {
            guard {
                var index = 0
                while (currentState != SessionState.STOPPED && index < callbacks.size) {
                    callbacks[index++]()
                }
            }
        } finally {
            dispatching = false
        }
    }

    /**
     * Stops dispatch and attempts every cleanup, even if one fails. Repeated STOP has no effect.
     * A callback can stop its session; no later update callback runs in that dispatch.
     * Cleanup cannot preempt or terminate the currently executing callback itself.
     */
    fun stop() {
        scope.checkThread()
        if (state == SessionState.STOPPED) return
        currentState = SessionState.STOPPED
        scope.stopped = true
        if (ProgramScope.current.get() === scope) ProgramScope.current.remove()
        scope.finishSetup()
        var failure: Throwable? = null
        for (callback in scope.stopCallbacks.asReversed()) {
            try {
                callback()
            } catch (error: Throwable) {
                if (failure == null) failure = error
                else if (failure !== error) failure.addSuppressed(error)
            }
        }
        scope.initCallbacks.clear()
        scope.startCallbacks.clear()
        scope.tickCallbacks.clear()
        scope.stopCallbacks.clear()
        failure?.let { throw it }
    }
}
