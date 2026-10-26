package net.tenyls.kinotv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Курсор-стрелка для управления пультом. Только рисует; логика — в MainActivity. */
class CursorView(context: Context) : View(context) {

    var cx = 0f
    var cy = 0f
    var isDown = false

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88E50914.toInt(); style = Paint.Style.FILL }

    init {
        isFocusable = false
        isClickable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = 1.6f * d
        if (isDown) canvas.drawCircle(cx, cy, 14f * d, ring)
        val p = Path().apply {
            moveTo(cx, cy)
            lineTo(cx, cy + 17 * s)
            lineTo(cx + 4.5f * s, cy + 13 * s)
            lineTo(cx + 8 * s, cy + 20 * s)
            lineTo(cx + 10.5f * s, cy + 19 * s)
            lineTo(cx + 7 * s, cy + 12 * s)
            lineTo(cx + 12.5f * s, cy + 12 * s)
            close()
        }
        canvas.drawPath(p, fill)
        canvas.drawPath(p, stroke)
    }
}
