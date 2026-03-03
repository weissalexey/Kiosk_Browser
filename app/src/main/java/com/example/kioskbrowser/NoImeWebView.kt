package com.example.kioskbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

/**
 * WebView that can suppress the system IME (soft keyboard) while still allowing typing
 * (e.g. via a hardware keyboard / scanner).
 *
 * Key idea:
 * - Keep the InputConnection so text input is NOT blocked.
 * - Prevent/close the IME when imeEnabled == false.
 */
class NoImeWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private var imeEnabled: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // Default: do not show IME until MainActivity applies the persisted setting.
        // This also prevents the "flash loop" on some Android 10 devices.
        setShowSoftInputOnFocusCompat(false)
    }

    fun setImeEnabled(enabled: Boolean) {
        imeEnabled = enabled
        // Key fix for the "IME flash loop":
        // When IME is disabled, do NOT allow WebView to request soft keyboard on focus.
        setShowSoftInputOnFocusCompat(enabled)
        if (!enabled) hideImeAggressive()
    }

    /**
     * Hide the soft keyboard right now (best-effort).
     * MainActivity calls this after toggling the IME flag or navigating.
     */
    fun hideIme() {
        hideImeAggressive()
    }

    /**
     * Show the system IME (soft keyboard) if IME is enabled.
     * Used when user explicitly toggles the keyboard on.
     */
    fun showIme() {
        if (!imeEnabled) return

        // Ensure focus first
        requestFocus()
        requestFocusFromTouch()

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        mainHandler.post {
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Let WebView process the touch FIRST (focus input / caret / selection).
        // Hiding IME before WebView consumes the event can prevent text selection/caret
        // placement on some Android 10 WebView builds.
        val handled = super.onTouchEvent(event)

        if (!imeEnabled && (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)) {
            // Post so focus/selection is already applied.
            post { hideImeAggressive() }
        }

        return handled
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (focused && !imeEnabled) {
            hideImeAggressive()
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)

        // IMPORTANT: do NOT return null here, otherwise input can be broken.
        // We only suppress the IME.
        if (!imeEnabled) {
            outAttrs.imeOptions = outAttrs.imeOptions or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
        }

        return ic
    }

    private fun hideImeAggressive() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return

        // Best-effort: some IMEs reopen right after focus changes.
        // We hide multiple times with small delays, but we do NOT clear all callbacks
        // to avoid breaking other posted work.
        fun hide() {
            imm.hideSoftInputFromWindow(windowToken, 0)
        }

        hide()
        mainHandler.postDelayed({ hide() }, 80)
        mainHandler.postDelayed({ hide() }, 250)
    }

    private fun setShowSoftInputOnFocusCompat(show: Boolean) {
        try {
            // API 21+ has this method, but we keep reflection for safety.
            // Use View method lookup to be robust across WebView implementations.
            val method = android.view.View::class.java.getMethod("setShowSoftInputOnFocus", Boolean::class.javaPrimitiveType)
            method.invoke(this, show)
        } catch (_: Throwable) {
            // Ignore on older/modified WebView implementations.
        }
    }
}
