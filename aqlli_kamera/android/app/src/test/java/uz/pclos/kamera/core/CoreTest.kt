package uz.pclos.kamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CoreTest {
    @Test
    fun lineCountsOncePerTrack() {
        val lc = LineCounter(CountLine("L", Pt(0f, 0.5f), Pt(1f, 0.5f), CountLine.Target.ALL))
        assertNull(lc.update(1, Pt(0.5f, 0.4f)))
        val c = lc.update(1, Pt(0.5f, 0.6f))
        assertNotNull(c)
        assertNull(lc.update(1, Pt(0.5f, 0.4f)))  // qaytib o'tsa ham qayta sanalmaydi
        assertEquals("L", c!!.line)
    }

    @Test
    fun directionsDiffer() {
        val line = CountLine("L", Pt(0f, 0.5f), Pt(1f, 0.5f), CountLine.Target.ALL)
        val lc = LineCounter(line)
        lc.update(1, Pt(0.5f, 0.4f)); val down = lc.update(1, Pt(0.5f, 0.6f))!!
        lc.update(2, Pt(0.5f, 0.6f)); val up = lc.update(2, Pt(0.5f, 0.4f))!!
        assertTrue(down.direction != up.direction)
    }

    @Test
    fun trackerKeepsIdentity() {
        val tr = Tracker()
        for (f in 0 until 10) {
            tr.update(listOf(
                Detection(Box(10f + f * 5, 100f, 40f + f * 5, 180f), Coco.PERSON, 0.9f),
                Detection(Box(300f - f * 10, 50f, 400f - f * 10, 120f), Coco.CAR, 0.9f),
            ), f)
        }
        assertEquals(2, tr.tracks.size)
        assertTrue(tr.tracks.all { it.hits == 10 })
    }

    @Test
    fun riderIsNotPedestrian() {
        val tr = Tracker()
        for (f in 0 until 6) {
            tr.update(listOf(
                Detection(Box(100f, 50f, 140f, 150f), Coco.PERSON, 0.9f),
                Detection(Box(95f, 110f, 145f, 160f), Coco.BICYCLE, 0.9f),
            ), f)
        }
        val person = tr.tracks.first { it.isPerson }
        assertTrue(person.isRider)
    }

    private fun tmp(): File = Files.createTempDirectory("pclos").toFile()

    @Test
    fun engineCountsAndAggregates() {
        val dir = tmp()
        val log = SessionLog(dir, 15)
        val lines = listOf(
            CountLine("piyoda", Pt(0f, 0.5f), Pt(1f, 0.5f), CountLine.Target.PEDESTRIAN),
            CountLine("transport", Pt(0.5f, 0f), Pt(0.5f, 1f), CountLine.Target.VEHICLE),
        )
        val eng = CountingEngine(lines, log, attrSamples = 3)
        val attr = AttrSource { _, _ -> Attr(child = 0.8f, adult = 0.3f, elderly = 0.05f, female = 0.9f) }
        val t0 = 1_700_000_000_000L
        // piyoda yuqoridan pastga yuradi (y: 100 -> 300), mashina chapdan o'ngga (x: 100 -> 500)
        for (f in 0 until 20) {
            val dets = listOf(
                Detection(Box(200f, 20f + f * 15, 240f, 120f + f * 15), Coco.PERSON, 0.9f),
                Detection(Box(50f + f * 25, 300f, 150f + f * 25, 360f), Coco.BUS, 0.9f),
            )
            eng.process(dets, f, 640, 480, t0 + f * 100L, attr)
        }
        eng.flush()
        assertEquals(1, log.totals["piyoda:bola"])
        assertEquals(1, log.totals["transport:avtobus"])
        val csv = File(dir, "intervallar.csv").readLines()
        assertEquals(3, csv.size) // sarlavha + 2 chiziq
        val ev = File(dir, "hodisalar.csv").readLines()
        assertTrue(ev.any { it.contains(",piyoda,bola,ayol,") })
        val head = csv[0].split(","); val row = csv.drop(1).map { it.split(",") }.first { it[2] == "piyoda" }
        assertEquals("1", row[head.indexOf("bola_ayol")])
        assertEquals("1", row[head.indexOf("ayol")])
    }

    @Test
    fun intervalsAlignToQuarterHour() {
        val log = SessionLog(tmp(), 15)
        val t = 1_700_000_000_000L
        val s = log.intervalStart(t)
        assertTrue(s <= t && t - s < 15 * 60_000L)
        val off = java.util.TimeZone.getDefault().getOffset(s)
        assertEquals(0L, (s + off) % (15 * 60_000L))
    }
}

class MetricsTest {
    private fun tmp(): java.io.File = java.nio.file.Files.createTempDirectory("pclos").toFile()

    @Test
    fun waitingCrossingSpeedAndConflict() {
        val dir = tmp()
        val log = SessionLog(dir, 15)
        val cw = Zone("otish", listOf(Pt(0.3f, 0.4f), Pt(0.7f, 0.4f), Pt(0.7f, 0.6f), Pt(0.3f, 0.6f)), Zone.Kind.CROSSWALK)
        val wait = Zone("kutish", listOf(Pt(0.3f, 0.6f), Pt(0.7f, 0.6f), Pt(0.7f, 0.8f), Pt(0.3f, 0.8f)), Zone.Kind.WAIT)
        val setup = StudySetup(crosswalk = cw, waits = listOf(wait), crosswalkLengthM = 5f,
            speedA = CountLine("a", Pt(0.2f, 0f), Pt(0.2f, 1f), CountLine.Target.VEHICLE),
            speedB = CountLine("b", Pt(0.8f, 0f), Pt(0.8f, 1f), CountLine.Target.VEHICLE), speedDistM = 20f)
        val eng = CountingEngine(emptyList(), log, study = setup)
        val W = 1000; val H = 1000; val t0 = 1_700_000_000_000L
        // 10 kadr/s: piyoda 0-4 s kutish joyida turadi (y=0.7), 4-8 s yuqoriga kesib o'tadi (0.6 -> 0.4), keyin chiqadi
        // mashina 8.5-10 s da o'tish joyiga kiradi (PET ~ 0.5-1 s)
        for (f in 0 until 170) {
            val ts = f / 10.0
            val dets = ArrayList<Detection>()
            val py = when { ts < 4 -> 0.7; ts < 8 -> 0.7 - 0.3 * (ts - 4) / 4; else -> 0.4 - 0.02 * (ts - 8) }.toFloat()
            if (ts < 10) dets += Detection(Box(480f, py * H - 100, 520f, py * H), Coco.PERSON, 0.9f)
            if (ts >= 7) {
                val vx = (0.0 + 0.1 * (ts - 7)).toFloat()  // 0.1 kadr/s -> 1 kenglik 10 s
                dets += Detection(Box(vx * W - 60, 440f, vx * W + 60, 500f), Coco.CAR, 0.9f)
            }
            eng.process(dets, f, W, H, t0 + (ts * 1000).toLong(), null)
        }
        eng.flush()
        val m = eng.metrics!!
        assertEquals(1, m.crossTimes.size)
        assertTrue("kutish ${m.waits}", m.waits[0] in 4.6..5.6)
        assertTrue("otish ${m.crossTimes}", m.crossTimes[0] in 2.3..3.0)
        assertTrue("tezlik ${m.walkSpeeds}", m.walkSpeeds[0] in 1.6..2.2)
        assertEquals(1, m.vehSpeeds.size)
        assertTrue("v ${m.vehSpeeds}", m.vehSpeeds[0] in 10.0..14.0) // 20 m / 6 s = 12 km/soat
        assertTrue("pet ${m.pets}", m.pets.isNotEmpty())
        val sum = Summary.build(listOf("chorraha" to "test"), log, m, 13.0 / 60)
        Summary.write(dir, sum)
        assertTrue(java.io.File(dir, "xulosa.csv").readText().contains("transport_tezligi_85%"))
        assertTrue(java.io.File(dir, "korsatkichlar.csv").exists())
    }

    @Test
    fun zoneContains() {
        val z = Zone("z", listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(0.5f, 1f)), Zone.Kind.WAIT)
        assertTrue(z.contains(Pt(0.5f, 0.3f)))
        assertTrue(!z.contains(Pt(0.05f, 0.9f)))
    }

    @Test
    fun percentiles() {
        assertEquals(1.5, percentile(listOf(1.0, 2.0), 50.0)!!, 1e-9)
        assertEquals(10.0, percentile((1..10).map { it.toDouble() }, 100.0)!!, 1e-9)
    }
}
