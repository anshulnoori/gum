package com.anshulnoori.gum.ftc

import android.app.Activity
import android.content.Context
import com.anshulnoori.gum.hardware.Device
import com.anshulnoori.gum.hardware.RobotConfig
import com.anshulnoori.gum.hardware.Role
import com.qualcomm.ftccommon.configuration.RobotConfigFile
import com.qualcomm.ftccommon.configuration.RobotConfigFileManager
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.qualcomm.hardware.limelightvision.Limelight3A
import com.qualcomm.hardware.rev.Rev2mDistanceSensor
import com.qualcomm.hardware.rev.RevColorSensorV3
import com.qualcomm.hardware.sparkfun.SparkFunOTOS
import com.qualcomm.robotcore.hardware.AnalogInput
import com.qualcomm.robotcore.hardware.CRServo
import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.DigitalChannel
import com.qualcomm.robotcore.hardware.HardwareDevice
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.IMU
import com.qualcomm.robotcore.hardware.PwmControl
import com.qualcomm.robotcore.hardware.Servo
import com.qualcomm.robotcore.hardware.VoltageSensor
import com.qualcomm.robotcore.util.RobotLog
import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import kotlin.math.abs
import kotlin.math.sign

/**
 * Typed access to devices declared in a [RobotConfig], for one session.
 * `hardware(RobotMap.liftLeft)` returns the SDK type for that device's role.
 */
class Hardware internal constructor(
    /** The SDK hardware map, for libraries that find devices by name, such as Pedro's `Constants.create`. */
    val map: HardwareMap,
) {
    @JvmName("motor") operator fun invoke(device: Device<Role.Motor>): DcMotorEx = cached(device, ::CachedMotor)
    @JvmName("servo") operator fun invoke(device: Device<Role.Servo>): Servo = cached(device, ::CachedServo)
    @JvmName("crServo") operator fun invoke(device: Device<Role.CrServo>): CRServo = cached(device, ::CachedCrServo)
    @JvmName("digital") operator fun invoke(device: Device<Role.Digital>): DigitalChannel = lookup(device)
    @JvmName("analog") operator fun invoke(device: Device<Role.Analog>): AnalogInput = lookup(device)
    @JvmName("imu") operator fun invoke(device: Device<Role.Imu>): IMU = lookup(device)
    @JvmName("pinpoint") operator fun invoke(device: Device<Role.Pinpoint>): GoBildaPinpointDriver = lookup(device)
    @JvmName("otos") operator fun invoke(device: Device<Role.Otos>): SparkFunOTOS = lookup(device)
    @JvmName("color") operator fun invoke(device: Device<Role.ColorSensorV3>): RevColorSensorV3 = lookup(device)
    @JvmName("distance") operator fun invoke(device: Device<Role.Distance2m>): Rev2mDistanceSensor = lookup(device)
    @JvmName("limelight") operator fun invoke(device: Device<Role.Limelight>): Limelight3A = lookup(device)
    @JvmName("webcam") operator fun invoke(device: Device<Role.Webcam>): WebcamName = lookup(device)

    /** The main battery, which every configuration has without declaring it. */
    val battery: VoltageSensor get() = map.voltageSensor.iterator().next()

    @Suppress("UNCHECKED_CAST")
    private fun <T> lookup(device: Device<*>): T = map.get(sdkType(device.role), device.name) as T

    private val cachedDevices = HashMap<Device<*>, Any>()

    /** The SDK device, or with a cache tolerance, one write-caching wrapper for the whole session. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(device: Device<*>, wrap: (T, WriteCache) -> T): T =
        if (device.cacheTolerance == 0.0) lookup(device)
        else cachedDevices.getOrPut(device) { wrap(lookup(device), WriteCache(device.cacheTolerance)) } as T

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
    Role.Limelight::class.java -> Limelight3A::class.java
    Role.Webcam::class.java -> WebcamName::class.java
    else -> error("No SDK type for role ${role.simpleName}")
}

/**
 * Decides which writes reach the hub. A value within [tolerance] of the last value sent is skipped,
 * except an exact 0 or a change of sign. [forget] makes the next write go through.
 */
private class WriteCache(private val tolerance: Double) {
    private var last = Double.NaN

    fun admits(value: Double): Boolean {
        // sign(0.0) is 0, so a write of exactly 0 after a nonzero one is a change of sign.
        val send = last.isNaN() || sign(value) != sign(last) || abs(value - last) > tolerance
        if (send) last = value
        return send
    }

    fun forget() {
        last = Double.NaN
    }
}

// The wrappers forget the last value whenever a call changes what a value means or does at the
// hub, so the next write is sent. Writes made without them, such as through `Hardware.map`, are not seen.

private class CachedMotor(private val motor: DcMotorEx, private val cache: WriteCache) : DcMotorEx by motor {
    override fun setPower(power: Double) { if (cache.admits(power)) motor.power = power }
    override fun setDirection(direction: DcMotorSimple.Direction) { cache.forget(); motor.direction = direction }
    override fun setMode(mode: DcMotor.RunMode) { cache.forget(); motor.mode = mode }
    override fun setMotorEnable() { cache.forget(); motor.setMotorEnable() }
    override fun setMotorDisable() { cache.forget(); motor.setMotorDisable() }
    override fun setPowerFloat() { cache.forget(); motor.setPowerFloat() }
    override fun setVelocity(angularRate: Double) { cache.forget(); motor.velocity = angularRate }
    override fun setVelocity(angularRate: Double, unit: AngleUnit) { cache.forget(); motor.setVelocity(angularRate, unit) }
}

/** REV hub servos are `ServoImplEx`, so the wrapper keeps their [PwmControl]. */
private class CachedServo(private val servo: Servo, private val cache: WriteCache) :
    Servo by servo, PwmControl by servo as PwmControl {
    private val pwm = servo as PwmControl
    override fun setPosition(position: Double) { if (cache.admits(position)) servo.position = position }
    override fun setDirection(direction: Servo.Direction) { cache.forget(); servo.direction = direction }
    override fun scaleRange(min: Double, max: Double) { cache.forget(); servo.scaleRange(min, max) }
    override fun setPwmRange(range: PwmControl.PwmRange) { cache.forget(); pwm.pwmRange = range }
    override fun setPwmEnable() { cache.forget(); pwm.setPwmEnable() }
    override fun setPwmDisable() { cache.forget(); pwm.setPwmDisable() }
    override fun setPulseWidth(pulseWidth: Double) { cache.forget(); pwm.pulseWidth = pulseWidth }
}

private class CachedCrServo(private val servo: CRServo, private val cache: WriteCache) :
    CRServo by servo, PwmControl by servo as PwmControl {
    private val pwm = servo as PwmControl
    override fun setPower(power: Double) { if (cache.admits(power)) servo.power = power }
    override fun setDirection(direction: DcMotorSimple.Direction) { cache.forget(); servo.direction = direction }
    override fun setPwmRange(range: PwmControl.PwmRange) { cache.forget(); pwm.pwmRange = range }
    override fun setPwmEnable() { cache.forget(); pwm.setPwmEnable() }
    override fun setPwmDisable() { cache.forget(); pwm.setPwmDisable() }
    override fun setPulseWidth(pulseWidth: Double) { cache.forget(); pwm.pulseWidth = pulseWidth }
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
        if (isActive(active, name, resourceId)) return
        manager.setActiveConfig(false, RobotConfigFile(name, resourceId))
        RobotLog.ii("Gum", "Activated robot configuration %s (was %s)", name, active.name)
    }

    /**
     * The SDK saves the active configuration's resource ID and loads that resource, and a rebuild
     * can renumber resources, so a saved entry with the right name can still point at another file.
     */
    internal fun isActive(active: RobotConfigFile, name: String, resourceId: Int) =
        active.location == RobotConfigFile.FileLocation.RESOURCE && active.name == name && active.resourceId == resourceId
}
