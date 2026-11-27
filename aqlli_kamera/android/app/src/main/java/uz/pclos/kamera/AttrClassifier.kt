package uz.pclos.kamera

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import uz.pclos.kamera.core.Attr
import uz.pclos.kamera.core.Box
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * Piyoda atributlari modeli: PaddleClas PULC "person_attribute" (PA-100K ma'lumotlar to'plamida
 * o'qitilgan, 26 atribut). PULC tartibi (PaddleClas attr_rec.py PersonAttribute):
 * 19 AgeLess18, 20 Age18-60, 21 AgeOver60, 22 Female. (Asl PA-100K tartibidan farq qiladi!)
 * Kirish: 1x3x256x192 RGB, ImageNet normallashtirish.
 */
class AttrClassifier private constructor(private val session: OrtSession) : AutoCloseable {
    companion object {
        const val ASSET = "pa100k_attr.onnx"
        private const val H = 256
        private const val W = 192
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /** Model ilovaga qo'shilmagan bo'lsa null qaytaradi. */
        fun createOrNull(ctx: Context): AttrClassifier? = try {
            val bytes = ctx.assets.open(ASSET).use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(2)
            }
            AttrClassifier(OrtEnvironment.getEnvironment().createSession(bytes, opts))
        } catch (_: Throwable) {
            null
        }
    }

    private val env = OrtEnvironment.getEnvironment()
    private val inputName = session.inputNames.first()
    private val input = FloatBuffer.allocate(3 * H * W)
    private val pixels = IntArray(H * W)

    fun classify(frame: Bitmap, box: Box): Attr? {
        // biroz kengaytirilgan kesma
        val b = box.expand(0.1f)
        val x1 = b.x1.toInt().coerceIn(0, frame.width - 1)
        val y1 = b.y1.toInt().coerceIn(0, frame.height - 1)
        val x2 = b.x2.toInt().coerceIn(x1 + 1, frame.width)
        val y2 = b.y2.toInt().coerceIn(y1 + 1, frame.height)
        if (x2 - x1 < 8 || y2 - y1 < 16) return null
        val crop = Bitmap.createBitmap(frame, x1, y1, x2 - x1, y2 - y1)
        val scaled = Bitmap.createScaledBitmap(crop, W, H, true)
        scaled.getPixels(pixels, 0, W, 0, 0, W, H)
        if (scaled !== crop) scaled.recycle()
        crop.recycle()
        val plane = H * W
        val arr = input.array()
        for (i in 0 until plane) {
            val c = pixels[i]
            arr[i] = (((c shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            arr[i + plane] = (((c shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            arr[i + 2 * plane] = ((c and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        input.rewind()
        val out = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, H.toLong(), W.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                @Suppress("UNCHECKED_CAST")
                (res[0].value as Array<FloatArray>)[0]
            }
        }
        val needSigmoid = out.any { it < 0f || it > 1f }
        fun p(i: Int) = if (needSigmoid) 1f / (1f + exp(-out[i])) else out[i]
        return Attr(child = p(19), adult = p(20), elderly = p(21), female = p(22))
    }

    override fun close() { session.close() }
}
