package com.example.mlbbresearch.vision

import com.example.mlbbresearch.capture.FrameHub
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Layout

// Everything the fast vision layer found in one frame.
class VisionResult(
    val ts: Long,
    val frameW: Int,
    val frameH: Int,
    val self: Seen?,
    val allies: List<Seen>,          // ally heroes
    val enemyHeroes: List<Track>,    // tracked, with ids
    val enemyMinions: List<Seen>,
    val allyMinions: List<Seen>,
    val all: List<Seen>,             // for drawing boxes
    val minimap: MinimapInfo,
    val saturation: Float,
    val skillBrightness: Map<String, Float>,
    val processMs: Long
) {
    val aspect: Float get() = if (frameH == 0) 1.78f else frameW.toFloat() / frameH
}

// Rectangles drawn by our own overlay (floating icon/panel) so vision ignores them
object ScreenMasks {
    @Volatile var overlay: List<FloatArray> = emptyList()
}

object FrameProcessor {

    // HUD areas where HP-bar-like colours are never units: scoreboard, buttons, minimap...
    private val hud = listOf(
        floatArrayOf(0.30f, 0.00f, 0.70f, 0.09f),   // top scoreboard
        floatArrayOf(0.80f, 0.00f, 1.00f, 0.14f),   // top-right buttons
        floatArrayOf(0.62f, 0.52f, 1.00f, 1.00f),   // skill buttons
        floatArrayOf(0.00f, 0.58f, 0.32f, 1.00f),   // joystick
        floatArrayOf(0.00f, 0.38f, 0.10f, 0.62f),   // shop / quick-buy column
        MinimapAnalyzer.rect
    )

    private val enemyTracker = EnemyDetector()
    private var lastId = 0L
    private var buffer: IntArray? = null

    @Volatile var latest: VisionResult? = null
        private set
    @Volatile var processedFps = 0f
    private var fpsCount = 0
    private var fpsStart = 0L

    // Returns null when there is no new frame yet
    fun processNext(): VisionResult? {
        val snap = FrameHub.snapshot(lastId, buffer) ?: return null
        buffer = snap.pixels
        lastId = snap.id
        val t0 = System.currentTimeMillis()
        val w = snap.width
        val h = snap.height
        val px = snap.pixels

        val seen = HeroDetector.scan(
            px, w, h, hud + ScreenMasks.overlay,
            Cfg.heroBarMinWidthPct / 100f, Cfg.bodyBelowBarPct / 100f
        )
        // you: the green hero bar closest to the centre of the screen
        val self = seen.filter { it.team == Team.SELF }
            .minByOrNull { (it.x - 0.5f) * (it.x - 0.5f) + (it.y - 0.5f) * (it.y - 0.5f) }
        val aspect = w.toFloat() / h
        val tracks = enemyTracker.update(seen.filter { it.team == Team.ENEMY && it.hero }, t0, aspect)

        val brightness = HashMap<String, Float>()
        if (Cfg.readCooldownsFromScreen) {
            for (k in listOf("s1", "s2", "ult")) {
                val (fx, fy) = Layout.get(k)
                brightness[k] = HeroDetector.brightness(px, w, h, fx, fy, 0.012f)
            }
        }

        val result = VisionResult(
            ts = t0, frameW = w, frameH = h,
            self = self,
            allies = seen.filter { it.team == Team.ALLY && it.hero },
            enemyHeroes = tracks,
            enemyMinions = seen.filter { it.team == Team.ENEMY && !it.hero },
            allyMinions = seen.filter { it.team == Team.ALLY && !it.hero },
            all = seen,
            minimap = MinimapAnalyzer.analyze(px, w, h),
            saturation = HeroDetector.saturation(px, w, h),
            skillBrightness = brightness,
            processMs = System.currentTimeMillis() - t0
        )
        latest = result
        fpsCount++
        if (fpsStart == 0L) fpsStart = t0
        if (t0 - fpsStart >= 1000) {
            processedFps = fpsCount * 1000f / (t0 - fpsStart)
            fpsCount = 0
            fpsStart = t0
        }
        return result
    }

    fun reset() {
        enemyTracker.clear()
        latest = null
        processedFps = 0f
    }
}
