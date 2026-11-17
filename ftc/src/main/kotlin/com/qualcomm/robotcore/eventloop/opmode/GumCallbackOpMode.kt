package com.qualcomm.robotcore.eventloop.opmode

import com.qualcomm.robotcore.hardware.Gamepad
import java.util.concurrent.locks.LockSupport

/**
 * Gum's OpMode base. It lives in the SDK's package because the SDK's gamepad callback is
 * package-private. Not for TeamCode.
 */
abstract class GumCallbackOpMode : LinearOpMode() {
    /**
     * The SDK calls this on its event loop thread whenever the Driver Station sends gamepad data.
     * Each packet is a new [Gamepad] the SDK does not change afterward.
     */
    override fun newGamepadDataAvailable(latestGamepad1Data: Gamepad, latestGamepad2Data: Gamepad) {
        super.newGamepadDataAvailable(latestGamepad1Data, latestGamepad2Data)
        onGamepadData(latestGamepad1Data, latestGamepad2Data)
    }

    protected abstract fun onGamepadData(gamepad1: Gamepad, gamepad2: Gamepad)

    /**
     * Clears STOP's interrupt so cleanup can run without it, and returns whether there was one.
     * The SDK's STOP sets the stop flag and then interrupts this thread, so a loop that sees the flag
     * can get here before the interrupt; this waits for it, up to [STOP_INTERRUPT_WAIT_NANOS].
     */
    protected fun clearStopInterrupt(): Boolean {
        if (Thread.interrupted()) return true
        if (!stopRequested) return false
        val deadline = System.nanoTime() + STOP_INTERRUPT_WAIT_NANOS
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return false
            // Returns at once when the interrupt arrives.
            LockSupport.parkNanos(left)
            if (Thread.interrupted()) return true
        }
    }

    private companion object {
        /** Far longer than STOP takes between its two steps, and well inside its 900 ms stuck limit. */
        const val STOP_INTERRUPT_WAIT_NANOS = 100_000_000L
    }
}
