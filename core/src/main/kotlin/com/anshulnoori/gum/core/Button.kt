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
 *
 * [and], [or], [not], [debounce], [multiPress], [doublePress], and [toggled] derive new buttons
 * that change as their sources change, in the same update and in arrival order, so they stay
 * event-based: `(gamepad1.a and gamepad1.rightBumper).onPress { }`. A derived button starts in the
 * state its sources give it, so like any button it presses only after its sources change.
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
    fun post(down: Boolean) {
        check(react == null) { "A derived button changes only with its sources" }
        session.post(this, down)
    }

    /** One reported change. Each button keeps one of each, so reporting allocates nothing. */
    internal class Change(val button: Button, val down: Boolean)

    internal val pressChange = Change(this, true)
    internal val releaseChange = Change(this, false)

    /** The latest state reported with [post], for catching up after the session's buffer was full. */
    @Volatile internal var reported: Boolean = down

    internal fun catchUp() {
        if (read == null && react == null) session.change(this, reported)
    }

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

    /**
     * Schedules a fresh command from [command] when the button goes down after START, and cancels
     * it at the next press. If the command has ended by then, that press schedules a fresh one.
     */
    fun toggleOnPress(command: () -> Command) {
        var running: Command? = null
        onPress {
            val current = running
            if (current != null && current.isScheduled) {
                current.cancel()
                running = null
            } else {
                running = command().also { it.schedule() }
            }
        }
    }

    /** Down while this button and [other] are both down. */
    infix fun and(other: Button): Button = derive(isDown && other.isDown, this, other) {
        it.set(isDown && other.isDown)
    }

    /** Down while this button or [other] is down. */
    infix fun or(other: Button): Button = derive(isDown || other.isDown, this, other) {
        it.set(isDown || other.isDown)
    }

    /** Down while this button is up: `(!gamepad1.a).onPress { }` runs when A is released. */
    operator fun not(): Button = derive(!isDown, this) { it.set(!isDown) }

    /**
     * Goes down once this button has stayed down for [pressSeconds], and up once it has stayed up
     * for [releaseSeconds]. A change that reverses before then is ignored, which filters a bouncing
     * switch or a bumped button. Times count from the update that applies each change.
     */
    fun debounce(pressSeconds: Double, releaseSeconds: Double = 0.0): Button {
        require(pressSeconds >= 0 && releaseSeconds >= 0) { "Debounce times cannot be negative" }
        val press = nanos(pressSeconds)
        val release = nanos(releaseSeconds)
        return derive(isDown, this) {
            val wait = if (isDown) press else release
            when {
                isDown == it.isDown -> it.timing = false
                wait == 0L -> it.set(isDown)
                else -> {
                    it.due = session.now + wait
                    it.timing = true
                }
            }
        }.also { it.timeout = { it.set(isDown) } }
    }

    /**
     * Goes down at the [presses]th press within [windowSeconds] of the first, and up when this
     * button is released. Presses that miss the window start a new count. Every press still runs
     * this button's own actions. Times count from the update that applies each press, and taps
     * between updates still count.
     */
    fun multiPress(presses: Int, windowSeconds: Double): Button {
        require(presses >= 1) { "A multi-press needs at least one press" }
        require(windowSeconds >= 0) { "The window cannot be negative" }
        val window = nanos(windowSeconds)
        var count = 0
        var first = 0L
        return derive(false, this) {
            if (!isDown) return@derive it.set(false)
            val now = session.now
            if (count == 0 || now - first > window) {
                count = 0
                first = now
            }
            if (++count >= presses) {
                count = 0
                it.set(true)
            }
        }
    }

    /** A second press within [windowSeconds] of the first: `multiPress(2, windowSeconds)`. */
    fun doublePress(windowSeconds: Double): Button = multiPress(2, windowSeconds)

    /**
     * Flips between down and up at each press of this button, starting [down]. For a latched
     * setting such as slow mode: `val slow = gamepad1.leftBumper.toggled()`, then read `slow.isDown`.
     */
    fun toggled(down: Boolean = false): Button = derive(down, this) {
        if (isDown) it.set(!it.isDown)
    }

    // Derived buttons: each source change calls the derived button's react, on the session thread.
    private var react: (() -> Unit)? = null
    private val dependents = ArrayList<Button>()

    private fun derive(down: Boolean, vararg sources: Button, react: (Button) -> Unit): Button =
        Button(null, down).also { derived ->
            derived.react = { react(derived) }
            for (source in sources) {
                check(source.session === session) { "Combine buttons from the same session" }
                source.dependents += derived
            }
        }

    private fun set(down: Boolean) = session.change(this, down)

    internal fun notifyDependents() {
        var index = 0
        while (index < dependents.size) dependents[index++].react!!()
    }

    // A debounced button's pending change: while timing, timeout runs at the first update at or
    // after due, in nanoseconds on the session's clock.
    private var timing = false
    private var due = 0L
    private var timeout: (() -> Unit)? = null

    internal fun expire() {
        if (!timing || session.now - due < 0) return
        timing = false
        timeout!!()
    }

    private fun nanos(seconds: Double): Long = (seconds * 1e9).toLong()

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

    internal fun check() {
        read?.let { session.change(this, it()) }
    }

    internal fun run(pressed: Boolean, active: () -> Boolean) {
        for (action in if (pressed) pressActions else releaseActions) if (active()) action()
    }
}
