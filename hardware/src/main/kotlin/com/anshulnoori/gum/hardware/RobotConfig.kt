package com.anshulnoori.gum.hardware

import java.security.MessageDigest
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

    private val hubs = mutableListOf<Module>()
    private val webcamSerialNumbers = mutableSetOf<String>()
    private var hasLimelight = false
    private val names = mutableSetOf<String>()
    private val registered = mutableListOf<Device<*>>()

    /** Every declared device, in declaration order. */
    val devices: List<Device<*>> get() = registered.toList()

    protected fun controlHub(): Hub {
        require(hubs.none { it.address == CONTROL_HUB_ADDRESS }) { "$name declares more than one Control Hub" }
        return Hub(this, "Control Hub", CONTROL_HUB_ADDRESS)
    }

    /** An Expansion Hub connected to the Control Hub over RS485, at its configured module [address]. */
    protected fun expansionHub(address: Int = 2): Hub = Hub(this, "Expansion Hub $address", rs485Address(address))

    /**
     * A REV Servo Hub connected to the Control Hub over RS485, at its configured module [address].
     * It has servo ports 0 to 5.
     */
    protected fun servoHub(address: Int): ServoHub = ServoHub(this, "Servo Hub $address", rs485Address(address))

    private fun rs485Address(address: Int): Int {
        require(address in 1..MAX_ADDRESS && address != CONTROL_HUB_ADDRESS) {
            "Hub address $address must be 1 to $MAX_ADDRESS and not $CONTROL_HUB_ADDRESS"
        }
        require(hubs.none { it.address == address }) { "$name declares two hubs at address $address" }
        return address
    }

    internal fun add(module: Module) {
        hubs += module
    }

    /**
     * A Limelight 3A plugged into a Control Hub USB port. [usbId] is the USB ID in the Limelight's
     * settings page; the Limelight's address is 172.29.<usbId>.1. Limelight supports one per Control Hub.
     */
    protected fun limelight(usbId: Int = 0): DeviceSlot<Role.Limelight> {
        require(usbId in 0..MAX_USB_ID) { "Limelight USB ID $usbId must be 0 to $MAX_USB_ID" }
        require(!hasLimelight) { "$name declares more than one Limelight" }
        hasLimelight = true
        val address = "172.29.$usbId.1"
        // The SDK reads the address from ipAddress; the serial number only names the device in logs.
        val serialNumber = "EthernetOverUsb:eth0:$address"
        return DeviceSlot { deviceName ->
            Device(deviceName, null, "EthernetDevice", """ serialNumber="$serialNumber" port="0" ipAddress="$address"""", Role.Limelight::class.java)
        }
    }

    /**
     * A USB webcam, identified by the serial number the Driver Station shows for it in Configure Robot.
     */
    protected fun webcam(serialNumber: String): DeviceSlot<Role.Webcam> {
        require(serialNumber.isNotBlank()) { "A webcam needs the serial number shown in Configure Robot" }
        require(webcamSerialNumbers.add(serialNumber)) { "$name declares two webcams with serial number $serialNumber" }
        return DeviceSlot { deviceName ->
            Device(deviceName, null, "Webcam", """ serialNumber="$serialNumber"""", Role.Webcam::class.java)
        }
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
            xml.append("        <${hub.xmlTag} name=\"${hub.name}\" port=\"${hub.address}\">\n")
            for (device in registered.filter { it.hub === hub }) {
                xml.append("            ").append(device.xml()).append('\n')
            }
            xml.append("        </${hub.xmlTag}>\n")
        }
        xml.append("    </LynxUsbDevice>\n")
        // USB devices sit directly under <Robot>, as the SDK writes them.
        for (device in registered.filter { it.hub == null }) {
            xml.append("    ").append(device.xml()).append('\n')
        }
        xml.append("</Robot>\n")
        return xml.toString()
    }

    /**
     * A hash of [toXml]. The generated activation hook records the installed configuration's hash,
     * so INIT can tell when this declaration has changed since the app was installed.
     */
    fun wiringHash(): String =
        MessageDigest.getInstance("SHA-256").digest(toXml().toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val NAME = Regex("[a-z][a-z0-9_]*")
        const val CONTROL_HUB_ADDRESS = 173
        const val MAX_ADDRESS = 254
        const val MAX_USB_ID = 255
    }
}

/** A module on the Control Hub's RS485 bus. Ports are checked against its hardware when a device is declared. */
abstract class Module internal constructor(
    private val config: RobotConfig,
    internal val name: String,
    internal val address: Int,
    internal val xmlTag: String,
) {
    private val used = mutableSetOf<Pair<PortKind, Int>>()

    init {
        config.add(this)
    }

    internal fun <R> slot(kind: PortKind, port: Int, xmlTag: String, role: Class<R>, cacheTolerance: Double = 0.0): DeviceSlot<R> {
        require(port in 0 until kind.count) { "$name has ${kind.label} ports 0 to ${kind.count - 1}, not $port" }
        require(cacheTolerance >= 0.0 && cacheTolerance.isFinite()) {
            "$name ${kind.label} port $port has cache tolerance $cacheTolerance; it must be 0 or more"
        }
        require(used.add(kind to port)) { "$name ${kind.label} port $port is declared twice" }
        return DeviceSlot { deviceName -> Device(deviceName, this, xmlTag, """ port="$port"""", role, cacheTolerance) }
    }

    internal fun register(device: Device<*>) = config.register(device)

    internal enum class PortKind(val label: String, val count: Int) {
        MOTOR("motor", 4), SERVO("servo", 6), DIGITAL("digital", 8), ANALOG("analog", 4),
    }
}

/** One REV Control Hub or Expansion Hub. */
class Hub internal constructor(config: RobotConfig, name: String, address: Int) :
    Module(config, name, address, "LynxModule") {
    private val i2cCount = IntArray(I2C_BUSES)
    private val i2cAddresses = Array(I2C_BUSES) { mutableMapOf<Int, String>() }

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

    /**
     * An I2C device on [bus]. Several devices can share a bus, but not two at the same I2C address,
     * such as two REV color sensors; put those on different buses.
     */
    fun <R> i2c(bus: Int, type: I2cType<R>): DeviceSlot<R> {
        require(bus in 0 until I2C_BUSES) { "$name has I2C buses 0 to ${I2C_BUSES - 1}, not $bus" }
        return DeviceSlot { deviceName ->
            type.address?.let { address ->
                val other = i2cAddresses[bus].put(address, deviceName)
                require(other == null) {
                    "$name I2C bus $bus has $other and $deviceName at the same address 0x${address.toString(16)}; " +
                        "put one on another bus"
                }
            }
            Device(deviceName, this, type.xmlTag, """ port="${i2cCount[bus]++}" bus="$bus"""", type.role)
        }
    }

    private companion object {
        const val I2C_BUSES = 4
    }
}

/** A REV Servo Hub, with servo ports 0 to 5. */
class ServoHub internal constructor(config: RobotConfig, name: String, address: Int) :
    Module(config, name, address, "ServoHub") {
    /** A servo. With a [cacheTolerance] above 0, a position within it of the last position sent is skipped. */
    fun servo(port: Int, cacheTolerance: Double = 0.0): DeviceSlot<Role.Servo> =
        slot(PortKind.SERVO, port, "Servo", Role.Servo::class.java, cacheTolerance)

    /** A continuous-rotation servo. With a [cacheTolerance] above 0, a power within it of the last power sent is skipped. */
    fun crServo(port: Int, cacheTolerance: Double = 0.0): DeviceSlot<Role.CrServo> =
        slot(PortKind.SERVO, port, "ContinuousRotationServo", Role.CrServo::class.java, cacheTolerance)
}

/** Binds a declared port to the property it is delegated to, which supplies the device name. */
class DeviceSlot<R> internal constructor(private val create: (String) -> Device<R>) {
    operator fun provideDelegate(thisRef: RobotConfig, property: KProperty<*>): ReadOnlyProperty<RobotConfig, Device<R>> {
        val device = create(property.name)
        val hub = device.hub
        if (hub != null) hub.register(device) else thisRef.register(device)
        return ReadOnlyProperty { _, _ -> device }
    }
}

/** A declared device. [R] is its role, which decides the SDK type code receives for it. */
class Device<R> internal constructor(
    val name: String,
    /** The hub the device is wired to, or null for a USB device such as a webcam. */
    internal val hub: Module?,
    internal val xmlTag: String,
    /** The tag's attributes after the name, such as ` port="0" bus="1"`. */
    private val attributes: String,
    val role: Class<R>,
    /**
     * Write caching for a motor or CR servo power, or a servo position: a write within this distance
     * of the last value sent is skipped. Exact 0 and a change of sign are always sent, so a mechanism
     * always stops and reverses. 0, the default, sends every write. Other devices have 0.
     */
    val cacheTolerance: Double = 0.0,
) {
    internal fun xml() = "<$xmlTag name=\"$name\"$attributes />"

    override fun toString() = name
}
