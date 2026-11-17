package com.anshulnoori.gum.hardware

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * A robot's wiring, declared once in Kotlin. The Gum Gradle plugin turns it into the FTC robot
 * configuration XML, and code resolves its devices through the same declarations.
 *
 * Declare it as a Kotlin `object` and each device as a delegated property; the property name is
 * the device's configuration name:
 *
 * ```kotlin
 * object Apex : RobotConfig("apex") {
 *     private val control = controlHub()
 *     val frontLeft by control.motor(0, GoBilda5202Series)
 * }
 * ```
 *
 * This module runs on the build machine as well as the robot, so it must not reference the FTC SDK.
 */
abstract class RobotConfig(val name: String) {
    init {
        require(NAME.matches(name)) {
            "Configuration name \"$name\" must use lowercase letters, digits, and underscores, starting with a letter"
        }
    }

    private val hubs = mutableListOf<Hub>()
    private val names = mutableSetOf<String>()
    private val registered = mutableListOf<Device<*>>()

    /** Every declared device, in declaration order. */
    val devices: List<Device<*>> get() = registered.toList()

    protected fun controlHub(): Hub {
        require(hubs.none { it.address == CONTROL_HUB_ADDRESS }) { "$name declares more than one Control Hub" }
        return Hub(this, "Control Hub", CONTROL_HUB_ADDRESS).also { hubs += it }
    }

    /** An Expansion Hub connected to the Control Hub over RS485, at its configured module [address]. */
    protected fun expansionHub(address: Int = 2): Hub {
        require(address in 1..MAX_ADDRESS && address != CONTROL_HUB_ADDRESS) {
            "Expansion Hub address $address must be 1 to $MAX_ADDRESS and not $CONTROL_HUB_ADDRESS"
        }
        require(hubs.none { it.address == address }) { "$name declares two hubs at address $address" }
        return Hub(this, "Expansion Hub $address", address).also { hubs += it }
    }

    internal fun register(device: Device<*>) {
        require(names.add(device.name)) { "$name declares two devices named ${device.name}" }
        registered += device
    }

    /** The FTC robot configuration XML that the SDK loads from `res/xml/<name>.xml`. */
    fun toXml(): String {
        require(hubs.any { it.address == CONTROL_HUB_ADDRESS }) {
            "$name needs a controlHub(); hubs connected only over USB are not supported"
        }
        val xml = StringBuilder()
        xml.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n")
        xml.append("<Robot type=\"FirstInspires-FTC\">\n")
        xml.append("    <LynxUsbDevice name=\"Control Hub Portal\" serialNumber=\"(embedded)\" ")
            .append("parentModuleAddress=\"$CONTROL_HUB_ADDRESS\">\n")
        for (hub in hubs) {
            xml.append("        <LynxModule name=\"${hub.name}\" port=\"${hub.address}\">\n")
            for (device in registered.filter { it.hub === hub }) {
                xml.append("            <${device.xmlTag} name=\"${device.name}\" port=\"${device.port}\"")
                device.bus?.let { xml.append(" bus=\"$it\"") }
                xml.append(" />\n")
            }
            xml.append("        </LynxModule>\n")
        }
        xml.append("    </LynxUsbDevice>\n")
        xml.append("</Robot>\n")
        return xml.toString()
    }

    private companion object {
        val NAME = Regex("[a-z][a-z0-9_]*")
        const val CONTROL_HUB_ADDRESS = 173
        const val MAX_ADDRESS = 254
    }
}

/** One REV hub. Ports are checked against the hub's hardware when a device is declared. */
class Hub internal constructor(
    private val config: RobotConfig,
    internal val name: String,
    internal val address: Int,
) {
    private val used = mutableSetOf<Pair<PortKind, Int>>()
    private val i2cCount = IntArray(I2C_BUSES)

    /**
     * A motor. With a [cacheTolerance] above 0, a power within it of the last power sent is skipped;
     * see [Device.cacheTolerance].
     */
    fun motor(port: Int, type: MotorType = UnspecifiedMotor, cacheTolerance: Double = 0.0): DeviceSlot<Role.Motor> =
        slot(PortKind.MOTOR, port, type.xmlTag, Role.Motor::class.java, cacheTolerance)

    /** A servo. With a [cacheTolerance] above 0, a position within it of the last position sent is skipped. */
    fun servo(port: Int, cacheTolerance: Double = 0.0): DeviceSlot<Role.Servo> =
        slot(PortKind.SERVO, port, "Servo", Role.Servo::class.java, cacheTolerance)

    /** A continuous-rotation servo. With a [cacheTolerance] above 0, a power within it of the last power sent is skipped. */
    fun crServo(port: Int, cacheTolerance: Double = 0.0): DeviceSlot<Role.CrServo> =
        slot(PortKind.SERVO, port, "ContinuousRotationServo", Role.CrServo::class.java, cacheTolerance)

    fun digital(port: Int): DeviceSlot<Role.Digital> =
        slot(PortKind.DIGITAL, port, "DigitalDevice", Role.Digital::class.java)

    fun analog(port: Int): DeviceSlot<Role.Analog> =
        slot(PortKind.ANALOG, port, "AnalogInput", Role.Analog::class.java)

    /** An I2C device on [bus]. Several devices can share a bus. */
    fun <R> i2c(bus: Int, type: I2cType<R>): DeviceSlot<R> {
        require(bus in 0 until I2C_BUSES) { "$name has I2C buses 0 to ${I2C_BUSES - 1}, not $bus" }
        return DeviceSlot { deviceName -> Device(deviceName, this, i2cCount[bus]++, bus, type.xmlTag, type.role) }
    }

    private fun <R> slot(kind: PortKind, port: Int, xmlTag: String, role: Class<R>, cacheTolerance: Double = 0.0): DeviceSlot<R> {
        require(port in 0 until kind.count) { "$name has ${kind.label} ports 0 to ${kind.count - 1}, not $port" }
        require(cacheTolerance >= 0.0 && cacheTolerance.isFinite()) {
            "$name ${kind.label} port $port has cache tolerance $cacheTolerance; it must be 0 or more"
        }
        require(used.add(kind to port)) { "$name ${kind.label} port $port is declared twice" }
        return DeviceSlot { deviceName -> Device(deviceName, this, port, null, xmlTag, role, cacheTolerance) }
    }

    internal fun register(device: Device<*>) = config.register(device)

    private enum class PortKind(val label: String, val count: Int) {
        MOTOR("motor", 4), SERVO("servo", 6), DIGITAL("digital", 8), ANALOG("analog", 4),
    }

    private companion object {
        const val I2C_BUSES = 4
    }
}

/** Binds a declared port to the property it is delegated to, which supplies the device name. */
class DeviceSlot<R> internal constructor(private val create: (String) -> Device<R>) {
    operator fun provideDelegate(thisRef: RobotConfig, property: KProperty<*>): ReadOnlyProperty<RobotConfig, Device<R>> {
        val device = create(property.name)
        device.hub.register(device)
        return ReadOnlyProperty { _, _ -> device }
    }
}

/** A declared device. [R] is its role, which decides the SDK type code receives for it. */
class Device<R> internal constructor(
    val name: String,
    internal val hub: Hub,
    internal val port: Int,
    internal val bus: Int?,
    internal val xmlTag: String,
    val role: Class<R>,
    /**
     * Write caching for a motor or CR servo power, or a servo position: a write within this distance
     * of the last value sent is skipped. Exact 0 and a change of sign are always sent, so a mechanism
     * always stops and reverses. 0, the default, sends every write. Other devices have 0.
     */
    val cacheTolerance: Double = 0.0,
) {
    override fun toString() = name
}
