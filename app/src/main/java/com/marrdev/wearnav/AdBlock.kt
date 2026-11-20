package com.marrdev.wearnav

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Bloqueo ligero de anuncios/rastreadores por dominio: ahorra datos, CPU y RAM. */
object AdBlock {
    private val hosts = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "adnxs.com", "adsrvr.org", "taboola.com", "outbrain.com", "criteo.com",
        "criteo.net", "scorecardresearch.com", "quantserve.com", "amazon-adsystem.com",
        "moatads.com", "rubiconproject.com", "pubmatic.com", "openx.net", "adform.net",
        "smartadserver.com", "casalemedia.com", "media.net", "hotjar.com", "mixpanel.com",
        "popads.net", "propellerads.com", "mgid.com", "revcontent.com", "yieldmo.com",
        "33across.com", "advertising.com", "adcolony.com", "applovin.com", "adsterra.com",
        "exoclick.com", "juicyads.com", "trafficjunky.com", "zedo.com", "bidswitch.net"
    )

    fun blocked(host: String?): Boolean {
        var h = host ?: return false
        while (true) {
            if (h in hosts) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
