package com.sphynx.ai

import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import com.sphynx.game.CooldownTracker
import com.sphynx.game.GameMemory
import com.sphynx.game.GameState
import com.sphynx.game.GameStateTracker
import com.sphynx.game.HeroProfile
import com.sphynx.game.Lane
import com.sphynx.game.MapIntel
import kotlin.math.hypot

// THE "WHY": every decision, the AI scores its possible actions like a player weighing options,
// each factor 0..1:
//   K = kill opportunity (enemy low and in reach)     H = HP advantage over that enemy
//   S = skills ready (s1, s2, ult)                    T = teammates close by
//   R = risk (enemies around, missing enemies that could be close, enemy tower, being hit)
//   Attack    = wK*K + wH*H + wS*S + wT*T - wR*R      (start: 0.30 0.20 0.20 0.20 0.10)
//   Farm      = minions / camps here, safe
//   Retreat   = low HP and high risk
//   Objective = turtle / lord / buff up close with allies
//   Push      = enemy tower in reach with your minions, safe
//   Recall    = low HP and nobody around
// The fast rules still play the game; these scores check them: a fight the scores call clearly
// worse than leaving (and not a finishing blow) is turned into a retreat. The weights are per
// role and LEARN: a fight that ends in your kill makes the factors that were high count more,
// a fight that ends in your death makes risk count more.
object Deliberation {

    class Scores(val k: Float, val h: Float, val sk: Float, val t: Float, val r: Float,
                 val attack: Float, val farm: Float, val retreat: Float, val objective: Float, val push: Float, val recall: Float) {
        fun table(): List<Pair<String, Float>> = listOf("Attack enemy" to attack, "Farm" to farm, "Retreat" to retreat,
            "Objective" to objective, "Push tower" to push, "Recall" to recall).sortedByDescending { it.second }
        fun best() = table().first()
        fun short() = table().take(3).joinToString(", ") { "%s %.2f".format(it.first.lowercase(), it.second) }
    }

    @Volatile var last: Scores? = null; private set
    @Volatile var lastNote = "-"; private set

    // ---- role weights (Jungler, Roamer, Mid, Gold, EXP start differently) ----
    private fun defaults(l: Lane) = when (l) {
        Lane.JUNGLE -> floatArrayOf(0.34f, 0.18f, 0.20f, 0.16f, 0.12f)
        Lane.ROAM -> floatArrayOf(0.22f, 0.16f, 0.20f, 0.30f, 0.12f)
        Lane.MID -> floatArrayOf(0.32f, 0.18f, 0.24f, 0.16f, 0.10f)
        Lane.GOLD -> floatArrayOf(0.26f, 0.20f, 0.16f, 0.20f, 0.18f)
        Lane.EXP -> floatArrayOf(0.30f, 0.24f, 0.18f, 0.16f, 0.12f)
    }
    fun weights(l: Lane): FloatArray =
        Cfg.raw("delib_w_${l.name}")?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 5 }?.toFloatArray() ?: defaults(l)
    private fun save(l: Lane, w: FloatArray) = Cfg.setRaw("delib_w_${l.name}", w.joinToString(",") { "%.3f".format(java.util.Locale.US, it) })
    fun resetWeights() { for (l in Lane.values()) Cfg.setRaw("delib_w_${l.name}", null) }

    fun score(s: GameState, p: HeroProfile): Scores {
        val now = s.ts
        val hp = s.selfHp.coerceIn(0f, 1f)
        val near = s.enemiesWithin(p.engageRange * 1.3f)
        val target = near.minByOrNull { it.hp }
        val k = if (target == null) 0f else ((1f - target.hp) * 0.7f + (if (target.hp < 0.3f) 0.3f else 0f)).coerceIn(0f, 1f)
        val h = if (target == null) hp else (0.5f + (hp - target.hp) / 2f).coerceIn(0f, 1f)
        val slots = listOf("s1", "s2", "ult")
        val sk = slots.count { CooldownTracker.ready(it, p) } / 3f
        val t = (s.alliesWithin(0.5f).size / 2f).coerceAtMost(1f)
        val me = s.minimapSelf
        val underTower = me != null && s.towers.any { hypot(it.x - me.first, it.y - me.second) < TacticalEngine.rules.towers.range() } && s.allyMinions.isEmpty()
        val r = (s.enemiesWithin(0.9f).sumOf { (0.15 + 0.2 * it.hp) }.toFloat() +
            GameMemory.fogDanger(me, now) * 0.2f +
            (if (MapIntel.gank(s)) 0.3f else 0f) +
            (if (underTower) 0.35f else 0f) +
            (if (GameStateTracker.hurtRecently(now)) 0.15f else 0f)).coerceIn(0f, 1f)
        val w = weights(s.lane)
        val attack = if (target == null) 0f else (w[0] * k + w[1] * h + w[2] * sk + w[3] * t - w[4] * r).coerceIn(0f, 1f) /
            (w[0] + w[1] + w[2] + w[3]).coerceAtLeast(0.1f)
        val farmHere = (s.enemyMinions.isNotEmpty() || s.creeps.isNotEmpty())
        val farm = ((if (farmHere) 0.6f else 0.15f) + 0.3f * (1f - r)) * (if (s.lane == Lane.ROAM) 0.6f else 1f)
        val retreat = ((1f - hp) * 0.55f + r * 0.6f - (if (target != null && target.hp < 0.15f) 0.3f else 0f)).coerceIn(0f, 1f)
        val objective = if (s.boss != null || s.bossHp >= 0f || s.creeps.any { TacticalEngine.rules.isBuffCamp(it.species) })
            (0.55f + 0.15f * t + 0.2f * hp - 0.4f * r).coerceIn(0f, 1f) else 0f
        val push = if (s.towerRing.zone == com.sphynx.vision.TowerRing.Zone.GREEN) (0.5f + 0.3f * (1f - r)) else 0.1f * (1f - r)
        val recall = if (hp < 0.35f && s.enemiesWithin(0.9f).isEmpty()) (0.9f - hp) else 0f
        return Scores(k, h, sk, t, r, attack, farm.coerceIn(0f, 1f), retreat, objective, push.coerceIn(0f, 1f), recall.coerceIn(0f, 1f))
    }

    // ---- checking the rules' decision ----
    // returns the reason to leave the fight, or null to keep the rules' decision
    fun vetoFight(s: GameState, d: TacticalDecision, sc: Scores): String? {
        if (d.intent != Intent.ENGAGE || d.targetHp in 0f..0.35f) return null     // finishing blows stay
        if (d.reason.startsWith("last stand")) return null                        // a 1v1 you can still win (your rule)
        if (s.selfHp >= 0.30f || sc.retreat <= sc.attack + 0.30f) return null
        return "the scores say leave: attack %.2f < retreat %.2f (risk %.2f)".format(sc.attack, sc.retreat, sc.r)
    }

    // the opposite: the rules want to leave, but a lone enemy at 25% or less is in reach and the
    // scores clearly favour the kill (and you are not too low) -> take it
    fun takeKill(s: GameState, p: HeroProfile, d: TacticalDecision, sc: Scores): com.sphynx.vision.Track? {
        if (d.intent != Intent.RETREAT || d.reason.contains("hiding") || s.selfHp < 0.3f || s.level < 4) return null   // (no early duels)
        if (sc.attack <= sc.retreat + 0.15f || s.enemiesWithin(0.9f).size != 1) return null
        return s.enemiesWithin(p.engageRange).firstOrNull { it.hp in 0f..0.25f }
    }

    // ---- learning from fights ----
    private var fight: Scores? = null
    private var fightLane = Lane.MID
    private var fightAt = 0L
    private var killsAt = 0
    private var deathsAt = 0

    fun observe(s: GameState, d: TacticalDecision, sc: Scores) {
        last = sc
        val now = s.ts
        val f = fight
        if (f == null) {
            if (d.intent == Intent.ENGAGE) {
                fight = sc; fightLane = s.lane; fightAt = now
                killsAt = GameStateTracker.kills + GameStateTracker.assists; deathsAt = GameStateTracker.deaths
            }
            return
        }
        val won = GameStateTracker.kills + GameStateTracker.assists > killsAt
        val died = GameStateTracker.deaths > deathsAt
        if (!won && !died && now - fightAt < 10_000) return
        fight = null
        val reward = when { died -> -1f; won -> 1f; else -> 0f }
        if (reward == 0f) return
        val w = weights(fightLane)
        val x = floatArrayOf(f.k, f.h, f.sk, f.t)
        val lr = 0.03f
        for (i in 0 until 4) w[i] = (w[i] + lr * reward * (x[i] - 0.5f)).coerceIn(0.05f, 0.5f)
        w[4] = (w[4] - lr * reward * f.r).coerceIn(0.05f, 0.5f)
        save(fightLane, w)
        lastNote = (if (reward > 0) "fight won" else "fight lost") + " (${fightLane.label}): weights now " +
            "kill %.2f, HP %.2f, skills %.2f, team %.2f, risk %.2f".format(w[0], w[1], w[2], w[3], w[4])
        Diag.info("Learned: $lastNote")
    }

    fun text(lane: Lane): String {
        val sc = last ?: return "Scores: -"
        val w = weights(lane)
        return "Why (scores 0-1): " + sc.table().joinToString("  ") { "%s %.2f".format(it.first, it.second) } +
            "\nFactors: kill chance %.2f, HP edge %.2f, skills %.2f, team %.2f, risk %.2f".format(sc.k, sc.h, sc.sk, sc.t, sc.r) +
            "\nWeights (${lane.label}): kill %.2f, HP %.2f, skills %.2f, team %.2f, risk %.2f".format(w[0], w[1], w[2], w[3], w[4]) +
            (if (lastNote != "-") "\nLast lesson: $lastNote" else "")
    }
}
