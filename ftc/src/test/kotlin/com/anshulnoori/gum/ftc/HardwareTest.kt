package com.anshulnoori.gum.ftc

import com.anshulnoori.gum.hardware.GoBilda5202Series
import com.anshulnoori.gum.hardware.RobotConfig
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.PwmControl
import com.qualcomm.robotcore.hardware.Servo
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class HardwareTest {
    private object Bot : RobotConfig("bot") {
        private val hub = controlHub()
        val lift by hub.motor(0, GoBilda5202Series, cacheTolerance = 0.05)
        val drive by hub.motor(1)
        val claw by hub.servo(0, cacheTolerance = 0.02)
    }

    /** Every call that reaches the fake SDK devices, as "device.method(args)". */
    private val sent = mutableListOf<String>()

    private inline fun <reified T> fake(name: String, vararg also: Class<*>): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java, *also)) { _, method, args ->
            if (method.name.startsWith("set")) sent += "$name.${method.name}(${args?.joinToString().orEmpty()})"
            null
        } as T

    private val devices = mapOf<String, Any>(
        "lift" to fake<DcMotorEx>("lift"),
        "drive" to fake<DcMotorEx>("drive"),
        "claw" to fake<Servo>("claw", PwmControl::class.java),
    )

    // HardwareMap.get loads the SDK's native library, which JVM tests lack.
    private val hardware = Hardware(object : HardwareMap(null, null) {
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(type: Class<out T>, name: String): T = type.cast(devices.getValue(name)) as T

        override fun <T> tryGet(type: Class<out T>, name: String): T? = devices[name]?.let { type.cast(it) }
    })

    @Test
    fun `a motor skips powers within its tolerance of the last power sent, but not 0 or a change of sign`() {
        val lift = hardware(Bot.lift)
        for (power in listOf(0.5, 0.52, 0.54, 0.56, 0.02, 0.0, 0.0, 0.01, -0.01, -0.03)) lift.power = power
        assertEquals(
            // 0.56 is 0.06 from 0.5, the last power sent, though only 0.02 from the 0.54 asked for.
            listOf(0.5, 0.56, 0.02, 0.0, 0.01, -0.01).map { "lift.setPower($it)" },
            sent,
        )
        sent.clear()
        assertSame(lift, hardware(Bot.lift), "one cache per device for the session")
        lift.direction = DcMotorSimple.Direction.REVERSE
        lift.power = -0.01
        assertEquals(listOf("lift.setDirection(REVERSE)", "lift.setPower(-0.01)"), sent, "a new direction resends")
    }

    @Test
    fun `devices without a tolerance get the SDK device and send every write`() {
        val drive = hardware(Bot.drive)
        assertSame(devices["drive"], drive)
        drive.power = 0.5
        drive.power = 0.5
        assertEquals(listOf("drive.setPower(0.5)", "drive.setPower(0.5)"), sent)
    }

    @Test
    fun `a servo skips positions within its tolerance and keeps PWM control`() {
        val claw = hardware(Bot.claw)
        for (position in listOf(0.3, 0.31, 0.01, 0.0, 0.015)) claw.position = position
        (claw as PwmControl).setPwmDisable()
        claw.position = 0.015
        assertEquals(
            listOf("claw.setPosition(0.3)", "claw.setPosition(0.01)", "claw.setPosition(0.0)", "claw.setPosition(0.015)",
                "claw.setPwmDisable()", "claw.setPosition(0.015)"),
            sent,
        )
    }

    @Test
    fun `INIT fails when the declaration changed since the installed app's configuration`() {
        val installed = RobotConfigActivation.installedWiring
        try {
            RobotConfigActivation.installedWiring = "an older wiring"
            val error = assertFailsWith<IllegalStateException> { hardware.requireDevices(Bot) }
            assertEquals(
                "Bot changed since the app was installed. Sloth reloads it, but the robot configuration \"bot\" " +
                    "comes from the installed app. Install the app again.",
                error.message,
            )
            RobotConfigActivation.installedWiring = Bot.wiringHash()
            hardware.requireDevices(Bot)
        } finally {
            RobotConfigActivation.installedWiring = installed
        }
    }
}
