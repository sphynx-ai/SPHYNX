package com.example.mlbbresearch.game

import com.example.mlbbresearch.ai.AiRuntime
import com.example.mlbbresearch.ai.GroqClient
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// What every skill of a hero DOES, not just where its button is: range, what it hits, crowd
// control, mobility, shield / heal, damage, and how to cast it (tap = quick cast on the
// nearest target, aim = drag the button towards a spot, hold = press, hold to charge, aim and
// release). Plus the hero's PASSIVE turned into play rules.
//
// Where it comes from:
//  1. the skill descriptions online (Groq with web search, the Meta page key): saved per hero
//  2. the descriptions are also read word by word ("dash", "stun", "hold to charge"...) so a
//     description you paste works too
//  3. without either, a default from the hero's role
// What the execution learns on top (per hero and skill): the cast mode that really fires the
// skill (verified on screen), how far ahead of a moving enemy to aim, how long to hold.

enum class CastMode { TAP, AIM, HOLD }
enum class SkillKind { TARGET, DIRECTION, AREA, SELF, DASH }
enum class Passive { WEAVE_ATTACK, STACKS, LOW_HP_POWER, OUT_OF_COMBAT_REGEN, BUSH_BONUS, EXECUTE_BONUS, AFTER_DASH_BONUS, SHIELD, MOVE_SPEED }

class SkillInfo(
    val slot: String,
    val name: String,
    val kind: SkillKind,
    val range: Float,            // screen-height units (like attack range)
    val hardCc: Boolean,         // stun, knock-up, suppress, taunt, freeze...
    val slow: Boolean,
    val mobility: Boolean,       // dash, leap, blink: can start a fight or escape
    val shield: Boolean,
    val heal: Boolean,
    val damage: Float,           // rough share of an enemy's HP this skill takes (0..1)
    val mode: CastMode,
    val holdMs: Long,
    val travelSec: Float,        // time for the skill to land (aim ahead of moving enemies)
    val description: String
)

class HeroSkills(val hero: String, val skills: Map<String, SkillInfo>, val passive: Set<Passive>, val passiveText: String, val source: String) {
    operator fun get(slot: String): SkillInfo? = skills[slot]
    fun has(p: Passive) = p in passive
    // estimated share of an enemy's HP the ready skills take (the combo's damage)
    fun burst(ready: (String) -> Boolean): Float = skills.values.filter { ready(it.slot) }.sumOf { it.damage.toDouble() }.toFloat()
    val escape: SkillInfo? get() = skills.values.firstOrNull { it.mobility && it.slot != "ult" } ?: skills.values.firstOrNull { it.mobility }
    val cc: List<SkillInfo> get() = skills.values.filter { it.hardCc || it.slow }
}

object SkillBook {

    private val cache = HashMap<String, HeroSkills>()
    @Volatile var busy = ""
        private set
    @Volatile var lastMessage = ""
        private set
    @Volatile var learnAllProgress = ""
        private set

    private fun key(hero: String) = hero.lowercase().replace(' ', '_')

    @Synchronized
    fun get(hero: String?): HeroSkills {
        val h = hero ?: "unknown"
        cache[h]?.let { return it }
        val saved = Cfg.raw("skills_${key(h)}")?.let { try { fromJson(h, JSONObject(it)) } catch (e: Exception) { null } }
        val v = saved ?: defaults(h)
        cache[h] = v
        return v
    }

    fun known(hero: String?): Boolean = hero != null && Cfg.raw("skills_${key(hero)}") != null

    // ---- what the execution learned (per hero and skill) ----
    fun castMode(hero: String?, slot: String): CastMode {
        val learned = hero?.let { Cfg.raw("castmode_${key(it)}_$slot") }?.let { runCatching { CastMode.valueOf(it) }.getOrNull() }
        return learned ?: get(hero)[slot]?.mode ?: CastMode.TAP
    }
    fun setCastMode(hero: String?, slot: String, m: CastMode) { if (hero != null) Cfg.setRaw("castmode_${key(hero)}_$slot", m.name) }
    fun aimLead(hero: String?, slot: String): Float = hero?.let { Cfg.raw("aimlead_${key(it)}_$slot")?.toFloatOrNull() } ?: 1f
    fun setAimLead(hero: String?, slot: String, v: Float) { if (hero != null) Cfg.setRaw("aimlead_${key(hero)}_$slot", "%.2f".format(java.util.Locale.US, v.coerceIn(0f, 2f))) }
    fun holdMs(hero: String?, slot: String): Long =
        hero?.let { Cfg.raw("holdms_${key(it)}_$slot")?.toLongOrNull() } ?: get(hero)[slot]?.holdMs ?: 800L
    fun setHoldMs(hero: String?, slot: String, v: Long) { if (hero != null) Cfg.setRaw("holdms_${key(hero)}_$slot", v.coerceIn(200L, 3000L).toString()) }

    // ---- reading descriptions ----
    private fun has(t: String, vararg w: String) = w.any { t.contains(it) }

    fun parseSkill(slot: String, name: String, desc: String, role: Role): SkillInfo {
        val t = desc.lowercase()
        val dashWords = has(t, "dash", "leap", "blink", "jump", "teleport", "charges forward", "charge forward", "lunges", "rushes", "flies to", "sprint")
        val hold = has(t, "hold to", "hold the", "holding", "charge up", "charging", "charges up", "channel", "release to", "when released", "upon release")
        val kind = when {
            dashWords -> SkillKind.DASH
            has(t, "target direction", "designated direction", "specified direction", "in a line", "fires", "throws", "launches", "projectile", "straight line", "shoots") -> SkillKind.DIRECTION
            has(t, "target area", "designated area", "specified area", "target location", "area of", "circular area", "at a location") -> SkillKind.AREA
            has(t, "around himself", "around herself", "around itself", "surrounding enemies", "nearby enemies", "gains", "enhances", "himself", "herself") -> SkillKind.SELF
            else -> SkillKind.TARGET
        }
        val range = when {
            has(t, "global", "anywhere on the map", "entire map") -> 2f
            has(t, "long range", "long distance", "far ") -> 0.6f
            kind == SkillKind.SELF -> 0.22f
            kind == SkillKind.DASH -> 0.35f
            role == Role.MARKSMAN || role == Role.MAGE -> 0.45f
            else -> 0.32f
        }
        val hardCc = has(t, "stun", "knock", "airborne", "suppress", "taunt", "freeze", "frozen", "petrif", "immobil", "root", "bind", "pull", "silence", "sleep")
        val holdMs = Regex("(?:up to|for|over) (\\d+(?:\\.\\d+)?) ?s").find(t)?.groupValues?.get(1)?.toFloatOrNull()
            ?.takeIf { hold }?.let { (it * 1000).toLong().coerceIn(300L, 3000L) } ?: if (hold) 1000L else 0L
        val damage = when {
            slot == "ult" -> 0.35f
            has(t, "massive", "huge damage", "high damage") -> 0.25f
            has(t, "deals") || has(t, "damage") -> 0.15f
            else -> 0.05f
        }
        return SkillInfo(
            slot, name, kind, range, hardCc, has(t, "slow"), dashWords, has(t, "shield"),
            has(t, "heal", "restore", "recover", "lifesteal", "regenerat"), damage,
            when { hold -> CastMode.HOLD; kind == SkillKind.DIRECTION || kind == SkillKind.AREA || kind == SkillKind.DASH -> CastMode.AIM; else -> CastMode.TAP },
            holdMs, if (kind == SkillKind.DIRECTION) 0.35f else if (kind == SkillKind.AREA) 0.5f else 0.15f, desc
        )
    }

    fun parsePassive(desc: String): Set<Passive> {
        val t = desc.lowercase()
        val out = HashSet<Passive>()
        if (has(t, "next basic attack", "enhanced basic attack", "after casting a skill", "after using a skill", "empowered basic")) out.add(Passive.WEAVE_ATTACK)
        if (has(t, "stack", "each basic attack", "every basic attack", "mark")) out.add(Passive.STACKS)
        if (has(t, "lost hp", "lower hp", "missing hp", "the lower") && has(t, "increase", "more damage", "bonus")) out.add(Passive.LOW_HP_POWER)
        if (has(t, "out of combat", "not taking damage", "not dealing damage") && has(t, "regen", "restore", "recover", "heal")) out.add(Passive.OUT_OF_COMBAT_REGEN)
        if (has(t, "bush", "grass")) out.add(Passive.BUSH_BONUS)
        if (has(t, "low hp enem", "enemies with low", "below 50% hp", "execute", "missing hp of the target")) out.add(Passive.EXECUTE_BONUS)
        if (has(t, "after dash", "after using a movement", "after blinking", "after each dash")) out.add(Passive.AFTER_DASH_BONUS)
        if (has(t, "gains a shield", "grants a shield", "shield")) out.add(Passive.SHIELD)
        if (has(t, "movement speed", "move speed")) out.add(Passive.MOVE_SPEED)
        return out
    }

    // the role's typical kit when nothing was learned yet
    private fun defaults(hero: String): HeroSkills {
        val role = HeroDb.role(hero)
        val dash = HeroDb.dashSlot(hero)
        val base = HeroDb.profile(hero)
        val m = HashMap<String, SkillInfo>()
        for (slot in listOf("s1", "s2", "ult")) {
            val isDash = slot == dash
            m[slot] = SkillInfo(slot, slot, if (isDash) SkillKind.DASH else SkillKind.TARGET, if (isDash) 0.35f else base.engageRange * 0.9f,
                slot == "ult" && role == Role.TANK, false, isDash, false, false, if (slot == "ult") 0.35f else 0.15f,
                if (isDash) CastMode.AIM else CastMode.TAP, 0L, 0.2f, "")
        }
        return HeroSkills(hero, m, emptySet(), "", "role defaults (not learned yet)")
    }

    private fun fromJson(hero: String, o: JSONObject): HeroSkills {
        val role = HeroDb.role(hero)
        val sk = o.optJSONObject("skills") ?: JSONObject()
        val m = HashMap<String, SkillInfo>()
        for (slot in listOf("s1", "s2", "ult")) {
            val j = sk.optJSONObject(slot) ?: continue
            val desc = j.optString("description", "")
            val p = parseSkill(slot, j.optString("name", slot), desc, role)
            // the structured answer wins where it is given; the words fill the rest
            val kind = runCatching { SkillKind.valueOf(j.optString("type", "").uppercase()) }.getOrNull() ?: p.kind
            val range = when (j.optString("range", "").lowercase()) {
                "short" -> 0.25f; "medium" -> 0.4f; "long" -> 0.6f; "global" -> 2f; else -> p.range
            }
            val cc = j.optString("cc", "").lowercase()
            val mode = when (j.optString("cast", "").lowercase()) { "tap" -> CastMode.TAP; "aim" -> CastMode.AIM; "hold" -> CastMode.HOLD; else -> p.mode }
            val dmg = when (j.optString("damage", "").lowercase()) { "low" -> 0.08f; "medium" -> 0.15f; "high" -> 0.28f; else -> p.damage }
            val hold = (j.optDouble("hold_seconds", 0.0) * 1000).toLong().takeIf { it > 0 } ?: p.holdMs
            m[slot] = SkillInfo(slot, p.name, kind, range, cc == "hard" || p.hardCc && cc.isEmpty(), cc == "slow" || p.slow,
                j.optBoolean("mobility", p.mobility) || kind == SkillKind.DASH, j.optBoolean("shield", p.shield), j.optBoolean("heal", p.heal),
                if (slot == "ult") maxOf(dmg, 0.2f) else dmg, if (mode == CastMode.HOLD && hold <= 0) CastMode.AIM else mode,
                if (hold > 0) hold else 1000L, j.optDouble("travel_seconds", p.travelSec.toDouble()).toFloat().coerceIn(0f, 1.5f), desc)
        }
        val pj = o.optJSONObject("passive")
        val ptext = pj?.optString("description", "") ?: o.optString("passive", "")
        val rules = parsePassive(ptext).toMutableSet()
        o.optJSONArray("passive_rules")?.let { a -> for (i in 0 until a.length()) runCatching { rules.add(Passive.valueOf(a.optString(i).uppercase())) } }
        val d = defaults(hero)
        return HeroSkills(hero, d.skills + m, rules, ptext, o.optString("source", "online"))
    }

    // a description you paste (one per line: "s1: ...", "s2: ...", "ult: ...", "passive: ...")
    fun fromText(hero: String, text: String): String {
        val o = JSONObject(); val sk = JSONObject()
        // "passive: ... s1: ... s2: ... ult: ..." (on one line or several)
        val rx = Regex("(?i)\\b(passive|s1|s2|s3|ult|ultimate|skill ?1|skill ?2|skill ?3)\\s*:")
        val marks = rx.findAll(text).toList()
        for ((i, m) in marks.withIndex()) {
            val k = m.groupValues[1].lowercase().replace("skill1", "skill 1").replace("skill2", "skill 2").replace("skill3", "skill 3")
            val v = text.substring(m.range.last + 1, marks.getOrNull(i + 1)?.range?.first ?: text.length).trim().trimEnd('/', ';').trim()
            when (k) {
                "s1", "skill 1", "skill1" -> sk.put("s1", JSONObject().put("description", v))
                "s2", "skill 2", "skill2" -> sk.put("s2", JSONObject().put("description", v))
                "ult", "ultimate", "s3", "skill 3" -> sk.put("ult", JSONObject().put("description", v))
                "passive" -> o.put("passive", JSONObject().put("description", v))
            }
        }
        o.put("skills", sk).put("source", "your text")
        save(hero, o)
        return describe(hero)
    }

    private fun save(hero: String, o: JSONObject) {
        Cfg.setRaw("skills_${key(hero)}", o.toString())
        synchronized(this) { cache.remove(hero) }
    }

    fun describe(hero: String?): String {
        val k = get(hero)
        val sk = listOf("s1", "s2", "ult").mapNotNull { k[it] }.joinToString("; ") { s ->
            "${s.slot} ${s.name}: ${s.kind.name.lowercase()}, ${s.mode.name.lowercase()}" +
                (if (s.hardCc) ", hard CC" else if (s.slow) ", slow" else "") + (if (s.mobility) ", mobility" else "") +
                (if (s.shield) ", shield" else "") + (if (s.heal) ", heal" else "") +
                ", learned cast: ${castMode(hero, s.slot).name.lowercase()}"
        }
        val p = if (k.passive.isEmpty()) "none recognised" else k.passive.joinToString { it.name.lowercase().replace('_', ' ') }
        return "${k.hero} (${k.source}): $sk. Passive: $p"
    }

    // ---- online ----
    fun fetch(hero: String, done: ((String) -> Unit)? = null) {
        if (busy.isNotEmpty()) { done?.invoke("Busy: $busy"); return }
        busy = hero
        AiRuntime.scope.launch {
            val msg = try { fetchNow(hero) } catch (e: Exception) {
                Diag.error("Skill download for $hero failed", e); "Skill download failed: ${e.message}"
            } finally { busy = "" }
            lastMessage = msg
            done?.invoke(msg)
        }
    }

    private suspend fun fetchNow(hero: String): String {
        val prompt = "You are a Mobile Legends: Bang Bang expert. Search the web for the CURRENT skill descriptions of the hero $hero " +
            "(passive, skill 1, skill 2, ultimate). Reply with ONLY one JSON object, no other words:\n" +
            "{\"passive\":{\"name\":\"\",\"description\":\"\"},\"skills\":{\"s1\":{\"name\":\"\",\"description\":\"\"," +
            "\"type\":\"target|direction|area|self|dash\",\"range\":\"short|medium|long|global\",\"cc\":\"none|slow|hard\"," +
            "\"mobility\":true/false,\"shield\":true/false,\"heal\":true/false,\"damage\":\"low|medium|high\"," +
            "\"cast\":\"tap|aim|hold\",\"hold_seconds\":0,\"travel_seconds\":0.3},\"s2\":{...same...},\"ult\":{...same...}}," +
            "\"passive_rules\":[\"any of: WEAVE_ATTACK (enhanced basic attack after a skill), STACKS (stacks on hits), " +
            "LOW_HP_POWER (stronger at low HP), OUT_OF_COMBAT_REGEN, BUSH_BONUS, EXECUTE_BONUS (bonus vs low HP enemies), " +
            "AFTER_DASH_BONUS, SHIELD, MOVE_SPEED\"]}\n" +
            "cast: tap = quick cast on the nearest target, aim = drag to aim a direction or area, hold = hold to charge then release."
        val (text, _) = GroqClient.ask(prompt)
        val o = GroqClient.json(text) ?: throw IllegalStateException("answer was not JSON: ${text.take(80)}")
        o.put("source", if (GroqClient.lastSearched) "online (web search)" else "online (model knowledge)")
        save(hero, o)
        Diag.info("Skills learned for $hero")
        return describe(hero)
    }

    // the hero you play: learn its skills once (Groq key needed)
    private val tried = HashMap<String, Long>()
    fun autoFetch(hero: String?) {
        if (hero == null || known(hero) || !GroqClient.ready || busy.isNotEmpty()) return
        if (System.currentTimeMillis() - (tried[hero] ?: 0L) < 10 * 60_000L) return
        tried[hero] = System.currentTimeMillis()
        fetch(hero)
    }

    // every hero, one after another (slow on purpose: the free Groq tier limits requests)
    @Volatile private var allRunning = false
    fun learnAll(done: (String) -> Unit) {
        if (allRunning) { done("Already running: $learnAllProgress"); return }
        allRunning = true
        AiRuntime.scope.launch {
            val names = HeroDb.allNames
            var ok = 0
            for ((i, h) in names.withIndex()) {
                if (!allRunning) break
                learnAllProgress = "${i + 1}/${names.size}: $h"
                if (known(h)) { ok++; continue }
                try { fetchNow(h); ok++ } catch (e: Exception) { Diag.error("Skills for $h", e) }
                delay(6000)
            }
            allRunning = false
            learnAllProgress = "done: $ok of ${names.size} heroes known"
            done(learnAllProgress)
        }
    }
    fun stopLearnAll() { allRunning = false }
}
