package com.anshulnoori.gum.pedro

import com.pedropathing.algorithm.Algorithm
import com.pedropathing.drivetrain.Drivetrain
import com.pedropathing.follower.Follower
import com.pedropathing.localization.Localizer
import com.qualcomm.robotcore.hardware.HardwareMap
import java.util.function.Function
import java.util.function.Supplier

/**
 * A robot's Pedro Pathing parts, declared once in TeamCode: its drivetrain, its localizer, and its
 * path-following algorithm. The drive builds its follower from them, and a tuning build tunes and
 * tests the same parts (see `gum:pedro-tuning`).
 *
 * ```
 * public static final PedroRobot ROBOT = new PedroRobot(
 *     map -> new Mecanum(map, drivetrainConfig),
 *     map -> new PinpointLocalizer(map, localizerConfig),
 *     () -> new Foresight(foresightConfig));
 * ```
 *
 * The parameters are Java functional interfaces, so Java constants files, which AutoTune's
 * generated code pastes into unchanged, declare them with lambdas.
 */
open class PedroRobot(
    val drivetrain: Function<HardwareMap, Drivetrain>,
    val localizer: Function<HardwareMap, Localizer>,
    val algorithm: Supplier<Algorithm>,
) {
    /** A new follower from these parts, on [map]: `PedroDrive(ROBOT.follower(hardware.map))`. */
    fun follower(map: HardwareMap): Follower = Follower(localizer.apply(map), drivetrain.apply(map), algorithm.get())
}
