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
//  6. SCREEN alone is not enough for long (device test, issue #2: jungle monsters have red HP bars
//     like enemy heroes and were taken for them). Every enemy your team can see is also on the
//     minimap, so a target that only the screen reports must be backed by a map icon or a covered arc
//     within `screenOnlyMs`, or it is dropped as a misread.
class TargetFusion(
    private val mergeGate: Float = 0.05f,
    private val trackGate: Float = 0.09f,
    private val occlusionRadius: Float = 0.07f,
    private val occludedKeepMs: Long = 1500L,
    private val lostKeepMs: Long = 400L,
    private val maxTargets: Int = 5,
    private val screenOnlyMs: Long = 800L
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
        val occluded: Boolean,       // not seen this frame, kept because your icon covers it
        val mapConfirmed: Boolean = false   // a map icon or a covered arc has backed it at least once
    )

    private class T(var id: Int, var x: Float, var y: Float, var lastSeen: Long, var confirmed: Long, var sources: Set<Source>,
                    val born: Long, var mapSeen: Long? = null)

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
            if (Source.MAP_ICON in g.second || Source.PARTIAL_ARC in g.second) t.mapSeen = nowMs
        }
        // 3. new targets: only from a full icon or the screen, never from arcs alone
        for ((i, g) in groups.withIndex()) {
            if (i in usedGroups) continue
            if (g.second == setOf(Source.PARTIAL_ARC)) continue
            tracks.add(T(nextId++, g.first.first, g.first.second, nowMs, nowMs, g.second, born = nowMs,
                mapSeen = if (Source.MAP_ICON in g.second) nowMs else null))
        }
        // 4. forget targets that are gone (longer when your own icon covers them)
        tracks.removeAll { t ->
            // 6. only the screen ever saw it, for too long: a misread (e.g. a monster's red HP bar)
            if (t.mapSeen == null && nowMs - t.born > screenOnlyMs) return@removeAll true
            val age = nowMs - t.lastSeen
            val covered = self != null && hypot(t.x - self.first, t.y - self.second) < occlusionRadius
            age > (if (covered) occludedKeepMs else lostKeepMs)
        }
        // 5. the most recently confirmed first, at most maxTargets
        return tracks.sortedByDescending { it.confirmed }.take(maxTargets).map { t ->
            Target(t.id, t.x, t.y, t.lastSeen, t.confirmed, t.sources, occluded = t.lastSeen < nowMs, mapConfirmed = t.mapSeen != null)
        }
    }

    companion object {
        // SCREEN CHECK (owner's recordings: the Lithowanderer's thin red bar was read as "an enemy hero at
        // 4%" and chased, and summons / companions can look like bars too). An enemy seen on the screen
        // is kept when:
        //  - it was first seen less than `graceMs` ago (the map scan is slower than the screen), or
        //  - a map-confirmed target (red hero icon, or its arc under your icon) is at its map spot, or
        //  - it is right next to you with real HP (your own icon may hide its map icon completely).
        // Everything else is a misread: creeps, companions and summons never get a red hero icon.
        fun keepScreenEnemy(firstSeenMs: Long, nowMs: Long, mapConfirmedNear: Boolean, distToYou: Float, hp: Float,
                            graceMs: Long = 800L, nearYou: Float = 0.12f, realHp: Float = 0.15f): Boolean =
            nowMs - firstSeenMs < graceMs || mapConfirmedNear || (distToYou <= nearYou && hp >= realHp)
    }
}
