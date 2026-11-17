package com.anshulnoori.gum.pedro.tuning

import com.anshulnoori.gum.pedro.PedroRobot
import com.pedropathing.tuning.autotune.TunerRegistrar
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Stands in for TeamCode's constants file. The parts are never built: listing procedures only
// stores them.
object FakeConstants {
    @JvmField
    val ROBOT = PedroRobot({ error("drivetrain") }, { error("localizer") }, { error("algorithm") })
}

class PedroTunerScannerTest {
    private val teamCode = object : ClassLoader() {}
    private fun names() = TunerRegistrar.getProcedures().map { it.name }

    @AfterTest
    fun clear() = TunerRegistrar.deregisterAll()

    @Test
    fun `lists the robot's tuners once, and a hot reload replaces them`() {
        PedroTunerScanner.scan(teamCode, String::class.java)
        assertEquals(emptyList(), names(), "a class without a PedroRobot lists nothing")

        PedroTunerScanner.scan(teamCode, FakeConstants::class.java)
        val tuners = listOf("Mecanum Tuner", "Pinpoint Tuner", "Foresight Tuner", "Tests")
        assertEquals(tuners, names())

        PedroTunerScanner.beforeUnload(object : ClassLoader() {})
        assertEquals(tuners, names(), "unloading another loader keeps the robot's tuners")

        PedroTunerScanner.beforeUnload(teamCode)
        val reloaded = object : ClassLoader() {}
        PedroTunerScanner.scan(reloaded, FakeConstants::class.java)
        assertEquals(tuners, names(), "a reload lists each tuner once")
        PedroTunerScanner.beforeUnload(reloaded)
        assertEquals(emptyList(), names())
    }
}
