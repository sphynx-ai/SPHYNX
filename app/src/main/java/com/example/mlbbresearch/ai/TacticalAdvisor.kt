package com.example.mlbbresearch.ai

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.game.CooldownTracker
import com.example.mlbbresearch.game.GameMemory
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.HeroDb
import com.example.mlbbresearch.game.HeroProfile
import com.example.mlbbresearch.learning.FightOption
import kotlinx.coroutines.launch

// THE SMALL TACTICAL MODEL (second language-model slot, e.g. Gemma 3 270M on the CPU).
// In a fight (an enemy hero close) it is asked about 3 times a second, in the background - the
// frame-by-frame play never waits for it. It gets a short summary of the moment and answers with
// ONE option from a fixed list (all in / poke / farm / retreat / group / rules).
// Its answer is ADVICE: the Kotlin checks below must agree before the Brain plays it, and the
// tactical engine's own safety rules (critical HP, tower range, bad-fight veto) still come after.
// The model never sends a touch.
object TacticalAdvisor {

    private const val ASK_EVERY_MS = 330L
    private const val VALID_MS = 1000L

    @Volatile var lastAnswer = "-"; private set
    @Volatile var lastPrompt = ""; private set
    @Volatile var lastMs = 0L; private set
    @Volatile var asked = 0; private set
    @Volatile var used = 0; private set
    @Volatile var rejected = 0; private set
    @Volatile var lastNote = "-"; private set

    @Volatile private var busy = false
    @Volatile private var askedAt = 0L
    @Volatile private var advice: FightOption? = null
    @Volatile private var adviceAt = 0L

    // the notebook's report (tactical_llm_info.json): a model that only learned to repeat the most
    // common answer gives no advice (ModelQuality)
    val weakReason: String? get() = com.example.mlbbresearch.learning.ModelQuality.weakReason(Cfg.raw("tactical_llm_info"))
    val active: Boolean get() = Cfg.tacticalLlmOn && TacticalLlmEngine.ready && weakReason == null

    // The moment in a few short lines (the fine-tuning notebook writes the same format)
    // ready: skills ready (null = yours; a live match passes the watched player's)
    fun summary(s: GameState, p: HeroProfile, readyOverride: List<String>? = null): String {
        val near = s.enemiesWithin(0.9f).sortedBy { s.distToSelf(it.x, it.y) }
        // the fight scores always (also while YOU play and in live matches: they help the model a lot)
        val sc = try { Deliberation.score(s, p) } catch (e: Exception) { Deliberation.last }
        val ready = readyOverride?.filter { it != "spell" } ?: com.example.mlbbresearch.game.HeroKit.slots(s.ownHero).filter { CooldownTracker.ready(it, p) }
        val me = s.minimapSelf
        val underTower = me != null && s.towers.any { kotlin.math.hypot(it.x - me.first, it.y - me.second) < com.example.mlbbresearch.game.TowerLogic.range() }
        return buildString {
            append("hero ").append(s.ownHero ?: "unknown").append(" (").append(HeroDb.role(s.ownHero).name.lowercase())
            append(if (HeroDb.isRanged(s.ownHero)) ", ranged" else ", melee").append("), ").append(s.lane.label)
            append(", time ").append(if (s.matchSec >= 0) "%d:%02d".format(s.matchSec / 60, s.matchSec % 60) else "?")
            append("\nme: HP ").append((s.selfHp * 100).toInt()).append("%, level ").append(s.level)
            append(", ready: ").append(if (ready.isEmpty()) "none" else ready.joinToString(" "))
            if (s.spell != null) append(", spell ").append(s.spell).append(if (readyOverride?.contains("spell") ?: CooldownTracker.ready("spell", p)) " ready" else " used")
            append("\nenemies close: ").append(near.size)
            if (near.isNotEmpty()) append(" (HP ").append(near.joinToString(" ") { "${(it.hp * 100).toInt()}%" }).append(")")
            append(", nearest ").append(if (near.isEmpty()) "-" else if (s.distToSelf(near[0].x, near[0].y) <= p.attackRange * 1.2f) "in reach" else "outside reach")
            append("\nallies close: ").append(s.alliesWithin(0.5f).size)
            append(", minions: ").append(s.enemyMinions.size)
            append(", under enemy tower: ").append(if (underTower) "yes" else "no")
            append(", enemies missing from map: ").append(GameMemory.missing(s.ts).size)
            if (sc != null) append("\nscores: attack %.2f, retreat %.2f, risk %.2f".format(sc.attack, sc.retreat, sc.r))
        }
    }

    fun prompt(summary: String) =
        "You play Mobile Legends: Bang Bang. Choose the best action for this fight moment.\n" + summary +
            "\nOptions: ALL_IN, POKE, FARM, RETREAT, GROUP, RULES\nAnswer with one option only."

    // reads the first option named in the reply
    fun parse(reply: String): FightOption? {
        val u = reply.uppercase().replace('-', '_').replace(' ', '_')
        return FightOption.values().map { it to u.indexOf(it.name) }.filter { it.second >= 0 }.minByOrNull { it.second }?.first
            ?: if (u.contains("ALLIN")) FightOption.ALL_IN else null
    }

    // the latest parsed answer and when it came (read-only, for the decision log); null if none
    fun latestAdvice(): Pair<FightOption, Long>? = advice?.let { it to adviceAt }

    // called by the fast loop on every decision: starts a question in the background when due
    fun tick(s: GameState, p: HeroProfile) {
        if (!active || busy || s.self == null) return
        val now = System.currentTimeMillis()
        if (now - askedAt < ASK_EVERY_MS) return
        if (s.enemiesWithin(0.9f).isEmpty()) return          // only fights need it
        busy = true
        askedAt = now
        val q = prompt(summary(s, p))
        lastPrompt = q
        AiRuntime.scope.launch {
            try {
                val t0 = System.currentTimeMillis()
                val r = TacticalLlmEngine.ask(q, null, 0.0)
                lastMs = System.currentTimeMillis() - t0
                asked++
                lastAnswer = r.take(40).replace('\n', ' ')
                parse(r)?.let { advice = it; adviceAt = System.currentTimeMillis() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Diag.error("Small tactical model", t)
            } finally { busy = false }
        }
    }

    // The Kotlin check: the model's latest advice if it is fresh and the scores allow it, else null
    fun validated(s: GameState, p: HeroProfile): FightOption? {
        val a = advice ?: return null
        if (!active || System.currentTimeMillis() - adviceAt > VALID_MS || s.enemiesWithin(0.9f).isEmpty()) return null
        val why = check(a, s)
        if (why != null) {
            rejected++
            lastNote = "rejected ${a.name.lowercase()}: $why"
            return null
        }
        used++
        lastNote = "using ${a.label}"
        return a
    }

    // THE KOTLIN CHECKS for any fight advice (the small model's or the fight-choice network's):
    // the reason to reject it, or null when it may be played
    fun check(a: FightOption, s: GameState): String? {
        val sc = Deliberation.last
        val hp = s.selfHp
        return when (a) {
            FightOption.RULES -> null
            FightOption.ALL_IN -> when {
                hp in 0f..0.2f -> "all in at ${(hp * 100).toInt()}% HP"
                sc != null && sc.attack < sc.retreat -> "all in while the scores favour leaving (%.2f < %.2f)".format(sc.attack, sc.retreat)
                else -> null
            }
            FightOption.POKE -> if (!com.example.mlbbresearch.game.SkillBook.canPoke(s.ownHero)) "poke with a melee hero that has no ranged skill" else null
            FightOption.FARM -> when {
                s.enemyMinions.isEmpty() && s.monsters.isEmpty() -> "farm with nothing to farm"
                sc != null && sc.r > 0.5f -> "farm at risk %.2f".format(sc.r)
                else -> null
            }
            FightOption.RETREAT -> if (hp > 0.6f && sc != null && sc.retreat < 0.3f) "retreat with nothing to fear" else null
            FightOption.GROUP -> if (s.allies.isEmpty()) "group with no ally on screen" else null
        }
    }

    fun text(): String {
        if (!Cfg.tacticalLlmOn) return "Small tactical model: off"
        if (!TacticalLlmEngine.ready) return "Small tactical model: ${TacticalLlmEngine.state}"
        weakReason?.let { return "Small tactical model: TOO WEAK, NOT USED ($it) - retrain with more varied fights" }
        return "Small tactical model (${com.example.mlbbresearch.models.ModelManager.tacticalName()}): asked $asked, used $used, " +
            "rejected by the checks $rejected; last answer \"$lastAnswer\" in $lastMs ms\n   $lastNote"
    }
}
