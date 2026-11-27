package uz.pclos.kamera.core

/**
 * Yengil ko'p obyektli kuzatuvchi (SORT g'oyasi asosida, Kalman o'rniga tezlik bilan bashorat).
 * Piyodalar va transport alohida guruhda moslashtiriladi.
 */
class Track(val id: Int, var box: Box, cls: Int, var lastFrame: Int) {
    var vx = 0f
    var vy = 0f
    var hits = 1
    val classVotes = HashMap<Int, Int>().apply { put(cls, 1) }
    var riderVotes = 0  // velosiped/mototsikl ustidagi odam bo'lib ko'ringan kadrlar soni
    var personVotes = 0

    val cls: Int get() = classVotes.maxByOrNull { it.value }!!.key
    val isPerson get() = cls == Coco.PERSON
    /** Velosiped yoki mototsikl haydovchisi (piyoda hisoblanmaydi). */
    val isRider get() = isPerson && personVotes > 0 && riderVotes * 2 > personVotes
    val foot get() = Pt(box.cx, box.y2)

    fun predicted(frame: Int): Box {
        val dt = (frame - lastFrame).toFloat()
        return box.shift(vx * dt, vy * dt)
    }
}

class Tracker(
    private val iouThreshold: Float = 0.25f,
    private val maxAgeFrames: Int = 30,
    private val minHits: Int = 3,
) {
    private var nextId = 1
    val tracks = ArrayList<Track>()

    /** Kadrdagi aniqlashlar bilan yangilaydi. Qaytaradi: o'chirilgan treklar. */
    fun update(dets: List<Detection>, frame: Int): List<Track> {
        val unmatched = dets.indices.toMutableSet()
        val pairs = ArrayList<Triple<Float, Int, Int>>() // (ball, trek, aniqlash)
        for ((ti, t) in tracks.withIndex()) {
            val p = t.predicted(frame)
            val diag = kotlin.math.sqrt(p.w * p.w + p.h * p.h)
            for ((di, d) in dets.withIndex()) {
                if ((d.cls == Coco.PERSON) != t.isPerson) continue
                val iou = p.iou(d.box)
                var score = iou
                if (iou < iouThreshold) {
                    // kichik obyektlar uchun markazlar masofasi bo'yicha zaxira moslashtirish
                    val dx = p.cx - d.box.cx; val dy = p.cy - d.box.cy
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (dist > 0.6f * diag) continue
                    score = 0.01f * (1f - dist / (0.6f * diag + 1e-6f))
                }
                pairs.add(Triple(score, ti, di))
            }
        }
        pairs.sortByDescending { it.first }
        val usedT = HashSet<Int>()
        for ((_, ti, di) in pairs) {
            if (ti in usedT || di !in unmatched) continue
            usedT.add(ti); unmatched.remove(di)
            val t = tracks[ti]; val d = dets[di]
            val dt = maxOf(1, frame - t.lastFrame).toFloat()
            val nvx = (d.box.cx - t.box.cx) / dt
            val nvy = (d.box.y2 - t.box.y2) / dt
            t.vx = 0.6f * t.vx + 0.4f * nvx
            t.vy = 0.6f * t.vy + 0.4f * nvy
            t.box = d.box
            t.lastFrame = frame
            t.hits++
            t.classVotes[d.cls] = (t.classVotes[d.cls] ?: 0) + 1
        }
        for (di in unmatched) {
            val d = dets[di]
            tracks.add(Track(nextId++, d.box, d.cls, frame))
        }
        // haydovchilarni belgilash: odamning oyog'i velosiped/mototsikl qutisi ichida
        val twoWheelers = dets.filter { it.cls == Coco.BICYCLE || it.cls == Coco.MOTORCYCLE }
        for (t in tracks) {
            if (t.lastFrame != frame || !t.isPerson) continue
            t.personVotes++
            val f = t.foot
            if (twoWheelers.any { it.box.expand(0.3f).contains(f.x, f.y) || it.box.iou(t.box) > 0.2f }) t.riderVotes++
        }
        val dead = tracks.filter { frame - it.lastFrame > maxAgeFrames }
        tracks.removeAll(dead.toSet())
        return dead
    }

    fun confirmed(t: Track) = t.hits >= minHits
}
