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
