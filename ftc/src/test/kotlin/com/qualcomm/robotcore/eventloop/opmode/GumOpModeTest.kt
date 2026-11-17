package com.qualcomm.robotcore.eventloop.opmode

import com.anshulnoori.gum.core.ProgramKind
import com.anshulnoori.gum.core.Subsystem
import com.anshulnoori.gum.core.teleop
import com.anshulnoori.gum.ftc.Chain
import com.anshulnoori.gum.ftc.FtcEnvironment
import com.anshulnoori.gum.ftc.FtcProgram
import com.anshulnoori.gum.ftc.GumOpMode
import com.anshulnoori.gum.ftc.Robot
import com.anshulnoori.gum.ftc.program
import com.anshulnoori.gum.hardware.RobotConfig
import com.anshulnoori.gum.ftc.gamepad1
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
    private val services = object : OpModeServices {
        override fun refreshUserTelemetry(telemetry: TelemetryMessage, sInterval: Double) {}
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

    private fun program(name: String, next: FtcProgram? = null, failInLoop: Boolean = false): FtcProgram =
        teleop<FtcEnvironment, Unit>(name, dependencies = {}) {
            if (failInLoop) loop { error("loop failed") }
            next
        }

    /** Runs [program] from INIT through START to STOP and returns the finished OpMode. */
    private fun run(program: FtcProgram, start: Boolean = true): GumOpMode {
        val opMode = GumOpMode(program)
        init(opMode)
        if (start) opMode.internalStart()
        Thread.sleep(20)
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
    fun `one OpMode thread runs every phase, updates without sleeping, reads sticks from the callback, and cleans up uninterrupted`() {
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

        val before = activeUpdates.get()
        Thread.sleep(100)
        val updates = activeUpdates.get() - before
        // An iterative OpMode sleeps 1 ms per update, so it manages at most 100 in this time.
        assertTrue(updates > 300, "updates run back to back: $updates in 100 ms")

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
}
