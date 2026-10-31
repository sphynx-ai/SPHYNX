package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Layout
import com.example.mlbbresearch.vision.MinimapAnalyzer
import com.example.mlbbresearch.vision.MinimapInfo
import com.example.mlbbresearch.vision.ObjectDetector
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

    private fun toMap(sx: Float, sy: Float): Pair<Float, Float> {
        val (l, t) = Layout.get("mapTL")
        val (r, b) = Layout.get("mapBR")
        return ((sx - l) / (r - l).coerceAtLeast(0.01f)) to ((sy - t) / (b - t).coerceAtLeast(0.01f))
    }

    fun read(mini: MinimapInfo, now: Long): Info {
        val det = ObjectDetector.latest
        val fresh = ObjectDetector.ready && now - ObjectDetector.latestAt < 1500
        val info = if (fresh) {
            val heroes = det.filter { it.label.contains("hero") && !it.label.contains("turret") }
            Info(
                heroes.filter { it.team == "enemy" }.map { toMap(it.cx, it.cy) },
                heroes.filter { it.team == "ally" }.map { toMap(it.cx, it.cy) },
                det.filter { it.label.contains("turtle") || it.label.contains("lord") }.maxByOrNull { it.score }?.let { toMap(it.cx, it.cy) },
                det.filter { it.label.contains("turr") && !it.label.contains("hero") }.map { toMap(it.cx, it.cy) },
                true
            )
        } else {
            // colour fallback: red portrait rings (no team-mates, no objectives)
            val r = MinimapAnalyzer.rect
            Info(mini.enemyDots.map { (dx, dy) -> toMap(r[0] + dx * (r[2] - r[0]), r[1] + dy * (r[3] - r[1])) },
                emptyList(), null, emptyList(), false)
        }
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
        status = (if (info.fromDetector) "detector" else "colours") + ": ${info.enemies.size} enemies, ${info.allies.size} allies on the map" +
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
