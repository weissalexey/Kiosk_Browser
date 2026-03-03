package com.example.kioskbrowser

import android.content.Context

object Prefs {
    private const val PREF_NAME = "kiosk_prefs"

    private const val KEY_START_URL = "start_url"

    const val KEY_IME_ENABLED = "ime_enabled"
    const val KEY_BAR_ENABLED = "bar_enabled"
    const val KEY_BAR_POSITION = "bar_position" // top | bottom
    const val KEY_BAR_IMAGE_URI = "bar_image_uri"
    const val KEY_BAR_OVERLAY = "bar_overlay" // draw bar over the page (do not resize WebView)

    const val KEY_BEEP_ON_ERROR = "beep_on_error"

    // Text zoom (50..150)
    const val KEY_TEXT_ZOOM = "text_zoom"

    // Keep input selected after scanner Enter when IME is disabled
    const val KEY_KEEP_SELECTION = "keep_selection"

    fun getStartUrl(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return p.getString(KEY_START_URL, "https://service.carstensen.eu/") ?: "https://service.carstensen.eu/"
    }

    fun setStartUrl(ctx: Context, url: String) {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        p.edit().putString(KEY_START_URL, url).apply()
    }

    fun isImeEnabled(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_IME_ENABLED, false)
    }

    fun setImeEnabled(ctx: Context, enabled: Boolean) {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        p.edit().putBoolean(KEY_IME_ENABLED, enabled).apply()
    }

    fun keepSelection(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_KEEP_SELECTION, true)
    }

    fun setKeepSelection(ctx: Context, enabled: Boolean) {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        p.edit().putBoolean(KEY_KEEP_SELECTION, enabled).apply()
    }

    fun isBarEnabled(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_BAR_ENABLED, true)
    }

    fun setBarEnabled(ctx: Context, enabled: Boolean) {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        p.edit().putBoolean(KEY_BAR_ENABLED, enabled).apply()
    }

    fun getBarPosition(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return p.getString(KEY_BAR_POSITION, "top") ?: "top"
    }

    fun setBarPosition(ctx: Context, position: String) {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        p.edit().putString(KEY_BAR_POSITION, position).apply()
    }

    fun getBarImageUri(ctx: Context): String? {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return p.getString(KEY_BAR_IMAGE_URI, null)
    }

    fun setBarImageUri(ctx: Context, uri: String?) {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        p.edit().putString(KEY_BAR_IMAGE_URI, uri).apply()
    }

    fun isBarOverlay(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_BAR_OVERLAY, true)
    }

    fun setBarOverlay(ctx: Context, enabled: Boolean) {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        p.edit().putBoolean(KEY_BAR_OVERLAY, enabled).apply()
    }

    fun isBeepOnError(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_BEEP_ON_ERROR, true)
    }

    fun setBeepOnError(ctx: Context, enabled: Boolean) {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        p.edit().putBoolean(KEY_BEEP_ON_ERROR, enabled).apply()
    }

    fun getTextZoom(ctx: Context): Int {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return (p.getInt(KEY_TEXT_ZOOM, 100)).coerceIn(50, 150)
    }

    fun setTextZoom(ctx: Context, percent: Int) {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        p.edit().putInt(KEY_TEXT_ZOOM, percent.coerceIn(50, 150)).apply()
    }
}
