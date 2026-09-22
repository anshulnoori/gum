package com.anshulnoori.gum.core

/** A reusable program definition. Dependencies are created only by [initialize]. */
class Program<D> internal constructor(
    val name: String,
    private val dependencies: ProgramScope.() -> D,
    private val setup: ProgramScope.(D) -> Unit,
) {
    /**
     * Creates a session on the calling thread and runs dependency creation and setup once.
     * A failure runs registered cleanup before propagating the original error.
     */
    fun initialize(): OpModeSession {
        val scope = ProgramScope()
        val session = OpModeSession(scope)
        session.guard {
            val graph = scope.dependencies()
            scope.setup(graph)
        }
        scope.finishSetup()
        return session
    }
}

fun <D> teleop(
    name: String,
    dependencies: ProgramScope.() -> D,
    setup: ProgramScope.(D) -> Unit,
): Program<D> = Program(name, dependencies, setup)

/**
 * Registration is permitted only during dependency creation and setup, on the initializing thread.
 * Register resource cleanup immediately after acquisition, including within a dependency factory.
 * Callbacks must not block waiting for physical motion.
 */
class ProgramScope internal constructor() {
    private val owner = Thread.currentThread()
    private var configuring = true
    internal val initCallbacks = mutableListOf<() -> Unit>()
    internal val startCallbacks = mutableListOf<() -> Unit>()
    internal val tickCallbacks = mutableListOf<() -> Unit>()
    internal val stopCallbacks = mutableListOf<() -> Unit>()

    /** Runs on host ticks before START, in registration order. */
    fun onInitTick(callback: () -> Unit) = register(initCallbacks, callback)

    /** Runs once when the host signals START. */
    fun onStart(callback: () -> Unit) = register(startCallbacks, callback)

    /** Runs on host ticks after START, in registration order. */
    fun onTick(callback: () -> Unit) = register(tickCallbacks, callback)

    /** Runs once on shutdown, in reverse registration order, including after setup failure. */
    fun onStop(callback: () -> Unit) = register(stopCallbacks, callback)

    internal fun checkThread() {
        check(Thread.currentThread() === owner) { "Use the thread that initialized this session" }
    }

    internal fun finishSetup() {
        configuring = false
    }

    private fun register(callbacks: MutableList<() -> Unit>, callback: () -> Unit) {
        checkThread()
        check(configuring) { "Register lifecycle callbacks during dependency creation or setup" }
        callbacks += callback
    }
}

enum class SessionState { INIT, ACTIVE, STOPPED }

/**
 * Host-driven lifecycle. All access belongs to the thread that called [Program.initialize].
 * The host must call [stop] when it exits, including before START.
 * Callback failures stop the session and propagate, with cleanup failures attached as suppressed errors.
 */
class OpModeSession internal constructor(private val scope: ProgramScope) {
    private var currentState = SessionState.INIT
    private var dispatching = false

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
