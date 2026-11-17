package com.anshulnoori.gum.hardware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RobotConfigTest {
    private object Bot : RobotConfig("practice_bot") {
        private val expansion = expansionHub(address = 3)
        private val control = controlHub()
        val frontLeft by control.motor(2, GoBilda5202Series)
        val claw by control.servo(5, cacheTolerance = 0.01)
        val imu by control.i2c(0, ControlHubImuBhi260ap)
        val pinpoint by control.i2c(1, GoBildaPinpoint)
        val color by control.i2c(1, RevColorSensorV3)
        val lift by expansion.motor(0)
        val spinner by expansion.crServo(0)
        val limit by expansion.digital(7)
    }

    @Test
    fun `writes the SDK configuration with hubs in declaration order and I2C indexes per bus`() {
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
                </LynxUsbDevice>
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
        assertEquals(listOf("frontLeft", "claw", "imu", "pinpoint", "color", "lift", "spinner", "limit"), Bot.devices.map { it.name })
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
    fun `rejects hub layouts the SDK cannot load`() {
        assertEquals(
            "bot needs a controlHub(); hubs connected only over USB are not supported",
            assertFailsWith<IllegalArgumentException> {
                object : RobotConfig("bot") { init { expansionHub() } }.toXml()
            }.message,
        )
        assertEquals(
            "Expansion Hub address 173 must be 1 to 254 and not 173",
            assertFailsWith<IllegalArgumentException> {
                object : RobotConfig("bot") { init { expansionHub(173) } }
            }.message,
        )
        assertFailsWith<IllegalArgumentException> { object : RobotConfig("Apex Bot") {} }
    }
}
