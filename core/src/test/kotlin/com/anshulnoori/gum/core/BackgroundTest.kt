package com.anshulnoori.gum.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BackgroundTest {
    @Test
    fun `reads run on another thread until STOP and the loop sees the latest`() {
        val loopThread = Thread.currentThread()
        val readThreads = mutableSetOf<Thread>()
        val reads = AtomicInteger()
        val firstRead = CountDownLatch(3)
        lateinit var latest: Latest<Int>
        val session = teleop<Unit, Unit>("Vision", dependencies = { Unit }) {
            latest = background(periodMillis = 1) {
                synchronized(readThreads) { readThreads += Thread.currentThread() }
                firstRead.countDown()
                reads.incrementAndGet()
            }
        }.initialize(Unit)
        assertTrue(firstRead.await(2, TimeUnit.SECONDS), "reads start in INIT")
        assertTrue(latest.value!! >= 3 && latest.ageSeconds < 1.0)
        session.stop()

        val afterStop = reads.get()
        Thread.sleep(30)
        assertEquals(afterStop, reads.get(), "STOP ends reading")
        assertNotEquals(loopThread, readThreads.single())
    }

    @Test
    fun `a failed read stops the OpMode when the loop reads it`() {
        val failed = CountDownLatch(1)
        lateinit var latest: Latest<Int>
        val session = teleop<Unit, Unit>("Sensor", dependencies = { Unit }) {
            latest = background<Int> { failed.countDown(); error("I2C timeout") }
            loop { latest.value }
        }.initialize(Unit)
        session.start()
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        Thread.sleep(20)
        val error = assertFailsWith<IllegalStateException> { session.tick() }
        assertEquals("I2C timeout", error.cause?.message)
        assertEquals(SessionState.STOPPED, session.state)
    }
}
