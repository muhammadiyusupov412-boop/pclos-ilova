package uz.pclos.kamera.core

/** Sanash chizig'i. Nuqtalar nisbiy koordinatalarda (0..1), kadr o'lchamiga qarab piksellarga o'giriladi. */
data class CountLine(val name: String, var a: Pt, var b: Pt, val target: Target) {
    enum class Target { PEDESTRIAN, VEHICLE, ALL }

    fun accepts(isPerson: Boolean) = when (target) {
        Target.ALL -> true
        Target.PEDESTRIAN -> isPerson
        Target.VEHICLE -> !isPerson
    }
}

data class Crossing(val trackId: Int, val line: String, val direction: String)

private fun side(a: Pt, b: Pt, p: Pt) = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

fun segmentsIntersect(p1: Pt, p2: Pt, q1: Pt, q2: Pt): Boolean {
    val d1 = side(q1, q2, p1); val d2 = side(q1, q2, p2)
    val d3 = side(p1, p2, q1); val d4 = side(p1, p2, q2)
    // nuqta aynan chiziq ustida bo'lsa (0), musbat tomon deb olinadi: kesish yo'qolmaydi
    return (d1 >= 0) != (d2 >= 0) && (d3 >= 0) != (d4 >= 0)
}

/** Har bir trek har bir chiziqda faqat bir marta sanaladi. */
class LineCounter(val line: CountLine) {
    private val last = HashMap<Int, Pt>()
    private val counted = HashSet<Int>()

    /** p - trek nuqtasi nisbiy koordinatalarda. */
    fun update(trackId: Int, p: Pt): Crossing? {
        val prev = last.put(trackId, p) ?: return null
        if (trackId in counted) return null
        if (!segmentsIntersect(prev, p, line.a, line.b)) return null
        counted.add(trackId)
        val dir = if (side(line.a, line.b, prev) > 0) "A->B" else "B->A"
        return Crossing(trackId, line.name, dir)
    }

    fun forget(trackId: Int) { last.remove(trackId) }

    fun reset() { last.clear(); counted.clear() }
}
