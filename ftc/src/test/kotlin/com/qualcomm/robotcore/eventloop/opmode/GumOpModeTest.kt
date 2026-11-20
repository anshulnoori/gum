package com.qualcomm.robotcore.eventloop.opmode

import com.anshulnoori.gum.core.ProgramKind
import com.anshulnoori.gum.core.Subsystem
import com.anshulnoori.gum.core.debug
import com.anshulnoori.gum.core.teleop
import com.anshulnoori.gum.ftc.Axis
import com.anshulnoori.gum.ftc.Chain
import com.anshulnoori.gum.ftc.FtcEnvironment
import com.anshulnoori.gum.ftc.FtcProgram
import com.anshulnoori.gum.ftc.GumOpMode
import com.anshulnoori.gum.ftc.Robot
import com.anshulnoori.gum.ftc.program
import com.anshulnoori.gum.hardware.RobotConfig
import com.anshulnoori.gum.ftc.gamepad1
import com.anshulnoori.gum.ftc.telemetry
import com.qualcomm.robotcore.hardware.Gamepad
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.robocol.TelemetryMessage
import org.firstinspires.ftc.robotcore.internal.opmode.OpModeServices
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

// Drives GumOpMode through the SDK's LinearOpMode lifecycle: init starts the OpMode
// thread, its start and stop flip the flags that thread watches, and its stop interrupts the thread
// and waits for it, as OpModeManagerImpl does on the event loop thread.
class GumOpModeTest {
    /** Every line the Driver Station received, and each message's lines in order. */
    private val sent = CopyOnWriteArrayList<String>()
    private val messages = CopyOnWriteArrayList<List<String>>()

    private val services = object : OpModeServices {
        override fun refreshUserTelemetry(telemetry: TelemetryMessage, sInterval: Double) {
            sent += telemetry.dataStrings.values
            messages += telemetry.dataStrings.values.toList()
        }
        override fun requestOpModeStop(opModeToStopIfActive: OpMode) {}
    }

    private fun init(opMode: OpMode) {
        opMode.gamepad1 = Gamepad()
        opMode.gamepad2 = Gamepad()
        opMode.hardwareMap = HardwareMap(null, null)
        opMode.internalOpModeServices = services
        // OpModeInternal.internalInit, whose thread pool needs Android: run the OpMode on its own
        // thread, keep what it throws, and mark the thread finished.
        opMode.isStarted = false
        opMode.stopRequested = false
        opMode.opModeThreadFinished = false
        opMode.executorService = Executors.newSingleThreadExecutor()
        opMode.executorService.execute {
            try {
                opMode.internalRunOpMode()
            } catch (error: RuntimeException) {
                opMode.exception = error
            } finally {
                opMode.opModeThreadFinished = true
            }
        }
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting until $what")
            Thread.sleep(1)
        }
    }

    private val initUpdates = AtomicInteger()
    private val activeUpdates = AtomicInteger()

    private fun program(name: String, next: FtcProgram? = null, failInLoop: Boolean = false): FtcProgram =
        teleop<FtcEnvironment, Unit>(name, dependencies = {}) {
            initLoop { initUpdates.incrementAndGet() }
            loop { activeUpdates.incrementAndGet() }
            if (failInLoop) loop { error("loop failed") }
            next
        }

    /** Runs [program] from INIT through START, waits for an update in each phase it reaches, then stops it. */
    private fun run(program: FtcProgram, start: Boolean = true): GumOpMode {
        initUpdates.set(0)
        activeUpdates.set(0)
        val opMode = GumOpMode(program)
        init(opMode)
        eventually("an INIT update runs") { initUpdates.get() > 0 }
        if (start) {
            opMode.internalStart()
            eventually("an active update runs") { activeUpdates.get() > 0 }
        }
        opMode.internalStop()
        return opMode
    }

    private val initialized = mutableListOf<String>()
    private val takeOverEntry = Chain.initialize

    @BeforeTest
    fun recordChain() {
        Chain.initialize = { program, _ -> initialized += program.name }
    }

    @AfterTest
    fun restoreChain() {
        Chain.initialize = takeOverEntry
    }

    @Test
    fun `one OpMode thread runs every phase, reads sticks from the callback, and cleans up uninterrupted`() {
        val threads = CopyOnWriteArrayList<Thread>()
        val initUpdates = AtomicInteger()
        val activeUpdates = AtomicInteger()
        val presses = AtomicInteger()
        val starts = AtomicInteger()
        val stick = AtomicReference(Double.NaN)
        val cleanupInterrupted = AtomicReference<Boolean>()
        val next = program("Next")
        val opMode = GumOpMode(teleop<FtcEnvironment, Unit>("Auto", dependencies = {}) {
            threads += Thread.currentThread()
            gamepad1.a.onPress { presses.incrementAndGet() }
            initLoop { threads.addIfAbsent(Thread.currentThread()); initUpdates.incrementAndGet() }
            onStart { starts.incrementAndGet() }
            loop {
                threads.addIfAbsent(Thread.currentThread())
                stick.set(gamepad1.leftStickX)
                activeUpdates.incrementAndGet()
            }
            onStop { cleanupInterrupted.set(Thread.currentThread().isInterrupted) }
            next
        })

        init(opMode)
        eventually("INIT updates run") { initUpdates.get() > 2 }
        assertEquals(0, activeUpdates.get(), "nothing active before START")
        opMode.internalStart()
        eventually("active updates run") { activeUpdates.get() > 2 }
        assertEquals(0.0, stick.get())

        (opMode as OpModeInternal).newGamepadDataAvailable(Gamepad().apply { left_stick_x = 0.5f; a = true }, Gamepad())
        eventually("the packet reaches the loop") { stick.get() == 0.5 && presses.get() == 1 }

        opMode.internalStop()
        assertEquals(false, cleanupInterrupted.get(), "the SDK interrupts the thread at STOP; cleanup runs without it")
        assertEquals(1, starts.get())
        assertEquals(1, threads.size, "setup, both loops, and cleanup share one thread")
        assertTrue(threads.single() !== Thread.currentThread())
        val handoff = opMode.takeHandoff()
        assertSame(next, handoff?.next)
        assertNull(opMode.takeHandoff(), "the handoff is taken once")
    }

    @Test
    fun `stick and trigger thresholds change from the callback packets, alone and combined`() {
        val events = CopyOnWriteArrayList<String>()
        val updates = AtomicInteger()
        val opMode = GumOpMode(teleop<FtcEnvironment, Unit>("Thresholds", dependencies = {}) {
            val a = gamepad1.a
            val trigger = gamepad1.above(Axis.RIGHT_TRIGGER, 0.5)
            trigger.onPress { events += "trigger press" }
            trigger.onRelease { events += "trigger release" }
            val centered = gamepad1.between(Axis.LEFT_STICK_Y, -0.2, 0.2)
            centered.onPress { events += "centered press" }
            centered.onRelease { events += "centered release" }
            (a and gamepad1.below(Axis.LEFT_STICK_X, -0.5)).onPress { events += "a and left" }
            loop { updates.incrementAndGet() }
        })
        init(opMode)
        opMode.internalStart()
        eventually("active updates run") { updates.get() > 0 }

        fun send(packet: Gamepad.() -> Unit) =
            (opMode as OpModeInternal).newGamepadDataAvailable(Gamepad().apply(packet), Gamepad())

        send { right_trigger = 0.6f }
        send { right_trigger = 0.55f }
        send { right_trigger = 0.5f }
        send { left_stick_y = 0.8f }
        send { left_stick_y = 0.1f }
        send { left_stick_x = -0.6f }
        send { left_stick_x = -0.6f; a = true }
        eventually("every change reaches the session") { events.size >= 5 }
        Thread.sleep(20)
        opMode.internalStop()

        assertEquals(
            listOf("trigger press", "trigger release", "centered release", "centered press", "a and left"),
            events,
            "0.5 is not above 0.5; 0.1 is between -0.2 and 0.2; the stick at rest starts centered",
        )
    }

    @Test
    fun `STOP in INIT skips START and leaves nothing to chain to`() {
        val opMode = run(program("Auto", next = program("Next")), start = false)
        assertNull(opMode.takeHandoff()?.next)
    }

    @Test
    fun `the next program initializes when the stop OpMode does, on either SDK stop order`() {
        val stopOpMode = OpModeManagerImpl.DefaultOpMode()

        // Driver Station STOP: the stop OpMode is queued first, then the run stops.
        Chain.onOpModePostStop(run(program("Auto", next = program("TeleOp A"))))
        Chain.onOpModePreInit(stopOpMode)
        assertEquals(listOf("TeleOp A"), initialized)

        // stopActiveOpMode: the run stops, then the stop OpMode is queued over anything queued
        // during the stop, and the swap reports the same run stopped again.
        initialized.clear()
        val auto = run(program("Auto", next = program("TeleOp B")))
        Chain.onOpModePostStop(auto)
        assertEquals(emptyList(), initialized, "nothing is queued while the stop OpMode is still to come")
        Chain.onOpModePostStop(auto)
        Chain.onOpModePreInit(stopOpMode)
        assertEquals(listOf("TeleOp B"), initialized)
        Chain.onOpModePreInit(stopOpMode)
        assertEquals(listOf("TeleOp B"), initialized, "a chain initializes once")
    }

    @Test
    fun `a failed run, or another program chosen first, does not chain`() {
        Chain.onOpModePostStop(run(program("Auto", next = program("TeleOp"), failInLoop = true)))
        Chain.onOpModePreInit(OpModeManagerImpl.DefaultOpMode())

        Chain.onOpModePostStop(run(program("Auto", next = program("TeleOp"))))
        Chain.onOpModePreInit(GumOpMode(program("Other")))
        Chain.onOpModePreInit(OpModeManagerImpl.DefaultOpMode())
        assertEquals(emptyList(), initialized)
    }

    enum class State { OFF, ON }

    class Roller : Subsystem<State>(State.OFF) {
        val entered = CopyOnWriteArrayList<State>()
        override fun onEntry(state: State) {
            entered += state
        }
    }

    interface TestGraph : Robot {
        val roller: Roller
    }

    /** Creates its subsystem on first access, as Metro does for a scoped value. */
    class LazyGraph : TestGraph {
        val lazyRoller = lazy { Roller() }
        override val roller by lazyRoller
    }

    object NoDevices : RobotConfig("none")

    interface ProvidingGraph : TestGraph {
        /** A provider with a body, as a `@Provides` function compiles; reading it would build an unscoped extra. */
        fun extraRoller(): Roller = error("INIT called a provider")
    }

    /** Runs INIT for a program whose graph [robot] creates, and returns what INIT threw. */
    private fun initGraph(robot: () -> TestGraph): Throwable? {
        val initUpdates = AtomicInteger()
        val opMode = GumOpMode(program(ProgramKind.TELEOP, "Graph", "", "", NoDevices, chain = {}, robot = { robot() }) {
            initLoop { initUpdates.incrementAndGet() }
        })
        init(opMode)
        eventually("INIT updates run or INIT fails") { initUpdates.get() > 0 || opMode.opModeThreadFinished }
        opMode.internalStop()
        return opMode.exception
    }

    @Test
    fun `INIT reads only accessors, not functions with a body`() {
        assertNull(initGraph {
            object : ProvidingGraph {
                override val roller by lazy { Roller() }
            }
        })
    }

    @Test
    fun `INIT fails when a subsystem accessor creates a new instance each read`() {
        val error = initGraph {
            object : TestGraph {
                override val roller get() = Roller()
            }
        }
        assertEquals(
            "RobotGraph.getRoller creates a new Roller each time it is read. Annotate Roller with @SingleIn(OpModeScope::class).",
            error?.message,
        )
    }

    @Test
    fun `INIT creates every subsystem the graph declares, and each enters its initial state before START`() {
        val graph = AtomicReference<LazyGraph>()
        val createdBySetup = AtomicReference<Boolean>()
        val initUpdates = AtomicInteger()
        val program = program(ProgramKind.TELEOP, "Graph", "", "", NoDevices, chain = {}, robot = { LazyGraph() }) {
            graph.set(it as LazyGraph)
            createdBySetup.set(it.lazyRoller.isInitialized())
            initLoop { initUpdates.incrementAndGet() }
        }
        val opMode = GumOpMode(program)
        init(opMode)
        eventually("INIT updates run") { initUpdates.get() > 0 }
        assertEquals(true, createdBySetup.get(), "created before setup, though setup never reads it")
        assertEquals(listOf(State.OFF), graph.get().roller.entered)
        opMode.internalStop()
    }

    @Test
    fun `a debug program shows loop time and every phase first in its telemetry, and the program it wraps shows neither`() {
        val updates = AtomicInteger()
        val plain = teleop<FtcEnvironment, Unit>("Main", dependencies = {}) {
            loop { telemetry.addData("Updates", updates.incrementAndGet()) }
        }
        val debugged = GumOpMode(debug(plain))
        init(debugged)
        debugged.internalStart()
        eventually("timing reaches the Driver Station") { sent.any { it.startsWith("Gum phases (ms)") } }
        debugged.internalStop()
        val loop = sent.last { it.startsWith("Gum loop") }
        assertTrue(Regex("Gum loop : [0-9.]+ ms avg, [0-9.]+ ms max, [0-9]+ Hz").matches(loop), loop)
        val phases = sent.last { it.startsWith("Gum phases (ms)") }
        for (phase in listOf("Input", "Buttons", "Ivy", "Subsystems", "Program", "Telemetry", "Panels", "Other")) {
            assertTrue(Regex("\\b$phase [0-9]+\\.[0-9]{2}\\b").containsMatchIn(phases), phases)
        }
        val timed = messages.filter { lines -> lines.any { it.startsWith("Gum loop") } }
        assertTrue(timed.all { it[0].startsWith("Gum loop") && it[1].startsWith("Gum phases") && it[2].startsWith("Updates") }, timed.toString())
        assertTrue(updates.get() > 0, "the wrapped program's own setup and loop run")

        sent.clear()
        val quiet = GumOpMode(plain)
        init(quiet)
        quiet.internalStart()
        // A window is one second; by now a debug program would have shown it.
        Thread.sleep(1_500)
        quiet.internalStop()
        assertTrue(sent.any { it.startsWith("Updates") }, "the program's own telemetry reaches the Driver Station")
        assertTrue(sent.none { it.startsWith("Gum") }, sent.toString())
    }

    @Test
    fun `cleanup runs uninterrupted even when STOP's interrupt arrives after the loop has ended`() {
        val updates = AtomicInteger()
        val interrupted = AtomicReference<Boolean>()
        val opMode = GumOpMode(teleop<FtcEnvironment, Unit>("Slow STOP", dependencies = {}) {
            loop { updates.incrementAndGet() }
            onStop { interrupted.set(Thread.currentThread().isInterrupted) }
            // Cleanup that takes a while, such as hardware writes; it runs before the check above.
            onStop { val end = System.nanoTime() + 50_000_000; while (System.nanoTime() < end) Unit }
        })
        init(opMode)
        opMode.internalStart()
        eventually("active updates run") { updates.get() > 100 }
        // OpModeInternal.internalStop sets the flag, then interrupts. Interrupt only once the loop has
        // seen the flag and ended, as happens when the event loop thread is slow between the two.
        opMode.stopRequested = true
        var seen = updates.get()
        while (true) {
            Thread.sleep(10)
            val now = updates.get()
            if (now == seen) break
            seen = now
        }
        opMode.executorService.shutdownNow()
        eventually("the OpMode thread finishes") { opMode.opModeThreadFinished }
        assertEquals(false, interrupted.get())
    }
}
