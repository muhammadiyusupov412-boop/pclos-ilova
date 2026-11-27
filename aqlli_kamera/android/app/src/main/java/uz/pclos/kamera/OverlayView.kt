package uz.pclos.kamera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import uz.pclos.kamera.core.Box
import uz.pclos.kamera.core.CountLine
import uz.pclos.kamera.core.Pt

/** Kamera ustida: qutilar, sanash chiziqlari va hisoblagichlar. Chiziqlar barmoq bilan chiziladi. */
class OverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    data class Item(val box: Box, val label: String, val person: Boolean)

    var frameW = 640; var frameH = 480
    var items: List<Item> = emptyList()
    var lines: List<CountLine> = emptyList()
    var stats: List<String> = emptyList()
    /** Tahrirlanayotgan chiziq raqami (-1 = tahrir yo'q). */
    var editIndex = -1
    var onLinesChanged: (() -> Unit)? = null

    private val boxPed = Paint().apply { color = Color.rgb(60, 220, 90); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val boxVeh = Paint().apply { color = Color.rgb(255, 160, 40); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val text = Paint().apply { color = Color.WHITE; textSize = 30f; isAntiAlias = true; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val small = Paint().apply { color = Color.WHITE; textSize = 24f; isAntiAlias = true; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val linePaint = Paint().apply { strokeWidth = 7f; isAntiAlias = true }
    private val panel = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val lineColors = intArrayOf(Color.rgb(255, 230, 0), Color.rgb(0, 200, 255), Color.MAGENTA)

    private fun scale() = minOf(width.toFloat() / frameW, height.toFloat() / frameH)
    private fun offX() = (width - frameW * scale()) / 2
    private fun offY() = (height - frameH * scale()) / 2
    private fun vx(fx: Float) = offX() + fx * scale()
    private fun vy(fy: Float) = offY() + fy * scale()

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        for (it in items) {
            val p = if (it.person) boxPed else boxVeh
            c.drawRect(vx(it.box.x1), vy(it.box.y1), vx(it.box.x2), vy(it.box.y2), p)
            c.drawText(it.label, vx(it.box.x1), vy(it.box.y1) - 6, small)
        }
        for ((i, l) in lines.withIndex()) {
            linePaint.color = lineColors[i % lineColors.size]
            val ax = vx(l.a.x * frameW); val ay = vy(l.a.y * frameH)
            val bx = vx(l.b.x * frameW); val by = vy(l.b.y * frameH)
            c.drawLine(ax, ay, bx, by, linePaint)
            c.drawText("${l.name}  A", ax + 8, ay - 8, text)
            c.drawText("B", bx + 8, by - 8, text)
        }
        if (stats.isNotEmpty()) {
            val lh = 32f
            c.drawRect(0f, 0f, 430f, 16f + lh * stats.size, panel)
            for ((i, s) in stats.withIndex()) c.drawText(s, 12f, 38f + i * lh, text)
        }
        if (editIndex >= 0) {
            val msg = "Barmoq bilan ${lines.getOrNull(editIndex)?.name ?: ""} chizig'ini torting"
            c.drawText(msg, width / 2f - text.measureText(msg) / 2, height - 40f, text)
        }
    }

    private fun toFrame(x: Float, y: Float) = Pt(
        ((x - offX()) / scale() / frameW).coerceIn(0f, 1f),
        ((y - offY()) / scale() / frameH).coerceIn(0f, 1f),
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val l = lines.getOrNull(editIndex) ?: return false
        val p = toFrame(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { l.a = p; l.b = p }
            MotionEvent.ACTION_MOVE -> l.b = p
            MotionEvent.ACTION_UP -> {
                l.b = p
                editIndex = -1
                onLinesChanged?.invoke()
            }
        }
        invalidate()
        return true
    }
}
