package com.example.kioskbrowser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet

class MainActivity : AppCompatActivity() {

    private lateinit var web: NoImeWebView
    private var imeEnabled: Boolean = false

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var barContainer: View
    private lateinit var barImage: ImageView

    private var toneGen: ToneGenerator? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Restrict navigation to the ScanApp host (start URL)
    private var allowedHost: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        barContainer = findViewById(R.id.barContainer)
        barImage = findViewById(R.id.barImage)
        web = findViewById(R.id.webView)

        allowedHost = try { Uri.parse(Prefs.getStartUrl(this)).host } catch (_: Exception) { null }

        // Keep the WebView focused (barcode scanners often act as a keyboard).
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.requestFocus(View.FOCUS_DOWN)

        // Prevent overlay buttons from stealing focus.
        findViewById<ImageButton>(R.id.btnKeyboard).apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }
        findViewById<ImageButton>(R.id.btnSettings).apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }

        // IME (soft keyboard) is blocked by default; user can toggle it with the icon.
        imeEnabled = Prefs.isImeEnabled(this)
        web.setImeEnabled(imeEnabled)
        updateKeyboardIcon()

        applyBarSettings()

        // WebView settings tuned for Android 10 / rugged devices.
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true // ScanApp usually needs this
        web.settings.databaseEnabled = false
        web.settings.setSupportZoom(false)
        web.settings.builtInZoomControls = false
        web.settings.displayZoomControls = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.setGeolocationEnabled(false)
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        web.settings.saveFormData = false
        @Suppress("DEPRECATION")
        web.settings.savePassword = false

        // IMPORTANT: allow HTML5 audio autoplay inside WebView.
        // Chrome allows the site to play a short beep; ScanApp portals often do the same.
        web.settings.mediaPlaybackRequiresUserGesture = false

        // Instant zoom without reload (50..150)
        applyTextZoom()

        // JS bridge for error notifications
        web.addJavascriptInterface(PageErrorBridge(), "KioskError")

        // Init native beep generator (uses MUSIC stream - same as Chrome).
        initBeep()

        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    beepError()
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                beepError()
                return super.onJsAlert(view, url, message, result)
            }
        }

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = (uri.scheme ?: "").lowercase()

                // Let the WebView handle normal pages, but only for ScanApp host.
                if (scheme == "http" || scheme == "https") {
                    val host = uri.host
                    val allowed = allowedHost
                    return if (allowed != null && host != null && !host.equals(allowed, ignoreCase = true)) {
                        Toast.makeText(this@MainActivity, "Blocked external site", Toast.LENGTH_SHORT).show()
                        true
                    } else {
                        false
                    }
                }

                // Delegate custom schemes (e.g. parcelcam://...) to Android.
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Cannot open link", Toast.LENGTH_SHORT).show()
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                // After refresh/navigation:
                // 1) put cursor into the first input,
                // 2) hook errors -> native beep,
                // 3) enforce keyboard toggle state.
                focusFirstInput()
                installSelectAllOnFocus()
                injectErrorHook(view)
                enforceImeState()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) beepError()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request.isForMainFrame && errorResponse.statusCode >= 400) beepError()
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // Renderer crashed/killed (common on low-RAM Android 10). Recover fast.
                try { view.destroy() } catch (_: Exception) {}
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "WebView crashed. Reloading...", Toast.LENGTH_SHORT).show()
                    recreate()
                }
                return true
            }
        }

        // Keep soft keyboard hidden unless explicitly enabled.
        web.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                v.requestFocus()
                if (!imeEnabled) web.hideIme()
            }
            false
        }

        // Keyboard toggle (icon)
        findViewById<View>(R.id.btnKeyboard).setOnClickListener {
            imeEnabled = !imeEnabled
            web.setImeEnabled(imeEnabled)
            Prefs.setImeEnabled(this, imeEnabled)
            updateKeyboardIcon()
            enforceImeState()

            // Always return focus to WebView.
            web.requestFocus(View.FOCUS_DOWN)
        }

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
        }

        loadStartUrl()
    }

    private fun loadStartUrl() {
        val url = Prefs.getStartUrl(this)
        web.loadUrl(url)
    }

    private fun applyTextZoom() {
        try {
            val percent = Prefs.getTextZoom(this)
            web.settings.textZoom = percent
        } catch (_: Exception) {
        }
    }

    private fun updateKeyboardIcon() {
        val btn = findViewById<ImageButton>(R.id.btnKeyboard)
        btn.setImageResource(if (imeEnabled) R.drawable.ic_keyboard else R.drawable.ic_keyboard_off)
        btn.contentDescription = if (imeEnabled) "Keyboard enabled" else "Keyboard disabled"
    }

    private fun enforceImeState() {
        if (imeEnabled) web.showIme() else web.hideIme()
    }

    private fun focusFirstInput() {
        val js = """
            (function(){
              try {
                var el = document.querySelector('input:not([type=hidden]):not([disabled]):not([readonly]), textarea:not([disabled]):not([readonly]), [contenteditable="true"]');
                if (!el) {
                  document.body && document.body.focus && document.body.focus();
                  return;
                }
                el.focus();
                if (el.setSelectionRange && typeof el.value === 'string') {
                  var l = el.value.length;
                  el.setSelectionRange(l, l);
                }
              } catch(e) {}
            })();
        """.trimIndent()

        web.postDelayed({ web.evaluateJavascript(js, null) }, 120)
    }

    private fun installSelectAllOnFocus() {
        // Select all text when an editable element gets focus, so new scans replace previous value.
        val js = """
            (function(){
              try {
                if (window.__kioskSelectAllInstalled) return;
                window.__kioskSelectAllInstalled = true;

                function isEditable(el){
                  if (!el) return false;
                  var tag = (el.tagName||'').toLowerCase();
                  if (tag === 'textarea') return true;
                  if (tag === 'input') {
                    var t = (el.type||'text').toLowerCase();
                    return t !== 'hidden' && t !== 'button' && t !== 'submit' && t !== 'checkbox' && t !== 'radio' && t !== 'file';
                  }
                  if (el.isContentEditable) return true;
                  return false;
                }

                document.addEventListener('focus', function(e){
                  try {
                    var el = e.target;
                    if (!isEditable(el)) return;
                    setTimeout(function(){
                      try {
                        if (el.isContentEditable) {
                          var r = document.createRange();
                          r.selectNodeContents(el);
                          var s = window.getSelection();
                          s.removeAllRanges();
                          s.addRange(r);
                        } else if (el.setSelectionRange && typeof el.value === 'string') {
                          el.setSelectionRange(0, el.value.length);
                        }
                      } catch(_){ }
                    }, 0);
                  } catch(_){ }
                }, true);
              } catch(e) {}
            })();
        """.trimIndent()

        web.evaluateJavascript(js, null)
    }

    private fun initBeep() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Request audio focus so the tone is not suppressed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* ignore */ }
                    .build()

                audioFocusRequest?.let { am.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }

            // Use MUSIC stream (Chrome uses it too). STREAM_ALARM is often muted in kiosk/MDM.
            toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (_: Exception) {
            toneGen = null
        }
    }

    private fun beepError() {
        if (!Prefs.isBeepOnError(this)) return
        try {
            // short + loud + annoying
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
        } catch (_: Exception) {
        }
    }

    private inner class PageErrorBridge {
        @JavascriptInterface
        fun onError(msg: String?) {
            // Don't spam UI: just beep.
            runOnUiThread { beepError() }
        }
    }

    private fun injectErrorHook(view: WebView) {
        if (!Prefs.isBeepOnError(this)) return
        val js = """
            (function(){
              try {
                if (window.__kioskErrorHookInstalled) return;
                window.__kioskErrorHookInstalled = true;

                window.addEventListener('error', function(e){
                  try { KioskError.onError(String(e && e.message ? e.message : 'error')); } catch(_){ }
                }, true);

                window.addEventListener('unhandledrejection', function(e){
                  try { KioskError.onError(String(e && e.reason ? e.reason : 'promise')); } catch(_){ }
                }, true);

                var oldErr = console.error;
                console.error = function(){
                  try { KioskError.onError(Array.prototype.join.call(arguments, ' ')); } catch(_){ }
                  try { oldErr && oldErr.apply(console, arguments); } catch(_){ }
                };
              } catch(e) { }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun applyBarSettings() {
        val showBlueBar = Prefs.isBarEnabled(this)
        val position = Prefs.getBarPosition(this) // "top" or "bottom"
        val overlay = Prefs.isBarOverlay(this)    // true => draw over the page
        val uriStr = Prefs.getBarImageUri(this)

        if (!uriStr.isNullOrBlank()) {
            try {
                barImage.setImageURI(Uri.parse(uriStr))
            } catch (_: Exception) {
                barImage.setImageDrawable(null)
            }
        } else {
            barImage.setImageDrawable(null)
        }

        // Keep the container so Settings/Keyboard buttons remain reachable.
        barContainer.visibility = View.VISIBLE
        barImage.visibility = if (showBlueBar) View.VISIBLE else View.GONE
        barContainer.setBackgroundColor(if (showBlueBar) 0xFF0099CC.toInt() else 0x00000000)

        val set = ConstraintSet()
        set.clone(rootLayout)

        val barId = R.id.barContainer
        val webId = R.id.webView

        set.clear(barId, ConstraintSet.TOP)
        set.clear(barId, ConstraintSet.BOTTOM)
        set.clear(webId, ConstraintSet.TOP)
        set.clear(webId, ConstraintSet.BOTTOM)

        // Bar position
        set.connect(barId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        set.connect(barId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        if (position == "bottom") {
            set.connect(barId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        } else {
            set.connect(barId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        }

        // WebView placement
        if (showBlueBar && !overlay) {
            // Reserve space for the bar
            if (position == "bottom") {
                set.connect(webId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                set.connect(webId, ConstraintSet.BOTTOM, barId, ConstraintSet.TOP)
            } else {
                set.connect(webId, ConstraintSet.TOP, barId, ConstraintSet.BOTTOM)
                set.connect(webId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            }
        } else {
            // Overlay or hidden => full-size WebView
            set.connect(webId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            set.connect(webId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        }

        set.applyTo(rootLayout)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SETTINGS && resultCode == Activity.RESULT_OK) {
            // Re-apply settings & reload
            imeEnabled = Prefs.isImeEnabled(this)
            web.setImeEnabled(imeEnabled)
            updateKeyboardIcon()
            applyBarSettings()
            applyTextZoom()
            enforceImeState()

            loadStartUrl()
        }
    }

    override fun onResume() {
        super.onResume()

        // Re-apply settings after returning from Settings or after system focus changes.
        imeEnabled = Prefs.isImeEnabled(this)
        web.setImeEnabled(imeEnabled)
        updateKeyboardIcon()
        applyBarSettings()
        applyTextZoom()
        enforceImeState()

        // Keep focus on WebView so barcode scanner / hardware keyboard input is delivered.
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.requestFocus(View.FOCUS_DOWN)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Barcode scanners emulate a keyboard. If focus is lost to overlay UI,
        // the page won't receive the key events.
        if (::web.isInitialized && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
            if (!web.hasFocus()) web.requestFocus(View.FOCUS_DOWN)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Sometimes the IME can pop up even with showSoftInputOnFocus disabled.
        // Hard-hide it on any touch when the toggle is OFF.
        if (!imeEnabled && ::web.isInitialized) {
            web.hideIme()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) {
            web.goBack()
        } else {
            // In kiosk mode it is usually better to stay in the app.
            // If you prefer closing, call super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try {
            toneGen?.release()
        } catch (_: Exception) {
        }
        toneGen = null

        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            }
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::web.isInitialized && level >= TRIM_MEMORY_RUNNING_LOW) {
            try { web.clearCache(true) } catch (_: Exception) {}
        }
    }

    companion object {
        private const val REQ_SETTINGS = 1001
    }
}
