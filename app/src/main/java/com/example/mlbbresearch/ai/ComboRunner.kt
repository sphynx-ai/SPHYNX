package com.example.mlbbresearch.ai

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.game.ComboBook
import com.example.mlbbresearch.game.ComboStep
import com.example.mlbbresearch.game.CooldownTracker
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.HeroProfile
import com.example.mlbbresearch.game.Passive
import com.example.mlbbresearch.game.SkillBook
import com.example.mlbbresearch.game.SpellDb

// Plays your hero's combo step by step - and THINKS before every step instead of firing the
// whole sequence blindly:
//  - each skill only when the target is inside THAT skill's range (else walk in first)
//  - the ultimate only when it is worth it: the ready skills can kill (lethal estimate), a
//    teamfight (2+ enemies), or the engine asked for it; an enemy about to die to basic
//    attacks does not get the ultimate (kill confirmation)
//  - a passive that powers up the next basic attack after a skill: a basic attack is woven
//    in after every skill
//  - the previous skill must have really gone off (its button went dark) before the next one;
//    one that did not fire is tried once more (the execution learns the right way to cast it)
//  - INTERRUPTED (and the engine reassesses) when: the target is gone or escaped out of
//    range, it reached its tower, another enemy shows up, your HP dropped hard, the fight
//    state is no longer "engaging", or 6 s passed
object ComboRunner {

    private var steps: List<ComboStep> = emptyList()
    private var idx = 0
    private var nextAt = 0L
    private var startedAt = 0L
    private var startHp = 1f
    private var startEnemies = 0
    private var targetId = -1
    private var lastSlot: String? = null
    private var lastSlotAt = 0L
    private var retried = false
    private val used = HashSet<String>()

    @Volatile var status = "-"
        private set

    val running: Boolean get() = steps.isNotEmpty() && idx < steps.size

    fun reset() {
        steps = emptyList()
        idx = 0
        used.clear()
        lastSlot = null
    }

    private fun stop(why: String) {
        if (running) status = "combo interrupted: $why"
        reset()
    }

    fun apply(d: TacticalDecision, s: GameState, p: HeroProfile): TacticalDecision {
        if (!Cfg.useCombos) return d
        val now = s.ts
        if (d.intent != Intent.ENGAGE) {
            if (running) stop(d.intent.name.lowercase())
            return d
        }
        val kit = SkillBook.get(s.ownHero)
        val target = s.enemies.firstOrNull { it.id == d.targetId }
        val dist = if (d.targetX >= 0f) s.distToSelf(d.targetX, d.targetY) else 9f
        if (!running) {
            var st = ComboBook.steps(s.ownHero)
            // passive: enhanced basic attack after each skill -> weave one in
            if (kit.has(Passive.WEAVE_ATTACK)) st = st.flatMapIndexed { i, x ->
                if (x.slot != "a" && st.getOrNull(i + 1)?.slot != "a") listOf(x, ComboStep("a", 380L)) else listOf(x)
            }
            // passive: bonus after a dash -> open with the dash when it is in the combo
            val dash = kit.escape?.slot
            if (kit.has(Passive.AFTER_DASH_BONUS) && dash != null && st.any { it.slot == dash }) {
                st = st.filter { it.slot == dash }.take(1) + st.filterIndexed { i, x -> !(x.slot == dash && i == st.indexOfFirst { it.slot == dash }) }
            }
            val first = st.firstOrNull { it.slot != "a" } ?: return d
            if (!CooldownTracker.ready(first.slot, p)) return d      // wait for the opener
            // the opener must reach the target
            val r = kit[first.slot]?.range ?: p.engageRange
            if (dist > r * 1.15f) { status = "walking into ${first.slot} range"; return d }
            steps = st; idx = 0; nextAt = now; startedAt = now
            startHp = s.selfHp; startEnemies = s.enemiesWithin(0.7f).size; targetId = d.targetId
            used.clear(); lastSlot = null; retried = false
            status = "combo started: ${ComboBook.format(st)}"
        }

        // ---- reassess before every step ----
        when {
            now - startedAt > 6000 -> { stop("took too long"); return d }
            d.targetId != targetId || target == null && now - startedAt > 400 -> { stop("target changed or gone"); return d }
            s.selfHp >= 0f && startHp - s.selfHp > 0.3f -> { stop("HP dropped ${(startHp - s.selfHp).times(100).toInt()}%"); return d }
            s.enemiesWithin(0.7f).size > startEnemies -> { stop("another enemy showed up"); return d }
            target != null && com.example.mlbbresearch.game.TowerLogic.enemyUnderTower(s, target) && target.hp > 0.1f -> { stop("target reached its tower"); return d }
            Utility.state == Utility.State.RETREATING -> { stop("survival too low"); return d }
        }

        val casts = ArrayList<String>()
        if (now >= nextAt) {
            // did the last skill really go off? (its button goes dark) - one more try if not
            val ls = lastSlot
            if (ls != null && ls != "a" && now - lastSlotAt in 450..1500 && CooldownTracker.lookReady(ls) && !retried) {
                retried = true
                casts.add(ls); lastSlotAt = now
                nextAt = now + 300
                status = "combo: $ls did not go off, again"
            } else {
                retried = false
                val burst = kit.burst { CooldownTracker.ready(it, p) && it !in used }
                val lethal = d.targetHp in 0f..(burst + 0.05f)
                val teamfight = s.enemiesWithin(0.6f).size >= 2
                // kill confirmation: almost dead and in basic attack reach = no ultimate needed
                val confirmKill = d.targetHp in 0f..0.06f && dist <= p.attackRange * 1.2f
                val ultOk = ("ult" in d.casts || lethal || teamfight) && !confirmKill
                while (idx < steps.size) {
                    val st = steps[idx++]
                    val inRange = st.slot == "a" || st.slot == "spell" || dist <= (kit[st.slot]?.range ?: p.engageRange) * 1.2f
                    val ok = inRange && when (st.slot) {
                        "a" -> dist <= p.attackRange * 1.4f
                        "ult" -> ultOk && (CooldownTracker.ready("ult", p) || "ult" in used)
                        "spell" -> s.spell in SpellDb.offensive && CooldownTracker.ready("spell", p)
                        else -> CooldownTracker.ready(st.slot, p) || st.slot in used
                    }
                    if (!ok) continue
                    casts.add(if (st.slot == "a") "attack" else st.slot)
                    used.add(st.slot)
                    lastSlot = st.slot; lastSlotAt = now
                    nextAt = now + if (Cfg.fastMode) (st.waitMs * 0.6f).toLong() else st.waitMs
                    break
                }
                status = "combo ${idx}/${steps.size}: ${ComboBook.format(steps)}" + if (!ultOk && steps.any { it.slot == "ult" }) " (ultimate saved)" else ""
            }
        }
        // keep hitting between skills when the target is in reach
        if (casts.isEmpty() && "attack" in d.casts) casts.add("attack")
        if (!running) { status = "combo done: reassessing"; reset() }
        return TacticalDecision(d.intent, d.moveX, d.moveY, casts, d.targetX, d.targetY, d.targetLabel,
            d.targetHp, d.targetId, d.reason + if (running) " | combo ${idx}/${steps.size}" else "")
    }
}
