package com.anshulnoori.gum.ftc

import com.anshulnoori.gum.core.UpdatePhase
import com.anshulnoori.gum.core.UpdateTiming
import org.firstinspires.ftc.robotcore.external.Telemetry
import java.util.Locale

/**
 * Loop time and the time each [UpdatePhase] takes, shown at the top of the telemetry of a program
 * made with `debug`. Gum formats them once per second, when [timing] finishes a window; each update
 * only adds the same two lines again.
 */
internal class Diagnostics(val timing: UpdateTiming) {
    private var shown: UpdateTiming.Report? = null
    private var loop = ""
    private var phases = ""

    fun addTo(telemetry: Telemetry) {
        val report = timing.report ?: return
        if (report !== shown) {
            shown = report
            loop = format("%.2f ms avg, %.2f ms max, %.0f Hz", report.periodMillis, report.maxPeriodMillis, report.hertz)
            phases = UpdatePhase.entries.joinToString(", ", postfix = ", Other ${format("%.2f", report.otherMillis)}") {
                "${it.label} ${format("%.2f", report.millis(it))}"
            }
        }
        telemetry.addData("Gum loop", loop)
        telemetry.addData("Gum phases (ms)", phases)
    }

    private fun format(pattern: String, vararg args: Any) = String.format(Locale.ROOT, pattern, *args)
}
