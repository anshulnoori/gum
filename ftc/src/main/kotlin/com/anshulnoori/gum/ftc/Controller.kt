package com.anshulnoori.gum.ftc

import com.anshulnoori.gum.core.Button
import com.qualcomm.robotcore.hardware.Gamepad
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One gamepad for one session: `gamepad1.rightBumper.whileHeld { robot.intake.collecting() }`.
 *
 * Buttons change from the SDK's gamepad callback, not by polling: each change the Driver Station
 * sends reaches the session at its next update, in order. PlayStation and Xbox names are the same
 * button and read either SDK field. Sticks and triggers come from the same callback: each update
 * reads the latest packet that arrived before it started, so every value in one update comes from
 * one packet. [above], [below], and [between] turn a stick or trigger into a button that changes
 * from the same callback, so a threshold is an event too.
 */
class Controller internal constructor(
    /**
     * The SDK gamepad, for rumble, LEDs, and anything this class does not cover. The SDK copies
     * each packet into it on another thread, so values read from it can mix two packets.
     */
    val raw: Gamepad,
) {
    /** A button the callback updates. [reported] is its last state posted from there, so unchanged buttons post nothing. */
    private class Input(val button: Button, val read: (Gamepad) -> Boolean) {
        var reported = button.isDown
    }

    // Thresholds join while the callback thread reads it; copy-on-write lets it read without locking.
    private val inputs = CopyOnWriteArrayList<Input>()

    private fun button(read: (Gamepad) -> Boolean): Button =
        Button.reported(read(raw)).also { inputs += Input(it, read) }

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

    /** Down while [axis] is greater than [threshold]: `gamepad1.above(Axis.RIGHT_TRIGGER, 0.5)`. */
    fun above(axis: Axis, threshold: Double): Button = button { axis.read(it) > threshold }

    /** Down while [axis] is less than [threshold]. Stick Y is positive down, as in the SDK. */
    fun below(axis: Axis, threshold: Double): Button = button { axis.read(it) < threshold }

    /** Down while [axis] is from [low] to [high], inclusive. */
    fun between(axis: Axis, low: Double, high: Double): Button = button { axis.read(it) in low..high }

    /** Called on the OpMode thread before each update: sticks and triggers read the newest packet. */
    internal fun sample() {
        current = latest
    }

    /** Called on the SDK's event loop thread with each new packet, which the SDK does not change afterward. */
    internal fun report(data: Gamepad) {
        latest = data
        // Indexed, so each packet allocates no iterator. Inputs are only appended.
        for (index in 0 until inputs.size) {
            val input = inputs[index]
            val down = input.read(data)
            if (down != input.reported) {
                input.reported = down
                input.button.post(down)
            }
        }
    }
}

/** A stick axis or trigger, for [Controller.above], [Controller.below], and [Controller.between]. */
enum class Axis {
    /** Right is positive. */
    LEFT_STICK_X,
    /** Down is positive, as in the SDK. */
    LEFT_STICK_Y,
    RIGHT_STICK_X,
    RIGHT_STICK_Y,
    /** From 0, released, to 1. */
    LEFT_TRIGGER,
    RIGHT_TRIGGER;

    // A when, not a stored lambda, so reading a packet boxes no Double.
    internal fun read(gamepad: Gamepad): Double = when (this) {
        LEFT_STICK_X -> gamepad.left_stick_x
        LEFT_STICK_Y -> gamepad.left_stick_y
        RIGHT_STICK_X -> gamepad.right_stick_x
        RIGHT_STICK_Y -> gamepad.right_stick_y
        LEFT_TRIGGER -> gamepad.left_trigger
        RIGHT_TRIGGER -> gamepad.right_trigger
    }.toDouble()
}
