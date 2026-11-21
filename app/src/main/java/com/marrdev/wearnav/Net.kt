package com.marrdev.wearnav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.HttpResponseCache
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.CookieHandler
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class Resp(val url: String, val type: String, val body: ByteArray, val truncated: Boolean)

object Net {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    @Volatile
    var current: HttpURLConnection? = null

    fun initCache(ctx: Context) {
        try {
            HttpResponseCache.install(File(ctx.cacheDir, "http"), 8L * 1024 * 1024)
        } catch (_: IOException) {
        }
    }

    fun clearCache(ctx: Context) {
        try {
            HttpResponseCache.getInstalled()?.delete()
        } catch (_: IOException) {
        }
        initCache(ctx)
    }

    fun resetCookies() {
        CookieHandler.setDefault(java.net.CookieManager(null, CookiePolicy.ACCEPT_ALL))
    }

    fun get(url0: String, accept: String, max: Int, track: Boolean): Resp {
        var url = url0
        for (i in 0 until 6) {
            val c = URL(url).openConnection() as HttpURLConnection
            if (track) current = c
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = 12000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", accept)
            c.setRequestProperty("Accept-Language", "es,en;q=0.8")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("Redirección inválida")
                url = URL(URL(url), loc).toString()
                continue
            }
            if (code >= 400) {
                c.disconnect()
                throw IOException("Error HTTP " + code)
            }
            val type = c.contentType ?: ""
            val out = ByteArrayOutputStream()
            var truncated = false
            c.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() >= max) {
                        truncated = true
                        break
                    }
                }
            }
            c.disconnect()
            return Resp(url, type, out.toByteArray(), truncated)
        }
        throw IOException("Demasiadas redirecciones")
    }
}

/** Imágenes: se decodifican reducidas (RGB_565) y con caché LRU pequeña. */
object Imgs {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(8L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    fun peek(url: String): Bitmap? = cache.get(url)

    fun clear() = cache.evictAll()

    /** Llamar solo desde el hilo principal. */
    fun load(url: String, maxW: Int, cb: (Bitmap?) -> Unit) {
        val l = waiting[url]
        if (l != null) {
            l.add(cb)
            return
        }
        waiting[url] = arrayListOf(cb)
        pool.execute {
            val bmp = try {
                decode(url, maxW)
            } catch (_: Throwable) {
                null
            }
            main.post {
                if (bmp != null) cache.put(url, bmp)
                waiting.remove(url)?.forEach { it(bmp) }
            }
        }
    }

    private fun decode(url: String, maxW: Int): Bitmap? {
        val bytes = Net.get(url, "image/*", 1_500_000, false).body
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= maxW) s *= 2
        val o2 = BitmapFactory.Options().apply {
            inSampleSize = s
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    }
}
