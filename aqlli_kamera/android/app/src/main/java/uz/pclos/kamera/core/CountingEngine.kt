package uz.pclos.kamera.core

/** Piyoda atributlari: yosh guruhi ehtimolliklari va ayol bo'lish ehtimoli. */
data class Attr(val child: Float, val adult: Float, val elderly: Float, val female: Float)

/** Kadrdan piyodaning kesmasini olib, atributlarini qaytaradigan manba (Android tomonda ONNX model). */
fun interface AttrSource {
    fun classify(trackId: Int, box: Box): Attr?
}

/**
 * Bir kadr natijalarini qabul qilib: kuzatadi, chiziqdan o'tishni sanaydi, piyodalarning
 * yoshi/jinsini bir necha kadr bo'yicha o'rtachalab aniqlaydi va hodisalarni jurnalga yozadi.
 */
class CountingEngine(
    lines: List<CountLine>,
    private val log: SessionLog,
    private val attrSamples: Int = 5,
    private val minCropHeightPx: Float = 60f,
    private val maxAttrPerFrame: Int = 2,
    maxAgeFrames: Int = 30,
) {
    val tracker = Tracker(maxAgeFrames = maxAgeFrames)
    private val counters = lines.map { LineCounter(it) }
    private val attrs = HashMap<Int, MutableList<Attr>>()
    private val lastSampleFrame = HashMap<Int, Int>()
    private val pending = ArrayList<Pair<CountEvent, Int>>()  // piyoda hodisasi, trek id

    var lines: List<CountLine> = lines
        private set

    fun attrResult(trackId: Int): Pair<String, String>? {
        val list = attrs[trackId] ?: return null
        if (list.isEmpty()) return null
        return decide(list).let { it.first to it.second }
    }

    /** (yosh guruhi, jins, ishonch) */
    private fun decide(list: List<Attr>): Triple<String, String, Float> {
        if (list.isEmpty()) return Triple("noaniq", "noaniq", 0f)
        val c = list.map { it.child }.average().toFloat()
        val a = list.map { it.adult }.average().toFloat()
        val e = list.map { it.elderly }.average().toFloat()
        val f = list.map { it.female }.average().toFloat()
        val (age, conf) = listOf("bola" to c, "kattalar" to a, "keksa" to e).maxByOrNull { it.second }!!
        val gender = when {
            f >= 0.6f -> "ayol"
            f <= 0.4f -> "erkak"
            else -> "noaniq"
        }
        val sum = c + a + e
        return Triple(age, gender, if (sum > 0) conf / sum else 0f)
    }

    /**
     * @param dets kadrdagi aniqlashlar (piksellarda)
     * @param w,h kadr o'lchami (aylantirilgandan keyin)
     * @param attr atribut manbai (null bo'lsa yosh aniqlanmaydi)
     */
    fun process(dets: List<Detection>, frame: Int, w: Int, h: Int, nowMs: Long, attr: AttrSource?) {
        val dead = tracker.update(dets, frame)
        var attrBudget = maxAttrPerFrame
        for (t in tracker.tracks) {
            if (t.lastFrame != frame || !tracker.confirmed(t)) continue
            // atribut namunasi
            if (attr != null && t.isPerson && !t.isRider && attrBudget > 0) {
                val list = attrs.getOrPut(t.id) { ArrayList() }
                val since = frame - (lastSampleFrame[t.id] ?: -100)
                if (list.size < attrSamples && t.box.h >= minCropHeightPx && since >= 3) {
                    attr.classify(t.id, t.box)?.let { list.add(it) }
                    lastSampleFrame[t.id] = frame
                    attrBudget--
                }
            }
            if (t.isRider) continue
            val foot = Pt(t.foot.x / w, t.foot.y / h)
            for (lc in counters) {
                if (!lc.line.accepts(t.isPerson)) continue
                val cr = lc.update(t.id, foot) ?: continue
                if (t.isPerson) {
                    val ev = CountEvent(nowMs, t.id, "piyoda", "noaniq", "noaniq", 0f, cr.line, cr.direction)
                    pending.add(ev to t.id)
                } else {
                    log.add(CountEvent(nowMs, t.id, "transport", Coco.vehicleName(t.cls), "", 1f, cr.line, cr.direction))
                }
            }
        }
        val deadIds = dead.map { it.id }.toSet()
        finalize(deadIds, force = false, withAttr = attr != null)
        for (id in deadIds) { counters.forEach { it.forget(id) }; lastSampleFrame.remove(id) }
    }

    private fun finalize(deadIds: Set<Int>, force: Boolean, withAttr: Boolean) {
        val it = pending.iterator()
        while (it.hasNext()) {
            val (ev, id) = it.next()
            val samples = attrs[id]?.size ?: 0
            val done = force || id in deadIds || !withAttr || samples >= attrSamples
            if (!done) continue
            val (age, gender, conf) = decide(attrs[id] ?: emptyList())
            log.add(ev.copy(category = age, gender = gender, confidence = conf))
            it.remove()
        }
        for (id in deadIds) attrs.remove(id)
    }

    /** Kuzatuv to'xtatilganda: kutilayotgan barcha piyoda hodisalarini yozish. */
    fun flush() {
        finalize(emptySet(), force = true, withAttr = true)
        log.writeIntervals()
    }
}
