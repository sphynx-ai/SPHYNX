package com.example.mlbbresearch.learning

import com.example.mlbbresearch.controller.AccessibilityController
import com.example.mlbbresearch.controller.ActionExecutor
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.HeroProfile
import kotlin.math.hypot
import kotlin.math.max

// COACH MODE: the AI plays and you help it. Take the joystick or press a skill at any moment:
// the AI steps back while your finger is on the screen (its touches would cancel yours) and
// takes over again 1.2 s after you let go. What you did is remembered and used:
//  - skill COMBO: the order you chain skills in fights (seen twice = becomes the hero's combo)
//  - skill AIM / timing: how far the enemy was when you cast each skill (the AI then waits
//    until the target is that close) and which skill you use on minion waves and camps
//  - JOYSTICK: how long you hold one direction before turning (the AI walks as steadily)
//  - every moment you play is also saved for "play like me" (your moves mixed into the AI's)
object Coach {

    @Volatile var lastUserAt = 0L
        private set
    @Volatile var status = "-"
        private set

    fun userActive(): Boolean {
        val now = System.currentTimeMillis()
        // Never pause the AI mid-combo unless a real touch cancellation occurred outside AI casts
        if (com.example.mlbbresearch.ai.ComboRunner.running) return false
        return now - max(lastUserAt, AccessibilityController.userTouchAt) < 900
    }

    // how long one joystick direction is held before turning (learned from you), ms
    val holdMs: Long get() = (Cfg.raw("coach_hold_ms")?.toLongOrNull() ?: 300L).coerceIn(300L, 2500L)

    // ---- your skill casts ----
    private class Cast(val slot: String, val at: Long, val vsHero: Boolean)
    private val chain = ArrayList<Cast>()

    // a skill button went dark that the AI did not press = you cast it
    @Synchronized
    fun onUserCast(slot: String, s: GameState) {
        // While Player is on, skill buttons dimming after 0.5s-0.8s holds are from the AI's own casts
        if (com.example.mlbbresearch.ai.AiRuntime.playerOn) return
        val now = System.currentTimeMillis()
        lastUserAt = now
        val hero = s.ownHero ?: return
        if (slot !in setOf("s1", "s2", "ult", "spell")) return
        val e = s.enemiesWithin(1.0f).minByOrNull { s.distToSelf(it.x, it.y) }
        if (e != null) {
            // how close the target was = the range you use this skill at
            val d = s.distToSelf(e.x, e.y)
            val (avg, n) = range(hero, slot)
            val na = if (n == 0) d else avg * 0.8f + d * 0.2f
            Cfg.setRaw("coach_rng_${key(hero)}_$slot", "%.3f,%d".format(java.util.Locale.US, na, n + 1))
        } else if (s.minionsWithin(0.6f).isNotEmpty() || s.creeps.isNotEmpty()) {
            val k = "coach_wave_${key(hero)}_$slot"
            Cfg.setRaw(k, ((Cfg.raw(k)?.toIntOrNull() ?: 0) + 1).toString())
        }
        if (chain.isNotEmpty() && now - chain.last().at > 2500) endChain(hero)
        chain.add(Cast(slot, now, e != null))
        status = "you cast $slot" + if (e != null) " at an enemy ${(s.distToSelf(e.x, e.y) * 100).toInt()} away" else ""
    }

    private fun endChain(hero: String) {
        val c = chain.toList()
        chain.clear()
        if (c.size < 2 || c.none { it.vsHero }) return
        val seq = c.take(6).joinToString(" > ") { it.slot }
        val k = "coach_combo_${key(hero)}"
        val counts = LinkedHashMap<String, Int>()
        Cfg.raw(k)?.split(";")?.forEach { e -> val p = e.split("="); if (p.size == 2) p[1].toIntOrNull()?.let { counts[p[0]] = it } }
        counts[seq] = (counts[seq] ?: 0) + 1
        Cfg.setRaw(k, counts.entries.sortedByDescending { it.value }.take(8).joinToString(";") { "${it.key}=${it.value}" })
        val best = counts.maxByOrNull { it.value }!!
        if (best.value >= 2 && Cfg.heroCombo(hero) != best.key) {
            Cfg.setHeroCombo(hero, best.key)
            Diag.info("Coach: learned your $hero combo: ${best.key} (seen ${best.value} times)")
            status = "learned your combo: ${best.key}"
        }
    }

    // ---- your joystick ----
    private var segDirX = 0f
    private var segDirY = 0f
    private var segStart = 0L
    private var segments = 0

    // every decision tick in a match
    @Synchronized
    fun tick(s: GameState, youPlay: Boolean = false) {
        val now = s.ts
        if (youPlay) lastUserAt = now
        val hero = s.ownHero
        if (hero != null && chain.isNotEmpty() && now - chain.last().at > 2500) endChain(hero)
        val mx = s.hud.motionX; val my = s.hud.motionY
        val ml = hypot(mx, my)
        // Do NOT infer human joystick takeover from camera motion mismatch while Player is on
        // (dashes, knockbacks, and attack stops move the camera differently than the joystick)
        if (!youPlay || ml <= 0.5f) { segStart = 0L; return }
        // you are walking: how long do you keep one direction before turning?
        val dx = mx / ml; val dy = my / ml
        if (segStart == 0L) { segStart = now; segDirX = dx; segDirY = dy; return }
        if (dx * segDirX + dy * segDirY < 0.87f) {           // turned more than ~30 degrees
            val dur = now - segStart
            if (dur in 150L..5000L) {
                val v = (holdMs * 0.8f + dur * 0.2f).toLong()
                segments++
                Cfg.setRaw("coach_hold_ms", v.toString())
                if (segments % 5 == 0) status = "your joystick: about ${v} ms per direction"
            }
            segStart = now; segDirX = dx; segDirY = dy
        }
    }

    // ---- what the AI uses ----
    private fun key(hero: String) = hero.lowercase().replace(' ', '_')

    fun range(hero: String, slot: String): Pair<Float, Int> {
        val p = Cfg.raw("coach_rng_${key(hero)}_$slot")?.split(",") ?: return 0f to 0
        return (p.getOrNull(0)?.toFloatOrNull() ?: 0f) to (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    // the distance you cast this skill at an enemy hero (null until seen 3 times)
    fun castRange(hero: String?, slot: String): Float? {
        if (hero == null) return null
        val (avg, n) = range(hero, slot)
        return if (n >= 3) avg else null
    }

    // your profile tweaks: the skill you use on waves / camps
    fun adjust(p: HeroProfile, hero: String?): HeroProfile {
        if (hero == null || !Cfg.coachMode) return p
        val wave = listOf("s1", "s2", "ult").maxByOrNull { Cfg.raw("coach_wave_${key(hero)}_$it")?.toIntOrNull() ?: 0 }
        val n = wave?.let { Cfg.raw("coach_wave_${key(hero)}_$it")?.toIntOrNull() } ?: 0
        if (wave == null || n < 3 || wave == p.waveClearSkill || wave == "ult") return p
        return HeroProfile(p.name, p.role, p.damage, p.attackRange, p.engageRange, p.retreatHp, p.combo, wave,
            p.escapeSkill, p.ult, p.cooldownSec, p.ranged)
    }

    fun summary(hero: String?): String {
        val h = hero ?: return "pick a hero first"
        val r = listOf("s1", "s2", "ult").mapNotNull { sl -> castRange(h, sl)?.let { "$sl at ${(it * 100).toInt()}" } }
        return "Joystick: ${holdMs} ms per direction. Combo: ${Cfg.heroCombo(h) ?: "not learned yet"}. " +
            "Cast distance: ${if (r.isEmpty()) "not learned yet" else r.joinToString(", ")}. Last: $status"
    }

    fun forget(hero: String?) {
        Cfg.setRaw("coach_hold_ms", null)
        val h = hero ?: return
        Cfg.setRaw("coach_combo_${key(h)}", null)
        Cfg.setHeroCombo(h, null)
        for (sl in listOf("s1", "s2", "ult", "spell")) {
            Cfg.setRaw("coach_rng_${key(h)}_$sl", null)
            Cfg.setRaw("coach_wave_${key(h)}_$sl", null)
        }
    }
}
