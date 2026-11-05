package com.example.mlbbresearch.game

import com.example.mlbbresearch.ai.Intent
import com.example.mlbbresearch.ai.TacticalDecision
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.vision.TowerRing
import com.example.mlbbresearch.vision.WallMap
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.hypot

// IN-GAME 3D SCENERY CALIBRATION & WORLD MEMORY (v37.09):
// Memorizes the actual 3D in-game map (not just the minimap):
//  - 3D Walls & Monster Pit back-walls (64x64 world grid, synced into WallMap A* pathfinding)
//  - 3D Bushes seen on screen (world coordinates)
//  - Monster Pits (exact world position, walkable pit-entrance stand spot so the hero never gets stuck
//    behind the horseshoe pit wall, and screen direction to the creep inside the pit)
//  - Towers (memorized from 3D TowerRing detections on screen)
//  - Autonomous 28-waypoint Practice Mode Roam Tour: walks the whole map once, memorizes every key
//    area into persistent storage ("scenery_map_v1"), and uses it in every match.
object SceneryMap {

    const val G = 64
    private const val SCREEN_SPAN_X = 0.16f
    private const val SCREEN_SPAN_Y = 0.10f
    private const val CFG_KEY = "scenery_map_v1"

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
    private var loaded = false
    private var lastSaveAt = 0L
    private var waypointArrivedAt = 0L
    private var waypointStartedAt = 0L

    // Default MLBB monster pits with open-mouth entrance stand-spots (so even before roaming,
    // the hero enters pits from their open side instead of getting stuck on the back wall)
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

    // 28 waypoints covering every lane, river bush, monster pit, and jungle corridor in Practice Mode
    class Waypoint(val x: Float, val y: Float, val name: String)
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
        Waypoint(0.50f, 0.50f, "Center River Return")
    )

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
                        WallMap.markSceneryWall(wx, wy)
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
                        WallMap.markSceneryBush(bx, by)
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
            com.example.mlbbresearch.learning.OnlineBackup.changed()
            lastSaveAt = System.currentTimeMillis()
        } catch (e: Exception) {
            Diag.error("SceneryMap save", e)
        }
    }

    fun clear() {
        wallHits.fill(0)
        walls = BooleanArray(G * G)
        bushes.clear()
        towers.clear()
        pits.clear()
        pits.addAll(defaultPits)
        Cfg.setRaw(CFG_KEY, null)
        status = "cleared (defaults restored)"
    }

    fun startRoam(): String {
        ensureLoaded()
        roamActive = true
        roamIdx = 0
        waypointArrivedAt = 0L
        waypointStartedAt = System.currentTimeMillis()
        if (!com.example.mlbbresearch.ai.AiRuntime.playerOn) {
            com.example.mlbbresearch.ai.AiRuntime.start("player")
        }
        status = "roaming map (1/${tourWaypoints.size}: ${tourWaypoints[0].name})"
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

    // Observe the actual 3D screen scenery every frame and map it into world coordinates
    fun observe(s: GameState) {
        ensureLoaded()
        val me = s.minimapSelf ?: return
        val self = s.self ?: return
        val now = s.ts

        // 0) Where the hero is standing right now is walkable ground, never a wall!
        val selfGx = (me.first * G).toInt().coerceIn(0, G - 1)
        val selfGy = (me.second * G).toInt().coerceIn(0, G - 1)
        walls[selfGy * G + selfGx] = false
        wallHits[selfGy * G + selfGx] = 0
        WallMap.clearWallCell(me.first, me.second)

        var changed = false

        // 1) Project 3D screen walls (40x20 grid) into world coordinates around `me`
        val sw = s.screenWalls
        if (sw.size >= 40 * 20) {
            for (gy in 1 until 19) for (gx in 1 until 39) {
                if (!sw[gy * 40 + gx]) continue
                // Require at least 2 adjacent wall cells so stray effects are not memorized as walls
                var neighbors = 0
                if (sw[gy * 40 + gx - 1]) neighbors++
                if (sw[gy * 40 + gx + 1]) neighbors++
                if (sw[(gy - 1) * 40 + gx]) neighbors++
                if (sw[(gy + 1) * 40 + gx]) neighbors++
                if (neighbors < 2) continue
                val sx = (gx + 0.5f) / 40f
                val sy = (gy + 0.5f) / 20f
                if (hypot(sx - self.x, sy - self.y) < 0.07f) continue
                val wx = (me.first + (sx - self.x) * SCREEN_SPAN_X).coerceIn(0.02f, 0.98f)
                val wy = (me.second + (sy - self.y) * SCREEN_SPAN_Y).coerceIn(0.02f, 0.98f)
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
            val wx = (me.first + (b.x - self.x) * SCREEN_SPAN_X).coerceIn(0.02f, 0.98f)
            val wy = (me.second + (b.y - self.y) * SCREEN_SPAN_Y).coerceIn(0.02f, 0.98f)
            if (bushes.none { hypot(it.first - wx, it.second - wy) < 0.025f }) {
                bushes.add(wx to wy)
                WallMap.markSceneryBush(wx, wy)
                changed = true
            }
        }

        // 3) Project 3D screen monsters (creeps, buffs, Turtle/Lord) into memorized Monster Pits
        //    along with the hero's current walkable stand-spot!
        for (m in s.monsters) {
            val wx = (me.first + (m.x - self.x) * SCREEN_SPAN_X).coerceIn(0.04f, 0.96f)
            val wy = (me.second + (m.y - self.y) * SCREEN_SPAN_Y).coerceIn(0.04f, 0.96f)
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
            val wx = (me.first + (s.towerRing.cx - self.x) * SCREEN_SPAN_X).coerceIn(0.02f, 0.98f)
            val wy = (me.second + (s.towerRing.cy - self.y) * SCREEN_SPAN_Y).coerceIn(0.02f, 0.98f)
            if (towers.none { hypot(it.first - wx, it.second - wy) < 0.04f }) {
                towers.add(wx to wy)
                changed = true
            }
        }

        if (changed && now - lastSaveAt > 6000L) {
            saveNow()
        }
    }

    // Best walkable entrance / stand-spot for a camp at (campX, campY) so the hero enters the pit cleanly
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
    fun roamDecision(s: GameState, p: HeroProfile): TacticalDecision? {
        if (!roamActive) return null
        val me = s.minimapSelf ?: return TacticalDecision(
            Intent.PUSH, 0.7f, -0.7f, emptyList(), -1f, -1f, "-", -1f, -1,
            "Scenery calibration: waiting for minimap position"
        )
        val now = s.ts
        if (roamIdx >= tourWaypoints.size) {
            stopRoam()
            return null
        }
        val wp = tourWaypoints[roamIdx]
        if (waypointStartedAt == 0L) waypointStartedAt = now
        val dist = hypot(wp.x - me.first, wp.y - me.second)

        // Arrived at waypoint (or spent > 9s trying to reach a blocked point): pause 350ms to scan 3D scenery, then advance!
        if (dist < 0.045f || now - waypointStartedAt > 9500L) {
            if (waypointArrivedAt == 0L) {
                waypointArrivedAt = now
                saveNow()
            }
            if (now - waypointArrivedAt < 350L) {
                status = "scanning ${wp.name} (${roamIdx + 1}/${tourWaypoints.size}) · ${summary()}"
                return TacticalDecision(
                    Intent.PUSH, 0f, 0f, emptyList(), -1f, -1f, wp.name, -1f, -1,
                    "Scenery calibration (${roamIdx + 1}/${tourWaypoints.size}): scanning ${wp.name}"
                )
            }
            roamIdx++
            waypointArrivedAt = 0L
            waypointStartedAt = now
            if (roamIdx >= tourWaypoints.size) {
                saveNow()
                roamActive = false
                status = "completed 100% · " + summary()
                Diag.info("Scenery calibration complete: $status")
                return null
            }
        }

        val nextWp = tourWaypoints[roamIdx]
        val (dx, dy) = WallMap.direction(me, nextWp.x to nextWp.y) ?: run {
            val rx = nextWp.x - me.first; val ry = nextWp.y - me.second
            val l = hypot(rx, ry).coerceAtLeast(1e-4f)
            (rx / l) to (ry / l)
        }
        val casts = ArrayList<String>()
        // Use mobility/dash skill to speed up roaming when off cooldown
        HeroDb.dashSlot(s.ownHero)?.let { dash ->
            if (dash != "ult" && CooldownTracker.ready(dash, p)) casts.add(dash)
        }
        status = "roaming ${roamIdx + 1}/${tourWaypoints.size}: ${nextWp.name} · ${summary()}"
        return TacticalDecision(
            Intent.PUSH, dx, dy, casts, -1f, -1f, nextWp.name, -1f, -1,
            "Scenery calibration (${roamIdx + 1}/${tourWaypoints.size}): -> ${nextWp.name}"
        )
    }
}
