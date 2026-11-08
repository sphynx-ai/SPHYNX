package com.example.mlbbresearch.universal

import kotlin.math.hypot

// TARGET FUSION (game-neutral; issue #2): keeps ONE consistent list of enemy characters on the map
// from several imperfect sources, instead of adding every source's points together.
//
// Sources, strongest first:
//  - MAP_ICON:    a full enemy icon on the minimap (colour ring or detector box);
//  - SCREEN:      an enemy seen on the gameplay screen, projected onto the map;
//  - PARTIAL_ARC: a fragment of an enemy icon next to your own icon (your icon covers the rest).
//
// Rules (each one fixes a failure of the old "add them all" workaround):
//  1. Observations closer than `mergeGate` are ONE target (the arcs of one half-covered icon, or a
//     screen enemy and its own map icon, never count twice).
//  2. A target keeps its id across frames (nearest track within `trackGate`), so decisions do not
//     jump between targets.
//  3. A PARTIAL_ARC alone never creates a target: it can only keep an existing one alive. So red
//     specks around your icon (minion marks, effects) cannot become phantom heroes.
//  4. A target that disappears while it is inside the occlusion zone around your own icon is
//     covered, not gone: it is kept (marked occluded) for `occludedKeepMs`; anywhere else for
//     `lostKeepMs` only.
//  5. At most `maxTargets` (5 in a 5v5 game), most recently confirmed first.
class TargetFusion(
    private val mergeGate: Float = 0.05f,
    private val trackGate: Float = 0.09f,
    private val occlusionRadius: Float = 0.07f,
    private val occludedKeepMs: Long = 1500L,
    private val lostKeepMs: Long = 400L,
    private val maxTargets: Int = 5
) {
    enum class Source(val strength: Int) { MAP_ICON(3), SCREEN(2), PARTIAL_ARC(1) }

    data class Observation(val x: Float, val y: Float, val source: Source)

    data class Target(
        val id: Int,
        val x: Float,
        val y: Float,
        val lastSeenMs: Long,        // last frame any source saw it
        val confirmedMs: Long,       // last frame a full icon or the screen saw it
        val sources: Set<Source>,    // what saw it on the latest frame it was seen
        val occluded: Boolean        // not seen this frame, kept because your icon covers it
    )

    private class T(var id: Int, var x: Float, var y: Float, var lastSeen: Long, var confirmed: Long, var sources: Set<Source>)

    private val tracks = ArrayList<T>()
    private var nextId = 1

    fun clear() { tracks.clear() }

    // groups observations of the same target; a group sits at its strongest source's position
    private fun cluster(obs: List<Observation>): List<Pair<Pair<Float, Float>, Set<Source>>> {
        val groups = ArrayList<MutableList<Observation>>()
        for (o in obs.sortedByDescending { it.source.strength }) {
            val g = groups.firstOrNull { gr -> gr.any { hypot(it.x - o.x, it.y - o.y) < mergeGate } }
            if (g != null) g.add(o) else groups.add(mutableListOf(o))
        }
        return groups.map { g ->
            val top = g.maxOf { it.source.strength }
            val best = g.filter { it.source.strength == top }
            (best.map { it.x }.average().toFloat() to best.map { it.y }.average().toFloat()) to g.map { it.source }.toSet()
        }
    }

    fun update(nowMs: Long, self: Pair<Float, Float>?, observations: List<Observation>): List<Target> {
        val groups = cluster(observations).toMutableList()
        val matched = HashSet<T>()
        // 2. associate: closest pairs first, so two nearby targets do not swap ids
        val pairs = ArrayList<Triple<T, Int, Float>>()
        for (t in tracks) for ((i, g) in groups.withIndex()) {
            val d = hypot(g.first.first - t.x, g.first.second - t.y)
            if (d < trackGate) pairs.add(Triple(t, i, d))
        }
        val usedGroups = HashSet<Int>()
        for ((t, i, _) in pairs.sortedBy { it.third }) {
            if (t in matched || i in usedGroups) continue
            val g = groups[i]
            matched.add(t); usedGroups.add(i)
            t.x = g.first.first; t.y = g.first.second; t.lastSeen = nowMs; t.sources = g.second
            if (Source.MAP_ICON in g.second || Source.SCREEN in g.second) t.confirmed = nowMs
        }
        // 3. new targets: only from a full icon or the screen, never from arcs alone
        for ((i, g) in groups.withIndex()) {
            if (i in usedGroups) continue
            if (g.second == setOf(Source.PARTIAL_ARC)) continue
            tracks.add(T(nextId++, g.first.first, g.first.second, nowMs, nowMs, g.second))
        }
        // 4. forget targets that are gone (longer when your own icon covers them)
        tracks.removeAll { t ->
            val age = nowMs - t.lastSeen
            val covered = self != null && hypot(t.x - self.first, t.y - self.second) < occlusionRadius
            age > (if (covered) occludedKeepMs else lostKeepMs)
        }
        // 5. the most recently confirmed first, at most maxTargets
        return tracks.sortedByDescending { it.confirmed }.take(maxTargets).map { t ->
            Target(t.id, t.x, t.y, t.lastSeen, t.confirmed, t.sources, occluded = t.lastSeen < nowMs)
        }
    }
}
