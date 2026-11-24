package com.marrdev.wearnav

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter

/** Guarda el motivo de cualquier cierre inesperado para mostrarlo en pantalla al reabrir. */
class NavApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                getSharedPreferences("crash", MODE_PRIVATE).edit()
                    .putString("log", sw.toString().take(5000)).commit()
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}
