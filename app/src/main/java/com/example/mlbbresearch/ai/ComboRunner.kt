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

// Continuous skill combo runner (v37.15):
// - Executes each step of your hero's combo in sequence (with 0.5s Tap, 0.3s+0.3s Short Aim, or 0.3s+0.5s Long Aim holds)
// - Always starts the combo with a Basic Attack ("a") when attacking enemy heroes or jungle monsters/buffs (v37.14)
// - Respects active continuous-damage skill durations displayed on the Level Badge and Skill Icon (v37.15):
//   * Never interrupts Balmond s2 (Cyclone Sweep), Cici s1 (Yo-Yo Blitz), X.Borg s1/ult, Khaleed s1, Odette ult,
//     Chang'e ult, Pharsa ult, Claude ult, Akai ult, etc. with other combo skills mid-duration
//   * Exceptions (Argus Eternal Evil, Leomord Phantom Steed, Thamuz Cauterant Inferno, Yu Zhong Dragonoid, etc.):
//     continues full skill combo + basic attack execution aggressively throughout the Ultimate duration!
//   * Yu Zhong Black Dragon flight: steers dragon onto the enemy while skills are locked, then unleashes Dragonoid combo!
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
                if (x.slot != "a" && st.getOrNull(i + 1)?.slot != "a") listOf(x, ComboStep("a", 150L)) else listOf(x)
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

        // 1. Yu Zhong Black Dragon Flight (1000033086..1000033088):
        // While flying as the Black Dragon (center-bottom red bar active, s1/s2/s3 locked with purple prohibition icons),
        // steer directly onto the target enemy hero to deal dragon collision damage and knockback!
        // Only tap "ult" early if already on top of the enemy (dist <= 0.14f) and the dragon bar is nearly finished (< 22%).
        if (HeroKit.isYuZhongBlackDragonFlight(s)) {
            val self = s.self
            val moveVec = if (self != null && tx >= 0f) {
                val dx = (tx - self.x) * s.aspect; val dy = ty - self.y
                val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
                (dx / l) to (dy / l)
            } else d.moveX to d.moveY
            val landNow = dist <= 0.14f && s.transformBarPct in 0.01f..0.22f
            val dragonCasts = if (landNow) listOf("ult") else emptyList()
            // Queue Dragonoid combo ready to fire the instant Black Dragon lands
            steps = ComboBook.steps("Yu Zhong", "a > s1 > s3 > s2 > a")
            idx = 0
            startedAt = now
            nextAt = now
            status = "Yu Zhong Black Dragon (${(s.transformBarPct.coerceAtLeast(0f) * 100).toInt()}%): steering onto target"
            return TacticalDecision(
                Intent.ENGAGE, moveVec.first, moveVec.second, dragonCasts,
                tx, ty, if (trackedTarget != null) "enemy #${trackedTarget.id}" else d.targetLabel,
                thp, if (trackedTarget != null) trackedTarget.id else d.targetId,
                d.reason + " | Black Dragon flight (${(s.transformBarPct.coerceAtLeast(0f) * 100).toInt()}%)"
            )
        }

        // 2. Active Continuous-Damage Skill Guard (Balmond s2, Cici s1, X.Borg s1/ult, Odette ult, Chang'e ult, Claude ult, Akai ult, etc.):
        // When a continuous-damage skill's duration is active on the Level Badge or Skill Icon, DO NOT interrupt it with other combo skills!
        HeroKit.activeContinuousSkill(s)?.let { cont ->
            val canExecuteWithUlt = cont.executeUltHp > 0f && thp in 0f..cont.executeUltHp &&
                dist <= maxOf(kit["ult"]?.range ?: p.engageRange, p.engageRange) && CooldownTracker.ready("ult", p)
            if (!canExecuteWithUlt) {
                nextAt = maxOf(nextAt, now + 120L)
                val contCasts = ArrayList<String>()
                if (cont.allowBasicAttack && dist <= maxOf(p.attackRange * 1.65f, 0.38f)) {
                    contCasts.add("attack")
                }
                val self = s.self
                val (mx, my) = when {
                    !cont.allowMove -> 0f to 0f
                    !cont.allowBasicAttack && self != null && tx >= 0f && dist > 0.04f -> {
                        val dx = (tx - self.x) * s.aspect; val dy = ty - self.y
                        val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
                        (dx / l) to (dy / l)
                    }
                    else -> d.moveX to d.moveY
                }
                val pctDisp = s.activeSkillIcons[cont.slot] ?: s.skillBadgePct
                val pctStr = if (pctDisp >= 0f) " ${(pctDisp * 100).toInt()}%" else ""
                status = "${s.ownHero} ${cont.slot} continuous damage active$pctStr (holding combo)"
                return TacticalDecision(
                    Intent.ENGAGE, mx, my, contCasts,
                    tx, ty, if (trackedTarget != null) "enemy #${trackedTarget.id}" else d.targetLabel,
                    thp, if (trackedTarget != null) trackedTarget.id else d.targetId,
                    d.reason + " | keeping ${cont.slot} continuous damage active$pctStr"
                )
            }
        }

        if (!running) {
            var st = buildComboSteps(s.ownHero, kit, s, dist, thp, p)
            if (st.firstOrNull()?.slot != "a") {
                st = listOf(ComboStep("a", 150L)) + st
            }
            val firstReadySkillIdx = st.indexOfFirst {
                it.slot != "a" && it.slot != "spell" && (CooldownTracker.ready(it.slot, p) || HeroKit.canRecastNow(s.ownHero, it.slot))
            }
            if (firstReadySkillIdx < 0) return d
            val firstSlot = st[firstReadySkillIdx].slot
            val r = maxOf(kit[firstSlot]?.range ?: p.engageRange, p.engageRange)
            if (dist > r * 1.35f) { status = "walking into $firstSlot range"; return d }
            // Always begin the combo with the opening Basic Attack ("a") followed by the ready skills! (v37.14)
            steps = listOf(ComboStep("a", 150L)) + st.drop(firstReadySkillIdx)
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
            trackedTarget != null && com.example.mlbbresearch.game.TowerLogic.enemyUnderTower(s, trackedTarget) && trackedTarget.hp > 0.15f && s.selfHp < 0.45f && !(s.ownHero == "Argus" && HeroKit.isBuffUltActive(s)) -> {
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
        // If Lukas (Sacred Beast) or Lapu-Lapu (Heavy Sword) transformation bar is about to expire (< 28%) and 2nd-phase ult recast is ready, fire it before the bar ends!
        if (s.ownHero in setOf("Lukas", "Lapu-Lapu") && s.transformBarPct in 0.02f..0.28f && HeroKit.canRecastNow(s.ownHero, "ult") && dist <= p.engageRange * 1.25f) {
            casts.add("ult")
            used.add("ult")
            nextAt = now + 420L
        } else if (now >= nextAt) {
            val burst = kit.burst { CooldownTracker.ready(it, p) && it !in used }
            val lethal = thp in 0f..(burst + 0.12f)
            val teamfight = s.enemiesWithin(0.65f).size >= 2
            val finisherUlt = p.ult == UltRule.EXECUTE && s.ownHero !in HeroKit.MULTI_PHASE_HEROES
            val confirmKill = thp in 0f..0.05f && dist <= p.attackRange * 1.15f
            val execThr = HeroKit.executeUltThreshold(s.ownHero, s.level)
            val ultOk = if (finisherUlt) thp in 0f..execThr || lethal
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
                        span = com.example.mlbbresearch.controller.CastTiming.ATTACK_HOLD_MS + 15L
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
                    s.ownHero == "Ling" && st.slot == "s2" && HeroKit.lingHasBladesNow() -> 360L
                    s.ownHero == "Fanny" && st.slot == "s2" -> 330L
                    HeroKit.shouldRecastTap(s.ownHero, st.slot) -> com.example.mlbbresearch.controller.CastTiming.TAP_HOLD_MS + 20L
                    else -> com.example.mlbbresearch.controller.CastTiming.gap(s.ownHero, st.slot, mode, longRange)
                }
                break
            }
            if (burstN > 0) {
                nextAt = now + span.coerceIn(140L, 1450L)
                status = "combo ${idx}/${steps.size} ($activeComboSource): ${ComboBook.format(steps)}"
            }
        }

        // Basic-attack reliant heroes AND heroes with an active Buff/Enhancement Ultimate (Argus, Leomord, Thamuz, Zilong, Freya, etc.)
        // ALWAYS keep basic attack active on every frame!
        val baReliant = com.example.mlbbresearch.game.HeroDb.isBasicAttackReliant(s.ownHero) || HeroKit.isBuffUltActive(s)
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
            }
        }

        val self = s.self
        var (mx, my) = d.moveX to d.moveY
        // During Ling's 4-blade phase, steer toward the next Field Eye blade so s2 + movement align
        if (s.ownHero == "Ling" && HeroKit.lingHasBladesNow() && self != null) {
            HeroKit.nextLingBladeTarget(self.x, self.y, tx, ty, s.aspect)?.let { (bx, by) ->
                val dx = (bx - self.x) * s.aspect; val dy = by - self.y
                val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
                mx = dx / l; my = dy / l
            }
        } else if (mx == 0f && my == 0f && dist > p.attackRange * 0.9f && self != null && tx >= 0f && !p.ranged) {
            val dx = (tx - self.x) * s.aspect; val dy = ty - self.y
            val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
            mx = dx / l; my = dy / l
        }

        return TacticalDecision(
            Intent.ENGAGE, mx, my, casts,
            tx, ty, if (trackedTarget != null) "enemy #${trackedTarget.id}" else d.targetLabel,
            thp, if (trackedTarget != null) trackedTarget.id else d.targetId,
            d.reason + " | combo (${idx}/${steps.size})"
        )
    }
}
