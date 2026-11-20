package com.anshulnoori.gum.hardware

/**
 * What code receives for a device. The `ftc` module maps each role to its SDK type, for example
 * [Motor] to `DcMotorEx`. Roles are markers; they have no instances.
 */
object Role {
    class Motor private constructor()
    class Servo private constructor()
    class CrServo private constructor()
    class Digital private constructor()
    class Analog private constructor()
    class Imu private constructor()
    class Pinpoint private constructor()
    class Otos private constructor()
    class ColorSensorV3 private constructor()
    class Distance2m private constructor()
    class Limelight private constructor()
    class Webcam private constructor()
}

/** A motor model. [xmlTag] is the SDK configuration tag. */
class MotorType(val xmlTag: String)

/**
 * An I2C device model. [xmlTag] is the SDK configuration tag. [address] is its fixed 7-bit I2C
 * address, so a bus cannot hold two of them; null when the address can be changed.
 */
class I2cType<R>(val xmlTag: String, val role: Class<R>, val address: Int? = null)

// Tags below come from the FTC SDK 12.0 device annotations, and addresses from its drivers.

val UnspecifiedMotor = MotorType("Motor")

/** goBILDA 5202, 5203, and 5204 series; the SDK has one type for all three. */
val GoBilda5202Series = MotorType("goBILDA5202SeriesMotor")
val GoBilda5201Series = MotorType("goBILDA5201SeriesMotor")
val RevCoreHex = MotorType("RevRoboticsCoreHexMotor")
val RevHdHex20 = MotorType("RevRobotics20HDHexMotor")
val RevHdHex40 = MotorType("RevRobotics40HDHexMotor")
val RevUltraPlanetary = MotorType("RevRoboticsUltraplanetaryHDHexMotor")

/** The Control Hub's built-in IMU on newer hubs. Declare it on I2C bus 0. */
val ControlHubImuBhi260ap = I2cType("ControlHubImuBHI260AP", Role.Imu::class.java, 0x28)

/** The Control Hub's built-in IMU on older hubs. Declare it on I2C bus 0. */
val ControlHubImuBno055 = I2cType("LynxEmbeddedIMU", Role.Imu::class.java, 0x28)

val GoBildaPinpoint = I2cType("goBILDAPinpoint", Role.Pinpoint::class.java, 0x31)
val SparkFunOtos = I2cType("SparkFunOTOS", Role.Otos::class.java, 0x17)
val RevColorSensorV3 = I2cType("RevColorSensorV3", Role.ColorSensorV3::class.java, 0x52)
val Rev2mDistance = I2cType("REV_VL53L0X_RANGE_SENSOR", Role.Distance2m::class.java, 0x29)
