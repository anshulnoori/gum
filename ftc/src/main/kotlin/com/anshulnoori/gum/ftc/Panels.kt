package com.anshulnoori.gum.ftc

import com.bylazar.field.FieldManager
import com.bylazar.field.PanelsField
import com.bylazar.panels.Panels
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
 * log stay as they are, so code written for SDK telemetry works unchanged.
 *
 * Panels receives lines only on updates when it will transmit them: a client is connected and its
 * send interval has passed. On other updates, including every update with no laptop connected,
 * nothing is formatted or evaluated for Panels. A [Func] value is then evaluated once for Panels,
 * when it is added, and again by the SDK when it transmits.
 */
internal class PanelsMirroredTelemetry(private val sdk: Telemetry) : Telemetry by sdk {
    private val panels = PanelsTelemetry.ftcTelemetry

    /** Whether this update's lines go to Panels; the host sets it at the start of each update. */
    var live = false

    override fun addData(caption: String, format: String, vararg args: Any?): Telemetry.Item? {
        if (live) panels.addData(caption, format, *args)
        return sdk.addData(caption, format, *args)
    }

    override fun addData(caption: String, value: Any?): Telemetry.Item? {
        if (live) panels.addData(caption, value)
        return sdk.addData(caption, value)
    }

    override fun <T : Any?> addData(caption: String, valueProducer: Func<T>): Telemetry.Item? {
        if (live) panels.addData(caption, valueProducer.value())
        return sdk.addData(caption, valueProducer)
    }

    override fun <T : Any?> addData(caption: String, format: String, valueProducer: Func<T>): Telemetry.Item? {
        if (live) panels.addData(caption, format, valueProducer.value())
        return sdk.addData(caption, format, valueProducer)
    }

    override fun addLine(): Telemetry.Line? {
        if (live) panels.addLine()
        return sdk.addLine()
    }

    override fun addLine(lineCaption: String): Telemetry.Line? {
        if (live) panels.addLine(lineCaption)
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

/** Whether Panels will transmit telemetry lines added from now until this update's send. */
internal fun panelsLive(): Boolean = Panels.clientsCount > 0 && PanelsTelemetry.telemetry.shouldUpdateLines

/**
 * Sends this update's telemetry lines and field drawing to Panels, then clears both. Panels limits
 * how often it actually transmits; between transmissions this only clears. With no client
 * connected, it only clears, so nothing is serialized for nobody.
 */
internal fun sendToPanels() {
    if (Panels.clientsCount > 0) {
        PanelsTelemetry.telemetry.update()
        PanelsField.field.update()
    } else {
        PanelsTelemetry.telemetry.lines.clear()
        PanelsField.field.apply {
            canvas.reset()
            cursorX = 0.0
            cursorY = 0.0
            cursorHeading = 0.0
        }
    }
}
