package uz.pclos.kamera.core

/** Kadr koordinatalaridagi to'rtburchak (piksellarda). */
data class Box(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val w get() = x2 - x1
    val h get() = y2 - y1
    val cx get() = (x1 + x2) / 2
    val cy get() = (y1 + y2) / 2
    val area get() = maxOf(0f, w) * maxOf(0f, h)

    fun iou(o: Box): Float {
        val ix = maxOf(0f, minOf(x2, o.x2) - maxOf(x1, o.x1))
        val iy = maxOf(0f, minOf(y2, o.y2) - maxOf(y1, o.y1))
        val inter = ix * iy
        val union = area + o.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun contains(px: Float, py: Float) = px in x1..x2 && py in y1..y2

    fun shift(dx: Float, dy: Float) = Box(x1 + dx, y1 + dy, x2 + dx, y2 + dy)

    fun expand(f: Float): Box {
        val ex = w * f / 2; val ey = h * f / 2
        return Box(x1 - ex, y1 - ey, x2 + ex, y2 + ey)
    }
}

data class Pt(val x: Float, val y: Float)

/** Aniqlangan obyekt: COCO sinf raqami va ishonch. */
data class Detection(val box: Box, val cls: Int, val score: Float)

object Coco {
    const val PERSON = 0
    const val BICYCLE = 1
    const val CAR = 2
    const val MOTORCYCLE = 3
    const val BUS = 5
    const val TRUCK = 7

    val VEHICLES = setOf(BICYCLE, CAR, MOTORCYCLE, BUS, TRUCK)
    val TRACKED = VEHICLES + PERSON

    fun vehicleName(cls: Int) = when (cls) {
        CAR -> "yengil"
        BUS -> "avtobus"
        TRUCK -> "yuk"
        MOTORCYCLE -> "mototsikl"
        BICYCLE -> "velosiped"
        else -> "boshqa"
    }
}
