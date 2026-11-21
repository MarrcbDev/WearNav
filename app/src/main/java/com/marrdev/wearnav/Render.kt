package com.marrdev.wearnav

import android.net.Uri
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

// kind: 0 párrafo, 1-3 títulos, 5 cita, 6 código, 7 meta (gris, centrado)
sealed class Block
class Txt(val text: CharSequence, val kind: Int, val href: String?, val links: Boolean) : Block()
class Img(val url: String, val alt: String, val href: String?) : Block()
object Rule : Block()

class Page(var url: String, var title: String, var blocks: List<Block>?) {
    var idx = 0
    var top = 0
}

fun txt(s: String, kind: Int = 0, href: String? = null): Txt = Txt(s, kind, href, false)

class LinkSpan(val url: String) : ClickableSpan() {
    override fun onClick(widget: View) {
        (widget.context as? MainActivity)?.openLink(url)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = 0xFF8AB4F8.toInt()
        ds.isUnderlineText = false
    }
}

private const val MAX_BLOCKS = 1200

private val SKIP = setOf(
    "script", "style", "noscript", "svg", "iframe", "canvas", "template", "head", "link", "meta",
    "button", "select", "input", "textarea", "audio", "video", "object", "embed", "map", "dialog", "option"
)

private val BLOCK = setOf(
    "p", "div", "section", "article", "main", "header", "footer", "nav", "aside", "ul", "ol", "table",
    "tr", "thead", "tbody", "tfoot", "figure", "figcaption", "form", "fieldset", "dl", "dt", "dd",
    "details", "summary", "address", "body", "center", "caption"
)

fun unwrap(u: String): String {
    val uri = Uri.parse(u)
    val h = uri.host ?: return u
    if (h.endsWith("duckduckgo.com") && uri.path == "/l/") {
        uri.getQueryParameter("uddg")?.let { return it }
    }
    if (h.contains("google.") && uri.path == "/url") {
        uri.getQueryParameter("q")?.let { return it }
    }
    return u
}

private class Rb(
    private val pageUrl: String,
    private val adblock: Boolean,
    private val noImages: Boolean
) {
    val blocks = ArrayList<Block>()
    var chars = 0
    private val sb = SpannableStringBuilder()
    private var kind = 0
    private var quote = 0
    private var link: String? = null

    fun run(root: Element) {
        walk(root)
        flush()
    }

    private fun walk(n: Node) {
        if (blocks.size >= MAX_BLOCKS) return
        if (n is TextNode) {
            text(n.text())
            return
        }
        if (n is Element) element(n)
    }

    private fun kids(e: Element) {
        for (c in e.childNodes()) walk(c)
    }

    private fun element(e: Element) {
        val tag = e.tagName()
        if (tag in SKIP) return
        if (e.hasAttr("hidden")) return
        if (e.attr("style").replace(" ", "").contains("display:none")) return
        when {
            tag == "br" -> {
                if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
            }
            tag == "hr" -> {
                flush()
                blocks.add(Rule)
            }
            tag == "img" -> image(e)
            tag == "a" -> anchor(e)
            tag == "pre" -> {
                flush()
                val t = e.wholeText().trim('\n', '\r').take(4000)
                if (t.isNotBlank()) blocks.add(Txt(t, 6, null, false))
            }
            tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> {
                flush()
                kind = minOf(3, tag[1] - '0')
                kids(e)
                flush()
            }
            tag == "li" -> {
                flush()
                val p = e.parent()
                val pre = if (p != null && p.tagName() == "ol") "" + (e.elementSiblingIndex() + 1) + ". " else "• "
                sb.append(pre)
                kids(e)
                flush()
            }
            tag == "blockquote" -> {
                flush()
                quote++
                kids(e)
                flush()
                quote--
            }
            tag == "td" || tag == "th" -> {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append("  ·  ")
                kids(e)
            }
            tag in BLOCK -> {
                flush()
                kids(e)
                flush()
            }
            else -> kids(e)
        }
    }

    private fun text(raw: String) {
        if (raw.isEmpty()) return
        var t = raw
        if (sb.isEmpty() || sb.last() == ' ' || sb.last() == '\n') t = t.trimStart()
        if (t.isEmpty()) return
        val start = sb.length
        sb.append(t)
        val l = link
        if (l != null) sb.setSpan(LinkSpan(l), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (sb.length > 3000) flush()
    }

    private fun anchor(e: Element) {
        val href = e.absUrl("href")
        val web = href.startsWith("http://") || href.startsWith("https://")
        val sameDoc = href.contains('#') && href.substringBefore('#') == pageUrl.substringBefore('#')
        if (!web || sameDoc) {
            kids(e)
            return
        }
        val u = unwrap(href)
        if (adblock && AdBlock.blocked(Uri.parse(u).host)) return
        val prev = link
        link = u
        kids(e)
        link = prev
    }

    private fun image(e: Element) {
        flush()
        var src = ""
        for (a in arrayOf("src", "data-src", "data-lazy-src", "data-original")) {
            src = e.absUrl(a)
            if (src.isNotEmpty()) break
        }
        if (!src.startsWith("http")) return
        val w = e.attr("width").toIntOrNull()
        val h = e.attr("height").toIntOrNull()
        if ((w != null && w < 40) || (h != null && h < 40)) return
        if (adblock && AdBlock.blocked(Uri.parse(src).host)) return
        if (noImages) {
            val alt = e.attr("alt").trim()
            if (alt.length > 2) blocks.add(Txt("[" + alt.take(80) + "]", 7, null, false))
            return
        }
        blocks.add(Img(src, e.attr("alt"), link))
    }

    private fun flush() {
        while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\n')) sb.delete(sb.length - 1, sb.length)
        if (sb.isNotEmpty() && !(sb.length == 1 && !sb[0].isLetterOrDigit())) {
            val k = if (kind != 0) kind else if (quote > 0) 5 else 0
            val spans = sb.getSpans(0, sb.length, LinkSpan::class.java)
            var href: String? = null
            if (spans.isNotEmpty()) {
                val first = spans[0]
                var covered = 0
                for (s in spans) if (s.url == first.url) covered += sb.getSpanEnd(s) - sb.getSpanStart(s)
                if (covered >= sb.length * 0.9) href = first.url
            }
            val lastHref = (blocks.lastOrNull() as? Txt)?.href
            chars += sb.length
            if (href != null && href == lastHref) {
                // mismo enlace repetido (título / url / resumen): solo el primero es tarjeta
                val s = sb.toString()
                if (s.contains(' ')) blocks.add(Txt(s, 0, null, false))
            } else {
                blocks.add(Txt(SpannableString(sb), k, href, spans.isNotEmpty()))
            }
        }
        sb.clear()
        sb.clearSpans()
        kind = 0
    }
}

object Loader {
    private val CHARSET = Regex("charset=([\\w\\-]+)", RegexOption.IGNORE_CASE)

    fun load(url: String, adblock: Boolean, noImages: Boolean, mainOnly: Boolean, onDownloaded: () -> Unit): Page {
        val r = Net.get(url, "text/html,application/xhtml+xml,text/plain;q=0.8,*/*;q=0.5", 1_500_000, true)
        onDownloaded()
        val ct = r.type.lowercase()
        val host = Uri.parse(r.url).host ?: r.url
        val meta = txt(host, 7)
        val charset = CHARSET.find(r.type)?.groupValues?.get(1)

        if (ct.startsWith("image/")) return Page(r.url, host, listOf(meta, Img(r.url, "", null)))
        if (ct.isEmpty() || ct.contains("html")) return parse(r, host, meta, charset, adblock, noImages, mainOnly)
        if (ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") || ct.contains("javascript")) {
            val s = try {
                String(r.body, if (charset != null) Charset.forName(charset) else Charsets.UTF_8)
            } catch (_: Exception) {
                String(r.body, Charsets.UTF_8)
            }
            return Page(r.url, host, listOf(meta, Txt(s.take(20000), 6, null, false)))
        }
        return Page(r.url, host, listOf(meta, txt("Tipo de archivo no soportado", 2), txt(r.type)))
    }

    private fun parse(
        r: Resp, host: String, meta: Txt, charset: String?,
        adblock: Boolean, noImages: Boolean, mainOnly: Boolean
    ): Page {
        val doc = try {
            Jsoup.parse(ByteArrayInputStream(r.body), charset, r.url)
        } catch (_: IllegalArgumentException) {
            Jsoup.parse(ByteArrayInputStream(r.body), null, r.url)
        }
        doc.select("script,style,noscript,svg,iframe,template").remove()
        if (mainOnly) {
            doc.select("nav,aside,footer,[role=navigation],[role=banner],[role=complementary],[role=contentinfo]").remove()
        }
        val body: Element = doc.body() ?: doc
        var root: Element = body
        if (mainOnly) {
            val m = doc.selectFirst("article,main,[role=main]")
            if (m != null && m.text().length >= 300) root = m
        }
        val title = doc.title().trim()
        val rb = Rb(r.url, adblock, noImages)
        rb.run(root)

        val out = ArrayList<Block>()
        out.add(meta)
        if (root !== body && root.selectFirst("h1") == null && title.isNotBlank()) out.add(txt(title, 1))
        out.addAll(rb.blocks)
        if (rb.chars < 60) {
            out.add(txt("Esta página casi no tiene contenido sin JavaScript. Prueba desactivar 'Solo contenido principal' en Ajustes.", 5))
        }
        if (r.truncated) out.add(txt("— Página recortada por tamaño —", 7))
        return Page(r.url, title.ifBlank { host }, out)
    }
}
