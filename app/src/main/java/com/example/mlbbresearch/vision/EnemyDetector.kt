package com.example.mlbbresearch.vision

import kotlin.math.hypot

// Keeps track of enemy heroes across frames: gives each one an id, smooths its HP
// and estimates where it is moving. This is what the tactical engine targets.
class Track(
    val id: Int,
    var x: Float,
    var y: Float,
    var hp: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var lastSeen: Long,
    val firstSeen: Long,
    var minHpSeen: Float,
    var dashAt: Long = 0L     // when it last moved far faster than walking: a dash / blink / Flicker
)

// walking moves a hero about 0.25-0.4 screen heights a second on screen (plus the camera); a dash,
// blink or Flicker covers that in a fraction of the time
const val DASH_SPEED = 1.1f

class EnemyDetector {
    private val tracks = ArrayList<Track>()
    private var nextId = 1

    // aspect = frame width / height, so distances are measured in screen-height units
    fun update(enemyHeroes: List<Seen>, now: Long, aspect: Float): List<Track> {
        val unused = enemyHeroes.toMutableList()
        for (t in tracks) {
            var best: Seen? = null
            var bestD = 0.24f
            for (s in unused) {
                val d = hypot((s.x - t.x) * aspect, s.y - t.y)
                if (d < bestD) { bestD = d; best = s }
            }
            if (best != null) {
                unused.remove(best)
                val dt = ((now - t.lastSeen).coerceAtLeast(1)) / 1000f
                // a burst of speed (in screen heights per second) well above walking = a mobility skill
                if (dt < 0.4f && bestD / dt > DASH_SPEED) t.dashAt = now
                t.vx = 0.6f * t.vx + 0.4f * (best.x - t.x) / dt
                t.vy = 0.6f * t.vy + 0.4f * (best.y - t.y) / dt
                t.x = best.x
                t.y = best.y
                t.hp = 0.5f * t.hp + 0.5f * best.hp
                if (t.hp < t.minHpSeen) t.minHpSeen = t.hp
                t.lastSeen = now
            }
        }
        for (s in unused) {
            tracks.add(Track(nextId++, s.x, s.y, s.hp, lastSeen = now, firstSeen = now, minHpSeen = s.hp))
        }
        tracks.removeAll { now - it.lastSeen > 1200 }
        return tracks.map { Track(it.id, it.x, it.y, it.hp, it.vx, it.vy, it.lastSeen, it.firstSeen, it.minHpSeen, it.dashAt) }
    }

    fun clear() = tracks.clear()
}
