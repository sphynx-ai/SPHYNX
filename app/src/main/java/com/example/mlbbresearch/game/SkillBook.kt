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
// SKILLSHOT = a projectile aimed in a direction that stops on the first unit hit (minions block it);
// TRANSFORM = changes the hero's form / skills (Roger, Selena, Yu Zhong, Sora...: see HeroKit)
enum class SkillKind { TARGET, DIRECTION, AREA, SELF, DASH, SKILLSHOT, TRANSFORM }
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
    val description: String,
    val cooldownSec: Float = 0f  // from the description / online answer (0 = not known)
)

class HeroSkills(val hero: String, val skills: Map<String, SkillInfo>, val passive: Set<Passive>, val passiveText: String, val source: String) {
    operator fun get(slot: String): SkillInfo? = skills[slot]
    fun has(p: Passive) = p in passive
    // estimated share of an enemy's HP the ready skills take (the combo's damage)
    fun burst(ready: (String) -> Boolean): Float = skills.values.filter { ready(it.slot) }.sumOf { it.damage.toDouble() }.toFloat()
    val escape: SkillInfo? get() = skills.values.firstOrNull { it.mobility && it.slot != "ult" } ?: skills.values.firstOrNull { it.mobility }
    val cc: List<SkillInfo> get() = skills.values.filter { it.hardCc || it.slow }
    // skills that hit from range without taking you in (a melee hero can poke with these)
    val pokeSkills: List<SkillInfo> get() = skills.values.filter { it.slot != "ult" && it.range >= 0.4f && it.damage > 0f && !it.mobility }
}

object SkillBook {

    // ranged heroes poke; a melee hero pokes only with a ranged skill (your rule)
    fun canPoke(hero: String?): Boolean = HeroDb.isRanged(hero) || get(hero).pokeSkills.isNotEmpty()

    private val cache = HashMap<String, HeroSkills>()
    @Volatile var busy = ""
        private set
    @Volatile var lastMessage = ""
        private set
    @Volatile var learnAllProgress = ""
        private set

    private fun key(hero: String) = hero.lowercase().replace(' ', '_')

    // HERO ADAPTER starting points for the training heroes (one per behaviour to learn): skill
    // types, crowd control, mobility, cast style, passive. Online / pasted descriptions replace them.
    private val SEEDS: Map<String, String> = mapOf(
        "Ruby" to """{"passive": {"description": "Lifesteal from all damage; after a skill, the next basic attack can dash a short way."}, "passive_rules": ["WEAVE_ATTACK"], "skills": {"s1": {"name": "Be Good!", "type": "area", "range": "short", "cc": "slow", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "tap", "hold_seconds": 0, "description": "Be Good!"}, "s2": {"name": "Don't Run, Wolf King!", "type": "area", "range": "short", "cc": "hard", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "tap", "hold_seconds": 0, "description": "Don't Run, Wolf King!"}, "ult": {"name": "I'm Offended!", "type": "area", "range": "short", "cc": "hard", "mobility": false, "shield": false, "heal": false, "damage": "high", "cast": "tap", "hold_seconds": 0, "description": "I'm Offended!"}}, "source": "built-in (check the current patch)"}""",
        "Yu Zhong" to """{"passive": {"description": "Dragon soul: attacks stack, lifesteal; strong sustained fighting."}, "passive_rules": ["STACKS"], "skills": {"s1": {"name": "Cursed Ripple", "type": "area", "range": "short", "cc": "slow", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "tap", "hold_seconds": 0, "description": "Cursed Ripple"}, "s2": {"name": "Soul Grip", "type": "dash", "range": "medium", "cc": "hard", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Soul Grip"}, "ult": {"name": "Black Dragon Form", "type": "dash", "range": "medium", "cc": "hard", "mobility": true, "shield": false, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "description": "Black Dragon Form"}}, "source": "built-in (check the current patch)"}""",
        "Chou" to """{"passive": {"description": "After using a skill, gains movement speed; enhanced basic attacks."}, "passive_rules": ["WEAVE_ATTACK", "MOVE_SPEED"], "skills": {"s1": {"name": "Jeet Kune Do", "type": "dash", "range": "short", "cc": "hard", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Jeet Kune Do"}, "s2": {"name": "Shunpo", "type": "dash", "range": "short", "cc": "none", "mobility": true, "shield": true, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Shunpo"}, "ult": {"name": "The Way of Dragon", "type": "target", "range": "short", "cc": "hard", "mobility": false, "shield": false, "heal": false, "damage": "high", "cast": "tap", "hold_seconds": 0, "description": "The Way of Dragon"}}, "source": "built-in (check the current patch)"}""",
        "Balmond" to """{"passive": {"description": "Restores HP when killing minions, monsters or heroes."}, "passive_rules": ["EXECUTE_BONUS"], "skills": {"s1": {"name": "Soul Lock", "type": "dash", "range": "medium", "cc": "slow", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Soul Lock"}, "s2": {"name": "Cyclone Sweep", "type": "self", "range": "short", "cc": "none", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "tap", "hold_seconds": 0, "description": "Cyclone Sweep"}, "ult": {"name": "Lethal Counter", "type": "direction", "range": "medium", "cc": "slow", "mobility": false, "shield": false, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "description": "Lethal Counter"}}, "source": "built-in (check the current patch)"}""",
        "Fredrinn" to """{"passive": {"description": "Damage taken turns into a shield of energy; combo points power the ultimate."}, "passive_rules": ["SHIELD"], "skills": {"s1": {"name": "Piercing Strike", "type": "dash", "range": "medium", "cc": "slow", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Piercing Strike"}, "s2": {"name": "Brave Assault", "type": "dash", "range": "medium", "cc": "hard", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Brave Assault"}, "s3": {"name": "Energy Eruption", "type": "area", "range": "short", "cc": "hard", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "tap", "hold_seconds": 0, "description": "Deals Physical Damage to nearby enemies and taunts them for 1 second. Hitting a non-minion enemy grants Physical and Magic Defense for 3 seconds and cuts the cooldowns of Piercing Strike and Brave Assault by 75%. Costs 1 Combo Point (learned together with Appraiser's Wrath)."}, "ult": {"name": "Appraiser's Wrath", "type": "area", "range": "short", "cc": "hard", "mobility": false, "shield": false, "heal": false, "damage": "high", "cast": "tap", "hold_seconds": 0, "description": "Slams his sword in the target direction (only Suppression interrupts it), dealing Physical Damage plus a share of his Crystal Energy in a fan-shaped area after a short delay; enemies in the centre take 175% damage. Costs 3 Combo Points."}}, "source": "built-in (check the current patch)"}""",
        "Hayabusa" to """{"passive": {"description": "Shadow marks stack on enemies hit; the ultimate deals more per mark."}, "passive_rules": ["STACKS"], "skills": {"s1": {"name": "Phantom Shuriken", "type": "direction", "range": "medium", "cc": "slow", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Phantom Shuriken"}, "s2": {"name": "Quad Shadow", "type": "dash", "range": "medium", "cc": "none", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Quad Shadow"}, "ult": {"name": "Ougi: Shadow Kill", "type": "target", "range": "short", "cc": "none", "mobility": false, "shield": false, "heal": false, "damage": "high", "cast": "tap", "hold_seconds": 0, "description": "Ougi: Shadow Kill"}}, "source": "built-in (check the current patch)"}""",
        "Eudora" to """{"passive": {"description": "Enemies stunned by her take extra damage from her ultimate."}, "passive_rules": [], "skills": {"s1": {"name": "Forked Lightning", "type": "direction", "range": "medium", "cc": "none", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Forked Lightning"}, "s2": {"name": "Ball Lightning", "type": "target", "range": "medium", "cc": "hard", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "tap", "hold_seconds": 0, "description": "Ball Lightning"}, "ult": {"name": "Thunder's Wrath", "type": "target", "range": "medium", "cc": "none", "mobility": false, "shield": false, "heal": false, "damage": "high", "cast": "tap", "hold_seconds": 0, "description": "Thunder's Wrath"}}, "source": "built-in (check the current patch)"}""",
        "Lylia" to """{"passive": {"description": "Gloom hits slow; her basic attack marks enemies."}, "passive_rules": ["MOVE_SPEED"], "skills": {"s1": {"name": "Magic Shockwave", "type": "area", "range": "long", "cc": "slow", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Magic Shockwave"}, "s2": {"name": "Shadow Energy", "type": "area", "range": "medium", "cc": "slow", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Shadow Energy"}, "ult": {"name": "Black Shoes", "type": "self", "range": "short", "cc": "none", "mobility": true, "shield": false, "heal": true, "damage": "medium", "cast": "tap", "hold_seconds": 0, "description": "Black Shoes"}}, "source": "built-in (check the current patch)"}""",
        "Kagura" to """{"passive": {"description": "With the umbrella back, a shield and movement speed."}, "passive_rules": ["SHIELD"], "skills": {"s1": {"name": "Seimei Umbrella Open", "type": "direction", "range": "long", "cc": "slow", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Seimei Umbrella Open"}, "s2": {"name": "Rasho Umbrella Flee", "type": "dash", "range": "medium", "cc": "none", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Rasho Umbrella Flee"}, "ult": {"name": "Yin Yang Overturn", "type": "area", "range": "short", "cc": "hard", "mobility": false, "shield": false, "heal": false, "damage": "high", "cast": "tap", "hold_seconds": 0, "description": "Yin Yang Overturn"}}, "source": "built-in (check the current patch)"}""",
        "Miya" to """{"passive": {"description": "Each basic attack stacks attack speed."}, "passive_rules": ["STACKS"], "skills": {"s1": {"name": "Moon Arrow", "type": "self", "range": "medium", "cc": "none", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "tap", "hold_seconds": 0, "description": "Moon Arrow"}, "s2": {"name": "Arrow of Eclipse", "type": "area", "range": "long", "cc": "hard", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Arrow of Eclipse"}, "ult": {"name": "Hidden Moonlight", "type": "self", "range": "short", "cc": "none", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "tap", "hold_seconds": 0, "description": "Hidden Moonlight"}}, "source": "built-in (check the current patch)"}""",
        "Brody" to """{"passive": {"description": "Abyss marks stack on basic attacks; the ultimate deals more per mark."}, "passive_rules": ["STACKS", "EXECUTE_BONUS"], "skills": {"s1": {"name": "Abyss Impact", "type": "direction", "range": "long", "cc": "slow", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Abyss Impact"}, "s2": {"name": "Torn Memories", "type": "dash", "range": "medium", "cc": "hard", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Torn Memories"}, "ult": {"name": "Corrosion Scythe", "type": "target", "range": "long", "cc": "none", "mobility": false, "shield": false, "heal": false, "damage": "high", "cast": "tap", "hold_seconds": 0, "description": "Corrosion Scythe"}}, "source": "built-in (check the current patch)"}""",
        "Claude" to """{"passive": {"description": "Dexter attacks with him: extra damage on basic attacks."}, "passive_rules": ["STACKS"], "skills": {"s1": {"name": "Art of Thievery", "type": "dash", "range": "medium", "cc": "none", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Art of Thievery"}, "s2": {"name": "Battle Mirror Image", "type": "dash", "range": "medium", "cc": "none", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Battle Mirror Image"}, "ult": {"name": "Blazing Duet", "type": "self", "range": "medium", "cc": "none", "mobility": false, "shield": false, "heal": false, "damage": "high", "cast": "tap", "hold_seconds": 0, "description": "Blazing Duet"}}, "source": "built-in (check the current patch)"}""",
        "Tigreal" to """{"passive": {"description": "Taking damage builds a damage-reduction stack."}, "passive_rules": ["SHIELD"], "skills": {"s1": {"name": "Attack Wave", "type": "direction", "range": "medium", "cc": "slow", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Attack Wave"}, "s2": {"name": "Sacred Hammer", "type": "dash", "range": "medium", "cc": "hard", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Sacred Hammer"}, "ult": {"name": "Implosion", "type": "area", "range": "short", "cc": "hard", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "tap", "hold_seconds": 0, "description": "Implosion"}}, "source": "built-in (check the current patch)"}""",
        "Minotaur" to """{"passive": {"description": "Rage mode after taking damage: enhanced skills."}, "passive_rules": ["LOW_HP_POWER"], "skills": {"s1": {"name": "Despair Stomp", "type": "dash", "range": "medium", "cc": "hard", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Despair Stomp"}, "s2": {"name": "Rage", "type": "self", "range": "short", "cc": "none", "mobility": false, "shield": false, "heal": true, "damage": "medium", "cast": "tap", "hold_seconds": 0, "description": "Rage"}, "ult": {"name": "Minoan Fury", "type": "area", "range": "short", "cc": "hard", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "tap", "hold_seconds": 0, "description": "Minoan Fury"}}, "source": "built-in (check the current patch)"}""",
        "Selena" to """{"passive": {"description": "Elven form skills apply Abyssal Marks; Abyssal form attacks and Garotte consume marks for bonus damage and reset Garotte cooldown."}, "passive_rules": ["STACKS", "WEAVE_ATTACK", "AFTER_DASH_BONUS"], "skills": {"s1": {"name": "Abyssal Trap / Soul Eater", "type": "area", "range": "medium", "cc": "slow", "mobility": false, "shield": true, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 7.0, "description": "Elven: places Abyssal Trap at target area. Abyssal: empowers next basic attack with claws and grants a shield."}, "s2": {"name": "Abyssal Arrow / Garotte", "type": "skillshot", "range": "long", "cc": "hard", "mobility": true, "shield": false, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 9.0, "description": "Elven: fires long-range arrow that stuns longer by distance. Abyssal: dashes in direction; resets cooldown if it consumes an Abyssal Mark."}, "ult": {"name": "Primal Darkness / Blessing", "type": "transform", "range": "short", "cc": "none", "mobility": false, "shield": false, "heal": false, "damage": "low", "cast": "tap", "hold_seconds": 0, "cooldown_seconds": 4.5, "description": "Switches between Elven (ranged) and Abyssal (melee) forms and immediately resets Elven/Abyssal skills."}}, "source": "built-in (dual identity)"}""",
        "Roger" to """{"passive": {"description": "Human basic attacks slow; Wolf basic attacks deal extra percentage HP damage."}, "passive_rules": ["WEAVE_ATTACK", "EXECUTE_BONUS"], "skills": {"s1": {"name": "Open Fire / Lycan Pounce", "type": "direction", "range": "medium", "cc": "slow", "mobility": true, "shield": false, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 7.0, "description": "Human: fires two shots that slow and reduce defense. Wolf: pounces untargetably onto enemies."}, "s2": {"name": "Hunter Steps / Bloodthirsty Howl", "type": "self", "range": "short", "cc": "none", "mobility": true, "shield": false, "heal": false, "damage": "low", "cast": "tap", "hold_seconds": 0, "cooldown_seconds": 8.0, "description": "Grants movement speed in Human form and attack speed in Wolf form."}, "ult": {"name": "Wolf Transformation / Restore Human", "type": "dash", "range": "medium", "cc": "slow", "mobility": true, "shield": true, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 5.5, "description": "Lunges forward into Wolf form (or dashes into Human form), granting a shield and refreshing skills."}}, "source": "built-in (dual identity)"}""",
        "Lapu-Lapu" to """{"passive": {"description": "Bravery Blessing stacks grant shield and enhanced basic attack dash."}, "passive_rules": ["SHIELD", "WEAVE_ATTACK"], "skills": {"s1": {"name": "Justice Blades / Land Shaker", "type": "direction", "range": "medium", "cc": "hard", "mobility": false, "shield": false, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 6.5, "description": "Throws twin boomerangs in base form; stuns in a line in Heavy Sword form."}, "s2": {"name": "Jungle Warrior / Storm Sword", "type": "dash", "range": "medium", "cc": "none", "mobility": true, "shield": true, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 7.5, "description": "Dashes in Twin Blades form; spins to reduce damage taken in Heavy Sword form."}, "ult": {"name": "Bravest Fighter / Raging Slash", "type": "dash", "range": "medium", "cc": "hard", "mobility": true, "shield": true, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 20.0, "description": "Leaps and merges blades into Heavy Sword, then recasts for 3 CC-immune Raging Slashes."}}, "source": "built-in (transformation)"}""",
        "Edith" to """{"passive": {"description": "After each skill, Overload empowers basic attacks with chain lightning."}, "passive_rules": ["WEAVE_ATTACK", "SHIELD"], "skills": {"s1": {"name": "Earth Shatter / Divine Retribution", "type": "area", "range": "medium", "cc": "hard", "mobility": false, "shield": false, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 7.5, "description": "Phylax slams the ground to knock up; Edith calls down AoE lightning."}, "s2": {"name": "Onward / Lightning Bolt", "type": "dash", "range": "medium", "cc": "hard", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 8.5, "description": "Phylax charges and flips an enemy; Edith fires a lightning bolt that immobilizes."}, "ult": {"name": "Primal Wrath", "type": "transform", "range": "medium", "cc": "none", "mobility": true, "shield": true, "heal": true, "damage": "high", "cast": "tap", "hold_seconds": 0, "cooldown_seconds": 28.0, "description": "Ejects Edith from Phylax into ranged Marksman form with refreshed skills."}}, "source": "built-in (transformation)"}""",
        "Lukas" to """{"passive": {"description": "Builds Resolve to unleash Sacred Beast form."}, "passive_rules": ["WEAVE_ATTACK", "STACKS"], "skills": {"s1": {"name": "Flash Combo", "type": "area", "range": "short", "cc": "hard", "mobility": false, "shield": false, "heal": true, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 6.5, "description": "Unleashes a combo pulverize enhanced by Vigor stacks."}, "s2": {"name": "Flash Step", "type": "dash", "range": "medium", "cc": "none", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 7.5, "description": "Dashes and empowers next basic attack into Flash Strike."}, "ult": {"name": "Unleash the Beast / Shockwave Blast", "type": "transform", "range": "long", "cc": "slow", "mobility": true, "shield": true, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 26.0, "description": "Transforms into Sacred Beast form and fires Shockwave Blast on recast."}}, "source": "built-in (transformation)"}""",
        "Paquito" to """{"passive": {"description": "Champ Stance: after 3 skill casts, all skills reset with zero cooldown and enhanced damage."}, "passive_rules": ["STACKS", "AFTER_DASH_BONUS", "SHIELD"], "skills": {"s1": {"name": "Heavy Left Punch", "type": "direction", "range": "short", "cc": "none", "mobility": false, "shield": true, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 6.5, "description": "Punches in direction and gains a shield."}, "s2": {"name": "Jab", "type": "dash", "range": "medium", "cc": "none", "mobility": true, "shield": false, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 7.0, "description": "Dashes forward and jabs."}, "ult": {"name": "Knockout Strike", "type": "dash", "range": "medium", "cc": "hard", "mobility": true, "shield": false, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 16.0, "description": "Uppercuts and knocks enemies airborne."}}, "source": "built-in (multi-phase)"}""",
        "Suyou" to """{"passive": {"description": "Tap-casting a skill puts Suyou in Mortal form (movement speed and fast attack interval); Hold-casting puts him in Immortal form (damage reduction and high physical damage)."}, "passive_rules": ["WEAVE_ATTACK", "MOVE_SPEED"], "skills": {"s1": {"name": "Blade Surge", "type": "dash", "range": "medium", "cc": "hard", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "hold", "hold_seconds": 0.6, "cooldown_seconds": 7.5, "description": "Tap: throws weapon and blinks to it. Hold: charges forward in Immortal form and stuns enemies along the path."}, "s2": {"name": "Soul Sever", "type": "area", "range": "short", "cc": "none", "mobility": false, "shield": false, "heal": true, "damage": "high", "cast": "hold", "hold_seconds": 0.6, "cooldown_seconds": 6.5, "description": "Tap: fan-shaped slash dealing missing-HP physical damage. Hold: 3 rapid cleaves in a cone, healing on the 3rd cleave."}, "ult": {"name": "Evil Queller", "type": "direction", "range": "long", "cc": "slow", "mobility": true, "shield": false, "heal": false, "damage": "high", "cast": "hold", "hold_seconds": 0.7, "cooldown_seconds": 8.5, "description": "Tap: glides backward and swings. Hold: aims and fires a long-range piercing arrow in Immortal form."}}, "source": "built-in (complex mechanics)"}""",
        "Hirara" to """{"passive": {"description": "Dragging one skill button onto the other unleashes a combined mobility + AOE combo."}, "passive_rules": ["WEAVE_ATTACK", "AFTER_DASH_BONUS"], "skills": {"s1": {"name": "Mobility", "type": "dash", "range": "medium", "cc": "none", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 6.0, "description": "Dashes in the target direction; drag onto Skill 2 to chain into AOE."}, "s2": {"name": "AOE", "type": "area", "range": "medium", "cc": "slow", "mobility": false, "shield": false, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 7.0, "description": "Deals area damage; drag onto Skill 1 to chain into Mobility dash."}}, "source": "built-in (complex mechanics)"}""",
        "Sora" to """{"passive": {"description": "Swiping Ultimate left enters Thunder form (single-target burst); swiping right enters Torrent form (AoE pull and control immunity)."}, "passive_rules": ["WEAVE_ATTACK"], "skills": {"s1": {"name": "Strike", "type": "dash", "range": "medium", "cc": "slow", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 6.0, "description": "Dashes and strikes enemies in path."}, "s2": {"name": "Tempest", "type": "area", "range": "short", "cc": "hard", "mobility": false, "shield": true, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 7.5, "description": "Area strike with crowd control."}, "ult": {"name": "Dual Stance", "type": "transform", "range": "medium", "cc": "hard", "mobility": true, "shield": true, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 24.0, "description": "Swipe left for Thunder form or swipe right for Torrent form."}}, "source": "built-in (complex mechanics)"}""",
        "Julian" to """{"passive": {"description": "After casting two different skills, Julian enhances his third skill and empowers his basic attacks with magic lifesteal and pull."}, "passive_rules": ["WEAVE_ATTACK", "SHIELD"], "skills": {"s1": {"name": "Scythe", "type": "direction", "range": "medium", "cc": "slow", "mobility": false, "shield": false, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 6.5, "description": "Hurls a flying scythe; Enhanced Scythe shreds continuously along the path."}, "s2": {"name": "Sword", "type": "dash", "range": "medium", "cc": "none", "mobility": true, "shield": false, "heal": true, "damage": "medium", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 6.5, "description": "Dashes with a flying sword; Enhanced Sword grants untargetable multi-slash AoE."}, "ult": {"name": "Chain", "type": "area", "range": "medium", "cc": "hard", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 6.5, "description": "Casts chains at target area to immobilize; Enhanced Chain knocks enemies airborne."}}, "source": "built-in (complex mechanics)"}""",
        "Fanny" to """{"passive": {"description": "Deals extra damage while flying and applies Prey Marks; restores energy when hitting marked heroes."}, "passive_rules": ["AFTER_DASH_BONUS", "STACKS"], "skills": {"s1": {"name": "Tornado Strike", "type": "area", "range": "short", "cc": "none", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "tap", "hold_seconds": 0, "cooldown_seconds": 3.2, "description": "Spins her blades to deal physical damage to nearby enemies."}, "s2": {"name": "Steel Cable", "type": "dash", "range": "long", "cc": "none", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 0.3, "description": "Throws a cable that pulls Fanny to the first wall hit; each successive cable within 2s reduces energy cost."}, "ult": {"name": "Cut Throat", "type": "target", "range": "medium", "cc": "none", "mobility": true, "shield": false, "heal": false, "damage": "high", "cast": "tap", "hold_seconds": 0, "cooldown_seconds": 22.0, "description": "Initiates a quick attack on an enemy, dealing massive extra damage per Prey Mark stack."}}, "source": "built-in (complex mechanics)"}""",
        "Gusion" to """{"passive": {"description": "Every skill cast adds a rune to Gusion dagger; stacking runes empowers the next basic attack to deal missing-HP damage and heal."}, "passive_rules": ["WEAVE_ATTACK", "EXECUTE_BONUS"], "skills": {"s1": {"name": "Sword Spike", "type": "skillshot", "range": "medium", "cc": "none", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 7.0, "description": "Throws a dagger to mark the first target hit; recast to teleport behind the marked target."}, "s2": {"name": "Dagger Specialist", "type": "direction", "range": "medium", "cc": "slow", "mobility": false, "shield": false, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 9.0, "description": "Throws 5 daggers in a fan; recast to recall all daggers back to Gusion."}, "ult": {"name": "Incandescence", "type": "dash", "range": "medium", "cc": "none", "mobility": true, "shield": false, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 18.0, "description": "Dashes to target location and immediately resets the cooldowns of Sword Spike and Dagger Specialist; can be recast once for a short dash."}}, "source": "built-in (complex mechanics)"}""",
        "Ling" to """{"passive": {"description": "Lightness skill allows leaping between walls and gaining extra crit chance."}, "passive_rules": ["AFTER_DASH_BONUS", "WEAVE_ATTACK"], "skills": {"s1": {"name": "Finch Poise", "type": "dash", "range": "medium", "cc": "none", "mobility": true, "shield": false, "heal": false, "damage": "low", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 9.0, "description": "Leaps onto a designated wall and enters half-stealth."}, "s2": {"name": "Defiant Sword", "type": "dash", "range": "medium", "cc": "slow", "mobility": true, "shield": false, "heal": true, "damage": "medium", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 2.5, "description": "Charges in a direction and stabs nearby enemies, triggering attack effects."}, "ult": {"name": "Tempest of Blades", "type": "area", "range": "medium", "cc": "hard", "mobility": true, "shield": false, "heal": false, "damage": "high", "cast": "aim", "hold_seconds": 0, "cooldown_seconds": 36.0, "description": "Leaps into the air for 1.5s then lands to knock enemies airborne and spawn 4 Field Eyes (Four Blades); touching each blade resets Defiant Sword cooldown."}}, "source": "built-in (complex mechanics)"}""",
        "Mathilda" to """{"passive": {"description": "Wisps gather when walking and hit the next target."}, "passive_rules": ["MOVE_SPEED"], "skills": {"s1": {"name": "Ancestral Guidance", "type": "area", "range": "medium", "cc": "none", "mobility": false, "shield": false, "heal": false, "damage": "medium", "cast": "tap", "hold_seconds": 0, "description": "Ancestral Guidance"}, "s2": {"name": "Guiding Wind", "type": "self", "range": "short", "cc": "none", "mobility": true, "shield": true, "heal": false, "damage": "medium", "cast": "tap", "hold_seconds": 0, "description": "Guiding Wind"}, "ult": {"name": "Circling Eagle", "type": "dash", "range": "medium", "cc": "none", "mobility": true, "shield": true, "heal": false, "damage": "medium", "cast": "aim", "hold_seconds": 0, "description": "Circling Eagle"}}, "source": "built-in (check the current patch)"}""",
    )

    @Synchronized
    fun get(hero: String?): HeroSkills {
        val h = hero ?: "unknown"
        cache[h]?.let { return it }
        // (skills an AI wrote from memory are not used: the built-in seed or the role's defaults instead)
        val saved = Cfg.raw("skills_${key(h)}")?.takeIf { !it.contains("model knowledge") }?.let { try { fromJson(h, JSONObject(it)) } catch (e: Exception) { null } }
            ?: SEEDS[h]?.let { try { fromJson(h, JSONObject(it)) } catch (e: Exception) { null } }
        val v = saved ?: defaults(h)
        cache[h] = v
        return v
    }

    // Safe, non-recursive transform check. Do not call get() here: defaults() asks
    // HeroDb for a profile, which asks HeroKit for its transform state.
    fun hasTransformUlt(hero: String): Boolean {
        cache[hero]?.get("ult")?.let { return it.kind == SkillKind.TRANSFORM }
        val raw = Cfg.raw("skills_${key(hero)}")?.takeIf { !it.contains("model knowledge") }
            ?: SEEDS[hero]
            ?: return false
        return runCatching {
            val ult = JSONObject(raw).optJSONObject("skills")?.optJSONObject("ult") ?: return@runCatching false
            val type = ult.optString("type", "").uppercase()
            val desc = ult.optString("description", "").lowercase()
            type == "TRANSFORM" || listOf("transform", "transforms", "switches form", "switch form", "turns into", "form for", "changes form", "switches between").any { it in desc }
        }.getOrDefault(false)
    }

    fun known(hero: String?): Boolean = hero != null && Cfg.raw("skills_${key(hero)}") != null && !unverified(hero) && !incomplete(hero)

    // ---- what the execution learned (per hero and skill) ----
    fun castMode(hero: String?, slot: String): CastMode {
        // (learned per form for transforming heroes: the transformed skill may cast differently)
        val f = HeroKit.formSuffix(hero)
        val learned = hero?.let { Cfg.raw("castmode_${key(it)}_$slot$f") ?: Cfg.raw("castmode_${key(it)}_$slot") }?.let { runCatching { CastMode.valueOf(it) }.getOrNull() }
        val own = get(hero)[slot]?.mode ?: CastMode.TAP
        // an old guess of "hold" (it blocked every other touch while charging) is ignored unless the
        // skill's description says hold
        if (learned == CastMode.HOLD && own != CastMode.HOLD) return own
        return learned ?: own
    }
    fun setCastMode(hero: String?, slot: String, m: CastMode) { if (hero != null) Cfg.setRaw("castmode_${key(hero)}_$slot${HeroKit.formSuffix(hero)}", m.name) }
    fun aimLead(hero: String?, slot: String): Float = hero?.let {
        val f = HeroKit.formSuffix(it)
        (Cfg.raw("aimlead_${key(it)}_$slot$f") ?: Cfg.raw("aimlead_${key(it)}_$slot"))?.toFloatOrNull() } ?: 1f
    fun setAimLead(hero: String?, slot: String, v: Float) { if (hero != null) Cfg.setRaw("aimlead_${key(hero)}_$slot${HeroKit.formSuffix(hero)}", "%.2f".format(java.util.Locale.US, v.coerceIn(0f, 2f))) }
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
            slot == "ult" && has(t, "transform", "transforms", "switches form", "switch form", "turns into", "form for", "changes form", "switches between") -> SkillKind.TRANSFORM
            dashWords -> SkillKind.DASH
            has(t, "first enemy hit", "first enemy it hits", "first target hit", "first enemy unit", "stops when it hits", "skillshot") -> SkillKind.SKILLSHOT
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
        val hardCc = has(t, "stun", "knock", "airborne", "suppress", "taunt", "freeze", "frozen", "petrif", "immobil", "root", "bind", "pull", "silence", "sleep", "kick", "launch", "grab", "charm", "fear", "trap", "imprison", "petrify")
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
            when { hold -> CastMode.HOLD; kind == SkillKind.DIRECTION || kind == SkillKind.SKILLSHOT || kind == SkillKind.AREA || kind == SkillKind.DASH -> CastMode.AIM; else -> CastMode.TAP },
            holdMs, if (kind == SkillKind.DIRECTION || kind == SkillKind.SKILLSHOT) 0.35f else if (kind == SkillKind.AREA) 0.5f else 0.15f, desc,
            // "Cooldown: 40s" / "cooldown 12 seconds" in the text (the first number: level 1)
            Regex("cooldown[^0-9]{0,12}(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(desc)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
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
    // Pure self-buff / instant target-lock skills that use CastMode.TAP by default; all other skills default to CastMode.AIM
    private val tapSlotsByHero: Map<String, Set<String>> = mapOf(
        "Miya" to setOf("s1", "ult"), "Hanabi" to setOf("s1"), "Lesley" to setOf("s1", "ult"),
        "Karrie" to setOf("ult"), "Irithel" to setOf("s2"), "Claude" to setOf("ult"), "Wanwan" to setOf("s2", "ult"),
        "Zilong" to setOf("s1", "s2", "ult"), "Alucard" to setOf("s2"), "Balmond" to setOf("s2"),
        "Saber" to setOf("s1", "ult"), "Karina" to setOf("s1", "s2", "ult"), "Natalia" to setOf("s2", "ult"),
        "Eudora" to setOf("s2", "ult"), "Aurora" to setOf("s2"), "Cyclops" to setOf("s2", "ult"),
        "Odette" to setOf("ult"), "Alice" to setOf("s2", "ult"), "Uranus" to setOf("s1", "ult"),
        "Hylos" to setOf("s1", "s2"), "Belerick" to setOf("s2", "ult"), "Gatotkaca" to setOf("s2"),
        "Rafaela" to setOf("s1", "s2"), "Estes" to setOf("s1", "ult"), "Floryn" to setOf("s1", "ult"),
        "Angela" to setOf("ult"), "Diggie" to setOf("ult"), "Faramis" to setOf("s1", "ult"),
        "Carmilla" to setOf("s1", "s2"), "Kaja" to setOf("s1", "ult"), "Argus" to setOf("ult"),
        "Masha" to setOf("s1", "ult"), "Aulus" to setOf("s1"), "Hilda" to setOf("s1", "s2", "ult"),
        "Sun" to setOf("ult"), "Freya" to setOf("ult"), "Aldous" to setOf("s1", "s2")
    )

    private fun defaults(hero: String): HeroSkills {
        val role = HeroDb.role(hero)
        val dash = HeroDb.dashSlot(hero)
        val base = HeroDb.baseProfile(hero)
        val tapSet = tapSlotsByHero[hero] ?: emptySet()
        val m = HashMap<String, SkillInfo>()
        for (slot in HeroKit.slots(hero)) {
            val isDash = slot == dash
            val isTap = !isDash && slot in tapSet
            val kind = when {
                isDash -> SkillKind.DASH
                isTap -> SkillKind.TARGET
                role == Role.MAGE || role == Role.MARKSMAN -> SkillKind.DIRECTION
                else -> SkillKind.AREA
            }
            val range = when {
                isDash -> 0.36f
                role == Role.MAGE || role == Role.MARKSMAN -> if (slot == "ult") 0.52f else 0.44f
                else -> base.engageRange * 0.95f
            }
            m[slot] = SkillInfo(slot, slot, kind, range,
                slot == "ult" && (role == Role.TANK || role == Role.SUPPORT), false, isDash, false, false,
                if (slot == "ult") 0.35f else 0.16f,
                if (isTap) CastMode.TAP else CastMode.AIM, 0L, 0.25f, "")
        }
        return HeroSkills(hero, m, setOf(Passive.WEAVE_ATTACK), "", "role defaults (v37.07)")
    }

    private fun fromJson(hero: String, o: JSONObject): HeroSkills {
        val role = HeroDb.role(hero)
        val sk = o.optJSONObject("skills") ?: JSONObject()
        val m = HashMap<String, SkillInfo>()
        for (slot in HeroKit.ALL) {
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
                if (hold > 0) hold else 1000L, j.optDouble("travel_seconds", p.travelSec.toDouble()).toFloat().coerceIn(0f, 1.5f), desc,
                j.optDouble("cooldown_seconds", p.cooldownSec.toDouble()).toFloat().coerceIn(0f, 300f))
        }
        val pj = o.optJSONObject("passive")
        val pname = pj?.optString("name", "").orEmpty()
        val pdesc = pj?.optString("description", "") ?: o.optString("passive", "")
        val ptext = if (pname.isNotBlank() && pdesc.isNotBlank()) "$pname - $pdesc" else pdesc
        val rules = parsePassive(ptext).toMutableSet()
        o.optJSONArray("passive_rules")?.let { a -> for (i in 0 until a.length()) runCatching { rules.add(Passive.valueOf(a.optString(i).uppercase())) } }
        val d = defaults(hero)
        // only the hero's real buttons (2, 3 or 4 active skills)
        val present = HeroKit.slots(hero)
        return HeroSkills(hero, (d.skills + m).filterKeys { it in present }, rules, ptext, o.optString("source", "online"))
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
        if (!o.has("at")) o.put("at", System.currentTimeMillis())
        Cfg.setRaw("skills_${key(hero)}", o.toString())
        synchronized(this) { cache.remove(hero) }
        HeroKit.forgetCache()
        com.example.mlbbresearch.learning.OnlineBackup.changed()      // also kept in the folder
    }

    // skills read back from the folder: parse them again
    @Synchronized
    fun forgetCache() = cache.clear()

    fun describe(hero: String?): String {
        val k = get(hero)
        val sk = HeroKit.ALL.mapNotNull { k[it] }.joinToString("; ") { s ->
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

    suspend fun fetchNow(hero: String): String {
        // REAL skills only: from the hero's wiki page (read on the phone). The model only copies them
        // into the format below, and every name it gives is checked against the page.
        val wiki = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WikiSkills.page(hero) }?.let { WikiSkills.skillsPart(it) }
            ?.takeIf { it.length > 200 }
        val format = "Reply with ONLY one JSON object, no other words:\n" +
            "{\"active_skills\":3,\"passive\":{\"name\":\"\",\"description\":\"\"},\"skills\":{\"s1\":{\"name\":\"\",\"description\":\"\"," +
            "\"type\":\"target|direction|skillshot|area|self|dash|transform\",\"range\":\"short|medium|long|global\",\"cc\":\"none|slow|hard\"," +
            "\"mobility\":true/false,\"shield\":true/false,\"heal\":true/false,\"damage\":\"low|medium|high\"," +
            "\"cast\":\"tap|aim|hold\",\"hold_seconds\":0,\"travel_seconds\":0.3,\"cooldown_seconds\":0},\"s2\":{...same...},\"s3\":{...same, ONLY for a 4th active skill...},\"ult\":{...same...}}," +
            "\"passive_rules\":[\"any of: WEAVE_ATTACK (enhanced basic attack after a skill), STACKS (stacks on hits), " +
            "LOW_HP_POWER (stronger at low HP), OUT_OF_COMBAT_REGEN, BUSH_BONUS, EXECUTE_BONUS (bonus vs low HP enemies), " +
            "AFTER_DASH_BONUS, SHIELD, MOVE_SPEED\"]}\n" +
            "cast: tap = quick cast on the nearest target, aim = drag to aim a direction or area, hold = hold to charge then release.\n" +
            "type: skillshot = a projectile aimed in a direction that stops on the first enemy hit; transform = the skill changes the " +
            "hero's form or skills (e.g. Roger, Selena).\n" +
            "active_skills = how many ACTIVE skills (not the passive) the hero has: usually 3 (s1, s2, ult); some heroes have 4 " +
            "(s1, s2, s3, ult: s3 is the third non-ultimate skill); some only 2 and no ultimate (give them as s1 and s2, leave ult out). " +
            "Wiki headings: \"Ultimate 1\" and \"Ultimate 2\" (e.g. Fredrinn) = 4 skills: s3 = Ultimate 1, ult = Ultimate 2. A \"Special\" / " +
            "extra skill heading (e.g. Chip) = 4 skills: s3 = the Ultimate (3rd button), ult = the Special skill (4th button). " +
            "Every hero has exactly one passive with its own name: always fill it in. Copy the names EXACTLY as written; " +
            "copy the descriptions from the source (shorten only if over 400 characters). NEVER invent a skill: if one is not " +
            "in the source, leave its name empty."
        // no wiki page = nothing saved (never an AI's memory, never an unchecked web answer); the
        // Library tries again on its next run
        if (wiki == null) throw IllegalStateException("$hero's wiki page could not be read (no internet, or the page has another name): nothing saved, tried again next time")
        val prompt = "Below is the skills section of the Mobile Legends: Bang Bang wiki page of the hero $hero. Using ONLY this text, " +
            "give the hero's passive and skills. $format\n\n--- WIKI TEXT ---\n$wiki"
        val (text, _) = GroqClient.ask(prompt, search = false)
        val o = GroqClient.json(text) ?: throw IllegalStateException("answer was not JSON: ${text.take(80)}")
        // the check: every name must be written in the wiki text; a name that is not = invented, dropped
        val sk = o.optJSONObject("skills") ?: JSONObject()
        val dropped = ArrayList<String>()
        // how many active skills: 2 (s1 s2), 3 (s1 s2 ult) or 4 (s1 s2 s3 ult)
        val n0 = o.optInt("active_skills", 3).coerceIn(2, 4)
        val expected = when (n0) { 2 -> listOf("s1", "s2"); 4 -> listOf("s1", "s2", "s3", "ult"); else -> listOf("s1", "s2", "ult") }
        for (slot in HeroKit.ALL) {
            val n = sk.optJSONObject(slot)?.optString("name").orEmpty()
            if (slot !in expected) { sk.remove(slot); continue }
            if (!WikiSkills.nameIn(n, wiki)) { sk.remove(slot); dropped.add(if (n.isBlank()) slot else "$slot \"$n\"") }
        }
        o.put("slots", org.json.JSONArray(expected))
        val pn = o.optJSONObject("passive")?.optString("name").orEmpty()
        if (!WikiSkills.nameIn(pn, wiki)) { o.remove("passive"); dropped.add(if (pn.isBlank()) "passive" else "passive \"$pn\"") }
        if (dropped.size >= 3) throw IllegalStateException("the answer did not match $hero's wiki page (${dropped.joinToString()}): nothing saved")
        o.put("skills", sk)
        o.put("source", if (dropped.isEmpty()) "wiki (checked)" else "wiki (checked; ${dropped.joinToString()} not found)")
        save(hero, o)
        Diag.info("Skills learned for $hero from the wiki" + (if (dropped.isEmpty()) "" else " (dropped as not on the page: ${dropped.joinToString()})"))
        return describe(hero)
    }

    // skills an AI wrote from its memory (before v36.53) are not trusted: they count as not known,
    // so the Library learns them again from the wiki
    fun unverified(hero: String?): Boolean = hero != null && Cfg.raw("skills_${key(hero)}")?.contains("model knowledge") == true
    // a wiki answer that had a skill dropped (its name was not on the page) is asked again next time
    private fun incomplete(hero: String): Boolean = Cfg.raw("skills_${key(hero)}")?.contains("not found)") == true

    // the hero you play: learn its skills once (Groq key needed)
    private val tried = HashMap<String, Long>()
    fun autoFetch(hero: String?) {
        if (hero == null || !com.example.mlbbresearch.core.Cfg.autoOnline || known(hero) || !GroqClient.ready || busy.isNotEmpty()) return
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
                // Groq resting after its rate limit: wait for it instead of skipping heroes
                while (allRunning && GroqClient.limited) {
                    learnAllProgress = "${i + 1}/${names.size}: waiting for Groq (${GroqClient.waitText()})"
                    delay(5000)
                }
                if (!allRunning) break
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
