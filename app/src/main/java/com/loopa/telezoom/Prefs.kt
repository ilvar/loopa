package com.loopa.telezoom

import android.content.Context

object Prefs {
    /** Default target magnification when the user has not picked one. */
    const val DEFAULT_ZOOM = 10f

    private const val FILE = "loopa_prefs"
    private const val KEY_ZOOM = "target_zoom"
    private const val KEY_OCR = "ocr_enabled"
    private const val KEY_LENS_CACHE = "lens_cache"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Desired effective magnification (e.g. 1f, 1.57f, 3f, 5f, 10f). */
    fun targetZoom(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_ZOOM, DEFAULT_ZOOM)

    fun setTargetZoom(ctx: Context, value: Float) {
        prefs(ctx).edit().putFloat(KEY_ZOOM, value).apply()
    }

    fun ocrEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_OCR, true)

    fun setOcrEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_OCR, value).apply()
    }

    /** Raw lens-cache blob; format defined by `LensRegistry`. */
    fun lensCache(ctx: Context): String? =
        prefs(ctx).getString(KEY_LENS_CACHE, null)

    fun setLensCache(ctx: Context, value: String?) {
        prefs(ctx).edit().putString(KEY_LENS_CACHE, value).apply()
    }
}
