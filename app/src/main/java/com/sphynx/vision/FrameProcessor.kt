package com.sphynx.vision

import com.sphynx.capture.FrameHub

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
    val bossHp: Float,                 // turtle / lord HP (0..1) from the bar under the scoreboard, -1 = none
    val bushes: List<Bush>,            // bushes on screen (tall grass: hide / ambush)
    val inBush: Boolean,               // you are standing in a bush (hidden from enemies)
    val screenWalls: BooleanArray,     // rock / cliff / tree cells on screen (40 x 20 grid)
    val level: Int = 1,                // your hero level 1..15 (LevelReader)
    val xp: Float = -1f,               // how full the EXP ring is, 0..1 (-1 unknown)
    val enemyLevel: Int = -1,          // nearest enemy hero's level, -1 unknown
    val towerRing: TowerRing.Ring = TowerRing.NONE,   // an enemy tower's range circle on screen and its colour
    val skillBadgePct: Float = -1f,    // 0..1 remaining skill duration displayed on the Level Badge, -1 = normal level badge (v37.15)
    val activeSkillIcons: Map<String, Float> = emptyMap(), // slot -> 0..1 remaining active duration displayed on the skill icon (v37.15)
    val transformBarPct: Float = -1f,  // 0..1 remaining duration on the red/orange center-bottom Transformation/Enhancement bar, -1 = none (v37.15)
    val recallBarPct: Float = -1f,     // 0..1 progress on the blue/cyan center-bottom Recall execution bar, -1 = none (v37.15)
    val processMs: Long
) {
    val aspect: Float get() = if (frameH == 0) 1.78f else frameW.toFloat() / frameH
}

// Rectangles drawn by our own overlay (floating icon/panel) so vision ignores them
object ScreenMasks {
    @Volatile var overlay: List<FloatArray> = emptyList()
}

// ONE FRAME'S READING, BY ONE GAME (Phase 2 adapter split, step 5): turns the screen's pixels into a VisionResult.
// Mobile Legends' is MlbbScreenReading; the app sets it at start (app/ProbesWiring). NONE reads nothing.
interface FrameReader {
    fun read(px: IntArray, w: Int, h: Int, t0: Long): VisionResult
    fun reset()

    object NONE : FrameReader {
        override fun read(px: IntArray, w: Int, h: Int, t0: Long) = VisionResult(
            ts = t0, frameW = w, frameH = h, self = null, allies = emptyList(), enemyHeroes = emptyList(),
            enemyMinions = emptyList(), allyMinions = emptyList(), all = emptyList(), minimap = MinimapInfo(0, 0, emptyList()),
            saturation = 0f, skillBrightness = emptyMap(), hint = ScreenHint.NONE, hud = HudInfo.NONE, heroSig = null,
            spellIcon = null, monsters = emptyList(), kda = IntArray(3), bossHp = -1f, bushes = emptyList(),
            inBush = false, screenWalls = BooleanArray(0), processMs = 0L)
        override fun reset() {}
    }
}

// THE FRAME PIPELINE (game-independent): takes the newest captured frame, lets the game's reader read it, keeps the
// latest result and the frame rate. Everything a game shows on screen is read by FrameProcessor.reader.
object FrameProcessor {

    @Volatile var reader: FrameReader = FrameReader.NONE

    private var lastId = 0L
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
        val result = reader.read(snap.pixels, snap.width, snap.height, t0)
        latest = result
        com.sphynx.core.Speed.vision(result.processMs)
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
        reader.reset()
        latest = null
        processedFps = 0f
    }
}
