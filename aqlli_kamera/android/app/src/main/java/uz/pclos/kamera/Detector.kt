package uz.pclos.kamera

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import uz.pclos.kamera.core.Box
import uz.pclos.kamera.core.Coco
import uz.pclos.kamera.core.Detection
import java.nio.FloatBuffer

/** YOLO11n (COCO) ONNX modeli bilan telefonda obyekt aniqlash. */
class Detector(ctx: Context, asset: String = "yolo11n_416.onnx", private val size: Int = 416) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val input = FloatBuffer.allocate(3 * size * size)
    private val pixels = IntArray(size * size)
    private val letterbox = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(letterbox)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    var confThreshold = 0.35f
    var iouThreshold = 0.5f

    init {
        val bytes = ctx.assets.open(asset).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)
            try { addXnnpack(mapOf("intra_op_num_threads" to "4")) } catch (_: Throwable) {}
        }
        session = env.createSession(bytes, opts)
        inputName = session.inputNames.first()
    }

    fun detect(bmp: Bitmap): List<Detection> {
        val r = minOf(size.toFloat() / bmp.width, size.toFloat() / bmp.height)
        val nw = bmp.width * r; val nh = bmp.height * r
        val px = (size - nw) / 2; val py = (size - nh) / 2
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(bmp, null, RectF(px, py, px + nw, py + nh), paint)
        letterbox.getPixels(pixels, 0, size, 0, 0, size, size)
        val plane = size * size
        input.rewind()
        val arr = input.array()
        for (i in 0 until plane) {
            val c = pixels[i]
            arr[i] = ((c shr 16) and 0xFF) / 255f
            arr[i + plane] = ((c shr 8) and 0xFF) / 255f
            arr[i + 2 * plane] = (c and 0xFF) / 255f
        }
        val tensor = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong()))
        val raw = tensor.use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                @Suppress("UNCHECKED_CAST")
                (res[0].value as Array<Array<FloatArray>>)[0]
            }
        }
        // raw: [84][N] -> cx, cy, w, h, 80 sinf ballari
        val n = raw[0].size
        val dets = ArrayList<Detection>()
        for (i in 0 until n) {
            var best = -1; var bestScore = confThreshold
            for (c in Coco.TRACKED) {
                val s = raw[4 + c][i]
                if (s > bestScore) { bestScore = s; best = c }
            }
            if (best < 0) continue
            val cx = raw[0][i]; val cy = raw[1][i]; val w = raw[2][i]; val h = raw[3][i]
            val box = Box(
                ((cx - w / 2 - px) / r).coerceIn(0f, bmp.width.toFloat()),
                ((cy - h / 2 - py) / r).coerceIn(0f, bmp.height.toFloat()),
                ((cx + w / 2 - px) / r).coerceIn(0f, bmp.width.toFloat()),
                ((cy + h / 2 - py) / r).coerceIn(0f, bmp.height.toFloat()),
            )
            dets.add(Detection(box, best, bestScore))
        }
        return nms(dets)
    }

    private fun nms(dets: List<Detection>): List<Detection> {
        val out = ArrayList<Detection>()
        for ((_, group) in dets.groupBy { if (it.cls == Coco.PERSON) 0 else 1 }) {
            val sorted = group.sortedByDescending { it.score }.toMutableList()
            while (sorted.isNotEmpty()) {
                val d = sorted.removeAt(0)
                out.add(d)
                // transportda avtomobil/yuk/avtobus bir xil obyektga ikki sinf berishi mumkin
                sorted.removeAll { it.box.iou(d.box) > iouThreshold }
            }
        }
        return out
    }

    override fun close() { session.close() }
}
