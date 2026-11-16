package com.anshulnoori.gum.core

import com.pedropathing.ivy.Command
import com.pedropathing.ivy.Scheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class IvySessionTest {
    private fun recording(events: MutableList<String>, name: String, requirement: Any) =
        Command.build()
            .requiring(requirement)
            .setStart { events += "$name start" }
            .setExecute { events += "$name execute" }
            .setEnd { events += "$name end $it" }

    @Test
    fun `commands advance on active ticks before program loops`() {
        val events = mutableListOf<String>()
        val intake = Any()
        val session = teleop<Unit, Unit>("Driver", dependencies = {
            loop { events += "program loop" }
        }) {
            onStart { recording(events, "collect", intake).schedule() }
        }.initialize(Unit)

        session.tick()
        session.start()
        session.tick()
        session.tick()
        session.stop()

        assertEquals(
            listOf(
                "collect start",
                "collect execute", "program loop",
                "collect execute", "program loop",
            ),
            events,
        )
    }

    @Test
    fun `STOP drops commands so the next session starts with free requirements`() {
        val events = mutableListOf<String>()
        val intake = Any()
        val leftover = recording(events, "leftover", intake)
        leftover.schedule()

        val first = teleop<Unit, Unit>("Driver", dependencies = { Unit }) {
            onStart { recording(events, "first", intake).schedule() }
        }.initialize(Unit)
        assertFalse(leftover.isScheduled, "INIT clears commands scheduled outside a session")
        first.start()
        first.stop()

        val second = teleop<Unit, Unit>("Driver", dependencies = { Unit }) {
            onStart { recording(events, "second", intake).schedule() }
        }.initialize(Unit)
        second.start()
        second.tick()
        second.stop()

        // Dropped commands get no end callback, and nothing holds the requirement for Ivy to interrupt.
        assertEquals(listOf("leftover start", "first start", "second start", "second execute"), events)
        assertFalse(Scheduler.isScheduled(leftover))
    }
}
