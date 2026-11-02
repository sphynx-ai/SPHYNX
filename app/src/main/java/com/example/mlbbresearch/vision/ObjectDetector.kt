package com.example.mlbbresearch.vision

import android.content.Context
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

// One object found by the trained detector. Positions are fractions of the whole screen.
// team: for hero icons on the minimap, "enemy" (red ring), "ally" (blue ring), "self" (green ring) or ""
class Detection(val label: String, val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float,
                val team: String = "") {
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
}

// A trained vision model (YOLOv8 exported to LiteRT .tflite). It runs a few times a second on
// its own thread next to the colour-based vision, never blocking it. Two of them:
//  - ObjectDetector: the MINIMAP (hero icons with team, turrets, turtle / lord)
//  - ScreenDetector: the WHOLE SCREEN (heroes, minions, creeps, buffs, turtle, lord, towers,
//    bushes, walls) - trained on pictures of your own matches
open class YoloDetector(
    private val fileName: String,
    private val labelsOf: () -> String,
    private val wholeScreen: Boolean,
    private val name: String
) {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, name) }
    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var busy = false
    private var lastRun = 0L

    private var inW = 640
    private var inH = 640
    private var inType: DataType = DataType.FLOAT32
    private var channelsFirstIn = false
    private var inBuf: ByteBuffer? = null
    private var outShape = intArrayOf()
    private var outType: DataType = DataType.FLOAT32
    private var outScale = 1f
    private var outZero = 0
    private var outBuf: ByteBuffer? = null

    @Volatile var labels: List<String> = emptyList()
        private set
    @Volatile var latest: List<Detection> = emptyList()
        private set
    @Volatile var latestAt = 0L
        private set
    @Volatile var lastMs = 0L
        private set
    @Volatile var state = "no detector loaded"
        private set

    val ready: Boolean get() = interpreter != null

    // the minimap area (same as MinimapAnalyzer) or the whole screen
    private fun region(): FloatArray = if (wholeScreen) floatArrayOf(0f, 0f, 1f, 1f) else MinimapAnalyzer.rect

    fun modelFile(ctx: Context) = File(ctx.applicationContext.filesDir, fileName)

    fun load(ctx: Context): String? {
        val f = modelFile(ctx)
        if (!f.exists()) return "Choose the detector file (.tflite) first"
        return try {
            close()
            val it = Interpreter(f, Interpreter.Options().setNumThreads(2))
            val inT = it.getInputTensor(0)
            // channels last [1, H, W, 3] (classic TFLite export) or channels first [1, 3, H, W]
            // (newer "litert" exports from Ultralytics / PyTorch)
            val s = inT.shape()
            when {
                s.size == 4 && s[3] == 3 -> { inH = s[1]; inW = s[2]; channelsFirstIn = false }
                s.size == 4 && s[1] == 3 -> { inH = s[2]; inW = s[3]; channelsFirstIn = true }
                else -> throw IllegalStateException("unexpected input shape ${s.joinToString()}")
            }
            inType = inT.dataType()
            val bytesPer = if (inType == DataType.FLOAT32) 4 else 1
            inBuf = ByteBuffer.allocateDirect(inW * inH * 3 * bytesPer).order(ByteOrder.nativeOrder())
            val outT = it.getOutputTensor(0)
            outShape = outT.shape()                 // [1, 4+classes, N] or [1, N, 4+classes]
            outType = outT.dataType()
            outScale = outT.quantizationParams().getScale().let { s2 -> if (s2 == 0f) 1f else s2 }
            outZero = outT.quantizationParams().getZeroPoint()
            val outCount = outShape.fold(1) { a, b -> a * b }
            outBuf = ByteBuffer.allocateDirect(outCount * (if (outType == DataType.FLOAT32) 4 else 1)).order(ByteOrder.nativeOrder())
            labels = labelsOf().split(",").map { l -> l.trim() }.filter { l -> l.isNotEmpty() }
            interpreter = it
            state = "ready (${inW}x$inH, ${classCount()} classes)"
            Diag.info("$name loaded: input ${inW}x$inH $inType ${if (channelsFirstIn) "channels first" else "channels last"}, output ${outShape.joinToString("x")}")
            null
        } catch (t: Throwable) {
            state = "load failed (see Logs)"
            Diag.error("$name load failed", t)
            t.message ?: t.toString()
        }
    }

    fun close() {
        try { interpreter?.close() } catch (t: Throwable) { }
        interpreter = null
        latest = emptyList()
        state = "no detector loaded"
    }

    private fun classCount(): Int {
        if (outShape.size != 3) return 0
        return min(outShape[1], outShape[2]) - 4
    }

    // Called by FrameProcessor on every frame; runs at most every `detectorEveryMs`
    fun maybeRun(px: IntArray, w: Int, h: Int) {
        if (!Cfg.detectorOn || interpreter == null || busy) return
        val now = System.currentTimeMillis()
        if (now - lastRun < Cfg.detectorEveryMs) return
        lastRun = now
        busy = true
        val r = region()
        val crop = cropResize(px, w, h, r)          // copy now, the frame buffer is reused
        worker.execute {
            try {
                val t0 = System.currentTimeMillis()
                latest = infer(crop, r)
                latestAt = System.currentTimeMillis()
                lastMs = latestAt - t0
            } catch (t: Throwable) {
                Diag.error("$name run failed", t)
                state = "error (see Logs)"
            } finally {
                busy = false
            }
        }
    }

    // Nearest-neighbour crop + resize to the model's input size, as packed RGB ints
    private fun cropResize(px: IntArray, w: Int, h: Int, r: FloatArray): IntArray {
        val x0 = r[0] * w
        val y0 = r[1] * h
        val cw = max(1f, (r[2] - r[0]) * w)
        val ch = max(1f, (r[3] - r[1]) * h)
        val out = IntArray(inW * inH)
        for (y in 0 until inH) {
            val sy = min(h - 1, (y0 + (y + 0.5f) * ch / inH).toInt())
            for (x in 0 until inW) {
                val sx = min(w - 1, (x0 + (x + 0.5f) * cw / inW).toInt())
                out[y * inW + x] = px[sy * w + sx]
            }
        }
        return out
    }

    private fun infer(rgb: IntArray, r: FloatArray): List<Detection> {
        val it = interpreter ?: return emptyList()
        val ib = inBuf ?: return emptyList()
        val ob = outBuf ?: return emptyList()
        ib.rewind()
        fun put(v: Int) {
            when (inType) {
                DataType.FLOAT32 -> ib.putFloat(v / 255f)
                DataType.INT8 -> ib.put((v - 128).toByte())
                else -> ib.put(v.toByte())
            }
        }
        if (channelsFirstIn) {
            // all reds, then all greens, then all blues
            for (shift in intArrayOf(16, 8, 0)) for (p in rgb) put((p shr shift) and 0xff)
        } else {
            for (p in rgb) { put((p shr 16) and 0xff); put((p shr 8) and 0xff); put(p and 0xff) }
        }
        ib.rewind()
        ob.rewind()
        it.run(ib, ob)
        ob.rewind()

        fun value(i: Int): Float = when (outType) {
            DataType.FLOAT32 -> ob.getFloat(i * 4)
            DataType.INT8 -> (ob.get(i) - outZero) * outScale
            else -> ((ob.get(i).toInt() and 0xff) - outZero) * outScale
        }

        // YOLOv8: [1, 4+C, N] (usual) or [1, N, 4+C]
        val a = outShape[1]
        val b = outShape[2]
        val channelsFirst = a < b
        val n = if (channelsFirst) b else a
        val c = (if (channelsFirst) a else b) - 4
        if (c <= 0) return emptyList()
        fun at(ch: Int, i: Int): Float = if (channelsFirst) value(ch * n + i) else value(i * (c + 4) + ch)

        // boxes may be 0..1 (normalised) or in input pixels
        var maxCoord = 0f
        for (i in 0 until min(n, 200)) maxCoord = max(maxCoord, at(0, i))
        val sx = if (maxCoord > 2f) 1f / inW else 1f
        val sy = if (maxCoord > 2f) 1f / inH else 1f

        val minScore = Cfg.detectorMinScore
        val found = ArrayList<Detection>()
        for (i in 0 until n) {
            var best = -1
            var bestS = minScore
            for (k in 0 until c) {
                val s = at(4 + k, i)
                if (s > bestS) { bestS = s; best = k }
            }
            if (best < 0) continue
            val cx = at(0, i) * sx
            val cy = at(1, i) * sy
            val bw = at(2, i) * sx
            val bh = at(3, i) * sy
            // map from the cropped region back to the whole screen
            val rw = r[2] - r[0]
            val rh = r[3] - r[1]
            found.add(
                Detection(
                    labels.getOrElse(best) { "class$best" }, bestS,
                    r[0] + (cx - bw / 2f) * rw, r[1] + (cy - bh / 2f) * rh,
                    r[0] + (cx + bw / 2f) * rw, r[1] + (cy + bh / 2f) * rh
                )
            )
        }
        // hero icons: which team, from the colour of the ring around the portrait
        return nms(found, 0.45f).take(50).map { d ->
            if (!d.label.contains("hero")) d else Detection(d.label, d.score, d.left, d.top, d.right, d.bottom, ringTeam(rgb, d, r))
        }
    }

    // count red / blue / green pixels on the icon's border ring (in the model's input image)
    private fun ringTeam(rgb: IntArray, d: Detection, r: FloatArray): String {
        val rw = r[2] - r[0]; val rh = r[3] - r[1]
        val cx = (d.cx - r[0]) / rw * inW; val cy = (d.cy - r[1]) / rh * inH
        val rad = maxOf((d.right - d.left) / rw * inW, (d.bottom - d.top) / rh * inH) / 2f
        var red = 0; var blue = 0; var green = 0
        for (k in 0 until 32) {
            val a = k * Math.PI * 2 / 32
            for (f in floatArrayOf(0.85f, 1.0f)) {
                val x = (cx + Math.cos(a) * rad * f).toInt(); val y = (cy + Math.sin(a) * rad * f).toInt()
                if (x !in 0 until inW || y !in 0 until inH) continue
                val p = rgb[y * inW + x]
                val rr = (p shr 16) and 0xff; val gg = (p shr 8) and 0xff; val bb = p and 0xff
                when {
                    rr > 150 && rr - maxOf(gg, bb) > 60 -> red++
                    bb > 150 && bb - rr > 60 -> blue++
                    gg > 150 && gg - maxOf(rr, bb) > 50 -> green++
                }
            }
        }
        val m = maxOf(red, blue, green)
        return when { m < 6 -> ""; m == green -> "self"; m == red -> "enemy"; else -> "ally" }
    }

    private fun nms(list: List<Detection>, iouMax: Float): List<Detection> {
        val sorted = list.sortedByDescending { it.score }
        val keep = ArrayList<Detection>()
        for (d in sorted) {
            if (keep.none { it.label == d.label && iou(it, d) > iouMax }) keep.add(d)
        }
        return keep
    }

    private fun iou(p: Detection, q: Detection): Float {
        val ix = max(0f, min(p.right, q.right) - max(p.left, q.left))
        val iy = max(0f, min(p.bottom, q.bottom) - max(p.top, q.top))
        val inter = ix * iy
        val union = (p.right - p.left) * (p.bottom - p.top) + (q.right - q.left) * (q.bottom - q.top) - inter
        return if (union <= 0f) 0f else inter / union
    }

    // Short text for Gemma's strategy prompt and the dashboard, e.g. "detector: 3 hero_icon, 1 turtle_icon"
    fun summary(): String {
        if (!ready || System.currentTimeMillis() - latestAt > 3000) return ""
        val counts = latest.groupingBy { it.label }.eachCount()
        if (counts.isEmpty()) return "detector: nothing"
        return "$name: " + counts.entries.joinToString { "${it.value} ${it.key}" }
    }
}

// the minimap detector
object ObjectDetector : YoloDetector("mlbb_detector.tflite", { Cfg.detectorLabels }, false, "detector")

// the whole-screen detector (notebooks/train_screen_detector.ipynb)
object ScreenDetector : YoloDetector("mlbb_screen_detector.tflite", { Cfg.screenLabels }, true, "screen detector") {
    // monsters by name (JungleDb): purple buff = thunder_fenrir, orange buff = molten_fiend;
    // jungle_creep = a small monster not named yet. Turrets: plated = gold energy shield (early game)
    val CLASSES = listOf("hero_ally", "hero_enemy", "hero_self", "minion_ally", "minion_enemy",
        "lord", "turtle", "molten_fiend", "thunder_fenrir", "lava_golem", "horned_lizard", "fire_beetle", "lithowanderer", "crab",
        "cyclone_eye", "jungle_creep",
        "tower_ally", "tower_ally_plated", "tower_enemy", "tower_enemy_plated", "base_ally", "base_enemy", "bush", "wall")
}
