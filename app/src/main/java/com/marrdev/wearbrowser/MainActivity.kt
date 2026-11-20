package com.marrdev.wearbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

class MainActivity : Activity() {

    companion object {
        const val HOME_HOST = "wb.home"
        const val HOME = "https://wb.home/"
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val REQ_VOICE = 11
        const val IDLE_DESTROY_MS = 45_000L

        // nombre, action del formulario de la home, prefijo de búsqueda
        val ENGINES = listOf(
            Triple("DuckDuckGo", "https://duckduckgo.com/", "https://duckduckgo.com/?q="),
            Triple("Google", "https://www.google.com/search", "https://www.google.com/search?q="),
            Triple("Brave", "https://search.brave.com/search", "https://search.brave.com/search?q=")
        )
        val WIDTHS = intArrayOf(0, 360, 480)

        const val READER_JS =
            """(function(){var r=document.querySelector('article')||document.querySelector('main')||document.body;var n=r.querySelectorAll('h1,h2,h3,p,li');var out=document.createDocumentFragment();var c=0;for(var i=0;i<n.length&&c<500;i++){var t=(n[i].innerText||'').trim();if(t.length<3)continue;var tg=n[i].tagName.toLowerCase();var e=document.createElement(tg==='li'?'p':tg);e.textContent=(tg==='li'?'• ':'')+t;out.appendChild(e);c++;}document.head.innerHTML='<meta name="viewport" content="width=device-width"><style>body{background:#000;color:#ddd;font:18px/1.55 sans-serif;padding:0 8px 80px;margin:0}h1,h2,h3{color:#fff;line-height:1.25}h1{font-size:22px}h2{font-size:20px}h3{font-size:19px}</style>';document.body.innerHTML='';document.body.removeAttribute('style');document.body.removeAttribute('class');document.body.appendChild(out);window.scrollTo(0,0);})();"""
    }

    private lateinit var store: Store
    private lateinit var root: FrameLayout
    private lateinit var webHolder: FrameLayout
    private lateinit var ring: RingProgressView
    private lateinit var bar: LinearLayout
    private lateinit var reloadBtn: TextView
    private lateinit var panel: FrameLayout

    private var web: WebView? = null
    private var savedState: Bundle? = null
    private var barVisible = true
    private var loading = false
    private var pageError = false
    private var stopped = true
    private var lastTitle = ""
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private val handler = Handler(Looper.getMainLooper())
    private val idleDestroy = Runnable { destroyWeb(true) }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webHolder = FrameLayout(this)
        root.addView(webHolder, FrameLayout.LayoutParams(-1, -1))
        ring = RingProgressView(this)
        root.addView(ring, FrameLayout.LayoutParams(-1, -1))
        buildBar()
        panel = FrameLayout(this).apply {
            setBackgroundColor(0xF2000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        applyFit()
        createWeb()
        val start = intent?.data?.toString() ?: store.lastUrl
        web?.loadUrl(if (start.isBlank()) HOME else start)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.toString()?.let { load(it) }
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        handler.removeCallbacks(idleDestroy)
        if (web == null) ensureWeb(true) else {
            web?.onResume()
            web?.resumeTimers()
        }
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        web?.let {
            it.onPause()
            it.pauseTimers()
        }
        // Si el usuario no vuelve en 45 s, liberamos el WebView completo (la RAM baja mucho)
        handler.postDelayed(idleDestroy, IDLE_DESTROY_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleDestroy)
        web?.let {
            webHolder.removeView(it)
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND && stopped) destroyWeb(true)
        else if (level >= TRIM_MEMORY_RUNNING_LOW) web?.clearCache(false)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        web?.clearCache(false)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            customView != null -> hideCustom()
            panel.visibility == View.VISIBLE -> closePanel()
            web?.canGoBack() == true -> web?.goBack()
            else -> super.onBackPressed()
        }
    }

    // Corona/bisel giratorio (en relojes que lo tengan) = scroll
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL && e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            val d = -e.getAxisValue(MotionEvent.AXIS_SCROLL) * dp(40)
            web?.scrollBy(0, d.toInt())
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWeb() {
        val w = WebView(this)
        w.setBackgroundColor(Color.BLACK)
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)
        applySettings(w)

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, r: WebResourceRequest): WebResourceResponse? {
                val host = r.url.host
                if (host == HOME_HOST) {
                    return WebResourceResponse(
                        "text/html", "utf-8", ByteArrayInputStream(homeHtml().toByteArray(Charsets.UTF_8))
                    )
                }
                if (store.adblock && AdBlock.blocked(host)) return AdBlock.empty()
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme
                if (s == "http" || s == "https" || s == "about" || s == "data" || s == "blob") return false
                toast("Enlace externo bloqueado")
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                loading = true
                pageError = false
                reloadBtn.text = "✕"
                showBar(true)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loading = false
                reloadBtn.text = "↻"
                ring.progress = 0
                if (url == null) return
                val home = isHome(url)
                store.lastUrl = if (home || url.startsWith("data:") || url == "about:blank") "" else url
                if (!home && !pageError && (url.startsWith("http://") || url.startsWith("https://"))) {
                    store.addHistory(view.title ?: url, url)
                    val vw = WIDTHS[store.widthIdx.coerceIn(0, WIDTHS.lastIndex)]
                    if (vw > 0) {
                        view.evaluateJavascript(
                            "(function(){var m=document.querySelector('meta[name=viewport]');" +
                                "if(!m){m=document.createElement('meta');m.name='viewport';" +
                                "(document.head||document.documentElement).appendChild(m);}" +
                                "m.content='width=" + vw + "';})()", null
                        )
                    }
                }
            }

            override fun onReceivedError(view: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) {
                    pageError = true
                    view.loadDataWithBaseURL(
                        null, errorHtml(r.url.toString(), e.description.toString()),
                        "text/html", "utf-8", null
                    )
                }
            }

            // Si el proceso del renderizador muere (poca RAM), recreamos el WebView en vez de cerrar la app
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                handler.post {
                    val u = store.lastUrl
                    destroyWeb(false)
                    createWeb()
                    web?.loadUrl(if (u.isBlank()) HOME else u)
                }
                return true
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                ring.progress = if (newProgress >= 100) 0 else newProgress
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCb = callback
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                bar.visibility = View.GONE
            }

            override fun onHideCustomView() {
                hideCustom()
            }
        }

        w.setDownloadListener { _, _, _, _, _ -> toast("Descargas no disponibles") }

        w.setOnScrollChangeListener { _, _, y, _, oy ->
            val d = y - oy
            if (y < 8 || d < -6) showBar(true) else if (d > 6) showBar(false)
        }

        webHolder.addView(w, FrameLayout.LayoutParams(-1, -1))
        web = w
    }

    private fun applySettings(w: WebView) {
        val s = w.settings
        s.textZoom = store.textZoom
        s.blockNetworkImage = store.noImages
        s.userAgentString = if (store.desktop) DESKTOP_UA else null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, store.dark)
        }
    }

    private fun ensureWeb(restore: Boolean) {
        if (web != null) return
        createWeb()
        val s = savedState
        savedState = null
        if (restore) {
            if (s != null) web?.restoreState(s) else {
                val u = store.lastUrl
                web?.loadUrl(if (u.isBlank()) HOME else u)
            }
        }
    }

    /** Destruye el WebView y guarda su estado (historial de navegación) para recrearlo luego. */
    private fun destroyWeb(save: Boolean) {
        val w = web ?: return
        if (save) {
            val b = Bundle()
            w.saveState(b)
            savedState = b
        } else savedState = null
        webHolder.removeView(w)
        w.stopLoading()
        w.webChromeClient = null
        w.destroy()
        web = null
    }

    private fun hideCustom() {
        customView?.let { root.removeView(it) }
        customCb?.onCustomViewHidden()
        customView = null
        customCb = null
        bar.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ navegación

    private fun isHome(u: String?): Boolean = u != null && Uri.parse(u).host == HOME_HOST

    private fun load(url: String) {
        if (web == null) {
            createWeb()
            savedState = null
        }
        web?.loadUrl(url)
        closePanel()
    }

    private fun goBack() {
        val w = web ?: return
        if (w.canGoBack()) w.goBack() else load(HOME)
    }

    private fun reloadOrStop() {
        val w = web ?: return
        if (loading) w.stopLoading() else w.reload()
    }

    private fun toUrl(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return HOME
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (!t.contains(' ') && (t.contains('.') || t.startsWith("localhost"))) return "https://" + t
        val e = ENGINES[store.engine.coerceIn(0, ENGINES.lastIndex)]
        return e.third + Uri.encode(t)
    }

    private fun submit(text: String) {
        load(toUrl(text))
    }

    // ------------------------------------------------------------------ páginas internas

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun homeHtml(): String {
        val e = ENGINES[store.engine.coerceIn(0, ENGINES.lastIndex)]
        val tiles = store.bookmarks().take(8).joinToString("") {
            "<a href=\"" + esc(it.url) + "\">" + esc(it.title.ifBlank { it.url }) + "</a>"
        }
        return "<!doctype html><html><head><meta charset=utf-8>" +
            "<meta name=viewport content='width=device-width,initial-scale=1'><style>" +
            "body{margin:0;background:#000;color:#fff;font-family:sans-serif;text-align:center;padding:24% 13% 32%}" +
            "h1{font-size:19px;margin:0 0 10px;font-weight:500}" +
            "form{margin:0 0 12px}" +
            "input{width:100%;box-sizing:border-box;padding:12px;border-radius:24px;border:0;background:#2b2d31;color:#fff;font-size:16px;text-align:center}" +
            "a{display:block;margin:6px 0;padding:11px;border-radius:20px;background:#1f3a5f;color:#fff;text-decoration:none;font-size:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}" +
            "</style></head><body><h1>" + esc(e.first) + "</h1>" +
            "<form action=\"" + e.second + "\"><input name=q placeholder=\"Buscar\" autocomplete=off></form>" +
            tiles + "</body></html>"
    }

    private fun errorHtml(url: String, msg: String): String =
        "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<style>body{background:#000;color:#ddd;font-family:sans-serif;text-align:center;padding:26% 14%}" +
            "a{display:inline-block;margin-top:14px;padding:12px 22px;border-radius:22px;background:#1f3a5f;color:#fff;text-decoration:none}" +
            "</style></head><body><h3>No se pudo cargar</h3><p>" + esc(msg) + "</p>" +
            "<a href=\"" + esc(url) + "\">Reintentar</a></body></html>"

    // ------------------------------------------------------------------ UI: barra inferior

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun applyFit() {
        val p = if (store.roundFit && resources.configuration.isScreenRound)
            (resources.displayMetrics.widthPixels * 0.07f).toInt() else 0
        webHolder.setPadding(p, p, p, p)
    }

    private fun buildBar() {
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                setColor(0xE61F2124.toInt())
                cornerRadius = dp(40).toFloat()
            }
        }

        fun btn(label: String, action: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener { action() }
        }

        bar.addView(btn("◀") { goBack() })
        reloadBtn = btn("↻") { reloadOrStop() }
        bar.addView(reloadBtn)
        bar.addView(btn("🔍") { openSearch() })
        bar.addView(btn("☰") { openMenu() })

        root.addView(
            bar,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(14)
            }
        )
    }

    private fun showBar(show: Boolean) {
        if (show == barVisible || customView != null) return
        barVisible = show
        bar.animate().cancel()
        if (show) {
            bar.visibility = View.VISIBLE
            bar.animate().alpha(1f).translationY(0f).setDuration(140).start()
        } else {
            bar.animate().alpha(0f).translationY(dp(60).toFloat()).setDuration(140)
                .withEndAction { if (!barVisible) bar.visibility = View.GONE }.start()
        }
    }

    // ------------------------------------------------------------------ UI: paneles

    private fun showPanel(title: String, build: (LinearLayout) -> Unit) {
        val prevY = if (title == lastTitle && panel.visibility == View.VISIBLE)
            (panel.getChildAt(0) as? ScrollView)?.scrollY ?: 0 else 0
        lastTitle = title
        panel.removeAllViews()

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(40), dp(26), dp(60))
        }
        list.addView(TextView(this).apply {
            text = title
            setTextColor(0xFF9AA0A6.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        })
        build(list)
        scroll.addView(list)
        panel.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        panel.visibility = View.VISIBLE
        scroll.post { scroll.scrollTo(0, prevY) }
    }

    private fun closePanel() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(panel.windowToken, 0)
        panel.visibility = View.GONE
        panel.removeAllViews()
        lastTitle = ""
    }

    private fun row(parent: LinearLayout, label: String, onClick: () -> Unit): TextView {
        val t = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable().apply {
                setColor(0xFF2B2D31.toInt())
                cornerRadius = dp(22).toFloat()
            }
            setOnClickListener { onClick() }
        }
        parent.addView(t, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return t
    }

    private fun onoff(b: Boolean) = if (b) "ON" else "OFF"

    private fun openSearch() {
        showPanel("Buscar / URL") { l ->
            val et = EditText(this).apply {
                hint = "Buscar o escribir URL"
                setHintTextColor(0xFF80868B.toInt())
                setTextColor(Color.WHITE)
                textSize = 15f
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_GO
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    setColor(0xFF1F2124.toInt())
                    setStroke(dp(1), 0xFF4FC3F7.toInt())
                    cornerRadius = dp(22).toFloat()
                }
                setOnEditorActionListener { v, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                        submit(v.text.toString())
                        true
                    } else false
                }
            }
            l.addView(et, LinearLayout.LayoutParams(-1, -2))
            row(l, "➡  Ir") { submit(et.text.toString()) }
            row(l, "🎤  Voz") { startVoice() }
            store.bookmarks().take(4).forEach { b ->
                row(l, "⭐  " + b.title.ifBlank { b.url }) { load(b.url) }
            }
            et.postDelayed({
                et.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }, 200)
        }
    }

    private fun startVoice() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_VOICE)
        } catch (e: ActivityNotFoundException) {
            toast("Sin reconocimiento de voz")
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { submit(it) }
        }
    }

    private fun openMenu() {
        val w = web
        showPanel("Menú") { l ->
            row(l, "🏠  Inicio") { load(HOME) }
            row(l, "⭐  Favoritos") { openList("Favoritos", store.bookmarks(), true) }
            row(l, "🕘  Historial") { openList("Historial", store.history(), false) }
            val url = w?.url
            if (url != null && !isHome(url) && url.startsWith("http")) {
                val saved = store.hasBookmark(url)
                row(l, if (saved) "★  Quitar favorito" else "☆  Guardar favorito") { toggleBookmark() }
            }
            if (w?.canGoForward() == true) row(l, "▶  Adelante") {
                closePanel()
                web?.goForward()
            }
            row(l, "📖  Modo lectura") {
                closePanel()
                web?.evaluateJavascript(READER_JS, null)
            }
            row(l, "🧹  Liberar RAM") { freeRam() }
            row(l, "⚙  Ajustes") { openSettings() }
        }
    }

    private fun openList(title: String, items: List<Entry>, bookmarks: Boolean) {
        showPanel(title) { l ->
            if (items.isEmpty()) row(l, "(vacío)") {}
            items.forEach { e ->
                val t = row(l, e.title.ifBlank { e.url }) { load(e.url) }
                if (bookmarks) t.setOnLongClickListener {
                    store.removeBookmark(e.url)
                    openList(title, store.bookmarks(), true)
                    true
                }
            }
            if (bookmarks && items.isNotEmpty()) row(l, "Mantén pulsado para borrar") {}
            if (!bookmarks && items.isNotEmpty()) row(l, "🗑  Borrar historial") {
                store.clearHistory()
                openList(title, store.history(), false)
            }
        }
    }

    private fun toggleBookmark() {
        val w = web ?: return
        val u = w.url ?: return
        if (store.hasBookmark(u)) {
            store.removeBookmark(u)
            toast("Favorito quitado")
        } else {
            store.addBookmark(w.title ?: u, u)
            toast("Guardado ⭐")
        }
        closePanel()
    }

    private fun openSettings() {
        showPanel("Ajustes") { l ->
            row(l, "🚫  Anuncios: " + onoff(store.adblock)) {
                store.adblock = !store.adblock
                openSettings()
            }
            row(l, "🖼  Sin imágenes: " + onoff(store.noImages)) {
                store.noImages = !store.noImages
                web?.let { applySettings(it); if (!store.noImages) it.reload() }
                openSettings()
            }
            row(l, "🖥  Modo escritorio: " + onoff(store.desktop)) {
                store.desktop = !store.desktop
                web?.let { applySettings(it); it.reload() }
                openSettings()
            }
            row(l, "🌙  Modo oscuro web: " + onoff(store.dark)) {
                store.dark = !store.dark
                web?.let { applySettings(it); it.reload() }
                openSettings()
            }
            row(l, "⭕  Ajuste redondo: " + onoff(store.roundFit)) {
                store.roundFit = !store.roundFit
                applyFit()
                openSettings()
            }
            val vw = WIDTHS[store.widthIdx.coerceIn(0, WIDTHS.lastIndex)]
            row(l, "↔  Ancho virtual: " + (if (vw == 0) "Auto" else vw.toString())) {
                store.widthIdx = (store.widthIdx + 1) % WIDTHS.size
                web?.reload()
                openSettings()
            }
            row(l, "🔎  Buscador: " + ENGINES[store.engine.coerceIn(0, ENGINES.lastIndex)].first) {
                store.engine = (store.engine + 1) % ENGINES.size
                openSettings()
            }
            row(l, "🔠  Texto +  (" + store.textZoom + "%)") {
                store.textZoom = (store.textZoom + 20).coerceAtMost(240)
                web?.let { applySettings(it) }
                openSettings()
            }
            row(l, "🔡  Texto −  (" + store.textZoom + "%)") {
                store.textZoom = (store.textZoom - 20).coerceAtLeast(60)
                web?.let { applySettings(it) }
                openSettings()
            }
            row(l, "🗑  Borrar datos y caché") {
                clearData()
                closePanel()
            }
        }
    }

    private fun clearData() {
        web?.apply {
            clearCache(true)
            clearHistory()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        store.clearHistory()
        toast("Datos borrados")
    }

    private fun freeRam() {
        web?.clearCache(false)
        System.gc()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val kb = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss
        toast("RAM app: " + (kb / 1024) + " MB")
        closePanel()
    }
}
