package uz.pclos.kamera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import uz.pclos.kamera.core.Box
import uz.pclos.kamera.core.CountLine
import uz.pclos.kamera.core.Pt
import uz.pclos.kamera.core.Zone

/**
 * Kamera/video ustida: qutilar, sanash chiziqlari, zonalar va hisoblagichlar.
 * Chiziq barmoq bilan tortiladi, zona 4 ta nuqtani bosib chiziladi.
 */
class OverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    data class Item(val box: Box, val label: String, val person: Boolean)

    var frameW = 640; var frameH = 480
    var items: List<Item> = emptyList()
    var lines: List<CountLine> = emptyList()
    var zones: List<Zone> = emptyList()
    var stats: List<String> = emptyList()
    var editLine: CountLine? = null
    var editZone: Zone? = null
    private val zonePts = ArrayList<Pt>()
    var onSetupChanged: (() -> Unit)? = null

    private val boxPed = Paint().apply { color = Color.rgb(60, 220, 90); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val boxVeh = Paint().apply { color = Color.rgb(255, 160, 40); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val text = Paint().apply { color = Color.WHITE; textSize = 28f; isAntiAlias = true; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val small = Paint().apply { color = Color.WHITE; textSize = 22f; isAntiAlias = true; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val linePaint = Paint().apply { strokeWidth = 7f; isAntiAlias = true }
    private val zoneFill = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val zoneStroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
    private val dot = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val panel = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val lineColors = intArrayOf(Color.rgb(255, 230, 0), Color.rgb(0, 200, 255), Color.rgb(255, 80, 200))

    private fun scale() = minOf(width.toFloat() / frameW, height.toFloat() / frameH)
    private fun offX() = (width - frameW * scale()) / 2
    private fun offY() = (height - frameH * scale()) / 2
    private fun vx(fx: Float) = offX() + fx * scale()
    private fun vy(fy: Float) = offY() + fy * scale()
    private fun rx(p: Pt) = vx(p.x * frameW)
    private fun ry(p: Pt) = vy(p.y * frameH)

    private fun zoneColor(z: Zone) = if (z.kind == Zone.Kind.CROSSWALK) Color.rgb(255, 255, 255) else Color.rgb(120, 255, 120)

    private fun drawPoly(c: Canvas, pts: List<Pt>, color: Int, label: String?) {
        if (pts.isEmpty()) return
        val path = Path()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(rx(p), ry(p)) else path.lineTo(rx(p), ry(p)) }
        if (pts.size >= 3) path.close()
        zoneFill.color = Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
        zoneStroke.color = color
        if (pts.size >= 3) c.drawPath(path, zoneFill)
        c.drawPath(path, zoneStroke)
        pts.forEach { c.drawCircle(rx(it), ry(it), 9f, dot) }
        if (label != null) c.drawText(label, rx(pts[0]) + 8, ry(pts[0]) + 30, small)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        for (z in zones) if (z !== editZone) drawPoly(c, z.pts, zoneColor(z), z.name)
        editZone?.let { drawPoly(c, zonePts, zoneColor(it), it.name) }
        for (it in items) {
            val p = if (it.person) boxPed else boxVeh
            c.drawRect(vx(it.box.x1), vy(it.box.y1), vx(it.box.x2), vy(it.box.y2), p)
            c.drawText(it.label, vx(it.box.x1), vy(it.box.y1) - 6, small)
        }
        for ((i, l) in lines.withIndex()) {
            linePaint.color = lineColors[i % lineColors.size]
            val ax = rx(l.a); val ay = ry(l.a); val bx = rx(l.b); val by = ry(l.b)
            c.drawLine(ax, ay, bx, by, linePaint)
            c.drawText("${l.name}  A", ax + 8, ay - 8, text)
            c.drawText("B", bx + 8, by - 8, text)
        }
        if (stats.isNotEmpty()) {
            val lh = 30f
            val w = stats.maxOf { text.measureText(it) } + 24f
            c.drawRect(0f, 0f, w, 14f + lh * stats.size, panel)
            for ((i, s) in stats.withIndex()) c.drawText(s, 12f, 36f + i * lh, text)
        }
        val msg = when {
            editLine != null -> "Barmoq bilan «${editLine!!.name}» ni torting"
            editZone != null -> "«${editZone!!.name}»: burchaklarini bosing (${zonePts.size}/4)"
            else -> null
        }
        if (msg != null) {
            c.drawRect(0f, height - 80f, width.toFloat(), height.toFloat(), panel)
            c.drawText(msg, width / 2f - text.measureText(msg) / 2, height - 30f, text)
        }
    }

    private fun toFrame(x: Float, y: Float) = Pt(
        ((x - offX()) / scale() / frameW).coerceIn(0f, 1f),
        ((y - offY()) / scale() / frameH).coerceIn(0f, 1f),
    )

    fun startZone(z: Zone) { editLine = null; editZone = z; zonePts.clear(); invalidate() }
    fun startLine(l: CountLine) { editZone = null; editLine = l; invalidate() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = toFrame(e.x, e.y)
        editZone?.let { z ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                zonePts.add(p)
                if (zonePts.size >= 4) {
                    z.pts = zonePts.toList(); editZone = null; zonePts.clear(); onSetupChanged?.invoke()
                }
                invalidate()
            }
            return true
        }
        val l = editLine ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { l.a = p; l.b = p }
            MotionEvent.ACTION_MOVE -> l.b = p
            MotionEvent.ACTION_UP -> { l.b = p; editLine = null; onSetupChanged?.invoke() }
        }
        invalidate()
        return true
    }
}
