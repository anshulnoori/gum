package com.anshulnoori.gum.ftc

import com.anshulnoori.gum.core.OpModeSession
import com.anshulnoori.gum.core.Program
import com.anshulnoori.gum.core.ProgramKind
import com.anshulnoori.gum.core.ProgramMetadata
import com.anshulnoori.gum.core.ProgramScope
import com.anshulnoori.gum.core.UpdatePhase
import com.anshulnoori.gum.core.UpdateTiming
import com.anshulnoori.gum.core.autonomous
import com.anshulnoori.gum.core.teleop
import com.anshulnoori.gum.core.utility
import com.anshulnoori.gum.hardware.RobotConfig
import com.qualcomm.hardware.lynx.LynxModule
import android.content.Context
import com.qualcomm.ftccommon.FtcEventLoop
import com.qualcomm.robotcore.eventloop.opmode.GumCallbackOpMode
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl
import com.qualcomm.robotcore.hardware.Gamepad
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.VoltageSensor
import com.qualcomm.robotcore.util.RobotLog
import org.firstinspires.ftc.robotcore.external.Telemetry
import dev.frozenmilk.sinister.sdk.apphooks.OnCreateEventLoop
import dev.frozenmilk.sinister.sdk.opmodes.OpModeScanner
import dev.frozenmilk.sinister.sdk.opmodes.SinisterRegisteredOpModes
import dev.frozenmilk.sinister.targeting.TeamCodeSearch
import org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/** FTC objects for one OpMode session. */
class FtcEnvironment internal constructor(
    /** Devices declared in TeamCode's [RobotConfig]. */
    val hardware: Hardware,
    val hardwareMap: HardwareMap,
    val telemetry: Telemetry,
    private val sdkGamepad1: Gamepad,
    private val sdkGamepad2: Gamepad,
) {
    /** The chain graph this run belongs to; null until the first run of a chain creates it. */
    internal var chain: Any? = null

    // Created on first use, on the OpMode thread inside the session, so their buttons join it.
    // The gamepad callback reads these from the SDK's event loop thread.
    @Volatile private var controller1: Controller? = null
    @Volatile private var controller2: Controller? = null

    val gamepad1: Controller get() = controller1 ?: Controller(sdkGamepad1).also { controller1 = it }
    val gamepad2: Controller get() = controller2 ?: Controller(sdkGamepad2).also { controller2 = it }

    internal fun report(data1: Gamepad, data2: Gamepad) {
        controller1?.report(data1)
        controller2?.report(data2)
    }

    internal fun sample() {
        controller1?.sample()
        controller2?.sample()
    }
}

typealias FtcProgram = Program<FtcEnvironment, *>
typealias FtcScope = ProgramScope<FtcEnvironment>

// Programs use these directly: `gamepad1.a.onPress { }`, `telemetry.addData(...)`.
val FtcScope.gamepad1: Controller get() = environment.gamepad1
val FtcScope.gamepad2: Controller get() = environment.gamepad2
val FtcScope.telemetry: Telemetry get() = environment.telemetry
val FtcScope.hardwareMap: HardwareMap get() = environment.hardwareMap

/**
 * Metro scope of one OpMode run. Gum creates a new robot graph at every INIT, so
 * `@SingleIn(OpModeScope::class)` subsystems are created once per run.
 */
abstract class OpModeScope private constructor()

/**
 * Metro scope of one chain of programs, such as an autonomous and the TeleOp it chains to.
 * Every run belongs to a chain; a run started from the Driver Station starts a new one.
 * `@SingleIn(ChainScope::class)` values, such as a remembered pose, last for the whole chain.
 * They must not hold hardware: each run gets its devices again.
 */
abstract class ChainScope private constructor()

/**
 * Supertype of TeamCode's per-run Metro graph, which lists every subsystem once:
 *
 * ```
 * @GraphExtension(OpModeScope::class)
 * interface RobotGraph : Robot {
 *     val intake: Intake
 * }
 * ```
 *
 * The Gum Gradle plugin generates the [ChainScope] graph it extends, and `teleop`, `autonomous`,
 * and `utility` in that package, typed to the graph: `teleop("Main") { robot -> robot.intake }`.
 * Each INIT creates a new robot graph.
 */
interface Robot {
    /** The main battery. */
    val battery: VoltageSensor get() = hardware.battery
}

private val currentHardware = ThreadLocal<Hardware>()

/**
 * Devices declared in TeamCode's [RobotConfig], for the OpMode running on this thread:
 * `hardware(RobotMap.intakeLeft)`. Subsystems use it for their constructor defaults.
 */
val hardware: Hardware
    get() = checkNotNull(currentHardware.get()) { "Use hardware while an OpMode runs, on the OpMode thread" }

/**
 * Called by the `teleop`, `autonomous`, and `utility` builders the Gum Gradle plugin generates.
 * Each INIT checks that the active configuration has every device [config] declares. A run that
 * starts from the Driver Station creates a new chain graph with [chain]; a chained run reuses its
 * predecessor's. [robot] then creates this run's graph inside the chain graph and reads every
 * accessor it declares, so each subsystem exists, and enters its initial state, at INIT; then [setup] runs.
 * A request a subsystem refuses goes to RobotLog and the Driver Station's telemetry log.
 */
fun <C : Any, R : Robot> program(
    kind: ProgramKind,
    name: String,
    group: String,
    description: String,
    config: RobotConfig,
    chain: () -> C,
    robot: (C) -> R,
    setup: FtcScope.(robot: R) -> Any?,
): FtcProgram {
    val dependencies: FtcScope.() -> R = {
        environment.hardware.requireDevices(config)
        onRefused { message ->
            RobotLog.ww("Gum", "%s", message)
            environment.telemetry.log().add(message)
        }
        @Suppress("UNCHECKED_CAST")
        val graph = environment.chain as C? ?: chain().also { environment.chain = it }
        robot(graph).also(::readAccessors)
    }
    return when (kind) {
        ProgramKind.TELEOP -> teleop(name, group, description, dependencies, setup)
        ProgramKind.AUTONOMOUS -> autonomous(name, group, description, dependencies, setup)
        ProgramKind.UTILITY -> utility(name, group, description, dependencies, setup)
    }
}

/**
 * Calls every accessor TeamCode's `RobotGraph` declares, such as `val intake: Intake`. Metro creates
 * a scoped value on first access, so this creates every subsystem now, not when code first uses it.
 */
private fun readAccessors(robot: Robot) {
    val graphs = generateSequence<Class<*>>(robot.javaClass) { it.superclass }
        .flatMap { it.interfaces.asSequence() }
        .filter { Robot::class.java.isAssignableFrom(it) && it != Robot::class.java }
    for (method in graphs.flatMap { it.methods.asSequence() }.distinct()) {
        if (method.parameterTypes.isNotEmpty() || method.declaringClass == Robot::class.java) continue
        if (Modifier.isStatic(method.modifiers) || method.returnType == Void.TYPE) continue
        try {
            method.invoke(robot)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }
}

/**
 * Registers every public top-level Gum program in TeamCode, such as `val main = teleop("Main") { }`.
 * Sloth runs it when the Robot Controller app starts and again after each hot reload, which
 * replaces the previous registrations.
 */
@Suppress("unused")
object GumProgramScanner : OpModeScanner() {
    override val targets = TeamCodeSearch()

    override fun scan(loader: ClassLoader, cls: Class<*>, registrationHelper: RegistrationHelper) {
        for (field in cls.declaredFields) {
            if (!Modifier.isStatic(field.modifiers) || !Program::class.java.isAssignableFrom(field.type)) continue
            if (!isPublic(cls, field)) continue
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val program = field.get(null) as FtcProgram
            registrationHelper.register(meta(program.metadata)) { GumOpMode(program) }
        }
    }

    // A Kotlin `val` compiles to a private field; its getter carries the declared visibility.
    // Method.getParameterCount needs Android 8 (API 26); the Control Hub runs Android 7.1.
    private fun isPublic(cls: Class<*>, field: Field): Boolean {
        if (Modifier.isPublic(field.modifiers)) return true
        val getter = "get" + field.name.replaceFirstChar { it.uppercaseChar() }
        return cls.declaredMethods.any { it.name == getter && it.parameterTypes.isEmpty() && Modifier.isPublic(it.modifiers) }
    }
}

private fun meta(metadata: ProgramMetadata): OpModeMeta = OpModeMeta.Builder()
    .setName(metadata.name)
    .setGroup(metadata.group.ifEmpty { OpModeMeta.DefaultGroup })
    .setFlavor(
        when (metadata.kind) {
            ProgramKind.TELEOP -> OpModeMeta.Flavor.TELEOP
            ProgramKind.AUTONOMOUS -> OpModeMeta.Flavor.AUTONOMOUS
            ProgramKind.UTILITY -> OpModeMeta.Flavor.UTILITY
        },
    )
    .setDescription(metadata.description)
    .setSource(OpModeMeta.Source.ANDROID_STUDIO)
    .build()

/**
 * Puts the next program of a chain into INIT. The next program takes over the Driver Station entry
 * with its name, so the Driver Station shows its name and kind, until that run stops; then the
 * entry's original program returns. It waits in INIT for a separate START.
 *
 * The SDK stops a run in one of two orders. Driver Station STOP queues the SDK's stop OpMode and then
 * stops the run. `requestOpModeStop`, a `runOpMode` that returns, and app teardown stop the run and
 * then queue the stop OpMode, which replaces anything queued while the run stopped. Either way the
 * stop OpMode initializes next, so the next program is queued then.
 */
@Suppress("unused")
internal object Chain : OnCreateEventLoop, OpModeManagerNotifier.Notifications {
    @Volatile
    private var manager: OpModeManagerImpl? = null

    /** The program to initialize when the stop OpMode next initializes, with its chain graph. */
    @Volatile
    private var pending: Pair<FtcProgram, Any?>? = null

    // Sloth calls this when the Robot Controller creates its event loop, including after a restart.
    override fun onCreateEventLoop(context: Context, ftcEventLoop: FtcEventLoop) {
        pending = null
        manager = ftcEventLoop.opModeManager.also { it.registerListener(this) }
    }

    // The SDK calls these on its event loop thread. It calls onOpModePostStop once the run's
    // thread has finished, and again for the same run when the second order above swaps it out.
    override fun onOpModePostStop(opMode: OpMode) {
        val run = opMode as? GumOpMode ?: return
        val handoff = run.takeHandoff() ?: return
        if (run.chained) restore(run.program.metadata.name, run.original)
        handoff.next?.let { pending = it to handoff.chain }
    }

    override fun onOpModePreInit(opMode: OpMode) {
        val (program, chain) = pending ?: return
        pending = null
        // Anything else initializing means someone chose another program instead.
        if (opMode is OpModeManagerImpl.DefaultOpMode) initialize(program, chain)
    }

    override fun onOpModePreStart(opMode: OpMode) {}

    /** Puts [program] into INIT in the SDK. JVM tests, which have no Robot Controller, replace it. */
    internal var initialize: (program: FtcProgram, chain: Any?) -> Unit = ::takeOverEntry

    private fun takeOverEntry(program: FtcProgram, chain: Any?) {
        val meta = meta(program.metadata)
        val registered = SinisterRegisteredOpModes.getOpMode(meta.name) as? GumOpMode
        // A chain that runs through the same entry twice restores the entry's first program.
        val original = registered?.let { it.original ?: it.program }
        SinisterRegisteredOpModes.unregister(meta.name)
        SinisterRegisteredOpModes.register(meta) { GumOpMode(program, original, chain) }
        checkNotNull(manager) { "The Robot Controller has no event loop" }.initOpMode(meta.name)
    }

    private fun restore(name: String, original: FtcProgram?) {
        SinisterRegisteredOpModes.unregister(name)
        if (original != null) SinisterRegisteredOpModes.register(meta(original.metadata)) { GumOpMode(original) }
    }
}

/**
 * Runs one Gum session per SDK run, all on the SDK's OpMode thread, which matches the session's
 * single-thread contract: setup, then an update loop until START, START, then an update loop until
 * STOP, then cleanup. Updates run back to back, without the iterative OpMode's 1 ms sleep.
 * After cleanup, a program that set up a next program hands off to it through [Chain].
 *
 * Gum owns bulk reads: every hub uses manual caching, cleared once before each update.
 * After each update, Gum sends that update's telemetry to the Driver Station and Panels.
 * For a program made with `debug`, Gum times every phase of each update and shows [Diagnostics] first.
 */
internal class GumOpMode(
    val program: FtcProgram,
    /** For a chained run, the program registered under this name before the chain replaced it. */
    val original: FtcProgram? = null,
    /** For a chained run, its chain graph; null starts a new chain. */
    private val chain: Any? = null,
) : GumCallbackOpMode() {
    val chained = chain != null
    @Volatile private var environment: FtcEnvironment? = null

    /** What this run leaves for [Chain] once it has finished: the next program and the chain graph. */
    class Handoff(val next: FtcProgram?, val chain: Any?)

    @Volatile private var handoff: Handoff? = null

    /** Returns this run's handoff once, after it has finished; null before that and afterward. */
    fun takeHandoff(): Handoff? = handoff.also { handoff = null }

    override fun runOpMode() {
        var environment: FtcEnvironment? = null
        var session: OpModeSession? = null
        var ended = false
        try {
            val hardware = Hardware(hardwareMap)
            val hubs = hardwareMap.getAll(LynxModule::class.java)
            hubs.forEach { it.bulkCachingMode = LynxModule.BulkCachingMode.MANUAL }
            currentHardware.set(hardware)
            environment = FtcEnvironment(hardware, hardwareMap, PanelsMirroredTelemetry(telemetry), gamepad1, gamepad2)
            environment.chain = chain
            this.environment = environment
            val diagnostics = if (program.debug) Diagnostics(UpdateTiming()) else null
            session = program.initialize(environment, diagnostics?.timing)
            sendTelemetry()
            while (opModeInInit()) update(hubs, environment, diagnostics, session::tick)
            if (!isStopRequested) {
                update(hubs, environment, diagnostics, session::start)
                while (opModeIsActive()) update(hubs, environment, diagnostics, session::tick)
            }
            ended = true
        } finally {
            this.environment = null
            // The SDK interrupts this thread at STOP. Cleanup runs uninterrupted, so its hardware
            // writes and thread joins work as they did in an iterative OpMode's stop().
            val interrupted = clearStopInterrupt()
            var next: FtcProgram? = null
            try {
                session?.stop()
                telemetry.update()
                // A run that failed does not chain.
                @Suppress("UNCHECKED_CAST")
                if (ended) next = session?.next as FtcProgram?
            } finally {
                currentHardware.remove()
                handoff = Handoff(next, environment?.chain)
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private inline fun update(hubs: List<LynxModule>, environment: FtcEnvironment, diagnostics: Diagnostics?, step: () -> Unit) {
        val timing = diagnostics?.timing
        // Added first so the timing heads the telemetry; adding counts as Other.
        diagnostics?.addTo(environment.telemetry)
        timing?.begin()
        for (hub in hubs) hub.clearBulkCache()
        environment.sample()
        timing?.end(UpdatePhase.INPUT)
        step()
        timing?.end(UpdatePhase.PROGRAM)
        telemetry.update()
        timing?.end(UpdatePhase.TELEMETRY)
        sendToPanels()
        timing?.end(UpdatePhase.PANELS)
    }

    private fun sendTelemetry() {
        telemetry.update()
        sendToPanels()
    }

    override fun onGamepadData(gamepad1: Gamepad, gamepad2: Gamepad) {
        environment?.report(gamepad1, gamepad2)
    }
}
