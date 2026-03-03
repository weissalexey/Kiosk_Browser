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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Основная активность Kiosk Browser.
 * Оптимизирована для работы со сканерами штрих-кодов без экранной клавиатуры.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_SETTINGS = 1001
    }

    private lateinit var web: NoImeWebView
    private var imeEnabled: Boolean = false

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var barContainer: View
    private lateinit var barImage: ImageView

    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var loadingText: TextView

    private var loadingShown: Boolean = false
    private var loadingRunnablePosted: Boolean = false
    private var pendingLoadingText: String = "Loading..."
    private val showLoadingRunnable = Runnable {
        loadingRunnablePosted = false
        showLoading(pendingLoadingText)
    }

    private var toneGen: ToneGenerator? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var allowedHost: String? = null

    private var lastHideImeMs: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        barContainer = findViewById(R.id.barContainer)
        barImage = findViewById(R.id.barImage)
        web = findViewById(R.id.webView)

        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingSpinner = findViewById(R.id.loadingSpinner)
        loadingText = findViewById(R.id.loadingText)

        allowedHost = try { Uri.parse(Prefs.getStartUrl(this)).host } catch (_: Exception) { null }

        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.requestFocus(View.FOCUS_DOWN)

        findViewById<ImageButton>(R.id.btnKeyboard).apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }
        findViewById<ImageButton>(R.id.btnSettings).apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }

        imeEnabled = Prefs.isImeEnabled(this)
        web.setImeEnabled(imeEnabled)
        updateKeyboardIcon()

        installImeVisibilityWatcher()
        applyBarSettings()

        // Настройки WebView
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
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
        web.settings.mediaPlaybackRequiresUserGesture = false

        applyTextZoom()
        web.addJavascriptInterface(PageErrorBridge(), "KioskError")
        initBeep()

        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) beepError()
                return super.onConsoleMessage(consoleMessage)
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Avoid instant flicker on fast pages: show after a short delay.
                scheduleLoading("Loading...")
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                super.onPageCommitVisible(view, url)
                // First pixels are on screen.
                hideLoading()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = (uri.scheme ?: "").lowercase()
                if (scheme == "http" || scheme == "https") {
                    val host = uri.host
                    val allowed = allowedHost
                    return if (allowed != null && host != null && !host.equals(allowed, ignoreCase = true)) {
                        Toast.makeText(this@MainActivity, "Сайт заблокирован", Toast.LENGTH_SHORT).show()
                        true
                    } else false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Ошибка ссылки", Toast.LENGTH_SHORT).show()
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                hideLoading()
                focusFirstInput()
                installSelectAllOnFocus()
                injectErrorHook(view)
                enforceImeState()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) beepError()
                if (request.isForMainFrame) {
                    showLoading("Network error. Retrying...")
                }
            }
        }

        web.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                v.requestFocus()
                if (!imeEnabled) {
                    web.hideIme()
                    nudgeFocusAndSelection()
                }
            }
            false
        }

        findViewById<View>(R.id.btnKeyboard).setOnClickListener {
            imeEnabled = !imeEnabled
            web.setImeEnabled(imeEnabled)
            Prefs.setImeEnabled(this, imeEnabled)
            updateKeyboardIcon()
            enforceImeState()
            web.requestFocus(View.FOCUS_DOWN)
        }

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
        }

        loadStartUrl()
    }

    private fun loadStartUrl() = web.loadUrl(Prefs.getStartUrl(this))

    private fun applyTextZoom() {
        try { web.settings.textZoom = Prefs.getTextZoom(this) } catch (_: Exception) {}
    }

    private fun installImeVisibilityWatcher() {
        val root = findViewById<View>(android.R.id.content)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            if (imeEnabled || !Prefs.keepSelection(this)) return@addOnGlobalLayoutListener
            val insets = ViewCompat.getRootWindowInsets(root)
            if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                // Debounce: prevents rare "blink loops" on some devices/IMEs.
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastHideImeMs > 250) {
                    lastHideImeMs = now
                    web.hideIme()
                    web.postDelayed({ nudgeFocusAndSelection() }, 100)
                }
            }
        }
    }

    private fun scheduleLoading(text: String) {
        pendingLoadingText = text
        // Always reschedule: prevents the case when page finishes quickly,
        // but the delayed runnable still shows the overlay after finish.
        web.removeCallbacks(showLoadingRunnable)
        loadingRunnablePosted = true
        web.postDelayed(showLoadingRunnable, 250)
    }

    private fun showLoading(text: String) {
        loadingText.text = text
        if (!loadingShown) {
            loadingShown = true
            loadingOverlay.visibility = View.VISIBLE
        }
    }

    private fun hideLoading() {
        // Critical: cancel delayed show, otherwise overlay may appear AFTER onPageFinished.
        web.removeCallbacks(showLoadingRunnable)
        loadingRunnablePosted = false
        loadingShown = false
        loadingOverlay.visibility = View.GONE
    }

    private fun updateKeyboardIcon() {
        val btn = findViewById<ImageButton>(R.id.btnKeyboard)
        btn.setImageResource(if (imeEnabled) R.drawable.ic_keyboard else R.drawable.ic_keyboard_off)
    }

    private fun enforceImeState() {
        if (imeEnabled) web.showIme()
        else {
            web.hideIme()
            nudgeFocusAndSelection()
        }
    }

    /**
     * Вызывает JavaScript для принудительного фокуса и выделения текста.
     */
    private fun nudgeFocusAndSelection() {
        if (!Prefs.keepSelection(this) || imeEnabled) return
        web.requestFocus()
        web.postDelayed({
            web.evaluateJavascript(
                "(function(){ try { if (window.__kb_focusAndSelectLast) { window.__kb_focusAndSelectLast(); } } catch(e){} })();",
                null
            )
        }, 100)
    }

    private fun focusFirstInput() {
        val js = "document.querySelector('input:not([type=hidden]), textarea')?.focus();"
        web.postDelayed({ web.evaluateJavascript(js, null) }, 200)
    }

    /**
     * Инъекция JS скрипта для автоматического выделения текста после сканирования.
     * Исправляет проблему "дописывания" текста вместо замены.
     */
    private fun installSelectAllOnFocus() {
        if (!Prefs.keepSelection(this) || imeEnabled) return

        val js = """
            (function(){
              try {
                if (window.__kioskSelectAllInstalled) return;
                window.__kioskSelectAllInstalled = true;
                var lastEditable = null;

                function isEditable(el){
                  if (!el) return false;
                  var tag = (el.tagName||'').toLowerCase();
                  if (tag === 'textarea') return true;
                  if (tag === 'input') {
                    var t = (el.type||'text').toLowerCase();
                    return !/hidden|button|submit|checkbox|radio|file/.test(t);
                  }
                  return el.isContentEditable;
                }

                function selectAll(el){
                  if (!isEditable(el)) return;
                  try {
                    if (el.isContentEditable) {
                      var r = document.createRange(); r.selectNodeContents(el);
                      var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
                    } else {
                      el.setSelectionRange(0, el.value.length);
                    }
                  } catch(e){}
                }

                function focusAndSelectLast(){
                  var el = lastEditable || document.activeElement;
                  if(isEditable(el)) {
                    el.focus();
                    setTimeout(function(){ selectAll(el); }, 10);
                  }
                }
                window.__kb_focusAndSelectLast = focusAndSelectLast;

                function setupDoc(doc){
                  if (!doc || doc.__kb_hooked) return;
                  doc.__kb_hooked = true;

                  doc.addEventListener('focusin', function(e){
                    if (isEditable(e.target)) {
                        lastEditable = e.target;
                        setTimeout(function(){ selectAll(e.target); }, 50);
                    }
                  }, true);

                  // Ключевой момент: выделение после ввода (сканирования) и нажатия Enter
                  doc.addEventListener('keydown', function(e){
                    if ((e.key === 'Enter' || e.keyCode === 13) && isEditable(doc.activeElement)) {
                        lastEditable = doc.activeElement;
                        // Выделяем текст сразу после того, как событие будет обработано
                        setTimeout(function(){ selectAll(lastEditable); }, 50);
                    }
                  }, true);
                }

                setupDoc(document);
                setInterval(function(){
                  document.querySelectorAll('iframe').forEach(function(f){
                    try { setupDoc(f.contentDocument); } catch(e){}
                  });
                }, 1000);
              } catch(e) {}
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun initBeep() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs).build()
                audioFocusRequest?.let { am.requestAudioFocus(it) }
            }
            toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (_: Exception) {}
    }

    private fun beepError() {
        if (Prefs.isBeepOnError(this)) toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
    }

    private inner class PageErrorBridge {
        @JavascriptInterface fun onError(msg: String?) { runOnUiThread { beepError() } }
    }

    private fun injectErrorHook(view: WebView) {
        if (!Prefs.isBeepOnError(this)) return
        val js = "window.addEventListener('error', function(e){ KioskError.onError(e.message); }, true);"
        view.evaluateJavascript(js, null)
    }

    private fun applyBarSettings() {
        val showBar = Prefs.isBarEnabled(this)
        val position = Prefs.getBarPosition(this)
        val overlay = Prefs.isBarOverlay(this)

        barContainer.visibility = View.VISIBLE
        barImage.visibility = if (showBar) View.VISIBLE else View.GONE
        barContainer.setBackgroundColor(if (showBar) 0xFF0099CC.toInt() else 0x00000000)

        // Apply selected logo for the blue bar
        val uriStr = Prefs.getBarImageUri(this)
        if (showBar && !uriStr.isNullOrBlank()) {
            try {
                barImage.setImageURI(Uri.parse(uriStr))
            } catch (_: Exception) {
                barImage.setImageDrawable(null)
            }
        } else {
            barImage.setImageDrawable(null)
        }

        val set = ConstraintSet()
        set.clone(rootLayout)
        set.clear(R.id.barContainer, ConstraintSet.TOP); set.clear(R.id.barContainer, ConstraintSet.BOTTOM)
        set.clear(R.id.webView, ConstraintSet.TOP); set.clear(R.id.webView, ConstraintSet.BOTTOM)

        if (position == "bottom") set.connect(R.id.barContainer, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        else set.connect(R.id.barContainer, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)

        if (showBar && !overlay) {
            if (position == "bottom") {
                set.connect(R.id.webView, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                set.connect(R.id.webView, ConstraintSet.BOTTOM, R.id.barContainer, ConstraintSet.TOP)
            } else {
                set.connect(R.id.webView, ConstraintSet.TOP, R.id.barContainer, ConstraintSet.BOTTOM)
                set.connect(R.id.webView, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            }
        } else {
            set.connect(R.id.webView, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            set.connect(R.id.webView, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        }
        set.applyTo(rootLayout)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Перехватываем Enter от сканера на уровне всей активности
        if (::web.isInitialized && !imeEnabled) {
            if (event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                web.hideIme()
                nudgeFocusAndSelection()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        if (!imeEnabled && ::web.isInitialized && ev.action == MotionEvent.ACTION_UP) {
            web.postDelayed({
                web.hideIme()
                nudgeFocusAndSelection()
            }, 50)
        }
        return handled
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack()
    }

    override fun onDestroy() {
        toneGen?.release()
        super.onDestroy()
    }
}