package com.example.mlbbresearch.ai

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.game.ComboBook
import com.example.mlbbresearch.game.ComboStep
import com.example.mlbbresearch.game.CooldownTracker
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.HeroKit
import com.example.mlbbresearch.game.HeroProfile
import com.example.mlbbresearch.game.Passive
import com.example.mlbbresearch.game.SkillBook
import com.example.mlbbresearch.game.SpellDb
import com.example.mlbbresearch.game.UltRule

// Continuous skill combo runner (v37.06):
// - Executes each step of your hero's combo in sequence (with 0.5s Tap, 0.3s+0.3s Short Aim, or 0.3s+0.5s Long Aim holds)
// - Full support for complex multi-phase heroes:
//   * Suyou (Tap Mortal vs Hold Immortal per skill)
//   * Hirara (2-skill drag combo s1<->s2; advances past paired skill when dragged together)
//   * Sora (swipe Ultimate left/right + transformed s1 > s2 reset)
//   * Julian (dynamic 2-skill setup -> 3rd Enhanced skill -> empowered basic attacks)
//   * Fanny (double Steel Cable wall-grapple s2 > s2 -> Cut Throat ult -> Tornado Strike s1)
//   * Gusion (s2 throw -> ult reset -> s2 throw -> s1 mark -> s2 recall -> s1 blink -> a -> ult dash -> a)
//   * Ling (s2 -> a -> ult 1.45s air leap -> 4x s2 across the 4 Field Eye blades -> s1 -> a)
object ComboRunner {

    private var steps: List<ComboStep> = emptyList()
    private var idx = 0
    private var nextAt = 0L
    private var startedAt = 0L
    private var lastEnemySeenAt = 0L
    private var targetId = -1
    private var lastSlot: String? = null
    private var lastSlotAt = 0L
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

    // Called by ActionExecutor when Hirara drags one skill onto another (consuming both in one gesture)
    @Synchronized
    fun onPairedSkillConsumed(otherSlot: String) {
        if (running && idx < steps.size && steps[idx].slot == otherSlot) {
            idx++
        }
    }

    private fun stop(why: String) {
        if (running) status = "combo ended: $why"
        reset()
    }

    @Volatile var activeComboSource: String = "built-in"
        private set

    private fun buildComboSteps(hero: String?, kit: com.example.mlbbresearch.game.HeroSkills, s: GameState, targetDist: Float, targetHp: Float = 0.5f, p: HeroProfile = com.example.mlbbresearch.game.HeroDb.profile(hero)): List<ComboStep> {
        val hasManual = hero != null && Cfg.manualCombo(hero)?.let { ComboBook.parse(it).isNotEmpty() } == true
        val ultReady = CooldownTracker.ready("ult", p) || HeroKit.canRecastNow(hero, "ult")
        val (selectedText, srcLabel) = ComboBook.selectForSituation(
            hero = hero,
            level = s.level,
            ultReady = ultReady,
            targetHp = targetHp,
            targetDist = targetDist,
            enemiesClose = s.enemiesWithin(0.55f).size,
            selfHp = s.selfHp,
            retreating = false
        )
        activeComboSource = srcLabel
        var st = ComboBook.steps(hero, selectedText).filter { it.slot == "a" || it.slot == "spell" || HeroKit.has(hero, it.slot) }
        if (st.none { it.slot != "a" }) {
            st = HeroKit.slots(hero).map { ComboStep(it, 600L) }
        }
        // If using built-in combos (not a custom manual input) on standard heroes, weave a basic attack after every skill
        if (!hasManual && hero !in HeroKit.MULTI_PHASE_HEROES) {
            st = st.flatMapIndexed { i, x ->
                if (x.slot != "a" && st.getOrNull(i + 1)?.slot != "a") listOf(x, ComboStep("a", 220L)) else listOf(x)
            }
            val dash = kit.escape?.slot
            if (kit.has(Passive.AFTER_DASH_BONUS) && dash != null && st.any { it.slot == dash }) {
                st = st.filter { it.slot == dash }.take(1) + st.filterIndexed { i, x -> !(x.slot == dash && i == st.indexOfFirst { it.slot == dash }) }
            }
        }
        return st
    }

    fun apply(d: TacticalDecision, s: GameState, p: HeroProfile): TacticalDecision {
        if (!Cfg.useCombos) return d
        val now = s.ts
        // Only abort immediately on an actual retreat / recall emergency
        if (d.intent == Intent.RETREAT || d.intent == Intent.RECALL || Utility.state == Utility.State.RETREATING) {
            if (running) stop(d.intent.name.lowercase())
            return d
        }
        val kit = SkillBook.get(s.ownHero)
        // Find the target (re-acquire nearest enemy within 0.65f if track ID changed during a dash)
        val trackedTarget = s.enemies.firstOrNull { it.id == (if (d.targetId >= 0) d.targetId else targetId) }
            ?: s.enemies.filter { s.distToSelf(it.x, it.y) <= 0.65f }.minByOrNull { s.distToSelf(it.x, it.y) }

        if (d.intent != Intent.ENGAGE && !running) return d
        if (trackedTarget != null) {
            lastEnemySeenAt = now
            targetId = trackedTarget.id
        }

        val tx = trackedTarget?.x ?: d.targetX
        val ty = trackedTarget?.y ?: d.targetY
        val thp = trackedTarget?.hp ?: d.targetHp
        val dist = if (tx >= 0f) s.distToSelf(tx, ty) else 9f

        if (!running) {
            var st = buildComboSteps(s.ownHero, kit, s, dist, thp, p)
            if (st.firstOrNull()?.slot != "a") {
                st = listOf(ComboStep("a", 220L)) + st
            }
            val firstReadySkillIdx = st.indexOfFirst {
                it.slot != "a" && it.slot != "spell" && (CooldownTracker.ready(it.slot, p) || HeroKit.canRecastNow(s.ownHero, it.slot))
            }
            if (firstReadySkillIdx < 0) return d
            val firstSlot = st[firstReadySkillIdx].slot
            val r = maxOf(kit[firstSlot]?.range ?: p.engageRange, p.engageRange)
            if (dist > r * 1.35f) { status = "walking into $firstSlot range"; return d }
            // Always begin the combo with the opening Basic Attack ("a") followed by the ready skills! (v37.14)
            steps = listOf(ComboStep("a", 220L)) + st.drop(firstReadySkillIdx)
            idx = 0
            nextAt = now
            startedAt = now
            lastEnemySeenAt = now
            used.clear()
            lastSlot = null
            status = "combo started ($activeComboSource): ${ComboBook.format(steps)}"
        }

        // Reassess before every step (allow up to 12s for 10-dagger Gusion / 4-blade Ling combos)
        when {
            now - startedAt > 12_000 -> { stop("completed cycle"); return d }
            trackedTarget == null && now - lastEnemySeenAt > 1400 -> { stop("target gone"); return d }
            trackedTarget != null && com.example.mlbbresearch.game.TowerLogic.enemyUnderTower(s, trackedTarget) && trackedTarget.hp > 0.15f && s.selfHp < 0.45f -> {
                stop("target under enemy tower"); return d
            }
        }

        // While Ling is airborne during Tempest of Blades (1.45s), steer landing center directly onto the enemy and wait to land before firing the 4 blade dashes!
        if (s.ownHero == "Ling" && HeroKit.lingInAirNow()) {
            status = "Ling airborne (Tempest of Blades): tracking target for 4-blade landing"
            val self = s.self
            val moveVec = if (self != null && tx >= 0f) {
                val dx = (tx - self.x) * s.aspect; val dy = ty - self.y
                val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
                (dx / l) to (dy / l)
            } else d.moveX to d.moveY
            return TacticalDecision(
                Intent.ENGAGE, moveVec.first, moveVec.second, emptyList(),
                tx, ty, if (trackedTarget != null) "enemy #${trackedTarget.id}" else d.targetLabel,
                thp, if (trackedTarget != null) trackedTarget.id else d.targetId,
                d.reason + " | Ling ult airborne"
            )
        }

        val casts = ArrayList<String>()
        if (now >= nextAt) {
            val burst = kit.burst { CooldownTracker.ready(it, p) && it !in used }
            val lethal = thp in 0f..(burst + 0.12f)
            val teamfight = s.enemiesWithin(0.65f).size >= 2
            val finisherUlt = p.ult == UltRule.EXECUTE && s.ownHero !in HeroKit.MULTI_PHASE_HEROES
            val confirmKill = thp in 0f..0.05f && dist <= p.attackRange * 1.15f
            val ultOk = if (finisherUlt) thp in 0f..0.52f || lethal
                else ("ult" in d.casts || lethal || teamfight || CooldownTracker.lowCdUlt(s.ownHero) || s.ownHero in HeroKit.MULTI_PHASE_HEROES || thp <= 0.72f) && !confirmKill

            var burstN = 0
            var span = 0L
            var guard = 0
            while (idx < steps.size && burstN < 1 && guard++ < steps.size + 2) {
                val st = steps[idx]
                if (st.slot == "a") {
                    val isOpeningAttack = idx == 0
                    idx++
                    // Always execute the opening basic attack (idx == 0) to lock onto the target before the first skill!
                    if ((isOpeningAttack || dist <= maxOf(p.attackRange * 1.65f, 0.38f)) && "attack" !in casts) {
                        casts.add("attack")
                        span = com.example.mlbbresearch.controller.CastTiming.ATTACK_HOLD_MS + 35L
                        burstN++
                        break
                    }
                    continue
                }
                val skillRange = maxOf(kit[st.slot]?.range ?: p.engageRange, p.engageRange)
                val inRange = st.slot == "spell" || dist <= skillRange * 1.38f || HeroKit.canRecastNow(s.ownHero, st.slot)
                val isReady = when (st.slot) {
                    "ult" -> (ultOk && CooldownTracker.ready("ult", p)) || HeroKit.canRecastNow(s.ownHero, "ult")
                    "spell" -> s.spell in SpellDb.offensive && CooldownTracker.ready("spell", p)
                    else -> CooldownTracker.ready(st.slot, p) || HeroKit.canRecastNow(s.ownHero, st.slot)
                }
                if (!isReady) {
                    idx++
                    continue
                }
                if (!inRange) {
                    status = "combo ${idx + 1}/${steps.size}: stepping into ${st.slot} range"
                    break
                }
                idx++
                casts.add(st.slot)
                used.add(st.slot)
                lastSlot = st.slot
                lastSlotAt = now
                burstN++
                val mode = SkillBook.castMode(s.ownHero, st.slot)
                val longRange = com.example.mlbbresearch.controller.ActionExecutor.isLongRangeSkill(s.ownHero, st.slot, false)
                span += when {
                    s.ownHero == "Ling" && st.slot == "ult" -> 1480L // Wait 1.45s air-leap before chaining the 4 Field Eye blades
                    s.ownHero == "Ling" && st.slot == "s2" && HeroKit.lingHasBladesNow() -> 620L
                    s.ownHero == "Fanny" && st.slot == "s2" -> 580L
                    HeroKit.shouldRecastTap(s.ownHero, st.slot) -> com.example.mlbbresearch.controller.CastTiming.TAP_HOLD_MS + 40L
                    else -> com.example.mlbbresearch.controller.CastTiming.gap(s.ownHero, st.slot, mode, longRange)
                }
                break
            }
            if (burstN > 0) {
                nextAt = now + span.coerceIn(180L, 1500L)
                status = "combo ${idx}/${steps.size} ($activeComboSource): ${ComboBook.format(steps)}"
            }
        }

        // Basic-attack reliant heroes (Zilong, Argus, Thamuz, Layla, Miya, Marksmen, etc.) ALWAYS keep basic attack active on every frame!
        val baReliant = com.example.mlbbresearch.game.HeroDb.isBasicAttackReliant(s.ownHero)
        val allowAutoWeave = baReliant || s.ownHero !in setOf("Fanny", "Gusion", "Ling") || !running || HeroKit.julianEmpoweredNow()
        if (allowAutoWeave && "attack" !in casts && ("attack" in d.casts || dist <= maxOf(p.attackRange * 1.65f, 0.36f))) {
            casts.add("attack")
        }

        // Loop combo if the enemy is still in fight range and skills are ready
        if (!running && trackedTarget != null && dist <= p.engageRange * 1.3f) {
            val nextReady = HeroKit.slots(s.ownHero).any { CooldownTracker.ready(it, p) || HeroKit.canRecastNow(s.ownHero, it) }
            if (nextReady) {
                steps = buildComboSteps(s.ownHero, kit, s, dist, thp, p)
                idx = 0
                used.clear()
                startedAt = now
            } else {
                status = "combo done: basic attacking"
                reset()
            }
        } else if (!running) {
            status = "combo done: reassessing"
            reset()
        }

        val stopCombatDist = if (p.ranged) p.attackRange * 0.95f else maxOf(p.attackRange * 1.25f, 0.22f)
        val moveVec = if (d.moveX == 0f && d.moveY == 0f && trackedTarget != null && dist > stopCombatDist) {
            val self = s.self
            if (self != null) {
                val dx = (tx - self.x) * s.aspect; val dy = ty - self.y
                val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
                (dx / l) to (dy / l)
            } else 0f to 0f
        } else d.moveX to d.moveY

        return TacticalDecision(
            Intent.ENGAGE,
            moveVec.first,
            moveVec.second,
            casts,
            tx,
            ty,
            if (trackedTarget != null) "enemy #${trackedTarget.id}" else d.targetLabel,
            thp,
            if (trackedTarget != null) trackedTarget.id else d.targetId,
            d.reason + if (running) " | combo ${idx}/${steps.size}" else ""
        )
    }
}
