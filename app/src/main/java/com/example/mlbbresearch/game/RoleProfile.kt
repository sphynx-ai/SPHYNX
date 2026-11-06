package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg
import kotlin.math.hypot

// ONE tactical engine for all five roles, powered by:
//  - Built-In Pro Meta (2025/2026 MLBB tournament timings: 0:20 Buff -> 0:42 Litho -> 1:15 Lv4 Gank -> 2:00 Turtle -> 5:00 Shield Drop -> 8:00 Lord)
//  - Latest Online Meta & Trend (parsed live from MetaStore.hero(ownHero)?.rotation / laning / jungle & MetaStore.general())
//  - Or a Hybrid combination of both (Cfg.rotationMetaMode = "hybrid" | "online" | "builtin") (v37.16).
enum class Job {
    STRONG_FIGHT,   // an enemy hero worth leaving everything for (engagement 70+)
    BOSS,           // turtle / lord on screen
    OBJECTIVE_MAP,  // turtle / lord up on the minimap: rotate there
    MONSTERS,       // jungle monsters on screen
    FIGHT,          // an enemy hero (engagement 50+)
    POSITIONING,    // not a good fight yet: keep a safe distance
    GANK,           // walk to an exposed enemy in a lane (minimap)
    INVADE,         // take an enemy buff / camp when the risk is low (jungle)
    BASE,           // the enemy base is exposed (no enemy tower left in front of it): go and hit it
    JUNGLE_ROUTE,   // next jungle camp
    MINIONS,        // the wave (clear / freeze / push)
    TOWER,          // push the enemy tower with minions in (green circle)
    DEFEND,         // our tower under attack
    TEAMFIGHT,      // join a fight seen on the minimap
    LANE_CAMPS,     // a jungle camp near the lane between waves
    SPLIT_PUSH,     // push a side lane alone while the enemies are elsewhere
    ROAM,           // walk with mid / jungler, check bushes ahead (vision), semi-roam after mid clear
    SAFETY          // do not wander alone deep in the enemy half
}

class RoleProfile(
    val lane: Lane,
    val focus: String,                 // what the role is for (shown in the status)
    val order: List<Job>,              // the order the jobs are tried
    val retreatBonus: Float = 0f,      // added to the retreat HP (gold lane: careful)
    val rotateEarly: Float = 0.35f,    // how far (map units) it rotates to objectives / fights before 8:00
    val rotateLate: Float = 0.35f,     // and after
    val objectiveNeedsAlly: Boolean = true,
    val campRadiusEarly: Float = 0.22f,
    val campRadiusLate: Float = 0.15f
) {
    companion object {
        private val builtIn = mapOf(
            Lane.EXP to RoleProfile(Lane.EXP, "lane pressure, Turtle zoning, split push",
                listOf(Job.STRONG_FIGHT, Job.MINIONS, Job.FIGHT, Job.TOWER, Job.DEFEND, Job.BOSS, Job.OBJECTIVE_MAP,
                    Job.POSITIONING, Job.BASE, Job.TEAMFIGHT, Job.SPLIT_PUSH, Job.LANE_CAMPS, Job.SAFETY),
                rotateEarly = 0.36f, rotateLate = 0.42f, objectiveNeedsAlly = false, campRadiusEarly = 0.11f, campRadiusLate = 0.13f),
            Lane.MID to RoleProfile(Lane.MID, "fast wave clear, 1-3-1 semi-roam, objective control",
                listOf(Job.STRONG_FIGHT, Job.MINIONS, Job.BOSS, Job.OBJECTIVE_MAP, Job.TEAMFIGHT, Job.GANK, Job.FIGHT,
                    Job.DEFEND, Job.TOWER, Job.ROAM, Job.POSITIONING, Job.BASE, Job.SAFETY),
                rotateEarly = 0.50f, rotateLate = 0.52f, objectiveNeedsAlly = false, campRadiusEarly = 0.09f, campRadiusLate = 0.11f),
            Lane.GOLD to RoleProfile(Lane.GOLD, "gold plating farm, safe spacing, late hyper-carry",
                listOf(Job.STRONG_FIGHT, Job.MINIONS, Job.TOWER, Job.FIGHT, Job.POSITIONING, Job.DEFEND, Job.BASE,
                    Job.LANE_CAMPS, Job.BOSS, Job.OBJECTIVE_MAP, Job.TEAMFIGHT, Job.SAFETY),
                retreatBonus = 0.06f, rotateEarly = 0.18f, rotateLate = 0.44f, campRadiusEarly = 0.11f, campRadiusLate = 0.14f),
            Lane.JUNGLE to RoleProfile(Lane.JUNGLE, "quadrant buff clear, Lv4 sidelane gank, Turtle/Lord retri",
                listOf(Job.STRONG_FIGHT, Job.BOSS, Job.OBJECTIVE_MAP, Job.MONSTERS, Job.FIGHT, Job.POSITIONING, Job.BASE, Job.GANK,
                    Job.INVADE, Job.JUNGLE_ROUTE, Job.MINIONS, Job.TOWER, Job.SAFETY),
                rotateEarly = 0.52f, rotateLate = 0.55f, objectiveNeedsAlly = false),
            Lane.ROAM to RoleProfile(Lane.ROAM, "mid Trio clear, bush vision, Turtle zoning, carry peel",
                listOf(Job.STRONG_FIGHT, Job.BOSS, Job.OBJECTIVE_MAP, Job.TEAMFIGHT, Job.FIGHT, Job.GANK, Job.DEFEND, Job.ROAM,
                    Job.POSITIONING, Job.MINIONS, Job.TOWER, Job.BASE, Job.SAFETY),
                rotateEarly = 0.56f, rotateLate = 0.58f, objectiveNeedsAlly = false)
        )

        fun modeLabel(mode: String = Cfg.rotationMetaMode): String = when (mode) {
            "online" -> "Latest Online Meta & Trend (with Built-In fallback)"
            "builtin" -> "Built-In Pro Meta Only"
            else -> "Hybrid (Built-In Pro Meta + Latest Online Meta & Trend)"
        }

        fun cycleMode(): String {
            val next = when (Cfg.rotationMetaMode) {
                "hybrid" -> "online"
                "online" -> "builtin"
                else -> "hybrid"
            }
            Cfg.rotationMetaMode = next
            return modeLabel(next)
        }

        private fun useOnline(): Boolean = Cfg.rotationMetaMode != "builtin" && Cfg.useOnlineMeta
        private fun useBuiltIn(): Boolean = Cfg.rotationMetaMode != "online"

        // Combined Online Meta + Built-In text corpus for the current hero & patch
        private fun onlineMetaCorpus(hero: String?): String {
            if (!useOnline() || hero == null) return ""
            val hm = MetaStore.hero(hero)
            val gm = MetaStore.general()
            if (hm == null && gm == null) return ""
            return buildString {
                hm?.let {
                    append(it.rotation.joinToString(" ")).append(" ")
                    append(it.laning.joinToString(" ")).append(" ")
                    append(it.jungle.joinToString(" ")).append(" ")
                    append(it.playstyle).append(" ").append(it.powerSpike).append(" ")
                }
                gm?.let { append(it.notes) }
            }.lowercase()
        }

        fun of(lane: Lane, hero: String? = GameStateTracker.ownHero(), matchSec: Int = GameStateTracker.state.matchSec): RoleProfile {
            val base = builtIn[lane] ?: builtIn.getValue(Lane.MID)
            val corpus = onlineMetaCorpus(hero)
            if (corpus.isEmpty() && Cfg.rotationMetaMode == "builtin") return base

            val order = base.order.toMutableList()
            var rotEarly = base.rotateEarly
            var rotLate = base.rotateLate
            var retBonus = base.retreatBonus
            val notes = ArrayList<String>()

            fun promoteBefore(job: Job, beforeJob: Job) {
                if (job !in order) return
                val iJob = order.indexOf(job)
                val iBef = order.indexOf(beforeJob)
                if (iBef in 0 until iJob) {
                    order.removeAt(iJob)
                    order.add(iBef, job)
                }
            }

            // 1. Built-in Pro Meta phase adaptations (active in "hybrid" and "builtin", or when online has no override)
            if (useBuiltIn() || corpus.isEmpty()) {
                when (lane) {
                    Lane.EXP -> {
                        // During Turtle windows (1:45..7:59), EXP laner rotates wider to contest Top-Left Turtle pit after clearing wave
                        if (matchSec in 105..480) rotEarly = 0.42f
                    }
                    Lane.GOLD -> {
                        // After 5:00 (Turret Gold Shield drops), Gold Laner rotates wider to join Mid/Lord fights
                        if (matchSec >= 300) rotEarly = 0.36f
                    }
                    else -> {}
                }
            }

            // 2. Online Meta & Trend adaptations (active in "hybrid" and "online" when MetaStore data is available)
            if (corpus.isNotEmpty()) {
                val wantsInvade = corpus.contains("invade") || corpus.contains("steal buff") || corpus.contains("aggressive early")
                val wantsGank = corpus.contains("gank") || corpus.contains("roam") || corpus.contains("pick off") || corpus.contains("ambush")
                val wantsSplit = corpus.contains("split push") || corpus.contains("split-push") || corpus.contains("backdoor") || corpus.contains("cut lane")
                val wantsObjective = corpus.contains("turtle") || corpus.contains("lord") || corpus.contains("objective")
                val wantsScale = corpus.contains("late game") || corpus.contains("scale") || corpus.contains("farm safely") || corpus.contains("freeze")

                if (wantsInvade && lane == Lane.JUNGLE) {
                    promoteBefore(Job.INVADE, Job.JUNGLE_ROUTE)
                    notes.add("online: invade priority")
                }
                if (wantsGank && lane in setOf(Lane.JUNGLE, Lane.MID, Lane.ROAM)) {
                    promoteBefore(Job.GANK, Job.JUNGLE_ROUTE)
                    rotEarly = (rotEarly + 0.06f).coerceAtMost(0.62f)
                    notes.add("online: heavy gank/roam")
                }
                if (wantsSplit && lane in setOf(Lane.EXP, Lane.GOLD)) {
                    promoteBefore(Job.SPLIT_PUSH, Job.TEAMFIGHT)
                    notes.add("online: split-push")
                }
                if (wantsObjective) {
                    rotEarly = (rotEarly + 0.05f).coerceAtMost(0.60f)
                    rotLate = (rotLate + 0.05f).coerceAtMost(0.62f)
                    notes.add("online: objective contest")
                }
                if (wantsScale && lane == Lane.GOLD) {
                    retBonus = (retBonus + 0.03f).coerceAtMost(0.12f)
                    notes.add("online: safe scaling")
                }
            }

            val focusText = if (notes.isEmpty()) base.focus else "${base.focus} [${notes.joinToString(", ")}]"
            return RoleProfile(lane, focusText, order, retBonus, rotEarly, rotLate, base.objectiveNeedsAlly, base.campRadiusEarly, base.campRadiusLate)
        }

        // True when the Jungler should start at Orange/Red Buff (0.52, 0.77) instead of Purple/Blue Buff (0.27, 0.56).
        // Blue/Energy-dependent assassins always start Purple Buff; marksman/fighter junglers start Orange Buff if online meta specifies red/orange start.
        private val BLUE_MANDATORY = setOf("Fanny", "Ling", "Nolan", "Hayabusa", "Joy", "Lancelot", "Gusion", "Karina", "Harith", "Alice", "Cyclops")
        fun preferOrangeBuffStart(hero: String?): Boolean {
            if (hero == null || hero in BLUE_MANDATORY) return false
            val corpus = onlineMetaCorpus(hero)
            if (corpus.isNotEmpty()) {
                val redIdx = listOf("orange buff", "red buff", "molten fiend").map { corpus.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: Int.MAX_VALUE
                val blueIdx = listOf("purple buff", "blue buff", "thunder fenrir").map { corpus.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: Int.MAX_VALUE
                if (redIdx < blueIdx) return true
                if (blueIdx < redIdx) return false
            }
            return hero in setOf("Granger", "Bruno", "Yi Sun-shin", "Roger", "Popol and Kupa")
        }

        // Pro + Online Meta time-windowed waypoint for Roamers (without Retribution)
        fun roamMetaWaypoint(s: GameState): Pair<Pair<Float, Float>, String>? {
            val me = s.minimapSelf ?: return null
            val sec = s.matchSec
            val corpus = onlineMetaCorpus(s.ownHero)
            val pickOffStyle = corpus.contains("pick") || corpus.contains("ambush") || corpus.contains("invade") ||
                s.ownHero in setOf("Franco", "Khufra", "Chou", "Kalea", "Jawhead", "Hilda", "Natalia", "Mathilda", "Chip")

            // 1) 0:00..0:35: Fast-clear Mid Wave 1 & contest Lithowanderer bush
            if (sec in 0..35) {
                val pt = if (sec < 22) (0.45f to 0.55f) else (0.39f to 0.61f)
                if (hypot(pt.first - me.first, pt.second - me.second) > 0.05f) {
                    return pt to "meta roam (0:00-0:35): Mid wave clear & Litho bush control"
                }
            }
            // 2) 0:36..1:40: Guard Jungler buff clear (or river invade bush for pick-off roamers)
            if (sec in 36..100) {
                val pt = if (pickOffStyle) (0.44f to 0.46f) else (0.46f to 0.72f)
                if (hypot(pt.first - me.first, pt.second - me.second) > 0.065f) {
                    return pt to if (pickOffStyle) "meta roam: river ambush & invade vision" else "meta roam: guarding Jungler rotation to Lv4"
                }
            }
            // 3) 1:45..2:15 (and 15s before Turtle/Lord spawns): Zone river bush at objective pit!
            val objSpot = s.map.objective ?: if (sec in 105..140) (0.31f to 0.26f) else null
            if (objSpot != null && hypot(objSpot.first - me.first, objSpot.second - me.second) in 0.06f..0.48f) {
                return objSpot to "meta roam: zoning ${LaneLogic.objectiveName(sec)} river bush for team"
            }
            return null
        }

        // Pro + Online Meta 1-3-1 semi-roam rotation for Mid Laners between minion waves (after wave is cleared)
        fun midSemiRoamTarget(s: GameState): Pair<Pair<Float, Float>, String>? {
            if (s.lane != Lane.MID) return null
            val me = s.minimapSelf ?: return null
            // Only rotate when the current Mid minion wave is cleared and we are healthy
            if (s.minionsWithin(0.65f).isNotEmpty() || s.selfHp < 0.52f || TowerLogic.canPush(s)) return null

            // 1) If an ally is skirmishing an enemy within 0.36 map units, rotate to support
            val skirmish = s.map.allies.filter { a -> !(a.first < 0.18f && a.second > 0.82f) }
                .firstOrNull { a ->
                    hypot(a.first - me.first, a.second - me.second) in 0.07f..0.36f &&
                        s.map.enemies.any { e -> hypot(e.first - a.first, e.second - a.second) < 0.16f }
                }
            if (skirmish != null) {
                return skirmish to "meta Mid 1-3-1 rotation: joining nearby ally skirmish after wave clear"
            }

            // 2) If Turtle/Lord is up within 0.35 map units and allies are contesting, rotate to river bush
            val obj = s.map.objective
            if (obj != null && hypot(obj.first - me.first, obj.second - me.second) in 0.07f..0.35f &&
                s.map.allies.any { hypot(it.first - obj.first, it.second - obj.second) < 0.22f }
            ) {
                return obj to "meta Mid rotation: zoning ${LaneLogic.objectiveName(s.matchSec)} after wave clear"
            }

            // 3) If an enemy Gold/EXP laner is overextended near our outer tower (0.10..0.36 away), gank through river
            val overextended = s.map.enemies.firstOrNull { e ->
                hypot(e.first - me.first, e.second - me.second) in 0.10f..0.34f &&
                    s.allyTowers.any { t -> hypot(t.first - e.first, t.second - e.second) < 0.16f }
            }
            if (overextended != null && s.level >= 2) {
                return overextended to "meta Mid gank: rotating to overextended sidelane enemy after wave clear"
            }
            return null
        }

        // Concise live status string shown on the Player page
        fun activeRotationSummary(s: GameState): String {
            val h = s.ownHero ?: GameStateTracker.ownHero()
            val hm = if (useOnline() && h != null) MetaStore.hero(h) else null
            val gm = if (useOnline()) MetaStore.general() else null
            val src = when {
                Cfg.rotationMetaMode == "builtin" -> "Built-In Pro Meta"
                hm != null -> "Hybrid (Built-In + Online Meta patch ${hm.patch})"
                gm != null -> "Hybrid (Built-In + Patch ${gm.patch} trend)"
                else -> "${modeLabel()} (using Built-In Pro Meta; tap Learn this hero for online trend)"
            }
            val tip = hm?.rotation?.firstOrNull()
                ?: hm?.laning?.firstOrNull()
                ?: hm?.jungle?.firstOrNull()
                ?: RoleTips.of(s.lane, s.matchSec.coerceAtLeast(0), h)
            return "Source: $src\nActive ${s.lane.label} rotation: $tip"
        }
    }
}
