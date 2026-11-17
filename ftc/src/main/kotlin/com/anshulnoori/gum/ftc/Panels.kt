package com.anshulnoori.gum.ftc

import com.bylazar.field.FieldManager
import com.bylazar.field.PanelsField
import com.bylazar.telemetry.PanelsTelemetry
import org.firstinspires.ftc.robotcore.external.Func
import org.firstinspires.ftc.robotcore.external.Telemetry

/**
 * The Panels field for the running program: `field.moveCursor(x, y); field.circle(9.0)`.
 * Gum sends it to Panels after every update, then clears it, so draw it again each update.
 * Panels transmits only when `shouldUpdateCanvas` is true at that send. Drawing only when the flag
 * is true can miss a transmission, because the flag can turn true between the check and the send.
 */
val FtcScope.field: FieldManager get() = PanelsField.field

/**
 * The SDK's telemetry, also shown in Panels. Each line goes to both; the SDK's items, lines, and
 * log stay as they are, so code written for SDK telemetry works unchanged. Panels takes each value
 * when it is added, including a [Func] value.
 */
internal class PanelsMirroredTelemetry(private val sdk: Telemetry) : Telemetry by sdk {
    private val panels = PanelsTelemetry.ftcTelemetry

    override fun addData(caption: String, format: String, vararg args: Any?): Telemetry.Item? {
        panels.addData(caption, format, *args)
        return sdk.addData(caption, format, *args)
    }

    override fun addData(caption: String, value: Any?): Telemetry.Item? {
        panels.addData(caption, value)
        return sdk.addData(caption, value)
    }

    override fun <T : Any?> addData(caption: String, valueProducer: Func<T>): Telemetry.Item? {
        panels.addData(caption, valueProducer.value())
        return sdk.addData(caption, valueProducer)
    }

    override fun <T : Any?> addData(caption: String, format: String, valueProducer: Func<T>): Telemetry.Item? {
        panels.addData(caption, format, valueProducer.value())
        return sdk.addData(caption, format, valueProducer)
    }

    override fun addLine(): Telemetry.Line? {
        panels.addLine()
        return sdk.addLine()
    }

    override fun addLine(lineCaption: String): Telemetry.Line? {
        panels.addLine(lineCaption)
        return sdk.addLine(lineCaption)
    }

    override fun update(): Boolean {
        panels.update()
        return sdk.update()
    }

    override fun clear() {
        panels.clear()
        sdk.clear()
    }

    override fun clearAll() {
        panels.clearAll()
        sdk.clearAll()
    }
}

/**
 * Sends this update's telemetry lines and field drawing to Panels, then clears both. Panels limits
 * how often it actually transmits; between transmissions this only clears.
 */
internal fun sendToPanels() {
    PanelsTelemetry.telemetry.update()
    PanelsField.field.update()
}
