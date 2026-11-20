package com.marrdev.wearnav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Barra de progreso circular que sigue el borde de la pantalla redonda. */
class RingProgressView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF4FC3F7.toInt()
    }
    private val rect = RectF()

    var progress = 0
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0 || progress >= 100) return
        val w = resources.displayMetrics.density * 3f
        paint.strokeWidth = w
        rect.set(w / 2, w / 2, width - w / 2, height - w / 2)
        canvas.drawArc(rect, -90f, progress * 3.6f, false, paint)
    }
}
