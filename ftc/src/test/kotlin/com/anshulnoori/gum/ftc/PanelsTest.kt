package com.anshulnoori.gum.ftc

import com.bylazar.telemetry.PanelsTelemetry
import org.firstinspires.ftc.robotcore.external.Func
import org.firstinspires.ftc.robotcore.external.Telemetry
import java.lang.reflect.Proxy
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PanelsTest {
    private val sdkCalls = mutableListOf<String>()

    private fun mirror(item: Telemetry.Item? = null): PanelsMirroredTelemetry {
        val sdk = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Telemetry::class.java)) { _, m, a ->
            sdkCalls += m.name + (a?.size ?: 0)
            when (m.returnType) { Telemetry.Item::class.java -> item; Boolean::class.javaPrimitiveType -> true; else -> null }
        } as Telemetry
        return PanelsMirroredTelemetry(sdk)
    }

    @Test
    fun `while live, telemetry reaches the SDK unchanged and Panels as text, cleared after each update`() {
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.ROOT)
        try {
            sendsBoth()
        } finally {
            Locale.setDefault(locale)
        }
    }

    private fun sendsBoth() {
        val item = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Telemetry.Item::class.java)) { _, _, _ -> null } as Telemetry.Item
        val t = mirror(item)
        t.live = true
        assertSame(item, t.addData("Battery (V)", "%.2f", 12.345))
        t.addData("Phase", "INIT")
        t.addData("Lazy", Func { 7 })
        t.addLine("hello")
        assertEquals(listOf("Battery (V): 12.35", "Phase: INIT", "Lazy: 7", "hello"), PanelsTelemetry.telemetry.lines.toList(), "a Func shows its value")
        t.speak("x")
        assertEquals(listOf("addData3", "addData3", "addData2", "addLine1", "speak1"), sdkCalls)
        sendToPanels()
        assertEquals(emptyList(), PanelsTelemetry.telemetry.lines.toList(), "cleared after each update")
    }

    @Test
    fun `while not live, nothing is formatted or evaluated for Panels and the SDK still gets every call`() {
        val t = mirror()
        var evaluated = 0
        t.addData("Battery (V)", "%.2f", 12.345)
        t.addData("Lazy", Func { evaluated++ })
        t.addLine("hello")
        assertEquals(0, evaluated, "the SDK evaluates a Func when it transmits, not when it is added")
        assertEquals(emptyList(), PanelsTelemetry.telemetry.lines.toList())
        assertEquals(listOf("addData3", "addData2", "addLine1"), sdkCalls)
    }
}
