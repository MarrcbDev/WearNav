package com.marrdev.wearnav

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
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController

class MainActivity : Activity() {

    companion object {
        // GeckoRuntime solo puede crearse una vez por proceso
        var sRuntime: GeckoRuntime? = null

        const val REQ_VOICE = 11
        const val IDLE_KILL_MS = 90_000L

        // nombre, action del formulario de la home, prefijo de búsqueda
        val ENGINES = listOf(
            Triple("DuckDuckGo", "https://duckduckgo.com/", "https://duckduckgo.com/?q="),
            Triple("Google", "https://www.google.com/search", "https://www.google.com/search?q="),
            Triple("Brave", "https://search.brave.com/search", "https://search.brave.com/search?q=")
        )

        // Densidad virtual: menor = más contenido por pantalla. 1.5 deja ~300 px CSS de ancho en el reloj.
        val DENSITIES = floatArrayOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    }

    private lateinit var store: Store
    private lateinit var root: FrameLayout
    private lateinit var webHolder: FrameLayout
    private lateinit var ring: RingProgressView
    private lateinit var bar: LinearLayout
    private lateinit var reloadBtn: TextView
    private lateinit var panel: FrameLayout

    private var geckoView: GeckoView? = null
    private var session: GeckoSession? = null

    private var curUrl = ""
    private var curTitle = ""
    private var canBack = false
    private var canFwd = false
    private var loading = false
    private var lastScrollY = 0
    private var barVisible = true
    private var fullscreen = false
    private var stopped = true
    private var crashMode = false
    private var lastTitle = ""

    private val handler = Handler(Looper.getMainLooper())
    private val idleKill = Runnable { shutdownEngine() }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("crash", MODE_PRIVATE)
        val prev = prefs.getString("log", null)
        if (prev != null) {
            crashMode = true
            showCrash("Cierre anterior:\n" + prev)
            return
        }
        try {
            initUi()
        } catch (t: Throwable) {
            android.util.Log.e("WearNav", "init failed", t)
            crashMode = true
            showCrash("Error al iniciar:\n" + android.util.Log.getStackTraceString(t).take(5000))
        }
    }

    private fun initUi() {
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
        createSession()
        val start = intent?.data?.toString() ?: store.lastUrl
        session?.loadUri(if (start.isBlank()) homeUrl() else start)
    }

    private fun showCrash(msg: String) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(36), dp(30), dp(40))
        }
        col.addView(TextView(this).apply {
            text = "WearNav falló"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = msg
            textSize = 9f
            setTextColor(0xFFFF8A80.toInt())
            setPadding(0, dp(8), 0, dp(8))
        })
        col.addView(TextView(this).apply {
            text = "Continuar"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable().apply {
                setColor(0xFF1F3A5F.toInt())
                cornerRadius = dp(22).toFloat()
            }
            setOnClickListener {
                getSharedPreferences("crash", MODE_PRIVATE).edit().remove("log").apply()
                recreate()
            }
        })
        val sv = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            addView(col)
        }
        setContentView(sv)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (crashMode) return
        intent.data?.toString()?.let { load(it) }
    }

    override fun onStart() {
        super.onStart()
        if (crashMode) return
        stopped = false
        handler.removeCallbacks(idleKill)
        session?.setActive(true)
    }

    override fun onStop() {
        super.onStop()
        if (crashMode) return
        stopped = true
        session?.setActive(false)
        // Gecko es pesado: si no vuelves en 90 s, cerramos el proceso entero (la URL queda guardada)
        if (store.autoFree) handler.postDelayed(idleKill, IDLE_KILL_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleKill)
        super.onDestroy()
    }

    /** Cierra la app y mata el proceso para liberar toda la RAM de Gecko. */
    private fun shutdownEngine() {
        if (!stopped) return
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            crashMode -> super.onBackPressed()
            fullscreen -> session?.exitFullScreen()
            panel.visibility == View.VISIBLE -> closePanel()
            canBack -> session?.goBack()
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ Gecko

    private fun runtime(): GeckoRuntime {
        sRuntime?.let { return it }
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .remoteDebuggingEnabled(false)
            .consoleOutput(false)
            .displayDensityOverride(DENSITIES[store.densityIdx.coerceIn(0, DENSITIES.lastIndex)])
            .build()
        val rt = GeckoRuntime.create(applicationContext, settings)
        sRuntime = rt
        applyRuntimeSettings(rt)
        return rt
    }

    private fun applyRuntimeSettings(rt: GeckoRuntime) {
        val s = rt.settings
        s.setPreferredColorScheme(
            if (store.dark) GeckoRuntimeSettings.COLOR_SCHEME_DARK else GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        )
        s.setFontSizeFactor(store.textZoom / 100f)
        val mask = ContentBlocking.AntiTracking.AD or ContentBlocking.AntiTracking.ANALYTIC or
            ContentBlocking.AntiTracking.SOCIAL or ContentBlocking.AntiTracking.CRYPTOMINING
        s.contentBlocking.setAntiTracking(if (store.adblock) mask else ContentBlocking.AntiTracking.NONE)
    }

    private fun applySessionSettings(s: GeckoSession) {
        if (store.desktop) {
            s.settings.setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            s.settings.setViewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
        } else {
            s.settings.setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            s.settings.setViewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        }
    }

    private fun createSession() {
        val rt = runtime()
        val gv = GeckoView(this)
        val s = GeckoSession()
        applySessionSettings(s)

        s.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onTitleChange(sess: GeckoSession, title: String?) {
                curTitle = title ?: ""
            }

            override fun onFullScreen(sess: GeckoSession, fs: Boolean) {
                setFullscreen(fs)
            }

            override fun onCrash(sess: GeckoSession) {
                recoverSession()
            }

            override fun onKill(sess: GeckoSession) {
                recoverSession()
            }
        })

        s.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(sess: GeckoSession, url: String) {
                loading = true
                reloadBtn.text = "✕"
                showBar(true)
            }

            override fun onPageStop(sess: GeckoSession, success: Boolean) {
                loading = false
                reloadBtn.text = "↻"
                ring.progress = 0
                val u = curUrl
                if (success && (u.startsWith("http://") || u.startsWith("https://"))) {
                    store.addHistory(curTitle.ifBlank { u }, u)
                }
            }

            override fun onProgressChange(sess: GeckoSession, progress: Int) {
                ring.progress = if (progress >= 100) 0 else progress
            }
        })

        s.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                sess: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                curUrl = url ?: ""
                store.lastUrl =
                    if (curUrl.isBlank() || isHome(curUrl) || curUrl.startsWith("about:")) "" else curUrl
            }

            override fun onCanGoBack(sess: GeckoSession, can: Boolean) {
                canBack = can
            }

            override fun onCanGoForward(sess: GeckoSession, can: Boolean) {
                canFwd = can
            }

            // Enlaces target=_blank: se abren en la misma pestaña (una sola sesión = menos RAM)
            override fun onNewSession(sess: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                sess.loadUri(uri)
                return null
            }
        })

        s.setScrollDelegate(object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(sess: GeckoSession, scrollX: Int, scrollY: Int) {
                val d = scrollY - lastScrollY
                lastScrollY = scrollY
                if (scrollY < 8 || d < -6) showBar(true) else if (d > 6) showBar(false)
            }
        })

        s.open(rt)
        gv.setSession(s)
        webHolder.addView(gv, FrameLayout.LayoutParams(-1, -1))
        geckoView = gv
        session = s
    }

    private fun closeSession() {
        val gv = geckoView
        val s = session
        if (gv != null) {
            gv.releaseSession()
            webHolder.removeView(gv)
        }
        s?.close()
        geckoView = null
        session = null
        canBack = false
        canFwd = false
    }

    /** El proceso de contenido murió (crash o poca RAM): recreamos la sesión y recargamos. */
    private fun recoverSession() {
        handler.post {
            val u = if (curUrl.isBlank() || isHome(curUrl)) store.lastUrl else curUrl
            closeSession()
            createSession()
            session?.loadUri(if (u.isBlank()) homeUrl() else u)
        }
    }

    private fun setFullscreen(fs: Boolean) {
        fullscreen = fs
        if (fs) {
            bar.visibility = View.GONE
        } else {
            bar.visibility = View.VISIBLE
            bar.alpha = 1f
            bar.translationY = 0f
            barVisible = true
        }
    }

    // ------------------------------------------------------------------ navegación

    private fun isHome(u: String?): Boolean = u != null && u.startsWith("data:text/html")

    private fun load(url: String) {
        if (session == null) createSession()
        session?.loadUri(url)
        closePanel()
    }

    private fun goBack() {
        if (canBack) session?.goBack() else load(homeUrl())
    }

    private fun reloadOrStop() {
        if (loading) session?.stop() else session?.reload()
    }

    private fun toUrl(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return homeUrl()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (!t.contains(' ') && (t.contains('.') || t.startsWith("localhost"))) return "https://" + t
        val e = ENGINES[store.engine.coerceIn(0, ENGINES.lastIndex)]
        return e.third + Uri.encode(t)
    }

    private fun submit(text: String) {
        load(toUrl(text))
    }

    // ------------------------------------------------------------------ página de inicio

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun homeUrl(): String = "data:text/html;charset=utf-8," + Uri.encode(homeHtml())

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
        if (show == barVisible || fullscreen) return
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
        showPanel("Menú") { l ->
            row(l, "🏠  Inicio") { load(homeUrl()) }
            row(l, "⭐  Favoritos") { openList("Favoritos", store.bookmarks(), true) }
            row(l, "🕘  Historial") { openList("Historial", store.history(), false) }
            val url = curUrl
            if (url.startsWith("http")) {
                val saved = store.hasBookmark(url)
                row(l, if (saved) "★  Quitar favorito" else "☆  Guardar favorito") { toggleBookmark() }
            }
            if (canFwd) row(l, "▶  Adelante") {
                closePanel()
                session?.goForward()
            }
            row(l, "🧹  Liberar RAM  (" + totalPssMb() + " MB)") { freeRam() }
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
        val u = curUrl
        if (!u.startsWith("http")) return
        if (store.hasBookmark(u)) {
            store.removeBookmark(u)
            toast("Favorito quitado")
        } else {
            store.addBookmark(curTitle.ifBlank { u }, u)
            toast("Guardado ⭐")
        }
        closePanel()
    }

    private fun openSettings() {
        showPanel("Ajustes") { l ->
            row(l, "🚫  Anuncios y rastreo: " + onoff(store.adblock)) {
                store.adblock = !store.adblock
                sRuntime?.let { applyRuntimeSettings(it) }
                openSettings()
            }
            row(l, "🖥  Modo escritorio: " + onoff(store.desktop)) {
                store.desktop = !store.desktop
                session?.let {
                    applySessionSettings(it)
                    it.reload()
                }
                openSettings()
            }
            row(l, "🌙  Modo oscuro web: " + onoff(store.dark)) {
                store.dark = !store.dark
                sRuntime?.let { applyRuntimeSettings(it) }
                session?.reload()
                openSettings()
            }
            row(l, "⭕  Ajuste redondo: " + onoff(store.roundFit)) {
                store.roundFit = !store.roundFit
                applyFit()
                openSettings()
            }
            val dens = DENSITIES[store.densityIdx.coerceIn(0, DENSITIES.lastIndex)]
            row(l, "↔  Escala de página: " + dens + "x  (menor = más contenido; reinicia la app)") {
                store.densityIdx = (store.densityIdx + 1) % DENSITIES.size
                toast("Reiniciando para aplicar…")
                handler.postDelayed({
                    finishAndRemoveTask()
                    Process.killProcess(Process.myPid())
                }, 1200)
            }
            row(l, "🔎  Buscador: " + ENGINES[store.engine.coerceIn(0, ENGINES.lastIndex)].first) {
                store.engine = (store.engine + 1) % ENGINES.size
                openSettings()
            }
            row(l, "🔠  Texto +  (" + store.textZoom + "%)") {
                store.textZoom = (store.textZoom + 20).coerceAtMost(240)
                sRuntime?.let { applyRuntimeSettings(it) }
                openSettings()
            }
            row(l, "🔡  Texto −  (" + store.textZoom + "%)") {
                store.textZoom = (store.textZoom - 20).coerceAtLeast(60)
                sRuntime?.let { applyRuntimeSettings(it) }
                openSettings()
            }
            row(l, "♻  Cerrar motor al salir (90 s): " + onoff(store.autoFree)) {
                store.autoFree = !store.autoFree
                openSettings()
            }
            row(l, "🗑  Borrar datos y caché") {
                clearData()
                closePanel()
            }
        }
    }

    private fun clearData() {
        sRuntime?.storageController?.clearData(StorageController.ClearFlags.ALL)
        store.clearHistory()
        toast("Datos borrados")
    }

    /** RAM total de WearNav: proceso principal + procesos de contenido de Gecko. */
    private fun totalPssMb(): Int {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pids = am.runningAppProcesses
                ?.filter { it.processName.startsWith(packageName) }
                ?.map { it.pid }
                ?.toIntArray() ?: intArrayOf(Process.myPid())
            var kb = 0
            for (m in am.getProcessMemoryInfo(pids)) kb += m.totalPss
            kb / 1024
        } catch (e: Throwable) {
            0
        }
    }

    /** Recrea la sesión (suelta la memoria de la página) y recarga la URL actual. */
    private fun freeRam() {
        val u = curUrl
        closeSession()
        createSession()
        session?.loadUri(if (u.isBlank()) homeUrl() else u)
        System.gc()
        closePanel()
        toast("Sesión reiniciada")
    }
}
