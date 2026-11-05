package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// HERO KITS, DUAL-IDENTITY TRANSFORMATIONS & MULTI-PHASE MECHANICS ENGINE (v37.07):
//  1. Dual-Identity & Transformation Heroes:
//      * Selena (Elven ranged trap/arrow -> Primal Darkness ult -> Abyssal melee Garotte dash resets)
//      * Roger (Human ranged slow/shred -> Wolf Transformation lunge ult -> Wolf melee Lycan Pounce)
//      * Lapu-Lapu (Twin Blades -> Bravest Fighter ult -> Heavy Sword Raging Slash 3-slam ult + Storm Sword)
//      * Edith (Phylax melee tank flip/knockup -> Primal Wrath ult -> Ranged Marksman Lightning Bolt + Divine Retribution)
//      * Yu Zhong (Black Dragon Form -> Dragonoid Form enhanced s3 > s1 > s2)
//      * Lukas (Unleash the Beast -> Sacred Beast form + Shockwave Blast 2nd ult recast)
//      * Sora (Swipe ult Left = Thunder single-target burst, Swipe Right = Torrent AoE pull + CC immunity -> transformed s1 > s2)
//      * Kagura (With Seimei Umbrella <-> Without Umbrella: s1 throw -> ult remote pull -> s2 teleport pickup -> ult close burst -> s2 dash)
//      * Lunox (Order <-> Chaos: Darkening ult grants 4s zero-cooldown Chaos Assault s2 spam; Brilliance ult grants untargetable heal)
//      * Yin (Yin human s1 > s2 -> My Turn domain ult -> Lieh form Frenzy Strike s2 > s1)
//      * Leomord (Unmounted -> Phantom Steed ult -> Mounted Barbiel s1 > s2 > a)
//      * Hanzo (Body -> Kinjutsu Pinnacle Ninja ult -> Demon Pneuma s2 > a > s1)
//  2. Multi-Phase Skill Heroes (2, 3, 4, or 5 recast phases within a single cooldown window):
//      * Chou (s1 Jeet Kune Do 3 phases -> 3rd phase resets s2 Shunpo; ult The Way of Dragon 2 phases)
//      * Paquito (Champ Stance: every 3rd skill cast immediately resets s1, s2, and ult with zero cooldown)
//      * Martis (s2 Mortal Coil 2 phases), Terizla (s2 Execution Strike 3 phases), Khaleed (s1 Desert Tornado 3 phases)
//      * Freya (s2 Spirit Combo 4 phases), Guinevere (s2 Spatial Migration 2 phases), Jawhead (s2 Ejector 2 phases)
//      * Tigreal (s2 Sacred Hammer 2 phases), Carmilla (s2 Bloodbath 2 phases), Cici (s2 Buoyant Bounce 2 phases)
//      * Arlott (s2 Vengeance multi-recast on marked enemy), Lancelot (s1 Puncture multi-recast), Joy (s2 4 rhythmic dashes)
//      * Hayabusa (s2 Quad Shadow teleport recast), Harley (s2 Space Escape hat return), Claude (s2 Battle Mirror Image swap)
//      * Kadita (s1 Ocean Ode wave return), Wanwan (s1 Swallow's Path recall stun), Natan (ult Entropy swap + CD cut)
//      * Gloo (ult Split/Attach 2 phases), X.Borg (ult Last Insanity spin + detonate), Phoveus (ult Infernal Pursuit multi-recast)
//      * Harith (s2 Synchro Fission rapid resets inside Zaman Force ult), Lylia (s2 Shadow Energy 5 charges), Valentina (s2 resets s1)
//  3. Special Input & Combo Heroes:
//      * Suyou (per-skill Tap Mortal vs Hold Immortal), Hirara (drag s1<->s2), Julian (2 skills -> 3rd Enhanced skill + empowered basic attacks),
//        Fanny (wall-grapple Steel Cable + double-cable triangulation), Gusion (10-dagger throw/reset/recall/blink), Ling (4 Field Eye blades).
object HeroKit {

    val ALL = listOf("s1", "s2", "s3", "ult")
    private fun key(h: String) = h.lowercase().filter { it.isLetterOrDigit() }

    // Heroes whose combo sequence explicitly defines multi-phase recasts, transformations, and attack weaves
    val MULTI_PHASE_HEROES = setOf(
        "Suyou", "Hirara", "Sora", "Julian", "Fanny", "Gusion", "Ling",
        "Selena", "Roger", "Lapu-Lapu", "Edith", "Yu Zhong", "Lukas", "Kagura", "Lunox", "Yin", "Leomord", "Hanzo",
        "Chou", "Paquito", "Martis", "Terizla", "Khaleed", "Freya", "Guinevere", "Jawhead", "Tigreal", "Carmilla",
        "Cici", "Arlott", "Lancelot", "Hayabusa", "Harley", "Claude", "Kadita", "Wanwan", "Natan", "Gloo",
        "X.Borg", "Phoveus", "Joy", "Harith", "Lylia", "Valentina", "Beatrix", "Yi Sun-shin", "Benedetta", "Nolan"
    )

    // ---------------- how many active skills ----------------
    private val builtSlots = mapOf(
        "Hirara" to listOf("s1", "s2"),
        "Yu Zhong" to listOf("s1", "s2", "s3", "ult"),
        "Pharsa" to listOf("s1", "s2", "s3", "ult"),
        "Fredrinn" to listOf("s1", "s2", "s3", "ult"),
        "Popol and Kupa" to listOf("s1", "s2", "s3", "ult"),
        "Chip" to listOf("s1", "s2", "s3", "ult"),
        "Sun" to listOf("s1", "s2", "s3", "ult"),
        "Lunox" to listOf("s1", "s2", "s3", "ult"),
        "Zhask" to listOf("s1", "s2", "s3", "ult"),
    )

    val buttonHints: Map<String, Map<String, String>> = mapOf(
        "Hirara" to mapOf("s1" to "Mobility", "s2" to "AOE"),
        "Pharsa" to mapOf("s1" to "CC", "s2" to "AOE", "s3" to "Burst", "ult" to "Morph"),
        "Zhask" to mapOf("s1" to "Summon", "s2" to "CC", "s3" to "Slow", "ult" to "Morph"),
        "Chip" to mapOf("s1" to "CC", "s2" to "Speed Up", "s3" to "Teleport", "ult" to "Teleport"),
    )

    private val slotCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<String>>>()
    fun slots(hero: String?): List<String> {
        if (hero == null) return listOf("s1", "s2", "ult")
        val now = System.currentTimeMillis()
        slotCache[hero]?.let { if (now - it.first < 2000) return it.second }
        return slotsNow(hero).also { slotCache[hero] = now to it }
    }
    private fun slotsNow(hero: String): List<String> {
        val learned = Cfg.raw("skills_" + hero.lowercase().replace(' ', '_'))?.let { txt ->
            try {
                val o = JSONObject(txt)
                o.optJSONArray("slots")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it in ALL } }?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) { null }
        }
        val fromPage = WikiSkills.icons(hero).keys.filter { it in ALL }.takeIf { it.size >= 2 }
        val base = builtSlots[hero] ?: fromPage ?: learned ?: listOf("s1", "s2", "ult")
        val absent = emptySet<String>()
        val confirmed = (Cfg.raw("kit_seen_${key(hero)}") ?: "").split(",").filter { it in ALL }
        return ALL.filter { (it in base || it in confirmed) && it !in absent }
    }
    fun kitSizeHint(hero: String): Int {
        builtSlots[hero]?.let { return it.size }
        Cfg.raw("skills_" + hero.lowercase().replace(' ', '_'))?.let { txt ->
            try { JSONObject(txt).optJSONArray("slots")?.length()?.takeIf { it in 2..4 }?.let { return it } } catch (e: Exception) { }
        }
        return Cfg.raw("kit_screen_" + key(hero))?.toIntOrNull()?.takeIf { it in 2..4 } ?: 0
    }
    fun kitOf(hero: String): Int {
        builtSlots[hero]?.let { return it.size }
        WikiSkills.icons(hero).keys.count { it in ALL }.takeIf { it >= 2 }?.let { return it }
        Cfg.raw("skills_" + hero.lowercase().replace(' ', '_'))?.let { txt ->
            try { JSONObject(txt).optJSONArray("slots")?.length()?.takeIf { it in 2..4 }?.let { return it } } catch (e: Exception) { }
        }
        return 3
    }
    fun fitsScreen(hero: String?): Boolean {
        if (hero == null) return false
        val k = com.example.mlbbresearch.vision.ScreenKit.screenKit()
        if (k == 0) return true
        val h = kitOf(hero)
        return h == 0 || h == k
    }
    fun has(hero: String?, slot: String) = slot !in ALL || slot in slots(hero)
    fun known(hero: String?): Boolean = hero != null && (hero in builtSlots ||
        Cfg.raw("skills_" + hero.lowercase().replace(' ', '_'))?.contains("\"slots\"") == true)
    fun count(hero: String?) = slots(hero).size

    private val misfires = HashMap<String, Int>()
    fun confirmed(hero: String?, slot: String) {
        if (hero == null || slot !in ALL) return
        misfires.remove(hero + slot)
        val k = "kit_seen_${key(hero)}"
        val cur = (Cfg.raw(k) ?: "").split(",").filter { it.isNotBlank() }
        if (slot !in cur) { Cfg.setRaw(k, (cur + slot).joinToString(",")); slotCache.remove(hero) }
        val ka = "kit_absent_${key(hero)}"
        val ab = (Cfg.raw(ka) ?: "").split(",").filter { it.isNotBlank() && it != slot }
        Cfg.setRaw(ka, ab.joinToString(",").ifEmpty { null })
    }
    @Suppress("UNUSED_PARAMETER")
    fun misfired(hero: String?, slot: String): Boolean = false
    fun resetScreenLearning(hero: String) { Cfg.setRaw("kit_absent_${key(hero)}", null); Cfg.setRaw("kit_seen_${key(hero)}", null); slotCache.remove(hero) }
    fun forgetCache() = slotCache.clear()

    // ---------------- 1. Transformation & Dual-Identity Heroes ----------------
    class Transform(val toggle: Boolean, val seconds: Float, val startsRanged: Boolean, val note: String, val byRange: Boolean = true)
    private val transforms = mapOf(
        "Roger" to Transform(true, 0f, true, "Human (ranged marksman) <-> Wolf (melee assassin)"),
        "Selena" to Transform(true, 0f, true, "Elven (ranged trap + arrow) <-> Abyssal (melee assassin)"),
        "Yu Zhong" to Transform(false, 10f, false, "Black Dragon -> Dragonoid form for 10s", byRange = false),
        "Sora" to Transform(false, 10f, false, "Thunder or Torrent form for 10s", byRange = false),
        "Lapu-Lapu" to Transform(false, 10f, false, "Twin Blades -> Heavy Sword form for 10s (unlocks Raging Slash ult)", byRange = false),
        "Edith" to Transform(false, 8f, false, "Phylax (melee tank) -> Edith (ranged marksman) for 8s", byRange = true),
        "Lukas" to Transform(false, 10f, false, "Sacred Beast form for 10s (unlocks Shockwave Blast ult)", byRange = false),
        "Yin" to Transform(false, 8f, false, "Yin -> Lieh domain form for 8s", byRange = false),
        "Leomord" to Transform(false, 10f, false, "Unmounted -> Mounted on Barbiel for 10s", byRange = false),
        "Hanzo" to Transform(false, 8f, false, "Body -> Demon Pneuma soul form for 8s", byRange = false),
    )
    fun transform(hero: String?): Transform? = hero?.let { transforms[it] }
        ?: hero?.let { h -> if (SkillBook.hasTransformUlt(h)) Transform(false, 10f, false, "transforms (from its description)") else null }

    @Volatile private var formHero: String? = null
    @Volatile private var form = 0
    @Volatile private var formAt = 0L

    fun onUltCast(hero: String?) {
        val t = transform(hero) ?: return
        if (formHero != hero) { formHero = hero; form = 0 }
        form = if (t.toggle) 1 - form else 1
        formAt = System.currentTimeMillis()
        // Transforming refreshes s1/s2 (and s3 for Yu Zhong) in the new form!
        CooldownTracker.resetSlots("s1", "s2", "s3")
    }

    fun formOf(hero: String?): Int {
        val t = transform(hero) ?: return 0
        if (formHero != hero) return 0
        if (!t.toggle && form == 1 && System.currentTimeMillis() - formAt > (t.seconds * 1000).toLong()) form = 0
        return form
    }

    fun resetForm() {
        formHero = null; form = 0
        resetComplexState()
    }

    fun formSuffix(hero: String?): String = if (formOf(hero) == 1) "_f2" else ""

    // Dynamic ranged/melee state for Roger, Selena, and Edith!
    fun rangedNow(hero: String?): Boolean? {
        val t = transform(hero) ?: return null
        if (!t.byRange) return null
        return if (formOf(hero) == 0) t.startsRanged else !t.startsRanged
    }

    // Roger & Selena form-switch intelligence:
    // - In Ranged form (Human / Elven): once s1/s2 have been cast (or enemy is within 0.45f during an engage),
    //   switch to Melee form (Wolf lunge / Abyssal form) to dash in and finish!
    // - In Melee form (Wolf / Abyssal): switch back to Ranged form when retreating, low HP (< 25%),
    //   no enemy is within 0.45f, or both melee skills (s1 & s2) are on cooldown!
    fun wantsSwitch(hero: String?, fighting: Boolean, nearestEnemy: Float, hp: Float): Boolean {
        if (hero != "Roger" && hero != "Selena") return false
        val ranged = rangedNow(hero) ?: return false
        val p = HeroDb.profile(hero)
        val s1Ready = CooldownTracker.ready("s1", p)
        val s2Ready = CooldownTracker.ready("s2", p)
        return if (ranged) {
            // Switch from Ranged (Human/Elven) -> Melee (Wolf/Abyssal) when engaging an enemy within 0.46f
            // (especially right after firing ranged s1/s2, or when enemy is within pounce range <= 0.35f)
            fighting && hp > 0.25f && nearestEnemy in 0f..0.46f && (!s1Ready || !s2Ready || nearestEnemy <= 0.34f)
        } else {
            // Switch from Melee (Wolf/Abyssal) -> Ranged (Human/Elven) when retreating, target escaped (> 0.46f),
            // low HP, or both melee skills were spent
            !fighting || nearestEnemy !in 0f..0.46f || hp in 0f..0.24f || (!s1Ready && !s2Ready && !canRecastNow(hero, "s2"))
        }
    }

    // ---------------- 2. Multi-Phase Skill Specification Table ----------------
    // Defines how many phases a skill has within a single cooldown window, how long the recast window lasts,
    // and which other slots get their cooldown reset when this skill (or its final phase) fires!
    class MultiPhaseSpec(
        val phases: Int,
        val windowMs: Long = 3800L,
        val minGapMs: Long = 280L,
        val recastTap: Boolean = false,
        val resetsOnCast: List<String> = emptyList(),
        val resetsOnFinalPhase: List<String> = emptyList()
    )

    private val multiPhaseSpecs: Map<String, Map<String, MultiPhaseSpec>> = mapOf(
        // Chou: s1 Jeet Kune Do (3 phases; 3rd airborne phase resets s2 Shunpo!), ult The Way of Dragon (2 phases)
        "Chou" to mapOf(
            "s1" to MultiPhaseSpec(phases = 3, windowMs = 4000L, minGapMs = 280L, resetsOnFinalPhase = listOf("s2")),
            "ult" to MultiPhaseSpec(phases = 2, windowMs = 3200L, minGapMs = 320L, recastTap = true)
        ),
        // Martis: s2 Mortal Coil (2 phases; Phase 2 airborne knock-up), ult Decimation
        "Martis" to mapOf(
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 3600L, minGapMs = 350L)
        ),
        // Terizla: s2 Execution Strike (3 hammer swings; 3rd heavy smash)
        "Terizla" to mapOf(
            "s2" to MultiPhaseSpec(phases = 3, windowMs = 3800L, minGapMs = 320L)
        ),
        // Khaleed: s1 Desert Tornado (3 spinning slashes)
        "Khaleed" to mapOf(
            "s1" to MultiPhaseSpec(phases = 3, windowMs = 3600L, minGapMs = 300L)
        ),
        // Freya: s2 Spirit Combo (up to 4 Sacred Orb phases)
        "Freya" to mapOf(
            "s2" to MultiPhaseSpec(phases = 4, windowMs = 4200L, minGapMs = 280L)
        ),
        // Guinevere: s2 Spatial Migration (Phase 1 knock-up jump -> Phase 2 clone blink)
        "Guinevere" to mapOf(
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 4000L, minGapMs = 350L)
        ),
        // Jawhead: s2 Ejector (Phase 1 shield/speed -> Phase 2 throw target)
        "Jawhead" to mapOf(
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 4000L, minGapMs = 280L, recastTap = true)
        ),
        // Tigreal: s2 Sacred Hammer (Phase 1 charge push -> Phase 2 airborne knock-up)
        "Tigreal" to mapOf(
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 3600L, minGapMs = 320L, recastTap = true)
        ),
        // Carmilla: s2 Bloodbath (Phase 1 speed up -> Phase 2 throw stun)
        "Carmilla" to mapOf(
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 4000L, minGapMs = 280L, recastTap = true)
        ),
        // Cici: s2 Buoyant Bounce (2 phases when landing on target)
        "Cici" to mapOf(
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 3200L, minGapMs = 300L)
        ),
        // Arlott: s2 Vengeance (resets on hitting Marked targets: s1 and ult both apply Marks!)
        "Arlott" to mapOf(
            "s1" to MultiPhaseSpec(phases = 1, resetsOnCast = listOf("s2")),
            "ult" to MultiPhaseSpec(phases = 1, resetsOnCast = listOf("s2")),
            "s2" to MultiPhaseSpec(phases = 3, windowMs = 4000L, minGapMs = 280L, recastTap = true)
        ),
        // Lancelot: s1 Puncture (resets when dashing through unmarked targets)
        "Lancelot" to mapOf(
            "s1" to MultiPhaseSpec(phases = 3, windowMs = 3800L, minGapMs = 280L)
        ),
        // Hayabusa: s2 Quad Shadow (Phase 1 dash + 4 phantoms -> Phase 2 teleport to phantom + cuts s1 cooldown)
        "Hayabusa" to mapOf(
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 4200L, minGapMs = 320L, resetsOnFinalPhase = listOf("s1"))
        ),
        // Joy: s2 Meow, Rhythm of Joy! (4 rhythmic beats)
        "Joy" to mapOf(
            "s2" to MultiPhaseSpec(phases = 4, windowMs = 4000L, minGapMs = 320L)
        ),
        // Harley: s2 Space Escape (Phase 1 blink in -> Phase 2 return to hat)
        "Harley" to mapOf(
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 4000L, minGapMs = 350L, recastTap = true)
        ),
        // Claude: s2 Battle Mirror Image (Phase 1 place mirror -> Phase 2 swap)
        "Claude" to mapOf(
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 4500L, minGapMs = 350L, recastTap = true)
        ),
        // Kadita: s1 Ocean Ode (Phase 1 wave dash -> Phase 2 return)
        "Kadita" to mapOf(
            "s1" to MultiPhaseSpec(phases = 2, windowMs = 3500L, minGapMs = 350L, recastTap = true)
        ),
        // Wanwan: s1 Swallow's Path (Phase 1 throw darts -> Phase 2 recall stun)
        "Wanwan" to mapOf(
            "s1" to MultiPhaseSpec(phases = 2, windowMs = 3500L, minGapMs = 300L, recastTap = true)
        ),
        // Natan: ult Entropy? (Phase 1 summon Reverse-Clone -> Phase 2 swap + cuts s1/s2 cooldowns)
        "Natan" to mapOf(
            "ult" to MultiPhaseSpec(phases = 2, windowMs = 6000L, minGapMs = 350L, recastTap = true, resetsOnFinalPhase = listOf("s1", "s2"))
        ),
        // Gloo: ult Split, Split (Phase 1 split into Goos -> Phase 2 attach to enemy host + resets s1/s2)
        "Gloo" to mapOf(
            "ult" to MultiPhaseSpec(phases = 2, windowMs = 5500L, minGapMs = 400L, recastTap = true, resetsOnFinalPhase = listOf("s1", "s2"))
        ),
        // X.Borg: ult Last Insanity (Phase 1 flame spin -> Phase 2 detonate)
        "X.Borg" to mapOf(
            "ult" to MultiPhaseSpec(phases = 2, windowMs = 2800L, minGapMs = 650L, recastTap = true)
        ),
        // Phoveus: ult Infernal Pursuit (multi-recast whenever enemy is displaced/marked by s1 or s2)
        "Phoveus" to mapOf(
            "s1" to MultiPhaseSpec(phases = 2, windowMs = 3500L, minGapMs = 300L, resetsOnCast = listOf("ult")),
            "s2" to MultiPhaseSpec(phases = 1, resetsOnCast = listOf("ult")),
            "ult" to MultiPhaseSpec(phases = 3, windowMs = 4500L, minGapMs = 320L, recastTap = true)
        ),
        // Lylia: s2 Shadow Energy (up to 5 charges)
        "Lylia" to mapOf(
            "s2" to MultiPhaseSpec(phases = 5, windowMs = 5000L, minGapMs = 300L)
        ),
        // Valentina: s2 Arcane Shade (2 shadow bolts reduce s1 Shadow Strike cooldown)
        "Valentina" to mapOf(
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 4000L, minGapMs = 300L, resetsOnCast = listOf("s1"))
        ),
        // Lapu-Lapu: ult Bravest Fighter (Phase 1 leap + transform -> Phase 2 Raging Slash in Heavy Sword form!)
        "Lapu-Lapu" to mapOf(
            "ult" to MultiPhaseSpec(phases = 2, windowMs = 9500L, minGapMs = 350L, resetsOnCast = listOf("s1", "s2"))
        ),
        // Lukas: ult Unleash the Beast (Phase 1 transform -> Phase 2 Shockwave Blast!)
        "Lukas" to mapOf(
            "ult" to MultiPhaseSpec(phases = 2, windowMs = 9500L, minGapMs = 350L, resetsOnCast = listOf("s1", "s2"))
        ),
        // Selena: in Abyssal form, s2 Garotte resets when hitting a marked enemy
        "Selena" to mapOf(
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 3800L, minGapMs = 280L)
        ),
        // Harith: inside Zaman Force (ult), s2 Synchro Fission has near-zero cooldown after basic attack
        "Harith" to mapOf(
            "ult" to MultiPhaseSpec(phases = 1, resetsOnCast = listOf("s2")),
            "s2" to MultiPhaseSpec(phases = 4, windowMs = 5500L, minGapMs = 320L)
        ),
        // Kagura: s1 Seimei Umbrella Open -> s2 Rasho Umbrella Flee (2 phases: teleport to umbrella + dash with umbrella) & ult Yin Yang Overturn (2 phases: remote pull + close burst)
        "Kagura" to mapOf(
            "s1" to MultiPhaseSpec(phases = 2, windowMs = 4500L, minGapMs = 320L),
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 4500L, minGapMs = 300L, recastTap = true, resetsOnCast = listOf("ult")),
            "ult" to MultiPhaseSpec(phases = 2, windowMs = 4500L, minGapMs = 320L, recastTap = true)
        ),
        // Lunox: ult Darkening lets her spam s2 Chaos Assault for 4s
        "Lunox" to mapOf(
            "ult" to MultiPhaseSpec(phases = 1, resetsOnCast = listOf("s2")),
            "s2" to MultiPhaseSpec(phases = 4, windowMs = 4200L, minGapMs = 280L, recastTap = true)
        ),
        // Fredrinn: s3 Energy Eruption cuts s1 and s2 cooldowns by 75%
        "Fredrinn" to mapOf(
            "s3" to MultiPhaseSpec(phases = 1, resetsOnCast = listOf("s1", "s2"))
        ),
        // Atlas: s2 Perfect Match (Phase 1 eject Mecha Sentry -> Phase 2 merge stun)
        "Atlas" to mapOf(
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 3500L, minGapMs = 350L, recastTap = true)
        ),
        // Benedetta: s1 Phantom Slash + s2 An Eye for an Eye
        "Benedetta" to mapOf(
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 3200L, minGapMs = 280L)
        ),
        // Nolan: low-cooldown s1/s2 rift cuts
        "Nolan" to mapOf(
            "s1" to MultiPhaseSpec(phases = 2, windowMs = 3500L, minGapMs = 320L),
            "s2" to MultiPhaseSpec(phases = 2, windowMs = 3500L, minGapMs = 320L)
        ),
    )

    // Tracks active multi-phase state per "Hero:slot" -> (currentPhaseUsed, windowExpiresAt)
    private val phaseState = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()
    // Paquito Champ Stance stack counter (every 3rd skill cast resets all skills!)
    @Volatile private var paquitoStacks = 0
    @Volatile private var paquitoLastAt = 0L

    fun shouldRecastTap(hero: String?, slot: String): Boolean {
        if (hero == null) return false
        if (hero == "Gusion") return gusionShouldTap(slot)
        val spec = multiPhaseSpecs[hero]?.get(slot) ?: return false
        if (!spec.recastTap) return false
        val st = phaseState["$hero:$slot"] ?: return false
        return System.currentTimeMillis() < st.second && st.first in 1 until spec.phases
    }

    // ---------------- special casts (Hirara, Suyou, Sora) ----------------
    enum class Special { DRAG_TO, HOLD_OR_TAP, SWIPE_ULT }
    class SpecialCast(val kind: Special, val slot: String, val other: String = "", val note: String)
    private val specials = mapOf(
        "Hirara" to listOf(
            SpecialCast(Special.DRAG_TO, "s1", "s2", "drag Skill 1 (Mobility) onto Skill 2 (AOE) = dash-in AOE combo"),
            SpecialCast(Special.DRAG_TO, "s2", "s1", "drag Skill 2 (AOE) onto Skill 1 (Mobility) = point-blank AOE + dash combo")
        ),
        "Suyou" to listOf(
            SpecialCast(Special.HOLD_OR_TAP, "s1", note = "hold = Immortal stun charge (+damage reduction), tap = Mortal instant blink (+speed)"),
            SpecialCast(Special.HOLD_OR_TAP, "s2", note = "hold = Immortal 3-cleave + heal, tap = Mortal instant fan slash"),
            SpecialCast(Special.HOLD_OR_TAP, "ult", note = "hold = Immortal long-range snipe arrow, tap = Mortal backstep shot")
        ),
        "Sora" to listOf(
            SpecialCast(Special.SWIPE_ULT, "ult", note = "swipe left = Thunder (single-target burst + untargetable), swipe right = Torrent (AoE pull + CC immunity)")
        ),
    )
    fun specials(hero: String?): List<SpecialCast> = hero?.let { specials[it] } ?: emptyList()
    fun special(hero: String?, slot: String): SpecialCast? = specials(hero).firstOrNull { it.slot == slot }

    fun dragOrder(hero: String?, targetDist: Float): Pair<String, String>? {
        val d = specials(hero).filter { it.kind == Special.DRAG_TO }
        if (d.size < 2) return d.firstOrNull()?.let { it.slot to it.other }
        return if (targetDist in 0f..0.25f) d[1].slot to d[1].other else d[0].slot to d[0].other
    }

    fun suyouShouldHold(slot: String, retreating: Boolean, chasing: Boolean, targetDist: Float, targetHp: Float, enemiesClose: Int, hp: Float): Boolean {
        if (retreating) return false
        return when (slot) {
            "s1" -> {
                if (chasing && (targetHp in 0f..0.25f || targetDist > 0.44f)) false
                else targetDist <= 0.44f || enemiesClose >= 2
            }
            "s2" -> {
                !(targetHp in 0f..0.26f && enemiesClose <= 1 && hp > 0.40f)
            }
            "ult" -> {
                targetDist > 0.34f || (enemiesClose >= 2 && targetDist > 0.22f)
            }
            else -> holdForImmortal(retreating, chasing, enemiesClose, hp)
        }
    }

    fun holdForImmortal(retreating: Boolean, chasing: Boolean, enemiesClose: Int, hp: Float): Boolean =
        !retreating && !chasing && (enemiesClose >= 2 || hp in 0f..0.6f || enemiesClose == 1)

    fun soraTorrent(enemiesClose: Int, hp: Float): Boolean = enemiesClose >= 2 || hp in 0f..0.5f

    // ---------------- JULIAN: Enhanced 3rd Skill & Empowered Basic Attacks ----------------
    @Volatile private var julianCastCount = 0
    @Volatile private var julianLastCastAt = 0L
    @Volatile private var julianEmpoweredUntil = 0L

    fun julianCombo(enemiesClose: Int, selfHp: Float, targetDist: Float): String = when {
        enemiesClose >= 2 || selfHp in 0f..0.55f -> "s1 > ult > s2 > a > a"
        targetDist > 0.30f -> "s2 > s1 > ult > a > a"
        else -> "s2 > ult > s1 > a > a"
    }

    fun julianEmpoweredNow(): Boolean = System.currentTimeMillis() < julianEmpoweredUntil

    // ---------------- FANNY: Wall-Grapple Steel Cable (s2) Mechanics ----------------
    @Volatile private var lastFannyCableAt = 0L
    @Volatile private var lastFannyWallSide = 1

    // s.screenWalls is a 40 x 20 BooleanArray grid from BushDetector (GX = 40, GY = 20)
    fun fannyCableTarget(s: GameState, sx: Float, sy: Float, tx: Float, ty: Float, retreating: Boolean): Pair<Float, Float>? {
        val now = System.currentTimeMillis()
        val aspect = s.aspect
        val walls = s.screenWalls
        val gxCount = 40
        val gyCount = 20
        if (retreating) {
            var bestAway: Pair<Float, Float>? = null
            var bestDist = -1f
            if (walls.size >= gxCount * gyCount) {
                for (i in 0 until gxCount * gyCount) {
                    if (!walls[i]) continue
                    val cellX = ((i % gxCount) + 0.5f) / gxCount
                    val cellY = ((i / gxCount) + 0.5f) / gyCount
                    val d = hypot((cellX - sx) * aspect, cellY - sy)
                    val awayOk = tx < 0f || ((cellX - sx) * (tx - sx) * aspect * aspect + (cellY - sy) * (ty - sy) <= 0.02f)
                    if (d in 0.16f..0.68f && awayOk && d > bestDist) {
                        bestDist = d
                        bestAway = cellX to cellY
                    }
                }
            }
            if (bestAway != null) {
                lastFannyCableAt = now
                return bestAway
            }
            return (sx - 0.32f).coerceIn(0.08f, 0.92f) to (sy + 0.32f).coerceIn(0.08f, 0.92f)
        }

        if (tx < 0f) return null
        val dxT = (tx - sx) * aspect
        val dyT = ty - sy
        val distT = hypot(dxT, dyT).coerceAtLeast(1e-3f)
        val ux = dxT / distT
        val uy = dyT / distT
        val px = -uy
        val py = ux
        val doubleCable = now - lastFannyCableAt < 950L
        if (doubleCable) lastFannyWallSide = -lastFannyWallSide else lastFannyWallSide = 1

        var bestWall: Pair<Float, Float>? = null
        var bestScore = -999f
        if (walls.size >= gxCount * gyCount) {
            for (i in 0 until gxCount * gyCount) {
                if (!walls[i]) continue
                val cellX = ((i % gxCount) + 0.5f) / gxCount
                val cellY = ((i / gxCount) + 0.5f) / gyCount
                val wx = (cellX - sx) * aspect
                val wy = cellY - sy
                val dWall = hypot(wx, wy)
                if (dWall !in 0.14f..0.72f) continue
                val along = wx * ux + wy * uy
                if (along < distT * 0.55f) continue
                val perp = wx * px + wy * py
                val passDist = kotlin.math.abs(perp)
                if (passDist > 0.26f) continue
                val sideBonus = if (doubleCable && perp * lastFannyWallSide > 0f) 0.12f else 0f
                val score = -passDist * 2.2f - kotlin.math.abs(along - (distT + 0.12f)) + sideBonus
                if (score > bestScore) {
                    bestScore = score
                    bestWall = cellX to cellY
                }
            }
        }
        lastFannyCableAt = now
        if (bestWall != null) return bestWall

        val reachPast = (distT + 0.18f).coerceIn(0.24f, 0.58f)
        val sideOffset = if (doubleCable) 0.09f * lastFannyWallSide else 0.04f * lastFannyWallSide
        val goalX = (sx + (ux * reachPast + px * sideOffset) / aspect).coerceIn(0.06f, 0.94f)
        val goalY = (sy + (uy * reachPast + py * sideOffset)).coerceIn(0.06f, 0.94f)
        return goalX to goalY
    }

    // ---------------- GUSION: Multi-Stage Dagger & Reset Mechanics ----------------
    @Volatile private var gusionS1MarkedUntil = 0L
    @Volatile private var gusionS2DaggersOutUntil = 0L
    @Volatile private var gusionS2FreshReset = false
    @Volatile private var gusionUltDash2Until = 0L

    fun gusionShouldTap(slot: String): Boolean {
        val now = System.currentTimeMillis()
        return when (slot) {
            "s1" -> now < gusionS1MarkedUntil
            "s2" -> now < gusionS2DaggersOutUntil && !gusionS2FreshReset
            else -> false
        }
    }

    // ---------------- LING: Tempest of Blades (ult) Four Blades Combo ----------------
    @Volatile var lingAirUntil = 0L
        private set
    @Volatile var lingBladesUntil = 0L
        private set
    @Volatile private var lingCenterX = 0.5f
    @Volatile private var lingCenterY = 0.5f
    @Volatile private var lingBladeIdx = 0

    private const val LING_BLADE_RADIUS = 0.145f
    private val LING_BLADE_ANGLES = floatArrayOf(
        (-Math.PI / 4.0).toFloat(),
        (3.0 * Math.PI / 4.0).toFloat(),
        (Math.PI / 4.0).toFloat(),
        (-3.0 * Math.PI / 4.0).toFloat()
    )

    fun lingInAirNow(): Boolean = System.currentTimeMillis() < lingAirUntil
    fun lingHasBladesNow(): Boolean {
        val now = System.currentTimeMillis()
        return now in lingAirUntil..lingBladesUntil && lingBladeIdx < 4
    }

    fun nextLingBladeTarget(sx: Float, sy: Float, tx: Float, ty: Float, aspect: Float): Pair<Float, Float>? {
        val now = System.currentTimeMillis()
        if (now < lingAirUntil || now > lingBladesUntil || lingBladeIdx >= 4) return null
        val cx = if (tx >= 0f) (lingCenterX * 0.65f + tx * 0.35f) else lingCenterX
        val cy = if (ty >= 0f) (lingCenterY * 0.65f + ty * 0.35f) else lingCenterY
        val angle = LING_BLADE_ANGLES[lingBladeIdx.coerceIn(0, 3)]
        val bx = (cx + cos(angle) * LING_BLADE_RADIUS / aspect).coerceIn(0.10f, 0.90f)
        val by = (cy + sin(angle) * LING_BLADE_RADIUS).coerceIn(0.10f, 0.90f)
        return bx to by
    }

    // ---------------- Unified Skill Cast State Hook & Multi-Phase Readiness ----------------
    fun onSkillCast(hero: String?, slot: String, targetX: Float = -1f, targetY: Float = -1f) {
        if (hero == null) return
        val now = System.currentTimeMillis()
        if (slot == "ult") onUltCast(hero)

        // 1) Multi-phase skill table state transitions
        multiPhaseSpecs[hero]?.get(slot)?.let { spec ->
            if (spec.resetsOnCast.isNotEmpty()) {
                CooldownTracker.resetSlots(*spec.resetsOnCast.toTypedArray())
            }
            if (spec.phases > 1) {
                val k = "$hero:$slot"
                val cur = phaseState[k]
                val usedNow = if (cur != null && now < cur.second) cur.first + 1 else 1
                if (usedNow >= spec.phases) {
                    phaseState.remove(k)
                    if (spec.resetsOnFinalPhase.isNotEmpty()) {
                        CooldownTracker.resetSlots(*spec.resetsOnFinalPhase.toTypedArray())
                    }
                } else {
                    phaseState[k] = usedNow to (now + spec.windowMs)
                    CooldownTracker.resetSlots(slot)
                }
            }
        }

        // 2) Paquito Champ Stance passive: every 3rd skill cast resets s1, s2, and ult!
        if (hero == "Paquito" && slot in setOf("s1", "s2", "ult")) {
            if (now - paquitoLastAt > 5000L) paquitoStacks = 0
            paquitoStacks++
            paquitoLastAt = now
            if (paquitoStacks >= 3) {
                paquitoStacks = 0
                CooldownTracker.resetSlots("s1", "s2", "ult")
            }
        }

        // 3) Dedicated complex hero state machines
        when (hero) {
            "Julian" -> {
                if (slot in setOf("s1", "s2", "ult")) {
                    if (now - julianLastCastAt > 4500L) julianCastCount = 0
                    julianCastCount++
                    julianLastCastAt = now
                    if (julianCastCount >= 3) {
                        julianEmpoweredUntil = now + 5000L
                        julianCastCount = 0
                    }
                }
            }
            "Gusion" -> {
                when (slot) {
                    "s1" -> {
                        if (now < gusionS1MarkedUntil) {
                            gusionS1MarkedUntil = 0L
                        } else {
                            gusionS1MarkedUntil = now + 3500L
                        }
                    }
                    "s2" -> {
                        if (gusionS2FreshReset) {
                            gusionS2FreshReset = false
                            gusionS2DaggersOutUntil = now + 4000L
                        } else if (now < gusionS2DaggersOutUntil) {
                            gusionS2DaggersOutUntil = 0L
                        } else {
                            gusionS2DaggersOutUntil = now + 4000L
                        }
                    }
                    "ult" -> {
                        if (now < gusionUltDash2Until) {
                            gusionUltDash2Until = 0L
                        } else {
                            gusionS2FreshReset = true
                            gusionS1MarkedUntil = 0L
                            gusionUltDash2Until = now + 4000L
                            CooldownTracker.resetSlots("s1", "s2")
                        }
                    }
                }
            }
            "Ling" -> {
                when (slot) {
                    "ult" -> {
                        lingCenterX = if (targetX >= 0f) targetX else 0.5f
                        lingCenterY = if (targetY >= 0f) targetY else 0.5f
                        lingAirUntil = now + 1450L
                        lingBladesUntil = now + 8000L
                        lingBladeIdx = 0
                    }
                    "s2" -> {
                        if (now in lingAirUntil..lingBladesUntil && lingBladeIdx < 4) {
                            lingBladeIdx++
                            if (lingBladeIdx < 4) CooldownTracker.resetSlots("s2")
                        }
                    }
                }
            }
        }
    }

    // True when a hero's skill is in a special multi-stage recast / reset / zero-cooldown state
    fun canRecastNow(hero: String?, slot: String): Boolean {
        if (hero == null) return false
        val now = System.currentTimeMillis()
        when (hero) {
            "Fanny" -> if (slot == "s2") return true
            "Ling" -> if (slot == "s2" && lingHasBladesNow()) return true
            "Gusion" -> return when (slot) {
                "s1" -> now < gusionS1MarkedUntil
                "s2" -> gusionS2FreshReset || now < gusionS2DaggersOutUntil
                "ult" -> now < gusionUltDash2Until
                else -> false
            }
        }
        val spec = multiPhaseSpecs[hero]?.get(slot) ?: return false
        val st = phaseState["$hero:$slot"] ?: return false
        return now < st.second && st.first in 1 until spec.phases
    }

    fun resetComplexState() {
        julianCastCount = 0; julianLastCastAt = 0L; julianEmpoweredUntil = 0L
        lastFannyCableAt = 0L; lastFannyWallSide = 1
        gusionS1MarkedUntil = 0L; gusionS2DaggersOutUntil = 0L; gusionS2FreshReset = false; gusionUltDash2Until = 0L
        lingAirUntil = 0L; lingBladesUntil = 0L; lingBladeIdx = 0
        paquitoStacks = 0; paquitoLastAt = 0L
        phaseState.clear()
    }

    // ---------------- HP bar models ----------------
    enum class BarModel(val label: String) {
        NORMAL("normal"), SHIELD("shield after the HP (light grey)"), ARMOR("armour after the HP (salmon)"),
        STORED("stored (crystalline) energy after the HP"), SEGMENTED("3 HP layers: dark (x3), medium (x2), bright (last)")
    }
    private val bars = mapOf("Esmeralda" to BarModel.SHIELD, "X.Borg" to BarModel.ARMOR, "Fredrinn" to BarModel.STORED, "Masha" to BarModel.SEGMENTED)
    fun barModel(hero: String?): BarModel = hero?.let { bars[it] } ?: BarModel.NORMAL

    fun effectiveHp(hero: String?, hp: Float, extra: Float): Float {
        if (hp < 0f) return hp
        val e = extra.coerceIn(0f, 1f)
        return when (barModel(hero)) {
            BarModel.STORED -> (hp + e * 0.5f).coerceAtMost(1f)
            BarModel.SEGMENTED -> hp
            else -> (hp + e).coerceAtMost(1f)
        }
    }
    fun retreatAdjust(hero: String?, extra: Float): Float = when (barModel(hero)) {
        BarModel.ARMOR -> if (extra < 0.02f) 0.10f else 0f
        BarModel.SHIELD -> if (extra > 0.1f) -0.05f else 0f
        BarModel.SEGMENTED -> 0f
        else -> 0f
    }

    val variations: Map<String, String> = linkedMapOf(
        "Selena" to "Elven (ranged trap + arrow) <-> Abyssal (melee assassin with Garotte resets on marked targets)",
        "Roger" to "Human (ranged slow/shred) <-> Wolf (melee lunge + Lycan Pounce)",
        "Lapu-Lapu" to "Twin Blades <-> Heavy Sword form: Bravest Fighter (ult) unlocks Raging Slash (3-slam CC-immune ult)",
        "Edith" to "Phylax (melee tank) <-> Edith (ranged Marksman form with refreshed Lightning Bolt & Divine Retribution)",
        "Yu Zhong" to "Black Dragon form -> 10s Dragonoid form with enhanced range on all 3 skills",
        "Lukas" to "Sacred Beast form (ult) refreshes skills and unlocks Shockwave Blast recast",
        "Sora" to "Thunder (swipe left) and Torrent (swipe right) forms: the form chosen changes his skills",
        "Kagura" to "Seimei Umbrella dual state: s1 throw -> ult link -> s2 teleport pickup -> ult knockback burst -> s2 dash",
        "Lunox" to "Order & Chaos: Chaos Darkening (ult) grants 4s zero-cooldown s2 spam; Order Brilliance grants invulnerable heal",
        "Yin" to "My Turn (ult) pulls target into domain as Lieh with refreshed Frenzy Strike & Instant Blast",
        "Julian" to "after two DIFFERENT basic skills, the 3rd skill is Enhanced (Scythe, Sword, or Chain) + empowered basic attacks",
        "Chou" to "Jeet Kune Do (s1) 3 phases (3rd airborne phase resets Shunpo s2) + The Way of Dragon (ult) 2 phases",
        "Paquito" to "Champ Stance passive: every 3rd skill cast immediately resets s1, s2, and ult with zero cooldown",
        "Gusion" to "10-dagger multi-stage combo: s2 throw -> ult reset -> s2 throw -> s1 mark -> s2 recall -> s1 blink -> empowered basic attack",
        "Fanny" to "Steel Cable (s2) wall-grapple triangulation -> Cut Throat (ult) on Prey-Marked target -> Tornado Strike (s1)",
        "Ling" to "Tempest of Blades (ult) spawns 4 Field Eyes (Four Blades): dashing (s2) to each blade resets Defiant Sword",
        "Hirara" to "drag Skill 1 onto Skill 2 (or Skill 2 onto Skill 1) in a continuous hold-and-drag combo",
        "Suyou" to "per-skill Tap (Mortal form: speed/blink/burst) vs Hold (Immortal form: stun charge/3-cleave heal/long-range snipe)",
        "Beatrix" to "four weapons: each changes her basic attack and ultimate",
        "Yi Sun-shin" to "Heavenly Vow weapon-switching passive: alternates glaive (melee) and longbow (ranged) attacks",
    )

    fun describe(hero: String): String = buildString {
        val sl = slots(hero)
        append(when (sl.size) { 2 -> "2 active skills"; 4 -> "4 active skills"; else -> "${sl.size} active skills" })
        append(" (").append(sl.joinToString(" ") { s -> buttonHints[hero]?.get(s)?.let { "$s=$it" } ?: s }).append(")")
        transform(hero)?.let { append(" · transform ultimate: ").append(it.note) }
        for (s in specials(hero)) append(" · ").append(s.slot).append(": ").append(s.note)
        val b = barModel(hero)
        if (b != BarModel.NORMAL) append(" · HP bar: ").append(b.label)
        variations[hero]?.let { append(" · skill variation: ").append(it) }
    }
}
