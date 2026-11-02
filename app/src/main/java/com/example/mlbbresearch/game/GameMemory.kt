package com.example.mlbbresearch.game

import kotlin.math.hypot
import kotlin.math.min

// GAME SENSE MEMORY: what an experienced player remembers, not only what is on screen now.
//  - every enemy hero seen on the minimap, with its last known spot and when
//  - an enemy that disappears (fog, bush, recall) is still "somewhere": since a hero walks about
//    a quarter of the map in 10 s, after t seconds it can be anywhere within about 0.025 * t of
//    where it vanished. Spots in reach of missing enemies are dangerous ("fog danger"), more so
//    for enemies that vanished recently (the oldest memories fade after 45 s)
//  - when you were last hurt (recent threats) and what you were doing (objective / plan)
// Example: the enemy jungler is seen near Gold lane, then vanishes. For the next seconds the
// lanes and jungle around that spot count as dangerous: the AI farms more carefully there and
// does not start fights it could not leave.
object GameMemory {

    class Enemy(var x: Float, var y: Float, var lastSeen: Long, val firstSeen: Long)

    private const val SPEED = 0.025f        // minimap units per second (walking hero)
    private const val FORGET_MS = 45_000L
    private val enemies = ArrayList<Enemy>()
    private val hurtTimes = ArrayDeque<Long>()
    @Volatile var currentObjective = "-"; private set
    @Volatile var currentPlan = "-"; private set

    @Synchronized
    fun reset() { enemies.clear(); hurtTimes.clear(); currentObjective = "-"; currentPlan = "-" }

    // once per decision
    @Synchronized
    fun update(s: GameState, objective: String, plan: String) {
        val now = s.ts
        currentObjective = objective; currentPlan = plan
        if (GameStateTracker.hurtRecently(now) && (hurtTimes.isEmpty() || now - hurtTimes.last() > 1500)) {
            hurtTimes.addLast(now); while (hurtTimes.size > 10) hurtTimes.removeFirst()
        }
        if (s.phase != Phase.IN_GAME && s.phase != Phase.MAP) return
        val seen = s.map.enemies
        val used = HashSet<Enemy>()
        for ((x, y) in seen) {
            // the remembered enemy that could have walked here
            val e = enemies.filter { it !in used }.minByOrNull { hypot(it.x - x, it.y - y) - SPEED * (now - it.lastSeen) / 1000f }
            val reach = e?.let { 0.06f + SPEED * (now - it.lastSeen) / 1000f } ?: 0f
            if (e != null && hypot(e.x - x, e.y - y) <= reach) {
                e.x = x; e.y = y; e.lastSeen = now; used.add(e)
            } else if (enemies.size < 5) {
                enemies.add(Enemy(x, y, now, now).also { used.add(it) })
            } else {
                // 5 known already: this is the one seen longest ago
                enemies.filter { it !in used }.minByOrNull { it.lastSeen }?.let { it.x = x; it.y = y; it.lastSeen = now; used.add(it) }
            }
        }
    }

    // enemies not on the minimap right now but seen in the last 45 s
    @Synchronized
    fun missing(now: Long): List<Enemy> = enemies.filter { now - it.lastSeen in 2500..FORGET_MS }

    // How many missing enemies could be at this minimap spot now (0 = none, each one up to 1,
    // fading as the memory gets old and their possible area grows)
    @Synchronized
    fun fogDanger(at: Pair<Float, Float>?, now: Long): Float {
        if (at == null) return 0f
        var d = 0f
        for (e in enemies) {
            val age = now - e.lastSeen
            if (age !in 2500..FORGET_MS) continue
            val reach = min(0.4f, 0.04f + SPEED * age / 1000f)
            if (hypot(e.x - at.first, e.y - at.second) <= reach) d += 1f - age.toFloat() / FORGET_MS
        }
        return d
    }

    @Synchronized
    fun hurtsInLast(ms: Long, now: Long): Int = hurtTimes.count { now - it <= ms }

    fun text(now: Long): String {
        val miss = missing(now)
        return "Enemies remembered: ${enemies.size}, missing from the map: ${miss.size}" +
            (if (miss.isNotEmpty()) " (last seen " + miss.joinToString { "%.0f s ago".format((now - it.lastSeen) / 1000f) } + ")" else "") +
            "\nObjective: $currentObjective   Plan: $currentPlan"
    }
}
