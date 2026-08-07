package com.rohittp.rentile.internal

import com.rohittp.rentile.DiagnosticSink
import com.rohittp.rentile.MetricsSink
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.RentileMetric

internal fun DiagnosticSink.recordSafely(diagnostic: RenderDiagnostic) {
    try {
        record(diagnostic)
    } catch (_: Throwable) {
        // Observability must not change rendering behavior.
    }
}

internal fun MetricsSink.recordSafely(metric: RentileMetric) {
    try {
        record(metric)
    } catch (_: Throwable) {
        // Observability must not change rendering behavior.
    }
}
