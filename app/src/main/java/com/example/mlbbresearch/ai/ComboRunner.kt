package com.example.mlbbresearch.ai

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.game.ComboBook
import com.example.mlbbresearch.game.ComboStep
import com.example.mlbbresearch.game.CooldownTracker
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.HeroProfile
import com.example.mlbbresearch.game.SpellDb

// Plays your hero's combo in order, with the right pauses, when the tactical engine
// decides to fight. Without it every ready skill would be pressed at once.
//  - starts only when the combo's first skill is ready
//  - a step whose skill is cooling down is skipped; a skill already used earlier in the
//    same combo may be pressed again (second stage, e.g. Gusion's daggers, Chou's kicks)
//  - the ultimate is only used if it is worth it (the engine wanted it, or the target is low)
//  - any other decision (retreat, recall...) or 6 seconds without finishing stops it
object ComboRunner {

    private var steps: List<ComboStep> = emptyList()
    private var idx = 0
    private var nextAt = 0L
    private var startedAt = 0L
    private val used = HashSet<String>()

    @Volatile var status = "-"
        private set

    val running: Boolean get() = steps.isNotEmpty() && idx < steps.size

    fun reset() {
        steps = emptyList()
        idx = 0
        used.clear()
    }

    fun apply(d: TacticalDecision, s: GameState, p: HeroProfile): TacticalDecision {
        if (!Cfg.useCombos) return d
        val now = s.ts
        if (d.intent != Intent.ENGAGE) {
            if (running) status = "combo stopped (${d.intent.name.lowercase()})"
            reset()
            return d
        }
        if (!running) {
            val st = ComboBook.steps(s.ownHero)
            val first = st.firstOrNull { it.slot != "a" } ?: return d
            if (!CooldownTracker.ready(first.slot, p)) return d      // wait for the opener
            steps = st; idx = 0; nextAt = now; startedAt = now
            used.clear()
            status = "combo started: ${ComboBook.format(st)}"
        }
        if (now - startedAt > 6000) {
            status = "combo timed out"
            reset()
            return d
        }

        val casts = ArrayList<String>()
        if (now >= nextAt) {
            val ultOk = "ult" in d.casts || d.targetHp in 0f..0.5f
            while (idx < steps.size) {
                val st = steps[idx++]
                val ok = when (st.slot) {
                    "a" -> true
                    "ult" -> ultOk && (CooldownTracker.ready("ult", p) || "ult" in used)
                    "spell" -> s.spell in SpellDb.offensive && CooldownTracker.ready("spell", p)
                    else -> CooldownTracker.ready(st.slot, p) || st.slot in used
                }
                if (!ok) continue
                casts.add(if (st.slot == "a") "attack" else st.slot)
                used.add(st.slot)
                // fast mode: shorter pauses between combo steps (as fast as the game takes them)
                nextAt = now + if (Cfg.fastMode) (st.waitMs * 0.6f).toLong() else st.waitMs
                break
            }
            status = "combo ${idx}/${steps.size}: ${ComboBook.format(steps)}"
        }
        // keep hitting between skills when the target is in reach
        if (casts.isEmpty() && "attack" in d.casts) casts.add("attack")
        if (!running) status = "combo done"
        return TacticalDecision(d.intent, d.moveX, d.moveY, casts, d.targetX, d.targetY, d.targetLabel,
            d.targetHp, d.targetId, d.reason + if (running) " | combo ${idx}/${steps.size}" else "")
    }
}
