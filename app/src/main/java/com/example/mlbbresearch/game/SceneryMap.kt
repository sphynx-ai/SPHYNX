package com.example.mlbbresearch.game

import com.example.mlbbresearch.ai.Intent
import com.example.mlbbresearch.ai.TacticalDecision
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.vision.MinimapAnalyzer
import com.example.mlbbresearch.vision.Seen
import com.example.mlbbresearch.vision.TowerRing
import com.example.mlbbresearch.vision.WallMap
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.hypot

// IN-GAME 3D SCENERY & MINIMAP SYNCHRONIZATION + WORLD MEMORY (v37.11):
// Synchronizes the 3D screen camera view with the 2D minimap and memorizes the entire map:
//  - Perspective-foreshortened 3D-to-Minimap projection (accounts for MLBB ~52° camera tilt, where
//    the top of the screen spans wider world ground than the bottom of the screen)
//  - Live Landmark Auto-Synchronization: aligns 3D screen Tower Rings, Monster Bars, and Bushes with
//    their Minimap counterparts to continuously calibrate scale (scaleX, scaleY) and offset (offX, offY)
//  - Bidirectional WallMap <-> SceneryMap fusion so 3D walls, monster pit back-walls, and bushes stay
//    permanently synchronized with minimap A* pathfinding
//  - Autonomous 28-waypoint Practice Mode Roam Tour
object SceneryMap {

    const val G = 64
    private const val CFG_KEY = "scenery_map_v1"
    // Perspective tilt factor: top of screen (sy < self.y) spans ~24% wider world distance than bottom
    private const val PERSP_TILT = 0.24f

    class PitSpot(
        var x: Float,
        var y: Float,
        var standX: Float,
        var standY: Float,
        var dirX: Float = 0f,
        var dirY: Float = -1f,
        var label: String = "camp",
        var buff: Boolean = false
    )

    private val wallHits = IntArray(G * G)
    @Volatile var walls = BooleanArray(G * G)
        private set
    val bushes = CopyOnWriteArrayList<Pair<Float, Float>>()
    val towers = CopyOnWriteArrayList<Pair<Float, Float>>()
    val pits = CopyOnWriteArrayList<PitSpot>()

    @Volatile var roamActive = false
        private set
    @Volatile var roamIdx = 0
        private set
    @Volatile var status = "ready"
        private set
    @Volatile var syncAnchors = 0
        private set
    @Volatile var lastSyncDrift = 0f
        private set
    private var loaded = false
    private var lastSaveAt = 0L
    private var waypointArrivedAt = 0L
    private var waypointStartedAt = 0L

    // Default MLBB monster pits with open-mouth entrance stand-spots
    private val defaultPits = listOf(
        PitSpot(0.27f, 0.56f, 0.29f, 0.58f, -0.5f, -0.5f, "purple_buff", true),
        PitSpot(0.52f, 0.77f, 0.50f, 0.75f, 0.5f, 0.5f, "orange_buff", true),
        PitSpot(0.20f, 0.66f, 0.22f, 0.65f, -0.6f, 0.2f, "rockursa", false),
        PitSpot(0.39f, 0.62f, 0.38f, 0.64f, 0.3f, -0.6f, "lithowanderer", false),
        PitSpot(0.44f, 0.84f, 0.44f, 0.81f, 0.0f, 0.8f, "horned_lizard", false),
        PitSpot(0.62f, 0.72f, 0.60f, 0.73f, 0.6f, -0.3f, "fire_beetle", false),
        PitSpot(0.31f, 0.26f, 0.33f, 0.29f, -0.5f, -0.6f, "turtle_top", false),
        PitSpot(0.67f, 0.68f, 0.64f, 0.65f, 0.5f, 0.6f, "lord_bot", false)
    )

    // timeoutMs: how long the stop may take before the tour moves on (the enemy-base stops are farther apart)
    class Waypoint(val x: Float, val y: Float, val name: String, val timeoutMs: Long = 9500L)
    val tourWaypoints: List<Waypoint> = listOf(
        Waypoint(0.18f, 0.82f, "Ally Base Gate"),
        Waypoint(0.22f, 0.65f, "Ally Rockursa Pit"),
        Waypoint(0.29f, 0.58f, "Ally Purple Buff Pit"),
        Waypoint(0.38f, 0.64f, "Ally Lithowanderer & Mid Bush"),
        Waypoint(0.50f, 0.50f, "Mid Lane River Crossing"),
        Waypoint(0.33f, 0.29f, "Top-Left Turtle / Lord Pit"),
        Waypoint(0.22f, 0.20f, "Top River Crab & Bush"),
        Waypoint(0.10f, 0.55f, "EXP Lane Inner Tower"),
        Waypoint(0.09f, 0.30f, "EXP Lane Mid Tower & Bush"),
        Waypoint(0.10f, 0.12f, "EXP Lane Corner & Top Bush"),
        Waypoint(0.30f, 0.10f, "Enemy Top Lane Outer Tower"),
        Waypoint(0.46f, 0.24f, "Enemy Orange Buff Pit"),
        Waypoint(0.38f, 0.30f, "Enemy Upper Jungle Pit"),
        Waypoint(0.58f, 0.36f, "Enemy Mid Bush & Litho"),
        Waypoint(0.70f, 0.42f, "Enemy Purple Buff Pit"),
        Waypoint(0.78f, 0.34f, "Enemy Golem Pit"),
        Waypoint(0.64f, 0.65f, "Bottom-Right Lord / Turtle Pit"),
        Waypoint(0.76f, 0.78f, "Bottom River Crab & Gold Bush"),
        Waypoint(0.88f, 0.88f, "Gold Lane Corner & Bush"),
        Waypoint(0.88f, 0.62f, "Gold Lane Enemy Tower"),
        Waypoint(0.68f, 0.90f, "Gold Lane Mid Tower & Bush"),
        Waypoint(0.45f, 0.90f, "Gold Lane Inner Tower"),
        Waypoint(0.44f, 0.81f, "Ally Lizard Pit"),
        Waypoint(0.50f, 0.75f, "Ally Orange Buff Pit"),
        Waypoint(0.60f, 0.73f, "Ally Fire Beetle Pit"),
        Waypoint(0.36f, 0.64f, "Mid Lane Ally Tower"),
        Waypoint(0.62f, 0.38f, "Mid Lane Enemy Tower"),
        // THE ENEMY BASE (owner, 2026-11-11: solo practice vs Computer with god mode on, so the Research player
        // cannot die there). Measured on the owner's minimap (screenshot, 2026-11-10): enemy tower icons at
        // top lane 0.27/0.54/0.72 (y 0.06), mid 0.60,0.42 / 0.70,0.32 / 0.78,0.21, gold lane 0.93,0.73 /
        // 0.93,0.53 / 0.92,0.27, base core 0.87,0.10. Each stop is just on the lane side of its tower.
        Waypoint(0.68f, 0.35f, "Mid Lane Enemy Inner Tower", 20_000L),
        Waypoint(0.76f, 0.25f, "Mid Lane Enemy Base Tower", 20_000L),
        Waypoint(0.83f, 0.15f, "Enemy Base (core)", 20_000L),
        Waypoint(0.71f, 0.10f, "Top Lane Enemy Base Tower", 20_000L),
        Waypoint(0.54f, 0.09f, "Top Lane Enemy Inner Tower", 20_000L),
        Waypoint(0.89f, 0.29f, "Gold Lane Enemy Base Tower", 20_000L),
        Waypoint(0.89f, 0.53f, "Gold Lane Enemy Inner Tower", 20_000L),
        Waypoint(0.90f, 0.72f, "Gold Lane Enemy Outer Tower", 20_000L),
        Waypoint(0.50f, 0.50f, "Center River Return", 20_000L)
    )

    // Perspective-foreshortened projection from 3D screen (sx, sy) to 2D minimap world coordinates (0..1)
    fun projectToWorld(me: Pair<Float, Float>, self: Seen, sx: Float, sy: Float): Pair<Float, Float> {
        val scaleX = Cfg.sceneryScaleX.coerceIn(0.11f, 0.22f)
        val scaleY = Cfg.sceneryScaleY.coerceIn(0.07f, 0.16f)
        val offX = Cfg.sceneryOffsetX.coerceIn(-0.04f, 0.04f)
        val offY = Cfg.sceneryOffsetY.coerceIn(-0.04f, 0.04f)
        val dy = sy - self.y
        val dx = sx - self.x
        // Isometric camera perspective: upper screen (dy < 0) covers wider horizontal & vertical ground
        val persp = (1f - dy * PERSP_TILT).coerceIn(0.82f, 1.24f)
        val wx = (me.first + offX + dx * scaleX * persp).coerceIn(0.02f, 0.98f)
        val wy = (me.second + offY + dy * scaleY * persp).coerceIn(0.02f, 0.98f)
        return wx to wy
    }

    // Inverse projection from 2D minimap world coordinates (wx, wy) to 3D screen fractions (sx, sy)
    fun worldToScreen(me: Pair<Float, Float>, self: Seen, wx: Float, wy: Float): Pair<Float, Float> {
        val scaleX = Cfg.sceneryScaleX.coerceIn(0.11f, 0.22f)
        val scaleY = Cfg.sceneryScaleY.coerceIn(0.07f, 0.16f)
        val offX = Cfg.sceneryOffsetX.coerceIn(-0.04f, 0.04f)
        val offY = Cfg.sceneryOffsetY.coerceIn(-0.04f, 0.04f)
        val dwy = wy - (me.second + offY)
        val dyApprox = (dwy / scaleY).coerceIn(-0.5f, 0.5f)
        val persp = (1f - dyApprox * PERSP_TILT).coerceIn(0.82f, 1.24f)
        val sx = (self.x + (wx - (me.first + offX)) / (scaleX * persp)).coerceIn(0f, 1f)
        val sy = (self.y + dwy / (scaleY * persp)).coerceIn(0f, 1f)
        return sx to sy
    }

    // Cycles Camera Height preset ("high" = MLBB High Camera Height, "low" = Low Camera Height, "wide" = Tablet/Wide)
    fun cycleCameraPreset(): String {
        val next = when (Cfg.cameraHeightMode) {
            "high" -> "low"
            "low" -> "wide"
            else -> "high"
        }
        Cfg.cameraHeightMode = next
        when (next) {
            "high" -> { Cfg.sceneryScaleX = 0.158f; Cfg.sceneryScaleY = 0.112f }
            "low" -> { Cfg.sceneryScaleX = 0.134f; Cfg.sceneryScaleY = 0.095f }
            "wide" -> { Cfg.sceneryScaleX = 0.172f; Cfg.sceneryScaleY = 0.124f }
        }
        return cameraPresetLabel()
    }

    fun cameraPresetLabel(): String = when (Cfg.cameraHeightMode) {
        "low" -> "Low camera height (13.4% x 9.5%)"
        "wide" -> "Wide / tablet camera (17.2% x 12.4%)"
        else -> "High camera height (15.8% x 11.2%)"
    }

    fun resetSyncCalibration(): String {
        Cfg.cameraHeightMode = "high"
        Cfg.sceneryScaleX = 0.158f
        Cfg.sceneryScaleY = 0.112f
        Cfg.sceneryOffsetX = 0f
        Cfg.sceneryOffsetY = 0f
        Cfg.setRaw("sync_reset_at", System.currentTimeMillis().toString())
        syncAnchors = 0
        lastSyncDrift = 0f
        com.example.mlbbresearch.learning.OnlineBackup.saveScenerySyncNow()
        return "Reset scenery-minimap sync offsets to defaults (saved to phone)"
    }

    fun syncStatus(): String =
        "Camera: ${cameraPresetLabel()} · Auto-sync: ${if (Cfg.sceneryAutoSync) "ON" else "OFF"}\n" +
            "Scale: %.1f%% x %.1f%% · Offset: %+.2f%%, %+.2f%% · Anchors matched: %d (drift %.2f%%)".format(
                Locale.US,
                Cfg.sceneryScaleX * 100f, Cfg.sceneryScaleY * 100f,
                Cfg.sceneryOffsetX * 100f, Cfg.sceneryOffsetY * 100f,
                syncAnchors, lastSyncDrift * 100f
            )

    // Synchronizes 3D screen landmarks (towers, camps/pits, bushes) with 2D minimap landmarks
    fun syncWithMinimap(s: GameState, forceManual: Boolean = false): Int {
        val me = s.minimapSelf ?: return 0
        val self = s.self ?: return 0
        if (!Cfg.sceneryAutoSync && !forceManual) return 0

        var errSumX = 0f
        var errSumY = 0f
        var scaleRatioXSum = 0f
        var scaleRatioYSum = 0f
        var scaleSamples = 0
        var count = 0

        fun addAnchor(sx: Float, sy: Float, mapX: Float, mapY: Float, maxMatchDist: Float) {
            val (projX, projY) = projectToWorld(me, self, sx, sy)
            val errX = mapX - projX
            val errY = mapY - projY
            val d = hypot(errX, errY)
            if (d <= maxMatchDist) {
                errSumX += errX
                errSumY += errY
                count++
                val dx = sx - self.x
                val dy = sy - self.y
                val persp = (1f - dy * PERSP_TILT).coerceIn(0.82f, 1.24f)
                if (abs(dx) >= 0.12f) {
                    val impliedX = (mapX - (me.first + Cfg.sceneryOffsetX)) / (dx * persp)
                    if (impliedX in 0.11f..0.22f) { scaleRatioXSum += impliedX; scaleSamples++ }
                }
                if (abs(dy) >= 0.12f) {
                    val impliedY = (mapY - (me.second + Cfg.sceneryOffsetY)) / (dy * persp)
                    if (impliedY in 0.07f..0.16f) { scaleRatioYSum += impliedY }
                }
            }
        }

        // 1) 3D TowerRing on screen <-> nearest Minimap Tower
        if (s.towerRing.zone != TowerRing.Zone.NONE && s.towers.isNotEmpty()) {
            val (px, py) = projectToWorld(me, self, s.towerRing.cx, s.towerRing.cy)
            val nearTower = s.towers.minByOrNull { hypot(it.x - px, it.y - py) }
            if (nearTower != null) {
                addAnchor(s.towerRing.cx, s.towerRing.cy, nearTower.x, nearTower.y, 0.055f)
            }
        }

        // 2) 3D Monsters on screen <-> Minimap Camps & Boss Pits
        val allMapCamps = s.camps.map { it.x to it.y } + MinimapAnalyzer.PITS + defaultPits.map { it.x to it.y }
        for (m in s.monsters) {
            val (px, py) = projectToWorld(me, self, m.x, m.y)
            val nearCamp = allMapCamps.minByOrNull { hypot(it.first - px, it.second - py) }
            if (nearCamp != null) {
                addAnchor(m.x, m.y, nearCamp.first, nearCamp.second, 0.050f)
            }
        }

        // 3) 3D Bushes on screen <-> Minimap Bush cells in WallMap
        if (WallMap.ready) {
            for (b in s.bushes) {
                val (px, py) = projectToWorld(me, self, b.x, b.y)
                val nearBush = WallMap.nearestBush(px to py, 0.045f)
                if (nearBush != null) {
                    addAnchor(b.x, b.y, nearBush.first, nearBush.second, 0.045f)
                }
            }
        }

        // 4) 3D Enemy Hero on screen <-> Minimap Enemy Dot (keeps combat aiming 100% synchronized) (v37.16)
        if (s.enemies.size == 1 && s.map.enemies.isNotEmpty()) {
            val e = s.enemies[0]
            val (px, py) = projectToWorld(me, self, e.x, e.y)
            val nearDot = s.map.enemies.minByOrNull { hypot(it.first - px, it.second - py) }
            if (nearDot != null) {
                addAnchor(e.x, e.y, nearDot.first, nearDot.second, 0.055f)
            }
        }

        if (count > 0) {
            val meanErrX = errSumX / count
            val meanErrY = errSumY / count
            lastSyncDrift = hypot(meanErrX, meanErrY)
            val alpha = if (forceManual) 0.55f else 0.08f
            Cfg.sceneryOffsetX = (Cfg.sceneryOffsetX + meanErrX * alpha).coerceIn(-0.035f, 0.035f)
            Cfg.sceneryOffsetY = (Cfg.sceneryOffsetY + meanErrY * alpha).coerceIn(-0.035f, 0.035f)
            if (scaleSamples > 0 && scaleRatioXSum > 0f) {
                val targetScaleX = (scaleRatioXSum / scaleSamples).coerceIn(0.12f, 0.20f)
                Cfg.sceneryScaleX = Cfg.sceneryScaleX * (1f - alpha * 0.5f) + targetScaleX * (alpha * 0.5f)
            }
            syncAnchors += count
        }
        return count
    }

    // One-tap manual sync called from Player / Setup page
    fun syncNow(s: GameState): String {
        val matched = syncWithMinimap(s, forceManual = true)
        reapplyToWallMap()
        saveNow()
        return if (matched > 0) {
            "Synced $matched 3D scenery landmark(s) with minimap (drift ${"%.2f".format(Locale.US, lastSyncDrift * 100f)}%)"
        } else {
            "Fused saved 3D scenery (${summary()}) with minimap"
        }
    }

    // Re-applies memorized 3D walls and bushes onto WallMap whenever WallMap.update() refreshes the 40x40 minimap grid
    fun reapplyToWallMap() {
        ensureLoaded()
        for (i in 0 until G * G) {
            if (walls[i]) {
                val wx = (i % G + 0.5f) / G
                val wy = (i / G + 0.5f) / G
                WallMap.markSceneryWall(wx, wy)
            }
        }
        for ((bx, by) in bushes) {
            WallMap.markSceneryBush(bx, by)
        }
    }

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (pits.isEmpty()) pits.addAll(defaultPits)
        val raw = Cfg.raw(CFG_KEY) ?: return
        try {
            val o = JSONObject(raw)
            o.optJSONArray("walls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val idx = arr.optInt(i, -1)
                    if (idx in 0 until G * G) {
                        walls[idx] = true
                        wallHits[idx] = 3
                        val wx = (idx % G + 0.5f) / G
                        val wy = (idx / G + 0.5f) / G
                        }
                }
            }
            o.optJSONArray("bushes")?.let { arr ->
                bushes.clear()
                for (i in 0 until arr.length()) {
                    val b = arr.optJSONObject(i) ?: continue
                    val bx = b.optDouble("x", -1.0).toFloat()
                    val by = b.optDouble("y", -1.0).toFloat()
                    if (bx in 0f..1f && by in 0f..1f) {
                        bushes.add(bx to by)
                        }
                }
            }
            o.optJSONArray("towers")?.let { arr ->
                towers.clear()
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val tx = t.optDouble("x", -1.0).toFloat()
                    val ty = t.optDouble("y", -1.0).toFloat()
                    if (tx in 0f..1f && ty in 0f..1f) towers.add(tx to ty)
                }
            }
            o.optJSONArray("pits")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val px = p.optDouble("x", -1.0).toFloat()
                    val py = p.optDouble("y", -1.0).toFloat()
                    if (px !in 0f..1f || py !in 0f..1f) continue
                    val sx = p.optDouble("sx", px.toDouble()).toFloat()
                    val sy = p.optDouble("sy", py.toDouble()).toFloat()
                    val dx = p.optDouble("dx", 0.0).toFloat()
                    val dy = p.optDouble("dy", -1.0).toFloat()
                    val lbl = p.optString("label", "camp")
                    val bf = p.optBoolean("buff", false)
                    val existing = pits.firstOrNull { hypot(it.x - px, it.y - py) < 0.05f }
                    if (existing != null) {
                        existing.x = px; existing.y = py; existing.standX = sx; existing.standY = sy
                        existing.dirX = dx; existing.dirY = dy; existing.label = lbl; existing.buff = bf
                    } else {
                        pits.add(PitSpot(px, py, sx, sy, dx, dy, lbl, bf))
                    }
                }
            }
            status = summary()
        } catch (e: Exception) {
            Diag.error("SceneryMap load", e)
        }
    }

    @Synchronized
    fun saveNow() {
        try {
            val o = JSONObject()
            val wArr = JSONArray()
            for (i in 0 until G * G) if (walls[i]) wArr.put(i)
            o.put("walls", wArr)
            val bArr = JSONArray()
            for ((bx, by) in bushes.take(120)) bArr.put(JSONObject().put("x", bx.toDouble()).put("y", by.toDouble()))
            o.put("bushes", bArr)
            val tArr = JSONArray()
            for ((tx, ty) in towers.take(40)) tArr.put(JSONObject().put("x", tx.toDouble()).put("y", ty.toDouble()))
            o.put("towers", tArr)
            val pArr = JSONArray()
            for (p in pits.take(40)) {
                pArr.put(JSONObject()
                    .put("x", p.x.toDouble()).put("y", p.y.toDouble())
                    .put("sx", p.standX.toDouble()).put("sy", p.standY.toDouble())
                    .put("dx", p.dirX.toDouble()).put("dy", p.dirY.toDouble())
                    .put("label", p.label).put("buff", p.buff))
            }
            o.put("pits", pArr)
            Cfg.setRaw(CFG_KEY, o.toString())
            com.example.mlbbresearch.learning.OnlineBackup.saveScenerySyncNow()
            lastSaveAt = System.currentTimeMillis()
        } catch (e: Exception) {
            Diag.error("SceneryMap save", e)
        }
    }

    @Synchronized
    fun reloadFromCfg() {
        loaded = false
        ensureLoaded()
    }

    fun clear() {
        wallHits.fill(0)
        walls = BooleanArray(G * G)
        bushes.clear()
        towers.clear()
        pits.clear()
        pits.addAll(defaultPits)
        Cfg.setRaw(CFG_KEY, null)
        Cfg.setRaw("scenery_cleared_at", System.currentTimeMillis().toString())
        com.example.mlbbresearch.learning.OnlineBackup.saveScenerySyncNow()
        status = "cleared (deleted from phone storage)"
    }

    fun startRoam(): String {
        ensureLoaded()
        roamActive = true
        roamIdx = 0
        waypointArrivedAt = 0L
        // (the tour's clock starts at its first step, once the practice gate has unlocked the Player)
        waypointStartedAt = 0L
        if (!com.example.mlbbresearch.ai.AiRuntime.playerOn) {
            com.example.mlbbresearch.ai.AiRuntime.start("player")
        }
        status = if (playerUnlocked()) "roaming map (1/${tourWaypoints.size}: ${tourWaypoints[0].name})"
            else "waiting: starts when the practice gate unlocks the Player (open the scoreboard once)"
        Diag.info("Scenery calibration roam started (${tourWaypoints.size} waypoints)")
        return status
    }

    fun stopRoam(): String {
        roamActive = false
        saveNow()
        status = "stopped · " + summary()
        Diag.info("Scenery calibration roam stopped: $status")
        return status
    }

    fun toggleRoam(): String = if (roamActive) stopRoam() else startRoam()

    fun summary(): String {
        ensureLoaded()
        val wCount = walls.count { it }
        return "${wCount} walls, ${bushes.size} bushes, ${pits.size} pits, ${towers.size} towers"
    }

    // Observe the actual 3D screen scenery every frame, synchronize landmarks with the minimap, and map into world coordinates
    fun observe(s: GameState) {
        ensureLoaded()
        val me = s.minimapSelf ?: return
        val self = s.self ?: return
        val now = s.ts

        // Synchronize 3D landmarks against minimap landmarks first so projection scale & offset are locked
        syncWithMinimap(s, forceManual = false)

        // 0) Where the hero is standing right now is walkable ground, never a wall!
        val selfGx = (me.first * G).toInt().coerceIn(0, G - 1)
        val selfGy = (me.second * G).toInt().coerceIn(0, G - 1)
        walls[selfGy * G + selfGx] = false
        wallHits[selfGy * G + selfGx] = 0
        WallMap.clearWallCell(me.first, me.second)

        var changed = false

        // 1) Project 3D screen walls (40x20 grid) into world coordinates around `me` using perspective projection
        val sw = s.screenWalls
        if (sw.size >= 40 * 20) {
            for (gy in 1 until 19) for (gx in 1 until 39) {
                if (!sw[gy * 40 + gx]) continue
                var neighbors = 0
                if (sw[gy * 40 + gx - 1]) neighbors++
                if (sw[gy * 40 + gx + 1]) neighbors++
                if (sw[(gy - 1) * 40 + gx]) neighbors++
                if (sw[(gy + 1) * 40 + gx]) neighbors++
                if (neighbors < 2) continue
                val sx = (gx + 0.5f) / 40f
                val sy = (gy + 0.5f) / 20f
                if (hypot(sx - self.x, sy - self.y) < 0.07f) continue
                val (wx, wy) = projectToWorld(me, self, sx, sy)
                val wgx = (wx * G).toInt().coerceIn(0, G - 1)
                val wgy = (wy * G).toInt().coerceIn(0, G - 1)
                if (wgx == selfGx && wgy == selfGy) continue
                val idx = wgy * G + wgx
                wallHits[idx]++
                val threshold = if (roamActive) 2 else 4
                if (wallHits[idx] >= threshold && !walls[idx]) {
                    walls[idx] = true
                    WallMap.markSceneryWall(wx, wy)
                    changed = true
                }
            }
        }

        // 2) Project 3D screen bushes into world coordinates
        for (b in s.bushes) {
            val (wx, wy) = projectToWorld(me, self, b.x, b.y)
            if (bushes.none { hypot(it.first - wx, it.second - wy) < 0.025f }) {
                bushes.add(wx to wy)
                WallMap.markSceneryBush(wx, wy)
                changed = true
            }
        }

        // 3) Project 3D screen monsters into memorized Monster Pits along with walkable entrance stand-spot
        for (m in s.monsters) {
            val (wx, wy) = projectToWorld(me, self, m.x, m.y)
            val dx = (m.x - self.x) * s.aspect
            val dy = m.y - self.y
            val dl = hypot(dx, dy).coerceAtLeast(1e-3f)
            val ux = dx / dl; val uy = dy / dl
            val isBuff = com.example.mlbbresearch.game.JungleDb.isBuff(m.species)
            val existing = pits.firstOrNull { hypot(it.x - wx, it.y - wy) < 0.055f }
            if (existing != null) {
                existing.x = existing.x * 0.7f + wx * 0.3f
                existing.y = existing.y * 0.7f + wy * 0.3f
                if (s.distToSelf(m.x, m.y) <= 0.34f) {
                    existing.standX = existing.standX * 0.6f + me.first * 0.4f
                    existing.standY = existing.standY * 0.6f + me.second * 0.4f
                    existing.dirX = ux; existing.dirY = uy
                }
                if (m.species.isNotEmpty()) existing.label = m.species
                if (isBuff) existing.buff = true
            } else {
                pits.add(PitSpot(wx, wy, me.first, me.second, ux, uy, m.species.ifEmpty { "camp" }, isBuff))
                changed = true
            }
        }

        // 4) Project 3D enemy tower rings seen on screen into memorized Towers
        if (s.towerRing.zone != TowerRing.Zone.NONE) {
            val (wx, wy) = projectToWorld(me, self, s.towerRing.cx, s.towerRing.cy)
            if (towers.none { hypot(it.first - wx, it.second - wy) < 0.04f }) {
                towers.add(wx to wy)
                changed = true
            }
        }

        if (changed && now - lastSaveAt > 6000L) {
            saveNow()
        }
    }

    // Best walkable entrance / stand-spot for a camp at (campX, campY)
    fun pitStandSpot(campX: Float, campY: Float): Pair<Float, Float> {
        ensureLoaded()
        val p = pits.minByOrNull { hypot(it.x - campX, it.y - campY) }
        if (p != null && hypot(p.x - campX, p.y - campY) < 0.07f) {
            return p.standX to p.standY
        }
        return campX to campY
    }

    // Screen direction toward the monster inside the pit when standing at a memorized pit before HP bar appears
    fun pitAimDirection(s: GameState): Pair<Float, Float>? {
        val me = s.minimapSelf ?: return null
        val p = pits.minByOrNull { hypot(it.standX - me.first, it.standY - me.second) } ?: return null
        if (hypot(p.standX - me.first, p.standY - me.second) > 0.055f) return null
        return p.dirX to p.dirY
    }

    // Autonomous Practice Mode Roam Tour decision
    // SCENERY CALIBRATION ONLY IN AN UNLOCKED MATCH (owner, 2026-11-10): the tour starts, and moves on,
    // only while the practice gate lets the Player play (verified practice vs Computer)
    fun playerUnlocked(): Boolean = com.example.mlbbresearch.ai.AiRuntime.playerOn &&
        com.example.mlbbresearch.ai.AiRuntime.playerGate == com.example.mlbbresearch.research.PlayerGate.Verdict.PLAY

    // RETURN TO BASE (owner, 2026-11-11): after the last tour stop the Research player walks back to its base
    // (gate, then fountain). The scenery is still observed the whole way and re-synced / saved at each of
    // these stops, so the walk back revalidates what the tour learned. The calibration ends at the fountain.
    val returnWaypoints: List<Waypoint> = listOf(
        Waypoint(0.18f, 0.82f, "Return: Ally Base Gate"),
        Waypoint(0.07f, 0.93f, "Return: Base (fountain)")
    )
    private val route: List<Waypoint> by lazy { tourWaypoints + returnWaypoints }
    fun returning(): Boolean = roamActive && roamIdx >= tourWaypoints.size
    private fun stepLabel(i: Int): String = if (i < tourWaypoints.size) "${i + 1}/${tourWaypoints.size}"
        else "returning to base ${i - tourWaypoints.size + 1}/${returnWaypoints.size}"

    fun roamDecision(s: GameState, p: HeroProfile): TacticalDecision? {
        if (!roamActive) return null
        if (!playerUnlocked()) {
            status = "waiting: starts when the practice gate unlocks the Player (open the scoreboard once)"
            waypointStartedAt = 0L; waypointArrivedAt = 0L
            return null
        }
        val me = s.minimapSelf ?: return TacticalDecision(
            Intent.PUSH, 0.7f, -0.7f, emptyList(), -1f, -1f, "-", -1f, -1,
            "Scenery calibration: waiting for minimap position"
        )
        val now = s.ts
        if (roamIdx >= route.size) {
            stopRoam()
            return null
        }
        val wp = route[roamIdx]
        // (the way back is long: more time per stop)
        val stopTimeout = if (roamIdx < tourWaypoints.size) wp.timeoutMs else 30_000L
        if (waypointStartedAt == 0L) waypointStartedAt = now
        val dist = hypot(wp.x - me.first, wp.y - me.second)

        if (dist < 0.045f || now - waypointStartedAt > stopTimeout) {
            if (waypointArrivedAt == 0L) {
                waypointArrivedAt = now
                syncWithMinimap(s, forceManual = true)
                saveNow()
            }
            if (now - waypointArrivedAt < 350L) {
                status = "scanning ${wp.name} (${stepLabel(roamIdx)}) · ${summary()}"
                return TacticalDecision(
                    Intent.PUSH, 0f, 0f, emptyList(), -1f, -1f, wp.name, -1f, -1,
                    "Scenery calibration (${stepLabel(roamIdx)}): scanning ${wp.name}"
                )
            }
            roamIdx++
            waypointArrivedAt = 0L
            waypointStartedAt = now
            if (roamIdx >= route.size) {
                saveNow()
                roamActive = false
                status = "completed 100% · back at base · " + summary()
                Diag.info("Scenery calibration complete: $status")
                return null
            }
        }

        val nextWp = route[roamIdx]
        val (dx, dy) = WallMap.direction(me, nextWp.x to nextWp.y) ?: run {
            val rx = nextWp.x - me.first; val ry = nextWp.y - me.second
            val l = hypot(rx, ry).coerceAtLeast(1e-4f)
            (rx / l) to (ry / l)
        }
        val casts = ArrayList<String>()
        HeroDb.dashSlot(s.ownHero)?.let { dash ->
            if (dash != "ult" && CooldownTracker.ready(dash, p)) casts.add(dash)
        }
        status = (if (returning()) "" else "roaming ") + "${stepLabel(roamIdx)}: ${nextWp.name} · ${summary()}"
        return TacticalDecision(
            Intent.PUSH, dx, dy, casts, -1f, -1f, nextWp.name, -1f, -1,
            "Scenery calibration (${stepLabel(roamIdx)}): -> ${nextWp.name}"
        )
    }
}
