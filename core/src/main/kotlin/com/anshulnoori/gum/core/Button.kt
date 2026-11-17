package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command

/**
 * A boolean input, such as a gamepad button.
 *
 * A host reports changes with [post] from one input thread, such as the SDK's gamepad callback;
 * `Button { condition }` instead checks a condition at the start of each update, for inputs that
 * have no events, such as a sensor threshold. Either way, the session applies changes on its own
 * thread at the start of the next update, in arrival order, so a tap between updates still
 * presses and releases.
 *
 * [isDown], [wasPressed], and [wasReleased] describe the latest update, in INIT and after START,
 * so INIT code can poll them. [onPress] and [onRelease] actions run only after START, before
 * commands advance; a command they schedule starts in the same update.
 * A button already down when it is created, or held through START, presses only after a release.
 *
 * Create a button while an OpMode runs, on its thread; it joins that session.
 */
class Button private constructor(private val read: (() -> Boolean)?, down: Boolean) {
    /** A button that is down while [read] is true, checked at the start of every update. */
    constructor(read: () -> Boolean) : this(read, read())

    companion object {
        /** A button whose changes the host reports with [post]. It starts [down]. */
        fun reported(down: Boolean = false): Button = Button(null, down)
    }

    private val pressActions = mutableListOf<() -> Unit>()
    private val releaseActions = mutableListOf<() -> Unit>()
    private val session: ProgramScope<*> = checkNotNull(ProgramScope.current.get()) {
        "Create buttons while an OpMode runs, on the OpMode thread"
    }

    /** Down after the latest update. */
    var isDown: Boolean = down
        private set

    /** Went down during the latest update. */
    var wasPressed: Boolean = false
        private set

    /** Went up during the latest update. */
    var wasReleased: Boolean = false
        private set

    init {
        session.join(this)
    }

    /**
     * Reports that the button is now [down]; repeats are ignored. Call it from one thread only, the
     * host's input thread, or from the session's own thread.
     */
    fun post(down: Boolean) = session.post(this, down)

    /** One reported change. Each button keeps one of each, so reporting allocates nothing. */
    internal class Change(val button: Button, val down: Boolean)

    internal val pressChange = Change(this, true)
    internal val releaseChange = Change(this, false)

    /** The latest state reported with [post], for catching up after the session's buffer was full. */
    @Volatile internal var reported: Boolean = down

    internal fun catchUp(): Boolean? = if (read == null) apply(reported) else null

    /** Runs [action] each time the button goes down after START. Register during setup. */
    fun onPress(action: () -> Unit) = bind(pressActions, action)

    /** Runs [action] each time the button goes up after START. Register during setup. */
    fun onRelease(action: () -> Unit) = bind(releaseActions, action)

    /**
     * Schedules a fresh command from [command] each time the button goes down after START, and
     * cancels it when the button goes up. Pair it with a subsystem's named hold command, such as
     * `robot.intake.collecting()`, for hold-to-run mechanisms.
     */
    fun whileHeld(command: () -> Command) {
        var running: Command? = null
        onPress { running = command().also { it.schedule() } }
        onRelease {
            running?.let { if (it.isScheduled) it.cancel() }
            running = null
        }
    }

    private fun bind(actions: MutableList<() -> Unit>, action: () -> Unit) {
        session.checkConfiguring("Bind buttons")
        actions += action
    }

    internal fun clearEdges() {
        wasPressed = false
        wasReleased = false
    }

    /** Applies a change; returns whether it was a press, or null if the state did not change. */
    internal fun apply(down: Boolean): Boolean? {
        if (down == isDown) return null
        isDown = down
        if (down) wasPressed = true else wasReleased = true
        return down
    }

    internal fun check(): Boolean? = read?.let { apply(it()) }

    internal fun run(pressed: Boolean, active: () -> Boolean) {
        for (action in if (pressed) pressActions else releaseActions) if (active()) action()
    }
}
