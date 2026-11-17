package com.anshulnoori.gum.pedro.tuning

import com.anshulnoori.gum.pedro.PedroRobot
import com.anshulnoori.gum.pedro.tuning.procedures.ForesightTuner
import com.anshulnoori.gum.pedro.tuning.procedures.MecanumTuner
import com.anshulnoori.gum.pedro.tuning.procedures.PinpointTuner
import com.anshulnoori.gum.pedro.tuning.procedures.Tests
import com.pedropathing.tuning.autotune.Procedure
import com.pedropathing.tuning.autotune.TunerRegistrar
import dev.frozenmilk.sinister.Scanner
import dev.frozenmilk.sinister.targeting.TeamCodeSearch
import java.lang.reflect.Modifier

/**
 * Lists Pedro's AutoTune procedures for the robot's [PedroRobot], on the AutoTune page that Pedro's
 * tuning library serves at port 10158 while the app runs.
 *
 * Sloth runs this when the Robot Controller app starts and again after each hot reload. It finds
 * the static [PedroRobot] field in TeamCode, such as `PedroConstants.ROBOT`, and registers the
 * Mecanum, Pinpoint, and Foresight tuners and the Follower tests for it, so TeamCode needs no
 * `@Tuner` methods.
 */
@Suppress("unused")
object PedroTunerScanner : Scanner {
    override val loadAdjacencyRule = Scanner.INDEPENDENT
    override val unloadAdjacencyRule = Scanner.INDEPENDENT
    override val targets = TeamCodeSearch()

    private val loaders = mutableSetOf<ClassLoader>()

    override fun scan(loader: ClassLoader, cls: Class<*>) {
        for (field in cls.declaredFields) {
            if (!Modifier.isStatic(field.modifiers) || !PedroRobot::class.java.isAssignableFrom(field.type)) continue
            field.isAccessible = true
            val robot = field.get(null) as? PedroRobot ?: continue
            for (procedure in procedures(robot)) TunerRegistrar.register("", procedure)
            loaders += loader
        }
    }

    override fun unload(loader: ClassLoader, cls: Class<*>) {}

    // TunerRegistrar removes single procedures only from its own package, so a reload clears every
    // procedure. TeamCode registers none of its own; this scanner is their only source.
    override fun beforeUnload(loader: ClassLoader) {
        if (loaders.remove(loader)) TunerRegistrar.deregisterAll()
    }

    /** The procedures listed for [robot], in the order Pedro's docs tune them. */
    fun procedures(robot: PedroRobot): List<Procedure> = listOf(
        MecanumTuner(),
        PinpointTuner(),
        ForesightTuner(robot.localizer, robot.drivetrain),
        Tests(robot.drivetrain, robot.localizer, robot.algorithm),
    )
}
