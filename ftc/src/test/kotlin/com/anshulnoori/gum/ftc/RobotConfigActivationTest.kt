package com.anshulnoori.gum.ftc

import com.qualcomm.ftccommon.configuration.RobotConfigFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobotConfigActivationTest {
    // The form the SDK saves the active configuration in, in the Robot Controller's preferences.
    private fun saved(json: String): RobotConfigFile = RobotConfigFile.fromString(null, json)

    @Test
    fun `keeps the saved configuration only when it is the generated resource`() {
        assertTrue(RobotConfigActivation.isActive(saved("""{"name":"apex","resourceId":2131886080,"location":"RESOURCE"}"""), "apex", 2131886080))
    }

    @Test
    fun `reactivates when a rebuild renumbered the generated resource`() {
        assertFalse(RobotConfigActivation.isActive(saved("""{"name":"apex","resourceId":2131886079,"location":"RESOURCE"}"""), "apex", 2131886080))
    }

    @Test
    fun `reactivates over a Driver Station file with the same name`() {
        assertFalse(RobotConfigActivation.isActive(saved("""{"name":"apex","resourceId":0,"location":"LOCAL_STORAGE"}"""), "apex", 2131886080))
    }
}
