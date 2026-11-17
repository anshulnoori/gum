package com.qualcomm.robotcore.eventloop.opmode

import com.qualcomm.robotcore.hardware.Gamepad

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
}
