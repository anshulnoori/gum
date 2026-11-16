package com.anshulnoori.gum.ftc

import com.anshulnoori.gum.core.Button
import com.qualcomm.robotcore.hardware.Gamepad

/**
 * One gamepad for one session: `gamepad1.rightBumper.whileHeld { robot.intake.hold(COLLECTING) }`.
 *
 * Buttons change from the SDK's gamepad callback, not by polling: each change the Driver Station
 * sends reaches the session at its next update, in order. PlayStation and Xbox names are the same
 * button and read either SDK field. Sticks and triggers read the SDK's gamepad, which the SDK
 * updates before each `init_loop` and `loop`.
 */
class Controller internal constructor(
    /** The SDK gamepad, for rumble, LEDs, and anything this class does not cover. */
    val raw: Gamepad,
) {
    private val inputs = ArrayList<Pair<Button, (Gamepad) -> Boolean>>()
    // Last state reported from the callback thread, so unchanged buttons post nothing.
    private lateinit var reported: BooleanArray

    private fun button(read: (Gamepad) -> Boolean): Button =
        Button.reported(read(raw)).also { inputs += it to read }

    val a = button { it.a || it.cross }
    val b = button { it.b || it.circle }
    val x = button { it.x || it.square }
    val y = button { it.y || it.triangle }
    val cross get() = a
    val circle get() = b
    val square get() = x
    val triangle get() = y

    val dpadUp = button { it.dpad_up }
    val dpadDown = button { it.dpad_down }
    val dpadLeft = button { it.dpad_left }
    val dpadRight = button { it.dpad_right }

    val leftBumper = button { it.left_bumper }
    val rightBumper = button { it.right_bumper }
    val leftStickButton = button { it.left_stick_button }
    val rightStickButton = button { it.right_stick_button }

    /** A trigger pressed past the SDK's threshold. */
    val leftTriggerButton = button { it.left_trigger > it.triggerThreshold }
    val rightTriggerButton = button { it.right_trigger > it.triggerThreshold }

    val back = button { it.back || it.share }
    val start = button { it.start || it.options }
    val guide = button { it.guide || it.ps }
    val share get() = back
    val options get() = start
    val ps get() = guide
    val touchpad = button { it.touchpad }

    init {
        reported = BooleanArray(inputs.size) { inputs[it].first.isDown }
    }

    /** Right is positive. */
    val leftStickX: Double get() = raw.left_stick_x.toDouble()
    /** Down is positive, as in the SDK. */
    val leftStickY: Double get() = raw.left_stick_y.toDouble()
    val rightStickX: Double get() = raw.right_stick_x.toDouble()
    val rightStickY: Double get() = raw.right_stick_y.toDouble()
    val leftTrigger: Double get() = raw.left_trigger.toDouble()
    val rightTrigger: Double get() = raw.right_trigger.toDouble()

    /** Called on the SDK's event loop thread with new gamepad data. */
    internal fun report(data: Gamepad) {
        for (index in inputs.indices) {
            val (button, read) = inputs[index]
            val down = read(data)
            if (down != reported[index]) {
                reported[index] = down
                button.post(down)
            }
        }
    }
}
