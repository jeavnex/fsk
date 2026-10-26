package net.tenyls.kinotv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var cursor: CursorView
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var errorTitle: TextView
    private lateinit var errorText: TextView
    private lateinit var retryBtn: Button

    private val ui = Handler(Looper.getMainLooper())
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private var isTv = false
    private var cursorOn = false
    private var cursorVisible = false
    private var homeUrl = ""
    private var homeDomain = ""
    private val allowedThisSession = HashSet<String>()

    private var okDownTime = 0L
    private var touchDownTime = 0L
    private var okLongFired = false
    private var lastBack = 0L
    private var httpErrorCode = 0
    private var updateChecked = false

    private val density by lazy { resources.displayMetrics.density }
    private val hideCursorRunnable = Runnable { setCursorVisible(false) }

    // ───────────────────────── Жизненный цикл ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("kino", Context.MODE_PRIVATE)
        isTv = detectTv()
        cursorOn = prefs.getBoolean("cursor", isTv)
        AdBlock.load(prefs.getString("remoteConfig", null))

        buildUi()
        setupWebView()
        goImmersive()

        homeUrl = prefs.getString("home", "") ?: ""
        if (homeUrl.isEmpty()) askHomeUrl(first = true)
        else {
            homeDomain = AdBlock.baseDomain(Uri.parse(homeUrl).host)
            if (savedInstanceState != null) web.restoreState(savedInstanceState)
            if (web.url == null) web.loadUrl(homeUrl)
        }

        refreshRemoteConfig()
        handleIntent(intent)

        val hints = prefs.getInt("hints", 0)
        if (isTv && hints < 3) {
            prefs.edit().putInt("hints", hints + 1).apply()
            toast("Стрелки — курсор, ОК — нажать.\nДолгое ОК или кнопка «Меню» — меню приложения", long = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        goImmersive()
        if (!updateChecked) {
            updateChecked = true
            ui.postDelayed({ checkUpdate(manual = false) }, 6000)
        }
    }

    override fun onPause() {
        web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){try{v.pause()}catch(e){}})", null)
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun detectTv(): Boolean {
        val um = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return um.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    @Suppress("DEPRECATION")
    private fun goImmersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    // ───────────────────────── Интерфейс ─────────────────────────

    private fun dp(v: Int) = (v * density).toInt()

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        web = WebView(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(4), Gravity.TOP))

        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF0101010.toInt())
            setPadding(dp(48), dp(32), dp(48), dp(32))
            visibility = View.GONE
            isClickable = true
        }
        errorTitle = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 26f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        errorText = TextView(this).apply {
            setTextColor(0xFFCCCCCC.toInt()); textSize = 18f; gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(24))
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        retryBtn = makeButton("Повторить") { hideError(); web.reload() }
        buttons.addView(retryBtn)
        buttons.addView(makeButton("На главную") { hideError(); loadHome() })
        buttons.addView(makeButton("Адрес сайта") { askHomeUrl(first = false) })
        errorBox.addView(errorTitle)
        errorBox.addView(errorText)
        errorBox.addView(buttons)
        root.addView(errorBox, FrameLayout.LayoutParams(-1, -1))

        cursor = CursorView(this).apply { visibility = View.GONE }
        root.addView(cursor, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        root.post {
            cursor.cx = root.width / 2f
            cursor.cy = root.height / 2f
        }
    }

    private fun makeButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), 0, dp(8), 0) }
    }

    private fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ───────────────────────── WebView ─────────────────────────

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private fun applyUserAgent() {
        val desktop = prefs.getBoolean("desktop", isTv)
        val s = web.settings
        s.userAgentString = null // сброс к стандартному
        s.userAgentString = if (desktop) desktopUa else s.userAgentString.replace("; wv", "")
    }

    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.setSupportZoom(false)
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_DEFAULT
        applyUserAgent()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
    }

    private fun injectCleaner(view: WebView) {
        view.evaluateJavascript(AdBlock.pageScript(homeDomain), null)
    }

    private fun loadHome() {
        if (homeUrl.isNotEmpty()) web.loadUrl(homeUrl)
    }

    private inner class Client : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (AdBlock.isBlocked(request.url.host)) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            val scheme = u.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return true // intent:, market:, tg: и т.п. — не пускаем
            if (AdBlock.isBlocked(u.host)) {
                if (request.isForMainFrame) toast("Реклама заблокирована")
                return true
            }
            if (!request.isForMainFrame) return false
            if (AdBlock.sameSite(u, homeDomain)) return false
            if (AdBlock.baseDomain(u.host) in allowedThisSession) return false
            if (request.isRedirect || !request.hasGesture()) {
                toast("Заблокирован переход на ${u.host}")
                return true
            }
            confirmExternal(u)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            httpErrorCode = 0
            progress.visibility = View.VISIBLE
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            injectCleaner(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progress.visibility = View.GONE
            injectCleaner(view)
            CookieManager.getInstance().flush()
            val code = httpErrorCode
            if (code != 0) checkHttpError(code, view.title ?: "")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            showNetError(error.errorCode, error.description?.toString() ?: "")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame) httpErrorCode = response.statusCode
        }
    }

    private inner class Chrome : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            if (newProgress >= 100) progress.visibility = View.GONE
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customCallback = callback
            root.addView(view, root.indexOfChild(cursor), FrameLayout.LayoutParams(-1, -1))
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            setCursorVisible(false)
            goImmersive()
        }

        override fun onHideCustomView() {
            val v = customView ?: return
            root.removeView(v)
            customView = null
            customCallback = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            goImmersive()
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false // всплывающие окна без нажатия — всегда реклама
            // Узнаём адрес нового окна через временный WebView и решаем сами
            val tmp = WebView(this@MainActivity)
            var handled = false
            fun handle(v: WebView, url: String?) {
                if (handled || url.isNullOrEmpty() || url == "about:blank") return
                handled = true
                val u = Uri.parse(url)
                if (!AdBlock.isBlocked(u.host) && AdBlock.sameSite(u, homeDomain)) web.loadUrl(url)
                else toast("Всплывающее окно заблокировано")
                ui.post { v.stopLoading(); v.destroy() }
            }
            tmp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    handle(v, r.url.toString()); return true
                }
                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    handle(v, url)
                }
            }
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = tmp
            resultMsg.sendToTarget()
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }
    }

    private fun confirmExternal(u: Uri) {
        val host = u.host ?: return
        AlertDialog.Builder(this)
            .setTitle("Переход на другой сайт")
            .setMessage("Страница хочет открыть $host.\nЕсли это вход через соцсеть — разреши. Если реклама — отмена.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть") { _, _ ->
                allowedThisSession.add(AdBlock.baseDomain(host))
                web.loadUrl(u.toString())
            }
            .show()
            .getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    // ───────────────────────── Ошибки и доступность ─────────────────────────

    private fun hasInternet(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showNetError(code: Int, desc: String) {
        val (title, text) = when {
            !hasInternet() -> "Нет интернета" to "Приставка не подключена к сети. Проверь Wi-Fi."
            code == WebViewClient.ERROR_HOST_LOOKUP ->
                "Сайт не найден" to "Адрес сайта не находится (DNS).\nСкорее всего, провайдер блокирует сайт или у сайта сменился адрес.\nПроверь адрес в настройках."
            code == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE ->
                "Ошибка защищённого соединения" to "Соединение с сайтом подменено или оборвано.\nЧасто это признак блокировки на стороне провайдера."
            code == WebViewClient.ERROR_TIMEOUT || code == WebViewClient.ERROR_CONNECT ->
                "Сайт не отвечает" to "Сайт не отвечает или соединение сброшено.\nБывает при блокировке сайта провайдером или если сайт временно не работает."
            else -> "Не удалось открыть страницу" to desc
        }
        showError(title, text)
    }

    private fun checkHttpError(code: Int, title: String) {
        val challenge = title.contains("moment", true) || title.contains("подожд", true) || title.contains("checking", true)
        if (challenge) return // проверка Cloudflare — проходит сама
        when {
            code == 451 -> showError("Недоступно в вашей стране", "Сайт сообщает, что заблокирован для вашего региона (код 451).")
            code == 403 && (title.contains("403") || title.contains("forbidden", true) || title.contains("denied", true) || title.contains("запрещ", true)) ->
                showError("Доступ запрещён (403)", "Сайт отказал в доступе.\nВозможно, доступ ограничен для вашего IP-адреса или страны.")
            code in 500..599 && (title.isBlank() || title.contains(code.toString())) ->
                showError("Сайт временно не работает", "Сервер сайта ответил ошибкой $code. Попробуй позже.")
        }
    }

    private fun showError(title: String, text: String) {
        errorTitle.text = title
        errorText.text = text
        errorBox.visibility = View.VISIBLE
        progress.visibility = View.GONE
        setCursorVisible(false)
        retryBtn.requestFocus()
    }

    private fun hideError() {
        errorBox.visibility = View.GONE
        web.requestFocus()
    }

    // ───────────────────────── Курсор и пульт ─────────────────────────

    private fun setCursorVisible(v: Boolean) {
        cursorVisible = v && cursorOn
        cursor.visibility = if (cursorVisible) View.VISIBLE else View.GONE
        ui.removeCallbacks(hideCursorRunnable)
        if (cursorVisible) ui.postDelayed(hideCursorRunnable, if (customView != null) 3500L else 15000L)
    }

    private fun touchTarget(): View = customView ?: web

    private fun sendTouch(action: Int) {
        val target = touchTarget()
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = now
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = cursor.cx - target.left; y = cursor.cy - target.top; pressure = 1f; size = 1f
        })
        val ev = MotionEvent.obtain(touchDownTime, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        target.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun tapTouch() {
        sendTouch(MotionEvent.ACTION_DOWN)
        ui.postDelayed({ sendTouch(MotionEvent.ACTION_UP) }, 40)
    }

    /** Нажатие ОК: обычные ссылки и кнопки жмём через страницу (надёжно), плеер и поля ввода — касанием. */
    private fun clickAtCursor() {
        if (customView != null) { tapTouch(); return }
        val fx = cursor.cx / max(1, web.width)
        val fy = cursor.cy / max(1, web.height)
        web.evaluateJavascript(
            "(function(fx,fy){var el=document.elementFromPoint(fx*innerWidth,fy*innerHeight);if(!el)return 'none';" +
                "var t=el.tagName;if(t==='IFRAME'||t==='VIDEO'||t==='INPUT'||t==='TEXTAREA'||t==='SELECT'||el.isContentEditable)return 'touch';" +
                "var a=el.closest('a,button,[onclick],[role=button],label,summary')||el;" +
                "['pointerdown','mousedown','pointerup','mouseup'].forEach(function(n){try{a.dispatchEvent(new MouseEvent(n,{bubbles:true,cancelable:true,view:window,clientX:fx*innerWidth,clientY:fy*innerHeight}))}catch(e){}});" +
                "a.click();return 'ok';})($fx,$fy)"
        ) { r -> if (r == null || !r.contains("ok")) tapTouch() }
    }

    private fun scrollPage(dx: Int, dy: Int) {
        if (customView != null) return
        val fx = cursor.cx / max(1, web.width)
        val fy = cursor.cy / max(1, web.height)
        web.evaluateJavascript(
            "(function(fx,fy,dx,dy){var x=fx*innerWidth,y=fy*innerHeight;dx=dx*innerWidth/${web.width};dy=dy*innerHeight/${web.height};" +
                "var el=document.elementFromPoint(x,y);while(el&&el!==document.body&&el!==document.documentElement){" +
                "var cs=getComputedStyle(el);if(/(auto|scroll)/.test(cs.overflowY+cs.overflowX)&&(el.scrollHeight>el.clientHeight+2||el.scrollWidth>el.clientWidth+2)){" +
                "var t=el.scrollTop,l=el.scrollLeft;el.scrollBy(dx,dy);if(el.scrollTop!==t||el.scrollLeft!==l)return;}el=el.parentElement;}" +
                "window.scrollBy(dx,dy);})($fx,$fy,$dx,$dy)", null
        )
    }

    private fun moveCursor(dirX: Int, dirY: Int, repeat: Int) {
        setCursorVisible(true)
        val step = dp(14) * (1f + min(repeat, 25) * 0.35f)
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        val margin = dp(6).toFloat()
        val nx = cursor.cx + dirX * step
        val ny = cursor.cy + dirY * step
        cursor.cx = nx.coerceIn(0f, w - 1)
        cursor.cy = ny.coerceIn(0f, h - 1)
        val scrollStep = (h * 0.35f).toInt()
        if (dirY < 0 && ny < margin) scrollPage(0, -scrollStep)
        if (dirY > 0 && ny > h - margin) scrollPage(0, scrollStep)
        if (dirX < 0 && nx < margin) scrollPage(-(w * 0.35f).toInt(), 0)
        if (dirX > 0 && nx > w - margin) scrollPage((w * 0.35f).toInt(), 0)
        cursor.invalidate()
    }

    private fun forwardKey(e: KeyEvent, code: Int) {
        val t = touchTarget()
        t.dispatchKeyEvent(KeyEvent(e.downTime, e.eventTime, e.action, code, e.repeatCount))
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        val up = e.action == KeyEvent.ACTION_UP

        when (code) {
            KeyEvent.KEYCODE_BACK -> {
                if (up) onBackKey()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_INFO -> {
                if (up) showMenu()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                forwardKey(e, KeyEvent.KEYCODE_SPACE)
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (down) scrollPage(0, -(root.height * 0.8f).toInt()); return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (down) scrollPage(0, (root.height * 0.8f).toInt()); return true
            }
        }

        if (errorBox.visibility == View.VISIBLE || !cursorOn) return super.dispatchKeyEvent(e)

        // Видео на весь экран и курсор спрятан: ←/→ — перемотка плеером, ОК — пауза
        if (customView != null && !cursorVisible) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { forwardKey(e, code); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { forwardKey(e, KeyEvent.KEYCODE_SPACE); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) setCursorVisible(true); return true }
            }
        }

        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) moveCursor(-1, 0, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) moveCursor(1, 0, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) moveCursor(0, -1, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) moveCursor(0, 1, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (down && e.repeatCount == 0) {
                    okDownTime = e.eventTime
                    okLongFired = false
                    setCursorVisible(true)
                    cursor.isDown = true; cursor.invalidate()
                } else if (down && !okLongFired && e.eventTime - okDownTime > 700) {
                    okLongFired = true
                    cursor.isDown = false; cursor.invalidate()
                    showMenu()
                } else if (up) {
                    cursor.isDown = false; cursor.invalidate()
                    if (!okLongFired) clickAtCursor()
                    okLongFired = false
                    setCursorVisible(true)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun onBackKey() {
        if (customView != null) {
            customCallback?.onCustomViewHidden()
            web.evaluateJavascript("try{document.exitFullscreen&&document.exitFullscreen()}catch(e){}", null)
            return
        }
        if (errorBox.visibility == View.VISIBLE) {
            hideError()
            if (web.canGoBack()) web.goBack()
            return
        }
        if (web.canGoBack()) { web.goBack(); return }
        val now = SystemClock.uptimeMillis()
        if (now - lastBack < 2000) finish()
        else { lastBack = now; toast("Нажми «Назад» ещё раз, чтобы выйти") }
    }

    // ───────────────────────── Меню ─────────────────────────

    private fun showMenu() {
        val desktop = prefs.getBoolean("desktop", isTv)
        val items = arrayOf(
            "На главную",
            "Обновить страницу",
            "Назад",
            if (cursorOn) "Выключить курсор (управление стрелками)" else "Включить курсор",
            if (desktop) "Вид сайта: компьютерный → сделать мобильный" else "Вид сайта: мобильный → сделать компьютерный",
            "Адрес сайта…",
            "Проверить обновление (версия ${BuildConfig.VERSION_NAME})",
            "Выйти из приложения"
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { hideError(); loadHome() }
                    1 -> { hideError(); web.reload() }
                    2 -> if (web.canGoBack()) web.goBack()
                    3 -> {
                        cursorOn = !cursorOn
                        prefs.edit().putBoolean("cursor", cursorOn).apply()
                        setCursorVisible(cursorOn)
                        if (!cursorOn) web.requestFocus()
                    }
                    4 -> {
                        prefs.edit().putBoolean("desktop", !desktop).apply()
                        applyUserAgent(); web.reload()
                    }
                    5 -> askHomeUrl(first = false)
                    6 -> checkUpdate(manual = true)
                    7 -> finish()
                }
            }
            .show()
    }

    private fun askHomeUrl(first: Boolean) {
        val input = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "например: https://site.net"
            setText(homeUrl)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (first) "Какой сайт открывать?" else "Адрес сайта")
            .setMessage("Введи адрес сайта. Его можно поменять позже в меню.")
            .setView(box)
            .setPositiveButton("Открыть") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isEmpty()) { if (first) askHomeUrl(true); return@setPositiveButton }
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                homeUrl = url
                homeDomain = AdBlock.baseDomain(Uri.parse(url).host)
                prefs.edit().putString("home", url).apply()
                hideError()
                web.loadUrl(url)
            }
            .setCancelable(!first)
        if (!first) b.setNegativeButton("Отмена", null)
        b.show()
        input.requestFocus()
    }

    // ───────────────────────── Обновления ─────────────────────────

    private fun refreshRemoteConfig() {
        Thread {
            val txt = Updater.fetchText(Updater.configUrl) ?: return@Thread
            try { JSONObject(txt) } catch (_: Exception) { return@Thread }
            prefs.edit().putString("remoteConfig", txt).apply()
            AdBlock.load(txt)
        }.start()
    }

    private fun checkUpdate(manual: Boolean) {
        if (manual) toast("Проверяю обновления…")
        Thread {
            val rel = Updater.latestRelease()
            ui.post {
                if (isFinishing) return@post
                when {
                    rel == null -> if (manual) toast("Не удалось проверить обновления")
                    rel.code <= BuildConfig.VERSION_CODE -> if (manual) toast("У тебя последняя версия")
                    else -> offerUpdate(rel)
                }
            }
        }.start()
    }

    private fun offerUpdate(rel: Updater.Release) {
        val notes = rel.notes.take(600)
        AlertDialog.Builder(this)
            .setTitle("Новая версия 1.${rel.code}")
            .setMessage((if (notes.isNotBlank()) "$notes\n\n" else "") + "Установить сейчас? Вход и настройки сохранятся.")
            .setNegativeButton("Позже", null)
            .setPositiveButton("Установить") { _, _ -> downloadAndInstall(rel) }
            .show()
    }

    private fun downloadAndInstall(rel: Updater.Release) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Разреши приложению устанавливать обновления и вернись назад", long = true)
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
            }
            updateChecked = false // проверим снова при возвращении
            return
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(16), dp(24), dp(8)); addView(bar) }
        val dlg = AlertDialog.Builder(this).setTitle("Загрузка обновления…").setView(box).setCancelable(false).show()
        Thread {
            val f = File(cacheDir, "update.apk")
            val ok = Updater.download(rel.apkUrl, f) { p -> ui.post { bar.progress = p } }
            ui.post {
                dlg.dismiss()
                if (!ok) { toast("Не удалось скачать обновление"); return@post }
                try {
                    Updater.install(this, f)
                } catch (e: Exception) {
                    toast("Ошибка установки: ${e.message}", long = true)
                }
            }
        }.start()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action != Updater.ACTION_INSTALL) return
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { startActivity(confirm) } catch (_: Exception) {}
                }
            }
            PackageInstaller.STATUS_SUCCESS -> toast("Обновлено")
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                toast("Обновление не установлено. $msg", long = true)
            }
        }
    }
}
