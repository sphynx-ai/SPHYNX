package com.sphynx.game

import com.sphynx.core.Layout
import com.sphynx.universal.TargetFusion
import com.sphynx.vision.MinimapAnalyzer
import com.sphynx.vision.MinimapInfo
import com.sphynx.vision.ObjectDetector
import kotlin.math.hypot

// What the minimap says about the whole map, with the trained YOLO detector as the main eye
// (hero icons with their team, turtle / lord icons, turrets) and the colour reading as the
// fallback when no detector is loaded. Positions are 0..1 inside the map.
//  - enemy heroes on the map (also the ones NOT on your screen: hidden in bushes / fog)
//  - which of them are coming towards you (a gank) over the last 2 seconds
//  - turtle / lord alive (their icon on the map) and where
//  - allied heroes on the map (who could help at an objective)
object MapIntel {

    class Info(
        val enemies: List<Pair<Float, Float>>,
        val allies: List<Pair<Float, Float>>,
        val objective: Pair<Float, Float>?,      // turtle or lord icon
        val turrets: List<Pair<Float, Float>>,
        val fromDetector: Boolean
    ) {
        companion object { val NONE = Info(emptyList(), emptyList(), null, emptyList(), false) }
    }

    @Volatile var gankAt = 0L
        private set
    @Volatile var status = "-"
        private set
    private val history = ArrayDeque<Pair<Long, List<Pair<Float, Float>>>>()
    private val fusion = TargetFusion()
    // the fused enemy targets of the latest read (stable ids; occluded = hidden under your icon)
    @Volatile var targets: List<TargetFusion.Target> = emptyList()
        private set

    fun reset() { fusion.clear(); targets = emptyList(); history.clear() }

    // the enemies seen on the screen that the minimap backs up (TargetFusion.keepScreenEnemy); without
    // your own minimap position nothing can be checked, so then all are kept
    fun screenEnemiesConfirmed(enemies: List<com.sphynx.vision.Track>, me: Pair<Float, Float>?,
                               self: com.sphynx.vision.Seen?, aspect: Float, now: Long): List<com.sphynx.vision.Track> {
        if (me == null || self == null) return enemies
        val confirmed = targets.filter { it.mapConfirmed }
        return enemies.filter { e ->
            val p = SceneryMap.projectToWorld(me, self, e.x, e.y)
            val near = confirmed.any { hypot(it.x - p.first, it.y - p.second) < 0.09f }
            val d = hypot((e.x - self.x) * aspect, e.y - self.y)
            TargetFusion.keepScreenEnemy(e.firstSeen, now, near, d, e.hp)
        }
    }

    private fun toMap(sx: Float, sy: Float): Pair<Float, Float> {
        val (l, t) = Layout.get("mapTL")
        val (r, b) = Layout.get("mapBR")
        return ((sx - l) / (r - l).coerceAtLeast(0.01f)) to ((sy - t) / (b - t).coerceAtLeast(0.01f))
    }

    fun read(
        mini: MinimapInfo,
        now: Long,
        screenEnemies: List<com.sphynx.vision.Track> = emptyList(),
        self: com.sphynx.vision.Seen? = null
    ): Info {
        val det = ObjectDetector.latest
        val fresh = ObjectDetector.ready && now - ObjectDetector.latestAt < 1500
        val baseInfo = if (fresh) {
            val heroes = det.filter { it.label.contains("hero") && !it.label.contains("turret") }
            Info(
                heroes.filter { it.team == "enemy" }.map { toMap(it.cx, it.cy) },
                heroes.filter { it.team == "ally" }.map { toMap(it.cx, it.cy) },
                det.filter { it.label.contains("turtle") || it.label.contains("lord") }.maxByOrNull { it.score }?.let { toMap(it.cx, it.cy) },
                det.filter { it.label.contains("turr") && !it.label.contains("hero") }.map { toMap(it.cx, it.cy) },
                true
            )
        } else {
            // colour fallback: red portrait rings + blue ally portrait rings
            val r = MinimapAnalyzer.rect
            Info(
                mini.enemyDots.map { (dx, dy) -> toMap(r[0] + dx * (r[2] - r[0]), r[1] + dy * (r[3] - r[1])) },
                mini.allyDots.map { (dx, dy) -> toMap(r[0] + dx * (r[2] - r[0]), r[1] + dy * (r[3] - r[1])) },
                null, emptyList(), false
            )
        }
        // Minimap <-> gameplay-screen synchronization (issue #2): full minimap icons, enemies seen on the
        // screen (projected onto the map) and the half-covered arcs next to your own icon are FUSED into
        // one target list with stable ids (universal.TargetFusion), instead of being added together.
        // A screen enemy next to its own map icon counts once; arcs alone never create an enemy; an enemy
        // hidden under your icon is kept for a moment instead of vanishing.
        val r = MinimapAnalyzer.rect
        fun local(p: Pair<Float, Float>) = toMap(r[0] + p.first * (r[2] - r[0]), r[1] + p.second * (r[3] - r[1]))
        val me0 = mini.self
        val obs = ArrayList<TargetFusion.Observation>()
        baseInfo.enemies.forEach { obs.add(TargetFusion.Observation(it.first, it.second, TargetFusion.Source.MAP_ICON)) }
        mini.occludedDots.forEach { local(it).let { p -> obs.add(TargetFusion.Observation(p.first, p.second, TargetFusion.Source.PARTIAL_ARC)) } }
        if (me0 != null && self != null) for (e in screenEnemies) {
            val proj = SceneryMap.projectToWorld(me0, self, e.x, e.y)
            obs.add(TargetFusion.Observation(proj.first, proj.second, TargetFusion.Source.SCREEN))
        }
        val fused = fusion.update(now, me0, obs)
        targets = fused
        val info0 = Info(fused.map { it.x to it.y }, baseInfo.allies, baseInfo.objective, baseInfo.turrets, baseInfo.fromDetector)
        // turtle / lord alive: from the detector, else from the icon in its pit (colour)
        val pitObj = (mini.turtleIcon ?: mini.lordIcon)?.let { toMap((it[0] + it[2]) / 2, (it[1] + it[3]) / 2) }
        val info = if (info0.objective == null && pitObj != null)
            Info(info0.enemies, info0.allies, pitObj, info0.turrets, info0.fromDetector) else info0
        // gank: an enemy that came 0.06+ closer to you over ~2 s and is now near
        val me = mini.self
        if (me != null) {
            history.addLast(now to info.enemies)
            while (history.isNotEmpty() && now - history.first().first > 2500) history.removeFirst()
            val old = history.firstOrNull()?.takeIf { now - it.first > 1200 }?.second
            val nearNow = info.enemies.map { hypot(it.first - me.first, it.second - me.second) }
            if (old != null && nearNow.any { it < 0.2f } &&
                nearNow.minOrNull()!! < (old.minOfOrNull { hypot(it.first - me.first, it.second - me.second) } ?: 9f) - 0.06f
            ) gankAt = now
        }
        status = (if (info.fromDetector) "detector" else "colours") + ": ${info.enemies.size} enemies" +
            (targets.count { it.occluded }.takeIf { it > 0 }?.let { " ($it under your icon)" } ?: "") + ", ${info.allies.size} allies on the map" +
            (info.objective?.let { ", objective icon up" } ?: "") + if (now - gankAt < 3000) ", ENEMY COMING" else ""
        return info
    }

    // enemies on the map close to you (in map units)
    fun enemiesNear(s: GameState, r: Float = 0.15f): Int {
        val me = s.minimapSelf ?: return 0
        return s.map.enemies.count { hypot(it.first - me.first, it.second - me.second) < r }
    }

    fun gank(s: GameState): Boolean = s.ts - gankAt < 3000
}
