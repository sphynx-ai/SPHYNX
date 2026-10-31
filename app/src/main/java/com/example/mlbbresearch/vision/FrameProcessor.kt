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
    val hint: ScreenHint,            // which MLBB screen the colours say this is
    val hud: HudInfo,                // upgrade "+", quick-buy icon, camera motion
    val heroSig: List<FloatArray>?,    // your hero's look: minimap portrait + in-game model colours (about once a second)
    val spellIcon: FloatArray?,        // fingerprint of the battle spell button
    val monsters: List<Seen>,          // jungle creeps (hero = false) and turtle / lord (hero = true)
    val kda: IntArray,                 // kills, deaths, assists gained on this frame (0 or 1 each)
    val bushes: List<Bush>,            // bushes on screen (tall grass: hide / ambush)
    val inBush: Boolean,               // you are standing in a bush (hidden from enemies)
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
        floatArrayOf(0.76f, 0.40f, 0.92f, 0.52f),   // the ultimate button's top (it reaches above the others)
        floatArrayOf(0.00f, 0.58f, 0.32f, 1.00f),   // joystick
        floatArrayOf(0.00f, 0.38f, 0.10f, 0.62f),   // shop / quick-buy column
        floatArrayOf(0.90f, 0.14f, 1.00f, 0.55f),   // right column (shop, signals, chat)
        floatArrayOf(0.45f, 0.82f, 0.66f, 1.00f),   // recall / regen / battle spell row and labels
        MinimapAnalyzer.rect
    )

    private val enemyTracker = EnemyDetector()
    private var lastId = 0L
    private var frameNo = 0L
    private var prevSmall: List<Seen> = emptyList()
    private var lastBush: BushDetector.Result? = null
    private var buffer: IntArray? = null

    @Volatile var latest: VisionResult? = null
        private set
    // "Learn from me" needs the camera motion and every button's brightness
    @Volatile var trackPlayer = false
    // the Player needs the camera motion too (to notice it is stuck on a wall)
    @Volatile var trackMotion = false
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

        frameNo++
        HeroDetector.neutralOn = Cfg.detectMonsters
        // a kill / first blood banner is up: ignore that area (its red lines are not enemies)
        val banner = ScreenReader.announcement(px, w, h)
        val seen = withoutAttachedBars(HeroDetector.scan(
            px, w, h, hud + ScreenMasks.overlay + (if (banner) listOf(ScreenReader.bannerRect) else emptyList()),
            Cfg.heroBarMinWidthPct / 100f, Cfg.bodyBelowBarPct / 100f
        ))
        // optional trained detector: runs on its own thread a few times a second
        ObjectDetector.maybeRun(px, w, h)
        // you: the green hero bar closest to the centre of the screen
        val self = seen.filter { it.team == Team.SELF }
            .minByOrNull { (it.x - 0.5f) * (it.x - 0.5f) + (it.y - 0.5f) * (it.y - 0.5f) }
        // coloured bits of your own hero (e.g. a blue skin) are not units
        fun onSelf(u: Seen) = self != null && u !== self && !u.hero &&
            kotlin.math.abs(u.x - self.x) < 0.05f && u.barTop > self.barBottom - 0.01f && u.barTop < self.y + 0.14f
        // a minion / monster must be seen on two frames in a row at about the same place:
        // a single-frame flash (effects, skill colours) is not a target
        fun steady(u: Seen) = prevSmall.any { it.team == u.team && kotlin.math.abs(it.x - u.x) < 0.04f && kotlin.math.abs(it.y - u.y) < 0.05f }
        val small = seen.filter { !it.hero && it.team != Team.SELF && !onSelf(it) }
        val steadySmall = small.filter { steady(it) }
        prevSmall = small
        val aspect = w.toFloat() / h
        val tracks = enemyTracker.update(seen.filter { it.team == Team.ENEMY && it.hero }, t0, aspect)

        val brightness = HashMap<String, Float>()
        if (Cfg.readCooldownsFromScreen || trackPlayer) {
            for (k in listOf("s1", "s2", "ult", "spell", "recall", "regen")) {
                val (fx, fy) = Layout.get(k)
                brightness[k] = ringBrightness(px, w, h, fx, fy)
            }
        }

        // walls from the minimap, every couple of seconds while the normal minimap shows
        val hint = ScreenReader.classify(px, w, h)
        val mini = MinimapAnalyzer.analyze(px, w, h)
        if (self != null && hint == ScreenHint.NONE && frameNo % 25 == 3L) WallMap.update(px, w, h)

        // bushes every 3rd frame (they do not move; the camera does, slowly)
        val bush = if (self != null && (frameNo % 3 == 0L || lastBush == null)) {
            BushDetector.detect(px, w, h, hud + ScreenMasks.overlay, self).also { lastBush = it }
        } else if (self != null) lastBush!! else BushDetector.Result(emptyList(), false)

        val result = VisionResult(
            ts = t0, frameW = w, frameH = h,
            self = self,
            allies = seen.filter { it.team == Team.ALLY && it.hero },
            enemyHeroes = tracks,
            enemyMinions = steadySmall.filter { it.team == Team.ENEMY },
            allyMinions = steadySmall.filter { it.team == Team.ALLY },
            all = seen,
            minimap = mini,
            saturation = HeroDetector.saturation(px, w, h),
            skillBrightness = brightness,
            hint = hint,
            hud = if (self != null || trackPlayer) ScreenReader.hud(px, w, h, trackPlayer || trackMotion) else HudInfo.NONE,
            heroSig = if (self != null && frameNo % 12 == 0L) heroSig(px, w, h, self, mini) else null,
            spellIcon = if (self != null && frameNo % 12 == 6L) IconMemory.print(px, w, h, "spell", 0.016f) else null,
            monsters = steadySmall.filter { it.team == Team.NEUTRAL } + seen.filter { it.team == Team.NEUTRAL && it.hero },
            kda = if (self != null) KdaReader.read(px, w, h) else IntArray(3),
            bushes = bush.bushes,
            inBush = bush.inBush,
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

    // Many heroes have a second bar right under their HP bar (passive, energy, mana, e.g. Thamuz's
    // red passive bar). It is part of that hero, not a unit: drop any bar that starts inside a
    // hero bar's width and sits just below it.
    private fun withoutAttachedBars(all: List<Seen>): List<Seen> {
        val heroes = all.filter { it.hero || it.team == Team.SELF }
        return all.filter { b ->
            heroes.none { a ->
                a !== b && b.barTop >= a.barBottom - 0.002f && b.barTop - a.barBottom <= 0.03f &&
                    b.barLeft >= a.barLeft - 0.01f && b.barLeft <= a.barRight
            }
        }
    }

    // Brightness of a ring inside a button, around (not at) its centre: a cooling-down skill is
    // dark there, while its countdown digits sit in the very middle and would look bright.
    private fun ringBrightness(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        var sum = 0
        var n = 0
        for (r in floatArrayOf(0.012f, 0.017f)) for (i in 0 until 24) {
            val a = i * Math.PI * 2 / 24
            val x = (fx * w + Math.cos(a) * r * w).toInt()
            val y = (fy * h + Math.sin(a) * r * w).toInt()
            if (x !in 0 until w || y !in 0 until h) continue
            val p = px[y * w + x]
            sum += (((p shr 16) and 0xff) * 3 + ((p shr 8) and 0xff) * 6 + (p and 0xff)) / 10
            n++
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    private fun heroSig(px: IntArray, w: Int, h: Int, self: Seen, mini: MinimapInfo): List<FloatArray>? {
        val icon = mini.selfIcon ?: return null
        val portrait = IconMemory.portrait(px, w, h, icon) ?: return null
        return listOf(portrait, IconMemory.model(px, w, h, self))
    }

    fun reset() {
        enemyTracker.clear()
        latest = null
        processedFps = 0f
    }
}
