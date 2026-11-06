package com.example.mlbbresearch.vision

import kotlin.math.hypot

// Keeps track of enemy heroes across frames: gives each one an id, tracks its HP with
// zero-lag drop response for Execute/Finisher Ultimates, coasts briefly through VFX / green-ring
// occlusions, and expires dead targets cleanly when a fight ends (v37.16).
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

    // True when (x, y) is near an enemy hero tracked within the last 1.6s (prevents FrameProcessor
    // from misclassifying an enemy hero as a neutral monster when their level badge is occluded or showing a skill icon).
    fun isNearActiveTrack(x: Float, y: Float, now: Long, aspect: Float, maxDist: Float = 0.20f): Boolean =
        tracks.any { now - it.lastSeen <= 1600L && hypot((x - it.x) * aspect, y - it.y) <= maxDist }

    // Called when KDA registers a new kill or assist so a dead low-HP enemy track ends immediately.
    fun onKillOrAssist(now: Long) {
        tracks.removeAll { now - it.lastSeen > 90L && it.minHpSeen <= 0.35f }
    }

    // aspect = frame width / height, so distances are measured in screen-height units
    fun update(enemyHeroes: List<Seen>, now: Long, aspect: Float): List<Track> {
        val unused = enemyHeroes.toMutableList()
        for (t in tracks) {
            var best: Seen? = null
            var bestD = 0.25f
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
                // Asymmetric HP filter for Execute/Finisher Ultimates (v37.16):
                // When enemy HP drops, snap/weight 85% toward the new lower HP immediately so Execute Ultimates
                // fire on the very frame the enemy enters lethal range; smooth upward jitter gently.
                t.hp = when {
                    t.hp - best.hp >= 0.07f -> best.hp
                    best.hp < t.hp -> 0.85f * best.hp + 0.15f * t.hp
                    else -> 0.65f * t.hp + 0.35f * best.hp
                }
                if (t.hp < t.minHpSeen) t.minHpSeen = t.hp
                t.lastSeen = now
            }
        }
        for (s in unused) {
            tracks.add(Track(nextId++, s.x, s.y, s.hp, lastSeen = now, firstSeen = now, minHpSeen = s.hp))
        }
        // Expire low-HP targets faster once their HP bar disappears (killed or walked into fog) so fights end cleanly;
        // keep healthy tracks in memory up to 1500 ms for occlusion recovery.
        tracks.removeAll { t ->
            val age = now - t.lastSeen
            age > 1500L || (t.minHpSeen <= 0.16f && age > 420L)
        }
        // Return seen tracks plus briefly occluded tracks (coasted up to 380 ms during combo VFX / green-ring overlap)
        val out = ArrayList<Track>(tracks.size)
        for (t in tracks) {
            val age = now - t.lastSeen
            val coastMax = if (t.minHpSeen <= 0.14f) 180L else 380L
            if (age <= coastMax) {
                val dt = (age / 1000f).coerceIn(0f, 0.38f)
                val cx = (t.x + t.vx * dt * 0.5f).coerceIn(0.02f, 0.98f)
                val cy = (t.y + t.vy * dt * 0.5f).coerceIn(0.02f, 0.98f)
                out.add(Track(t.id, cx, cy, t.hp, t.vx, t.vy, t.lastSeen, t.firstSeen, t.minHpSeen, t.dashAt))
            }
        }
        return out
    }

    fun clear() = tracks.clear()
}
