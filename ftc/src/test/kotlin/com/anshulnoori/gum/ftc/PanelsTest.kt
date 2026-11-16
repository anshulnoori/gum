package com.anshulnoori.gum.ftc

import com.bylazar.telemetry.PanelsTelemetry
import org.firstinspires.ftc.robotcore.external.Func
import org.firstinspires.ftc.robotcore.external.Telemetry
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PanelsTest {
    @Test
    fun `telemetry reaches the SDK unchanged and Panels as text, cleared after each update`() {
        val sdkCalls = mutableListOf<String>()
        val item = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Telemetry.Item::class.java)) { _, _, _ -> null } as Telemetry.Item
        val sdk = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Telemetry::class.java)) { _, m, a ->
            sdkCalls += m.name + (a?.size ?: 0)
            when (m.returnType) { Telemetry.Item::class.java -> item; Boolean::class.javaPrimitiveType -> true; else -> null }
        } as Telemetry
        val t = PanelsMirroredTelemetry(sdk)
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
}
