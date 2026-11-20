package com.anshulnoori.gum.hardware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RobotConfigTest {
    private object Bot : RobotConfig("practice_bot") {
        private val expansion = expansionHub(address = 3)
        private val control = controlHub()
        private val servos = servoHub(address = 4)
        val frontLeft by control.motor(2, GoBilda5202Series)
        val claw by control.servo(5, cacheTolerance = 0.01)
        val imu by control.i2c(0, ControlHubImuBhi260ap)
        val pinpoint by control.i2c(1, GoBildaPinpoint)
        val color by control.i2c(1, RevColorSensorV3)
        val lift by expansion.motor(0)
        val spinner by expansion.crServo(0)
        val limit by expansion.digital(7)
        val hood by servos.servo(0)
        val camera by webcam("VendorProduct:vid=0x46d|pid=0x825|connection=")
        val limelight by limelight(usbId = 2)
    }

    @Test
    fun `writes the SDK configuration with hubs in declaration order, I2C indexes per bus, and USB devices under the robot`() {
        val expected = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <Robot type="FirstInspires-FTC">
                <LynxUsbDevice name="Control Hub Portal" serialNumber="(embedded)" parentModuleAddress="173">
                    <LynxModule name="Expansion Hub 3" port="3">
                        <Motor name="lift" port="0" />
                        <ContinuousRotationServo name="spinner" port="0" />
                        <DigitalDevice name="limit" port="7" />
                    </LynxModule>
                    <LynxModule name="Control Hub" port="173">
                        <goBILDA5202SeriesMotor name="frontLeft" port="2" />
                        <Servo name="claw" port="5" />
                        <ControlHubImuBHI260AP name="imu" port="0" bus="0" />
                        <goBILDAPinpoint name="pinpoint" port="0" bus="1" />
                        <RevColorSensorV3 name="color" port="1" bus="1" />
                    </LynxModule>
                    <ServoHub name="Servo Hub 4" port="4">
                        <Servo name="hood" port="0" />
                    </ServoHub>
                </LynxUsbDevice>
                <Webcam name="camera" serialNumber="VendorProduct:vid=0x46d|pid=0x825|connection=" />
                <EthernetDevice name="limelight" serialNumber="EthernetOverUsb:eth0:172.29.2.1" port="0" ipAddress="172.29.2.1" />
            </Robot>

        """.trimIndent()
        assertEquals(expected, Bot.toXml())
    }

    @Test
    fun `property names become device names and roles follow the declaration`() {
        assertEquals("frontLeft", Bot.frontLeft.name)
        assertSame(Role.Pinpoint::class.java, Bot.pinpoint.role)
        assertSame(Role.CrServo::class.java, Bot.spinner.role)
        assertEquals(0.01, Bot.claw.cacheTolerance)
        assertEquals(0.0, Bot.lift.cacheTolerance, "write caching is opt-in")
        assertSame(Role.Limelight::class.java, Bot.limelight.role)
        assertSame(Role.Webcam::class.java, Bot.camera.role)
        assertEquals(listOf("frontLeft", "claw", "imu", "pinpoint", "color", "lift", "spinner", "limit", "hood", "camera", "limelight"), Bot.devices.map { it.name })
    }

    @Test
    fun `servo and CR servo share the hub's servo ports`() {
        val error = assertFailsWith<IllegalArgumentException> {
            object : RobotConfig("bot") {
                private val hub = controlHub()
                val a by hub.servo(1)
                val b by hub.crServo(1)
            }
        }
        assertEquals("Control Hub servo port 1 is declared twice", error.message)
    }

    @Test
    fun `rejects ports the hub does not have and invalid cache tolerances`() {
        for ((declare, message) in listOf<Pair<(Hub) -> Any, String>>(
            { hub: Hub -> hub.motor(4) } to "Control Hub has motor ports 0 to 3, not 4",
            { hub: Hub -> hub.servo(-1) } to "Control Hub has servo ports 0 to 5, not -1",
            { hub: Hub -> hub.analog(4) } to "Control Hub has analog ports 0 to 3, not 4",
            { hub: Hub -> hub.i2c(4, SparkFunOtos) } to "Control Hub has I2C buses 0 to 3, not 4",
            { hub: Hub -> hub.motor(1, cacheTolerance = -0.01) } to "Control Hub motor port 1 has cache tolerance -0.01; it must be 0 or more",
            { hub: Hub -> hub.crServo(2, cacheTolerance = Double.NaN) } to "Control Hub servo port 2 has cache tolerance NaN; it must be 0 or more",
        )) {
            val error = assertFailsWith<IllegalArgumentException> {
                object : RobotConfig("bot") { init { declare(controlHub()) } }
            }
            assertEquals(message, error.message)
        }
    }

    @Test
    fun `rejects USB devices the SDK or the Limelight cannot tell apart`() {
        for ((declare, message) in listOf<Pair<() -> Any, String>>(
            { object : RobotConfig("bot") { init { webcam("A1"); webcam("A1") } } } to "bot declares two webcams with serial number A1",
            { object : RobotConfig("bot") { init { webcam(" ") } } } to "A webcam needs the serial number shown in Configure Robot",
            { object : RobotConfig("bot") { init { limelight(); limelight(usbId = 1) } } } to "bot declares more than one Limelight",
            { object : RobotConfig("bot") { init { limelight(usbId = 256) } } } to "Limelight USB ID 256 must be 0 to 255",
        )) {
            assertEquals(message, assertFailsWith<IllegalArgumentException> { declare() }.message)
        }
    }

    @Test
    fun `rejects hub layouts the SDK cannot load`() {
        assertEquals(
            "bot needs a controlHub(); hubs connected only over USB are not supported",
            assertFailsWith<IllegalArgumentException> {
                object : RobotConfig("bot") { init { expansionHub() } }.toXml()
            }.message,
        )
        assertEquals(
            "Hub address 173 must be 1 to 254 and not 173",
            assertFailsWith<IllegalArgumentException> {
                object : RobotConfig("bot") { init { expansionHub(173) } }
            }.message,
        )
        assertFailsWith<IllegalArgumentException> { object : RobotConfig("Apex Bot") {} }
        assertEquals(
            "bot declares two hubs at address 2",
            assertFailsWith<IllegalArgumentException> {
                object : RobotConfig("bot") { init { expansionHub(2); servoHub(2) } }
            }.message,
        )
    }

    @Test
    fun `rejects two fixed-address I2C devices on one bus, but not on different buses`() {
        val error = assertFailsWith<IllegalArgumentException> {
            object : RobotConfig("bot") {
                private val hub = controlHub()
                val left by hub.i2c(1, RevColorSensorV3)
                val right by hub.i2c(1, RevColorSensorV3)
            }.devices
        }
        assertEquals("Control Hub I2C bus 1 has left and right at the same address 0x52; put one on another bus", error.message)
        val split = object : RobotConfig("bot") {
            private val hub = controlHub()
            val left by hub.i2c(1, RevColorSensorV3)
            val right by hub.i2c(2, RevColorSensorV3)
            val distance by hub.i2c(1, Rev2mDistance)
        }
        assertEquals(listOf("left", "right", "distance"), split.devices.map { it.name })
    }

    @Test
    fun `the wiring hash changes with the generated configuration, and only with it`() {
        class Ports(port: Int) : RobotConfig("bot") {
            private val hub = controlHub()
            val lift by hub.motor(port)
        }
        assertEquals(Ports(0).wiringHash(), Ports(0).wiringHash())
        assertTrue(Ports(0).wiringHash() != Ports(1).wiringHash(), "moving a device to another port changes it")
    }
}
