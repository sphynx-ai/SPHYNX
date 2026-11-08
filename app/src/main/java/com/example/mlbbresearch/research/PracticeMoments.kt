package com.example.mlbbresearch.research

import com.example.mlbbresearch.engines.TacticalReadiness
import com.example.mlbbresearch.universal.CapabilitySet
import com.example.mlbbresearch.universal.DecisionKind
import com.example.mlbbresearch.universal.MatchPhase
import com.example.mlbbresearch.universal.Side
import com.example.mlbbresearch.universal.UniversalMatchState
import com.example.mlbbresearch.universal.UnitKind
import com.example.mlbbresearch.universal.UnitState
import org.json.JSONArray
import org.json.JSONObject

// Research mode, phase R4 (pure logic, unit tested): fight moments recorded in VERIFIED PRACTICE
// only, used to measure the small tactical model (Gemma 3 270M) with TacticalReadiness.
// Each moment keeps:
//  - the exact situation summary the small model is asked about (TacticalAdvisor.summary);
//  - what the app played (after R3) - the answer the model is measured against;
//  - the options that were dangerous at that moment (from the app's own Kotlin checks);
//  - a few counts so the shared validator can check the model's answer.
// No player names, no pictures: only the summary text and numbers.
data class PracticeMoment(
    val matchId: Long,
    val atMs: Long,
    val summary: String,
    val played: DecisionKind,
    val unsafe: Set<DecisionKind>,
    val health: Float?,
    val enemies: Int,
    val allies: Int,
    val minions: Int,
    val monsters: Int
) {
    fun toJson(): JSONObject = JSONObject()
        .put("match", matchId).put("at", atMs).put("summary", summary).put("played", played.name)
        .put("unsafe", JSONArray(unsafe.map { it.name }))
        .put("hp", health?.toDouble() ?: JSONObject.NULL)
        .put("enemies", enemies).put("allies", allies).put("minions", minions).put("monsters", monsters)

    // the universal state the validator needs (counts only; nothing else is known offline)
    fun state(caps: CapabilitySet): UniversalMatchState = UniversalMatchState("mlbb", atMs, MatchPhase.IN_MATCH,
        self = UnitState(Side.SELF, UnitKind.CHARACTER, health = health),
        units = List(enemies) { UnitState(Side.ENEMY, UnitKind.CHARACTER) } +
            List(allies) { UnitState(Side.ALLY, UnitKind.CHARACTER) } +
            List(minions) { UnitState(Side.ENEMY, UnitKind.MINION) } +
            List(monsters) { UnitState(Side.NEUTRAL, UnitKind.NEUTRAL_MONSTER) },
        capabilities = caps)

    fun scenario(caps: CapabilitySet) = TacticalReadiness.Scenario(
        "match $matchId at $atMs", state(caps), setOf(played), unsafe, prompt = summary)

    companion object {
        fun fromJson(o: JSONObject): PracticeMoment? = try {
            val u = o.optJSONArray("unsafe") ?: JSONArray()
            PracticeMoment(o.getLong("match"), o.getLong("at"), o.getString("summary"),
                DecisionKind.valueOf(o.getString("played")),
                (0 until u.length()).map { DecisionKind.valueOf(u.getString(it)) }.toSet(),
                if (o.isNull("hp")) null else o.getDouble("hp").toFloat(),
                o.getInt("enemies"), o.getInt("allies"), o.getInt("minions"), o.optInt("monsters", 0))
        } catch (e: Exception) { null }              // a damaged line is skipped, never guessed
    }
}

// a bounded, de-duplicated collection of moments (oldest dropped first)
class PracticeMomentSet(private val max: Int = 2000, private val minGapMs: Long = 1500L) {
    private val items = ArrayDeque<PracticeMoment>()
    val size: Int @Synchronized get() = items.size
    val matches: Int @Synchronized get() = items.map { it.matchId }.toSet().size

    // adds one moment; refuses unidentified matches and moments closer than minGapMs to the last one
    @Synchronized fun add(m: PracticeMoment): Boolean {
        if (m.matchId == 0L || m.summary.isBlank()) return false
        items.lastOrNull()?.let { if (it.matchId == m.matchId && m.atMs - it.atMs < minGapMs) return false }
        if (items.size >= max) items.removeFirst()
        items.addLast(m)
        return true
    }

    @Synchronized fun all(): List<PracticeMoment> = items.toList()
    @Synchronized fun clear() = items.clear()

    @Synchronized fun toJsonLines(): String = items.joinToString("\n") { it.toJson().toString() }
    @Synchronized fun loadJsonLines(text: String) {
        items.clear()
        text.lineSequence().filter { it.isNotBlank() }.forEach { l ->
            try { PracticeMoment.fromJson(JSONObject(l))?.let { if (items.size >= max) items.removeFirst(); items.addLast(it) } } catch (e: Exception) {}
        }
    }

    // how many moments each answer has: a set that is nearly all one answer measures little
    @Synchronized fun mix(): Map<DecisionKind, Int> = items.groupingBy { it.played }.eachCount()
}
