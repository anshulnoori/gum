package com.anshulnoori.gum.ftc

import android.app.Activity
import android.content.Context
import com.anshulnoori.gum.hardware.Device
import com.anshulnoori.gum.hardware.RobotConfig
import com.anshulnoori.gum.hardware.Role
import com.qualcomm.ftccommon.configuration.RobotConfigFile
import com.qualcomm.ftccommon.configuration.RobotConfigFileManager
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.qualcomm.hardware.rev.Rev2mDistanceSensor
import com.qualcomm.hardware.rev.RevColorSensorV3
import com.qualcomm.hardware.sparkfun.SparkFunOTOS
import com.qualcomm.robotcore.hardware.AnalogInput
import com.qualcomm.robotcore.hardware.CRServo
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DigitalChannel
import com.qualcomm.robotcore.hardware.HardwareDevice
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.IMU
import com.qualcomm.robotcore.hardware.Servo
import com.qualcomm.robotcore.hardware.VoltageSensor
import com.qualcomm.robotcore.util.RobotLog

/**
 * Typed access to devices declared in a [RobotConfig], for one session.
 * `hardware(RobotMap.liftLeft)` returns the SDK type for that device's role.
 */
class Hardware internal constructor(
    /** The SDK hardware map, for libraries that find devices by name, such as Pedro's `Constants.create`. */
    val map: HardwareMap,
) {
    @JvmName("motor") operator fun invoke(device: Device<Role.Motor>): DcMotorEx = lookup(device)
    @JvmName("servo") operator fun invoke(device: Device<Role.Servo>): Servo = lookup(device)
    @JvmName("crServo") operator fun invoke(device: Device<Role.CrServo>): CRServo = lookup(device)
    @JvmName("digital") operator fun invoke(device: Device<Role.Digital>): DigitalChannel = lookup(device)
    @JvmName("analog") operator fun invoke(device: Device<Role.Analog>): AnalogInput = lookup(device)
    @JvmName("imu") operator fun invoke(device: Device<Role.Imu>): IMU = lookup(device)
    @JvmName("pinpoint") operator fun invoke(device: Device<Role.Pinpoint>): GoBildaPinpointDriver = lookup(device)
    @JvmName("otos") operator fun invoke(device: Device<Role.Otos>): SparkFunOTOS = lookup(device)
    @JvmName("color") operator fun invoke(device: Device<Role.ColorSensorV3>): RevColorSensorV3 = lookup(device)
    @JvmName("distance") operator fun invoke(device: Device<Role.Distance2m>): Rev2mDistanceSensor = lookup(device)

    /** The main battery, which every configuration has without declaring it. */
    val battery: VoltageSensor get() = map.voltageSensor.iterator().next()

    @Suppress("UNCHECKED_CAST")
    private fun <T> lookup(device: Device<*>): T = map.get(sdkType(device.role), device.name) as T

    /** Fails INIT with one message naming every declared device the active configuration lacks. */
    internal fun requireDevices(config: RobotConfig) {
        val missing = config.devices.filter { map.tryGet(sdkType(it.role), it.name) == null }
        check(missing.isEmpty()) {
            "The active robot configuration is missing ${missing.joinToString()} from \"${config.name}\". " +
                "Select \"${config.name}\" in Configure Robot, or restart the Robot Controller app."
        }
    }
}

private fun sdkType(role: Class<*>): Class<out HardwareDevice> = when (role) {
    Role.Motor::class.java -> DcMotorEx::class.java
    Role.Servo::class.java -> Servo::class.java
    Role.CrServo::class.java -> CRServo::class.java
    Role.Digital::class.java -> DigitalChannel::class.java
    Role.Analog::class.java -> AnalogInput::class.java
    Role.Imu::class.java -> IMU::class.java
    Role.Pinpoint::class.java -> GoBildaPinpointDriver::class.java
    Role.Otos::class.java -> SparkFunOTOS::class.java
    Role.ColorSensorV3::class.java -> RevColorSensorV3::class.java
    Role.Distance2m::class.java -> Rev2mDistanceSensor::class.java
    else -> error("No SDK type for role ${role.simpleName}")
}

/** Called by the generated `@OnCreate` hook. Not part of the TeamCode interface. */
object RobotConfigActivation {
    /**
     * Makes the generated configuration active. The Robot Controller calls `@OnCreate` hooks at the
     * end of its activity's `onCreate`, before its service connects and it builds the hardware map.
     */
    @JvmStatic
    fun activate(context: Context, name: String, resourceId: Int) {
        val manager = RobotConfigFileManager(context as Activity)
        val active = manager.activeConfig
        if (active.location == RobotConfigFile.FileLocation.RESOURCE && active.name == name) return
        manager.setActiveConfig(false, RobotConfigFile(name, resourceId))
        RobotLog.ii("Gum", "Activated robot configuration %s (was %s)", name, active.name)
    }
}
