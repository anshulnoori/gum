package com.anshulnoori.gum.ftc

import com.anshulnoori.gum.core.OpModeSession
import com.anshulnoori.gum.core.Program
import com.anshulnoori.gum.core.ProgramKind
import com.anshulnoori.gum.core.ProgramScope
import com.anshulnoori.gum.hardware.RobotConfig
import com.qualcomm.hardware.lynx.LynxModule
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.eventloop.opmode.OpModeManager
import com.qualcomm.robotcore.hardware.Gamepad
import com.qualcomm.robotcore.hardware.HardwareMap
import org.firstinspires.ftc.robotcore.external.Telemetry
import org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta
import com.anshulnoori.gum.core.autonomous as coreAutonomous
import com.anshulnoori.gum.core.teleop as coreTeleop
import com.anshulnoori.gum.core.utility as coreUtility

/**
 * FTC objects for one OpMode session. Hardware lookups belong in the dependency factory.
 * Gamepads are the SDK's live objects; controller events are not implemented yet.
 */
class FtcEnvironment internal constructor(
    /** Devices declared in the registered [RobotConfig]. */
    val hardware: Hardware,
    val hardwareMap: HardwareMap,
    val telemetry: Telemetry,
    val gamepad1: Gamepad,
    val gamepad2: Gamepad,
)

typealias FtcProgram<D> = Program<FtcEnvironment, D>
typealias FtcScope = ProgramScope<FtcEnvironment>

fun <D> teleop(
    name: String,
    group: String = "",
    description: String = "",
    dependencies: FtcScope.() -> D,
    setup: FtcScope.(D) -> Unit,
): FtcProgram<D> = coreTeleop(name, group, description, dependencies, setup)

fun <D> autonomous(
    name: String,
    group: String = "",
    description: String = "",
    dependencies: FtcScope.() -> D,
    setup: FtcScope.(D) -> Unit,
): FtcProgram<D> = coreAutonomous(name, group, description, dependencies, setup)

fun <D> utility(
    name: String,
    group: String = "",
    description: String = "",
    dependencies: FtcScope.() -> D,
    setup: FtcScope.(D) -> Unit,
): FtcProgram<D> = coreUtility(name, group, description, dependencies, setup)

/**
 * Registers programs with the FTC SDK. Call it from a TeamCode method annotated with
 * `@OpModeRegistrar`, which the SDK discovers when the Robot Controller app starts.
 * Each INIT first checks that the active configuration has every device [config] declares.
 */
fun registerPrograms(manager: OpModeManager, config: RobotConfig, vararg programs: FtcProgram<*>) {
    for (program in programs) {
        val metadata = program.metadata
        val flavor = when (metadata.kind) {
            ProgramKind.TELEOP -> OpModeMeta.Flavor.TELEOP
            ProgramKind.AUTONOMOUS -> OpModeMeta.Flavor.AUTONOMOUS
            ProgramKind.UTILITY -> OpModeMeta.Flavor.UTILITY
        }
        val meta = OpModeMeta.Builder()
            .setName(metadata.name)
            .setGroup(metadata.group.ifEmpty { OpModeMeta.DefaultGroup })
            .setFlavor(flavor)
            .setDescription(metadata.description)
            .build()
        manager.register(meta, GumOpMode(config, program))
    }
}

/**
 * Drives one Gum session per SDK run. The SDK calls every method below on the OpMode thread,
 * which matches the session's single-thread contract. The SDK reuses a registered instance,
 * so each `init` starts a fresh session.
 *
 * Gum owns bulk reads: every hub uses manual caching, cleared once before each dispatch.
 */
private class GumOpMode(private val config: RobotConfig, private val program: FtcProgram<*>) : OpMode() {
    private var session: OpModeSession? = null
    private var hubs: List<LynxModule> = emptyList()

    override fun init() {
        val hardware = Hardware(hardwareMap).apply { requireDevices(config) }
        hubs = hardwareMap.getAll(LynxModule::class.java)
        hubs.forEach { it.bulkCachingMode = LynxModule.BulkCachingMode.MANUAL }
        session = program.initialize(FtcEnvironment(hardware, hardwareMap, telemetry, gamepad1, gamepad2))
    }

    override fun init_loop() {
        clearBulkCache()
        session?.tick()
    }

    override fun start() {
        clearBulkCache()
        session?.start()
    }

    override fun loop() {
        clearBulkCache()
        session?.tick()
    }

    override fun stop() {
        val current = session
        session = null
        hubs = emptyList()
        current?.stop()
    }

    private fun clearBulkCache() = hubs.forEach { it.clearBulkCache() }
}
