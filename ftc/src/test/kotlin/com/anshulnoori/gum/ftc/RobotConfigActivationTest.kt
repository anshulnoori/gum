package com.anshulnoori.gum.ftc

import com.qualcomm.ftccommon.configuration.RobotConfigFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobotConfigActivationTest {
    // The form the SDK saves the active configuration in, in the Robot Controller's preferences.
    private fun saved(json: String): RobotConfigFile = RobotConfigFile.fromString(null, json)

    private val generated = saved("""{"name":"apex","resourceId":2131886080,"location":"RESOURCE"}""")
    private val renumbered = saved("""{"name":"apex","resourceId":2131886079,"location":"RESOURCE"}""")
    private val teamChoice = saved("""{"name":"sjsjs","resourceId":0,"location":"LOCAL_STORAGE"}""")
    private val teamFileNamedApex = saved("""{"name":"apex","resourceId":0,"location":"LOCAL_STORAGE"}""")

    private fun activates(active: RobotConfigFile, wiringChanged: Boolean) =
        RobotConfigActivation.shouldActivate(active, "apex", 2131886080, wiringChanged)

    @Test
    fun `leaves the generated configuration alone while its resource ID matches`() {
        assertFalse(activates(generated, wiringChanged = true))
    }

    @Test
    fun `reactivates when a rebuild renumbered the generated resource`() {
        assertTrue(activates(renumbered, wiringChanged = false))
    }

    @Test
    fun `keeps a Driver Station choice until new wiring is installed`() {
        assertFalse(activates(teamChoice, wiringChanged = false))
        assertFalse(activates(teamFileNamedApex, wiringChanged = false))
        assertTrue(activates(teamChoice, wiringChanged = true))
        assertTrue(activates(teamFileNamedApex, wiringChanged = true))
    }
}
