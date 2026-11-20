package com.marrdev.wearbrowser

import android.content.Context

data class Entry(val title: String, val url: String)

/** Preferencias, favoritos e historial. Todo en SharedPreferences (sin base de datos = poca RAM). */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("wb", Context.MODE_PRIVATE)

    var lastUrl: String
        get() = p.getString("last", "") ?: ""
        set(v) = p.edit().putString("last", v).apply()

    var textZoom: Int
        get() = p.getInt("zoom", 100)
        set(v) = p.edit().putInt("zoom", v).apply()

    var adblock: Boolean
        get() = p.getBoolean("adblock", true)
        set(v) = p.edit().putBoolean("adblock", v).apply()

    var noImages: Boolean
        get() = p.getBoolean("noimg", false)
        set(v) = p.edit().putBoolean("noimg", v).apply()

    var desktop: Boolean
        get() = p.getBoolean("desktop", false)
        set(v) = p.edit().putBoolean("desktop", v).apply()

    var dark: Boolean
        get() = p.getBoolean("dark", false)
        set(v) = p.edit().putBoolean("dark", v).apply()

    var roundFit: Boolean
        get() = p.getBoolean("round", true)
        set(v) = p.edit().putBoolean("round", v).apply()

    var engine: Int
        get() = p.getInt("engine", 0)
        set(v) = p.edit().putInt("engine", v).apply()

    var widthIdx: Int
        get() = p.getInt("widthIdx", 0)
        set(v) = p.edit().putInt("widthIdx", v).apply()

    // ---- listas ----
    private fun read(key: String): MutableList<Entry> {
        val raw = p.getString(key, "") ?: ""
        val out = ArrayList<Entry>()
        for (line in raw.split('\n')) {
            val i = line.indexOf('\t')
            if (i >= 0 && i < line.length - 1) out.add(Entry(line.substring(0, i), line.substring(i + 1)))
        }
        return out
    }

    private fun write(key: String, l: List<Entry>) {
        p.edit().putString(key, l.joinToString("\n") { it.title + "\t" + it.url }).apply()
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim().take(80)

    fun bookmarks(): List<Entry> = read("bm")
    fun hasBookmark(url: String) = read("bm").any { it.url == url }

    fun addBookmark(title: String, url: String) {
        val l = read("bm")
        l.removeAll { it.url == url }
        l.add(0, Entry(clean(title), url))
        write("bm", l.take(60))
    }

    fun removeBookmark(url: String) {
        val l = read("bm")
        l.removeAll { it.url == url }
        write("bm", l)
    }

    fun history(): List<Entry> = read("hist")

    fun addHistory(title: String, url: String) {
        val l = read("hist")
        l.removeAll { it.url == url }
        l.add(0, Entry(clean(title), url))
        write("hist", l.take(40))
    }

    fun clearHistory() = p.edit().remove("hist").apply()
}
