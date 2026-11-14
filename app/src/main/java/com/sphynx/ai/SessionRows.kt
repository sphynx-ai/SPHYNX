package com.sphynx.ai

import com.sphynx.core.SessionRecorder
import org.json.JSONArray
import org.json.JSONObject

// THE SESSION RECORDING'S ROWS FOR MLBB (moved out of core.SessionRecorder, ADR 0001 step B2): what the app saw
// and decided, written by the AI loop through SessionRecorder (which only limits the rate and writes). The row
// is exactly the one the recorder built before (same keys, same order) - the recording format is unchanged.
object SessionRows {

    /** The last decision while recording (the session video draws it: app.ProbesWiring). */
    @Volatile var lastDecision: TacticalDecision? = null; private set

    fun frame(s: com.sphynx.game.GameState, d: TacticalDecision) {
        if (!SessionRecorder.on) return
        lastDecision = d
        SessionRecorder.frame { now -> row(s, d, now) }
    }

    fun row(s: com.sphynx.game.GameState, d: TacticalDecision, now: Long): JSONObject {
        val o = JSONObject().put("k", "f").put("t", now).put("ms", s.matchSec).put("ph", s.phase.name)
            .put("age", now - s.ts).put("hp", SessionRecorder.r3(s.selfHp)).put("lvl", s.level).put("lane", s.lane.name)
        s.self?.let { o.put("me", JSONArray(listOf(SessionRecorder.r3(it.x), SessionRecorder.r3(it.y)))) }
        s.minimapSelf?.let { o.put("mm", JSONArray(listOf(SessionRecorder.r3(it.first), SessionRecorder.r3(it.second)))) }
        o.put("in", d.intent.name).put("why", d.reason.take(140))
            .put("mv", JSONArray(listOf(SessionRecorder.r3(d.moveX), SessionRecorder.r3(d.moveY)))).put("cast", JSONArray(d.casts))
        if (d.targetX >= 0f) o.put("tg", JSONArray(listOf(SessionRecorder.r3(d.targetX), SessionRecorder.r3(d.targetY), d.targetLabel, SessionRecorder.r3(d.targetHp), d.targetId)))
        o.put("en", JSONArray(s.enemies.map { JSONArray(listOf(it.id, SessionRecorder.r3(it.x), SessionRecorder.r3(it.y), SessionRecorder.r3(it.hp), SessionRecorder.r3(it.vx), SessionRecorder.r3(it.vy), now - it.lastSeen)) }))
        o.put("mon", JSONArray(s.monsters.map { JSONArray(listOf(SessionRecorder.r3(it.x), SessionRecorder.r3(it.y), SessionRecorder.r3(it.hp), if (it.hero) 1 else 0, it.species)) }))
        o.put("map", JSONArray(com.sphynx.game.MapIntel.targets.map {
            JSONArray(listOf(it.id, SessionRecorder.r3(it.x), SessionRecorder.r3(it.y), if (it.occluded) 1 else 0, it.sources.joinToString("+") { s2 -> s2.name.take(3) }))
        }))
        o.put("jg", com.sphynx.game.JungleNav.status.take(80))
        // EVERYTHING THE APP SAW (owner, 2026-11-12): allies, minions, towers, the minimap reading, the gate,
        // which lane is on top, the kind of match
        fun pts(l: List<Pair<Float, Float>>) = JSONArray(l.take(12).map { JSONArray(listOf(SessionRecorder.r3(it.first), SessionRecorder.r3(it.second))) })
        o.put("al", JSONArray(s.allies.take(6).map { JSONArray(listOf(SessionRecorder.r3(it.x), SessionRecorder.r3(it.y), SessionRecorder.r3(it.hp))) }))
        o.put("emn", JSONArray(s.enemyMinions.take(8).map { JSONArray(listOf(SessionRecorder.r3(it.x), SessionRecorder.r3(it.y), SessionRecorder.r3(it.hp))) }))
        o.put("amn", s.allyMinions.size)
        o.put("tw", JSONArray(s.towers.take(12).map { JSONArray(listOf(SessionRecorder.r3(it.x), SessionRecorder.r3(it.y))) }))
        if (s.towerRing.zone != com.sphynx.vision.TowerRing.Zone.NONE) o.put("ring", s.towerRing.zone.name)
        o.put("mmE", pts(s.map.enemies)).put("mmA", pts(s.map.allies)).put("ew", pts(s.enemyWaves))
        s.map.objective?.let { o.put("obj", JSONArray(listOf(SessionRecorder.r3(it.first), SessionRecorder.r3(it.second)))) }
        o.put("gate", com.sphynx.ai.AiRuntime.playerGate?.name ?: "?")
        o.put("side", when (com.sphynx.game.MapSide.goldTop) { true -> "GOLD_TOP"; false -> "EXP_TOP"; null -> "?" })
        o.put("kind", com.sphynx.game.MatchKind.current().name)
        o.put("lat", com.sphynx.core.Speed.total().toInt())
        // the buffs you hold, read from the emblems around your hero (vision.BuffEmblems)
        com.sphynx.vision.BuffEmblems.held.takeIf { it.isNotEmpty() }?.let { b -> o.put("buf", JSONArray(b.map { it.name })) }
        // research mode: rs = [verified practice?, arm (ARBITER / NORMAL)] - R3 / R5
        if (com.sphynx.research.ResearchMode.on) o.put("rs", JSONArray(listOf(
            com.sphynx.research.ResearchMode.allowsAutonomy(), com.sphynx.research.ResearchMode.arm().name)))
        return o
    }

    /** The decision's fields for an aimed-skill row: in, tg, mv (as before). */
    fun aimDecision(d: TacticalDecision?): JSONObject? = d?.let {
        JSONObject().put("in", it.intent.name)
            .put("tg", JSONArray(listOf(SessionRecorder.r3(it.targetX), SessionRecorder.r3(it.targetY), it.targetLabel, it.targetId)))
            .put("mv", JSONArray(listOf(SessionRecorder.r3(it.moveX), SessionRecorder.r3(it.moveY))))
    }
}
