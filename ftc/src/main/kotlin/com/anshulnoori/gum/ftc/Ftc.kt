package com.anshulnoori.gum.ftc

import com.anshulnoori.gum.core.OpModeSession
import com.anshulnoori.gum.core.Program
import com.anshulnoori.gum.core.ProgramKind
import com.anshulnoori.gum.core.ProgramMetadata
import com.anshulnoori.gum.core.ProgramScope
import com.anshulnoori.gum.core.autonomous
import com.anshulnoori.gum.core.teleop
import com.anshulnoori.gum.core.utility
import com.anshulnoori.gum.hardware.RobotConfig
import com.qualcomm.hardware.lynx.LynxModule
import android.content.Context
import com.qualcomm.ftccommon.FtcEventLoop
import com.qualcomm.robotcore.eventloop.opmode.GumCallbackOpMode
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl
import com.qualcomm.robotcore.hardware.Gamepad
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.VoltageSensor
import org.firstinspires.ftc.robotcore.external.Telemetry
import dev.frozenmilk.sinister.sdk.apphooks.OnCreateEventLoop
import dev.frozenmilk.sinister.sdk.opmodes.OpModeScanner
import dev.frozenmilk.sinister.sdk.opmodes.SinisterRegisteredOpModes
import dev.frozenmilk.sinister.targeting.TeamCodeSearch
import org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta
import java.lang.reflect.Field
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
    // Created on first use, on the OpMode thread inside the session, so their buttons join it.
    // The gamepad callback reads these from the SDK's event loop thread.
    /** The chain graph this run belongs to; null until the first run of a chain creates it. */
    internal var chain: Any? = null

    @Volatile private var controller1: Controller? = null
    @Volatile private var controller2: Controller? = null

    val gamepad1: Controller get() = controller1 ?: Controller(sdkGamepad1).also { controller1 = it }
    val gamepad2: Controller get() = controller2 ?: Controller(sdkGamepad2).also { controller2 = it }

    internal fun report(data1: Gamepad, data2: Gamepad) {
        controller1?.report(data1)
        controller2?.report(data2)
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
 * predecessor's. [robot] then creates this run's graph inside the chain graph, for [setup].
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
        @Suppress("UNCHECKED_CAST")
        val graph = environment.chain as C? ?: chain().also { environment.chain = it }
        robot(graph)
    }
    return when (kind) {
        ProgramKind.TELEOP -> teleop(name, group, description, dependencies, setup)
        ProgramKind.AUTONOMOUS -> autonomous(name, group, description, dependencies, setup)
        ProgramKind.UTILITY -> utility(name, group, description, dependencies, setup)
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
    private fun isPublic(cls: Class<*>, field: Field): Boolean {
        if (Modifier.isPublic(field.modifiers)) return true
        val getter = "get" + field.name.replaceFirstChar { it.uppercaseChar() }
        return cls.declaredMethods.any { it.name == getter && it.parameterCount == 0 && Modifier.isPublic(it.modifiers) }
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
 */
@Suppress("unused")
internal object Chain : OnCreateEventLoop {
    @Volatile
    private var manager: OpModeManagerImpl? = null

    // Sloth calls this when the Robot Controller creates its event loop.
    override fun onCreateEventLoop(context: Context, ftcEventLoop: FtcEventLoop) {
        manager = ftcEventLoop.opModeManager
    }

    fun initialize(program: FtcProgram, chain: Any?) {
        val meta = meta(program.metadata)
        val registered = SinisterRegisteredOpModes.getOpMode(meta.name) as? GumOpMode
        // A chain that runs through the same entry twice restores the entry's first program.
        val original = registered?.let { it.original ?: it.program }
        SinisterRegisteredOpModes.unregister(meta.name)
        SinisterRegisteredOpModes.register(meta) { GumOpMode(program, original, chain) }
        checkNotNull(manager) { "The Robot Controller has no event loop" }.initOpMode(meta.name)
    }

    fun restore(name: String, original: FtcProgram?) {
        SinisterRegisteredOpModes.unregister(name)
        if (original != null) SinisterRegisteredOpModes.register(meta(original.metadata)) { GumOpMode(original) }
    }
}

/**
 * Drives one Gum session per SDK run. The SDK calls every method below on the OpMode thread,
 * which matches the session's single-thread contract. Each `init` starts a fresh session.
 * After STOP, a program that set up a next program hands off to it through [Chain].
 *
 * Gum owns bulk reads: every hub uses manual caching, cleared once before each dispatch.
 * After each dispatch, Gum sends that update's telemetry and field drawing to Panels.
 */
internal class GumOpMode(
    val program: FtcProgram,
    /** For a chained run, the program registered under this name before the chain replaced it. */
    val original: FtcProgram? = null,
    /** For a chained run, its chain graph; null starts a new chain. */
    private val chain: Any? = null,
) : GumCallbackOpMode() {
    private val chained = chain != null
    private var session: OpModeSession? = null
    @Volatile private var environment: FtcEnvironment? = null
    private var hubs: List<LynxModule> = emptyList()

    override fun init() {
        val hardware = Hardware(hardwareMap)
        hubs = hardwareMap.getAll(LynxModule::class.java)
        hubs.forEach { it.bulkCachingMode = LynxModule.BulkCachingMode.MANUAL }
        currentHardware.set(hardware)
        try {
            val environment = FtcEnvironment(hardware, hardwareMap, PanelsMirroredTelemetry(telemetry), gamepad1, gamepad2)
            environment.chain = chain
            this.environment = environment
            session = program.initialize(environment)
            sendToPanels()
        } catch (error: Throwable) {
            currentHardware.remove()
            throw error
        }
    }

    override fun init_loop() {
        clearBulkCache()
        session?.tick()
        sendToPanels()
    }

    override fun start() {
        clearBulkCache()
        session?.start()
        sendToPanels()
    }

    override fun loop() {
        clearBulkCache()
        session?.tick()
        sendToPanels()
    }

    override fun onGamepadData(gamepad1: Gamepad, gamepad2: Gamepad) {
        environment?.report(gamepad1, gamepad2)
    }

    override fun stop() {
        val current = session
        val chainGraph = environment?.chain
        session = null
        environment = null
        hubs = emptyList()
        try {
            current?.stop()
        } finally {
            currentHardware.remove()
            if (chained) Chain.restore(program.metadata.name, original)
        }
        @Suppress("UNCHECKED_CAST")
        (current?.next as FtcProgram?)?.let { Chain.initialize(it, chainGraph) }
    }

    private fun clearBulkCache() = hubs.forEach { it.clearBulkCache() }
}
