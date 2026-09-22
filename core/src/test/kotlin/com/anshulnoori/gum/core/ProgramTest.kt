package com.anshulnoori.gum.core

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ProgramTest {
    @Test
    fun `descriptor defers graph creation until INIT and creates a fresh graph each time`() {
        val events = mutableListOf<String>()
        val graphs = mutableListOf<Any>()
        val program = teleop(
            name = "Driver",
            dependencies = {
                events += "graph"
                Any()
            },
        ) { graph ->
            events += "setup"
            graphs += graph
        }

        assertEquals(emptyList(), events)
        val first = program.initialize()
        assertEquals(SessionState.INIT, first.state)
        assertEquals(listOf("graph", "setup"), events)
        first.stop()

        val second = program.initialize()
        assertEquals(SessionState.INIT, second.state)
        assertEquals(listOf("graph", "setup", "graph", "setup"), events)
        assertNotSame(graphs[0], graphs[1])
        second.stop()
    }

    @Test
    fun `host gates active callbacks until START and STOP prevents further callbacks`() {
        val events = mutableListOf<String>()
        val session = teleop("Driver", dependencies = { Unit }) {
            onInitTick { events += "init" }
            onStart { events += "start" }
            onTick { events += "active" }
            onStop { events += "stop" }
        }.initialize()

        session.tick()
        assertEquals(listOf("init"), events)
        session.start()
        session.start()
        assertEquals(SessionState.ACTIVE, session.state)
        session.tick()
        session.tick()
        session.stop()
        session.stop()
        session.start()
        session.tick()

        assertEquals(SessionState.STOPPED, session.state)
        assertEquals(listOf("init", "start", "active", "active", "stop"), events)
    }

    @Test
    fun `STOP attempts every cleanup in reverse order and preserves failures`() {
        val events = mutableListOf<String>()
        val firstFailure = IllegalStateException("second resource")
        val secondFailure = IllegalArgumentException("first resource")
        val session = teleop("Driver", dependencies = { Unit }) {
            onStop { events += "first"; throw secondFailure }
            onStop { events += "second"; throw firstFailure }
            onStop { events += "third" }
        }.initialize()

        val error = assertFailsWith<IllegalStateException> { session.stop() }
        assertSame(firstFailure, error)
        assertEquals(listOf(secondFailure), error.suppressed.toList())
        assertEquals(listOf("third", "second", "first"), events)
        assertEquals(SessionState.STOPPED, session.state)
        session.stop()
        assertEquals(3, events.size)
    }

    @Test
    fun `setup failure closes acquired resources and preserves the original error`() {
        val events = mutableListOf<String>()
        val original = IllegalArgumentException("setup")
        val cleanup = IllegalStateException("cleanup")
        val program = teleop("Driver", dependencies = { Unit }) {
            onStop { events += "release" }
            onStop { throw cleanup }
            throw original
        }

        val error = assertFailsWith<IllegalArgumentException> { program.initialize() }
        assertSame(original, error)
        assertEquals(listOf(cleanup), error.suppressed.toList())
        assertEquals(listOf("release"), events)
    }

    @Test
    fun `dependency factory can register cleanup before construction fails`() {
        val events = mutableListOf<String>()
        val original = IllegalStateException("missing hardware")
        val program = teleop<Unit>("Driver", dependencies = {
            onStop { events += "release acquired resource" }
            throw original
        }) {
            events += "setup must not run"
        }

        assertSame(original, assertFailsWith<IllegalStateException> { program.initialize() })
        assertEquals(listOf("release acquired resource"), events)
    }

    @Test
    fun `failure in each update phase stops the session before another callback`() {
        for (phase in listOf("init", "start", "active")) {
            val events = mutableListOf<String>()
            val original = IllegalArgumentException(phase)
            val fail: () -> Unit = { throw original }
            val session = teleop("Driver", dependencies = { Unit }) {
                onStop { events += "cleanup" }
                when (phase) {
                    "init" -> { onInitTick(fail); onInitTick { events += "unexpected" } }
                    "start" -> { onStart(fail); onStart { events += "unexpected" } }
                    "active" -> { onTick(fail); onTick { events += "unexpected" } }
                }
            }.initialize()

            if (phase == "active") session.start()
            val error = assertFailsWith<IllegalArgumentException>(phase) {
                if (phase == "start") session.start() else session.tick()
            }
            assertSame(original, error)
            assertEquals(SessionState.STOPPED, session.state, phase)
            session.tick()
            session.stop()
            assertEquals(listOf("cleanup"), events, phase)
        }
    }

    @Test
    fun `STOP inside a callback prevents later callbacks in the same tick`() {
        val events = mutableListOf<String>()
        lateinit var session: OpModeSession
        session = teleop("Driver", dependencies = { Unit }) {
            onTick { events += "first"; session.stop() }
            onTick { events += "unexpected" }
            onStop { events += "stop" }
        }.initialize()
        session.start()
        session.tick()
        assertEquals(listOf("first", "stop"), events)
    }

    @Test
    fun `callback registration closes when setup returns including after STOP`() {
        lateinit var scope: ProgramScope
        val session = teleop("Driver", dependencies = { Unit }) { scope = this }.initialize()
        assertFailsWith<IllegalStateException> { scope.onTick {} }
        session.stop()
        assertFailsWith<IllegalStateException> { scope.onStop {} }
    }

    @Test
    fun `foreign threads cannot read or advance a session`() {
        val session = teleop("Driver", dependencies = { Unit }) {}.initialize()
        val worker = Executors.newSingleThreadExecutor()
        try {
            for (action in listOf<() -> Unit>(
                { session.start() }, { session.tick() }, { session.stop() }, { session.state },
            )) {
                val failure = worker.submit<Throwable?> {
                    runCatching(action).exceptionOrNull()
                }.get(5, TimeUnit.SECONDS)
                assertIs<IllegalStateException>(failure)
            }
            assertEquals(SessionState.INIT, session.state)
        } finally {
            worker.shutdownNow()
            session.stop()
        }
    }

    @Test
    fun `recursive tick fails and cleans up instead of nesting control updates`() {
        val events = mutableListOf<String>()
        lateinit var session: OpModeSession
        session = teleop("Driver", dependencies = { Unit }) {
            onTick { session.tick() }
            onStop { events += "stop" }
        }.initialize()
        session.start()
        assertFailsWith<IllegalStateException> { session.tick() }
        assertEquals(SessionState.STOPPED, session.state)
        assertEquals(listOf("stop"), events)
    }
}
