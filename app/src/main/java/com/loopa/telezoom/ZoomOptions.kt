package com.loopa.telezoom

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The list of selectable zoom levels for a device.
 *
 * One entry per back-facing lens at its native (optical) multiplier — e.g.
 * 1x and 1.57x — plus the synthetic steps 3x / 5x / 10x reached by cropping
 * (digital zoom) the longest lens that does not overshoot. A synthetic step is
 * dropped when it lands within [DEDUP_EPS] of a native multiplier so we never
 * show the same magnification twice. Results are sorted ascending.
 */
object ZoomOptions {

    /** Magnification factors offered on top of the native lenses. */
    private val SYNTHETIC = listOf(3f, 5f, 10f)

    /** Two zoom values within this distance are treated as the same step. */
    private const val DEDUP_EPS = 0.25f

    data class Level(
        val zoom: Float,
        val label: String,
        /** True when the zoom is a lens's native optical multiplier. */
        val native: Boolean
    )

    fun list(ctx: Context): List<Level> {
        val natives = LensRegistry.list(ctx)
            .map { it.opticalMultiplier }
            .distinct()

        val zooms = natives.map { it to true }.toMutableList()
        for (s in SYNTHETIC) {
            if (zooms.none { abs(it.first - s) <= DEDUP_EPS }) zooms.add(s to false)
        }

        return zooms
            .sortedBy { it.first }
            .map { (z, native) -> Level(z, formatLabel(z), native) }
    }

    /** Trim trailing zeros: 1.0 -> "1x", 1.57 -> "1.57x", 3.0 -> "3x". */
    private fun formatLabel(z: Float): String {
        val rounded = z.roundToInt()
        val text = if (abs(z - rounded) < 0.005f) rounded.toString()
                   else "%.2f".format(z).trimEnd('0').trimEnd('.')
        return "${text}x"
    }
}
