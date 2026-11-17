package com.anshulnoori.gum.ftc

import com.anshulnoori.gum.core.Button
import com.qualcomm.robotcore.hardware.Gamepad

/**
 * One gamepad for one session: `gamepad1.rightBumper.whileHeld { robot.intake.collecting() }`.
 *
 * Buttons change from the SDK's gamepad callback, not by polling: each change the Driver Station
 * sends reaches the session at its next update, in order. PlayStation and Xbox names are the same
 * button and read either SDK field. Sticks and triggers come from the same callback: each update
 * reads the latest packet that arrived before it started, so every value in one update comes from
 * one packet.
 */
class Controller internal constructor(
    /**
     * The SDK gamepad, for rumble, LEDs, and anything this class does not cover. The SDK copies
     * each packet into it on another thread, so values read from it can mix two packets.
     */
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

    // The newest packet from the callback thread, and the one this update reads.
    @Volatile private var latest: Gamepad = Gamepad().also { it.copy(raw) }
    private var current: Gamepad = latest

    /** Right is positive. */
    val leftStickX: Double get() = current.left_stick_x.toDouble()
    /** Down is positive, as in the SDK. */
    val leftStickY: Double get() = current.left_stick_y.toDouble()
    val rightStickX: Double get() = current.right_stick_x.toDouble()
    val rightStickY: Double get() = current.right_stick_y.toDouble()
    val leftTrigger: Double get() = current.left_trigger.toDouble()
    val rightTrigger: Double get() = current.right_trigger.toDouble()

    /** Called on the OpMode thread before each update: sticks and triggers read the newest packet. */
    internal fun sample() {
        current = latest
    }

    /** Called on the SDK's event loop thread with each new packet, which the SDK does not change afterward. */
    internal fun report(data: Gamepad) {
        latest = data
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
