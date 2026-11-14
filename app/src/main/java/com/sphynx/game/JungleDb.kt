package com.sphynx.game

import com.sphynx.vision.MinimapInfo

// THE JUNGLE (Mobile Legends wiki, Jungle + Battlefield Guide pages): every neutral monster of the
// current map, plus the turrets. "Blue buff" / "red buff" are old names: the buffs are now the
// PURPLE buff (Thunder Fenrir) and the ORANGE buff (Molten Fiend).
//
// HP bars: ONLY the Lord shows its bar before it is attacked; every other monster (Turtle, buffs,
// camps) shows it only after it takes damage (from you, allies or enemies). Unhit monsters are
// found by the trained screen detector, or started with a basic attack at their camp / pit.
// How the app tells them apart on screen (measured on 20 screenshots, 2712 x 1220):
//   HP bar (red, orange frame, no level badge): full width 0.095+ of the screen, 5 rows thick and a
//   purple skull icon = Lord / Turtle; 0.078-0.095 = a buff; less = a common creep. (A hero's bar
//   is about 0.077 but always has a level badge; a turret's bar has a red emblem.)
//   Lord or Turtle: which pit you stand by on the minimap (Lord top-left, Turtle bottom-right).
//   Buffs: the body under the bar - orange (Molten Fiend, 21-33% orange) or blue / lavender
//   (Thunder Fenrir, 46-54% blue). Horned Lizard is blue too but has a small bar.
//   The other common creeps look too alike by colour: the trained screen detector tells them
//   apart (the training pictures label them "jungle_creep" until you fix the name in Roboflow).
enum class MonsterKind(val label: String) { BOSS("legend creep"), BUFF("elite creep"), CAMP("common creep"), RIVER("river creep") }

class Monster(
    val id: String,              // the class name in the screen detector / training pictures
    val name: String,
    val aliases: List<String>,
    val kind: MonsterKind,
    val look: String,            // what it looks like (for labelling pictures)
    val reward: String,          // what killing it gives (wiki)
    val gold: Int = 0,           // gold for the kill (wiki; 0 = not listed)
    val firstSec: Int = -1,      // first spawn (match time), -1 = from the start / not listed
    val respawnSec: Int = -1     // respawn after it is killed, -1 = not listed
)

object JungleDb {

    val LORD = Monster("lord", "Lord", listOf("Elemental Lord"), MonsterKind.BOSS,
        "a human-like giant holding a trident, in the Lord pit (by the Gold lane; from 8:00 - 9:00 it can take the Turtle's pit)",
        "the Lord comes back to life on your side and charges down the lane with the fewest / weakest turrets, " +
            "strengthens your minion waves in all three lanes, and charges the first turret it meets (true damage, " +
            "disables it for a few seconds). Gold and EXP for the team. 5v5 (wiki; in solo practice both are up from the start): first 2 min after the last Turtle " +
            "(or at 8:00 - 9:00 in the Turtle's place), back 180 s after its kill (120 s from 18:00)",
        firstSec = 480, respawnSec = 180)
    val TURTLE = Monster("turtle", "Turtle", listOf("Dragon Turtle"), MonsterKind.BOSS,
        "a giant turtle, in the Turtle pit (next to the EXP lane)",
        "the killer gets the Turtle Buff (a regenerating shield that adds Physical Attack and Magic Power); " +
            "allies get bonus gold and a one-time shield. 5v5 (wiki; in solo practice both are up from the start): 2:00, back 120 s after its kill, none after one " +
            "slain after 6:00, turns into the Lord at 8:00 - 9:00", firstSec = 120, respawnSec = 120)
    val MOLTEN_FIEND = Monster("molten_fiend", "Molten Fiend", listOf("orange buff", "red buff", "Fiery Hound"), MonsterKind.BUFF,
        "a big fire monster with orange flames, by a red cave",
        "the Orange Buff: attacks on enemy heroes deal extra true damage and slow them (every 3 s); " +
            "plus 5% (fighter / assassin / tank) or 10% (marksman / mage / support) adaptive penetration",
        gold = 120, firstSec = 30, respawnSec = 120)
    val THUNDER_FENRIR = Monster("thunder_fenrir", "Thunder Fenrir", listOf("purple buff", "blue buff", "Little Thunder Fenrir"), MonsterKind.BUFF,
        "a big purple / lavender wolf-dragon with a small cub beside it (a duo camp)",
        "the Purple Buff: cooldown reduction and lower mana / energy costs; each kill heals you " +
            "(minion 3%, hero 8%, creep 12%)", gold = 100, firstSec = 30, respawnSec = 120)
    val LAVA_GOLEM = Monster("lava_golem", "Lava Golem", emptyList(), MonsterKind.CAMP,
        "a human-like rock with glowing orange cracks", "the Healing Buff", gold = 81)
    val HORNED_LIZARD = Monster("horned_lizard", "Horned Lizard", emptyList(), MonsterKind.CAMP,
        "a blue lizard with a pink horned head", "the Healing Buff; it attacks from range and raises its defence when low", gold = 92)
    val FIRE_BEETLE = Monster("fire_beetle", "Fire Beetle", listOf("Crammer"), MonsterKind.CAMP,
        "a small red / magenta beetle; when killed it turns into a little Crammer (lives about 15 s)",
        "the Healing Buff; its Crammer gives the extra EXP that reaches level 4 in the first clear")
    // owner's notes (v37.19): spawns at 0:35 in the river by the mid lane, respawns every 120 s; never
    // attacks back and scurries away (so it is never "an enemy to chase"); its bar is thin. When killed it
    // splits into Walkie Grass (a green thing that FOLLOWS the killer: +15% speed in the river and 1% mana
    // per second for 45 s, also to nearby allies) and a Stone Roamer (a FRIENDLY patrol, blue segmented
    // bar, that reveals enemies around it for 45 s). Neither of the two is a target.
    val LITHOWANDERER = Monster("lithowanderer", "Lithowanderer", listOf("Stone Roamer", "Walkie Grass"), MonsterKind.RIVER,
        "a small walking green rock in the river by the mid lane (spawns 0:35, back every 120 s); it never attacks and runs away",
        "Walkie Grass follows the killer: +15% movement speed in the river and 1% mana per second for 45 s (nearby allies too); " +
            "the Stone Roamer is a friendly patrol that reveals enemies around it for 45 s", gold = 92)
    val CRAB = Monster("crab", "Scavenger Crab", listOf("crab", "Little Crab"), MonsterKind.RIVER,
        "a crab with a purple crystal shell and orange legs, by the river (a little crab first; upgraded at 2:00)",
        "the Gold Buff or EXP Buff (depends on the lane; 30 s). The EXP-lane crab gives the EXP of a siege minion", gold = 36)
    // a small monster whose kind the colour vision cannot tell (training pictures: fix the name)
    // a small monster whose kind the colour vision cannot tell (training pictures: fix the name)
    val CREEP = Monster("jungle_creep", "jungle monster", emptyList(), MonsterKind.CAMP, "any small jungle monster", "gold and EXP")

    // not a monster: a terrain feature (blue diamond on the minimap) that blinks you forward in the
    // direction you move. It keeps its own class in the screen detector.
    const val CYCLONE_EYE = "cyclone_eye"
    // what the minimap dots mean (wiki): purple dots = buff camps, yellow = crab (gold / EXP buff),
    // green = healing-buff camps, turquoise = Lithowanderer, blue diamonds = Cyclone Eyes,
    // purple paw = Turtle, purple cross = Lord

    val ALL = listOf(LORD, TURTLE, MOLTEN_FIEND, THUNDER_FENRIR, LAVA_GOLEM, HORNED_LIZARD, FIRE_BEETLE, LITHOWANDERER, CRAB)
    val IDS: Set<String> = (ALL + CREEP).map { it.id }.toSet()

    // (older names map on: fiery_hound was this app's first name for the Molten Fiend)
    fun byId(id: String?): Monster? = (ALL + CREEP).firstOrNull { it.id == id } ?: if (id == "fiery_hound") MOLTEN_FIEND else null
    fun isMonster(label: String) = label in IDS || label == "creep" || label == "buff" || label == "fiery_hound"
    fun isBoss(label: String) = label == "lord" || label == "turtle"
    fun isBuff(label: String) = label == MOLTEN_FIEND.id || label == THUNDER_FENRIR.id || label == "buff" || label == "fiery_hound"

    // ---- turrets ----
    // Outer turrets carry a gold energy shield (gold plating, yellow HP bar) early in the match:
    // "Outer Turret Energy Shield will disappear in 30s" shows at 4:30, so it ends at 5:00.
    const val OUTER_SHIELD_ENDS_SEC = 300
    val TURRET_CLASSES = listOf("tower_ally", "tower_ally_plated", "tower_enemy", "tower_enemy_plated", "base_ally", "base_enemy")
    fun isEnemyTurret(label: String) = label == "tower_enemy" || label == "tower_enemy_plated"

    // ---- from the HP bar ----
    const val BOSS_WIDTH = 0.095f
    const val BUFF_WIDTH = 0.078f

    fun kindOf(fullWidth: Float, thickRows: Boolean): MonsterKind = when {
        fullWidth >= BOSS_WIDTH || (thickRows && fullWidth >= 0.085f) -> MonsterKind.BOSS
        fullWidth >= BUFF_WIDTH -> MonsterKind.BUFF
        else -> MonsterKind.CAMP
    }

    // Lord or Turtle - by the KIND OF MATCH (owner, 2026-11-11; MatchKind):
    //  - SOLO PRACTICE (the owner's calibration mode): both are up from the start and the timers do not apply -
    //    the pit you stand next to decides (which pit is which follows MapSide);
    //  - 5v5 (the real data; the wiki's timers are right there): the Turtle spawns at 2:00 in the pit by the
    //    EXP lane, back 120 s after its kill, none after one slain after 6:00; a Turtle not slain by 8:00 turns
    //    into the Lord (at the latest 9:00) - so from 9:00 there is no Turtle and the Lord can stand in its pit.
    //    The Lord comes 2 min after the last Turtle (or replaces it at 8:00 - 9:00), in the pit by the Gold lane;
    //    back 180 s after its kill (120 s from 18:00);
    //  - not known yet: as 5v5, except that both pit icons on the minimap mean both are up (practice).
    // The minimap's pit icons decide first when only ONE boss shows.
    const val NO_TURTLE_SEC = 540
    fun boss(mini: MinimapInfo?, matchSec: Int, kind: MatchKind.Kind = MatchKind.current()): Monster {
        val solo = kind == MatchKind.Kind.SOLO
        if (!solo) {
            if (mini?.lordIcon != null && mini.turtleIcon == null) return LORD
            if (mini?.turtleIcon != null && mini.lordIcon == null) return TURTLE
            val bothShown = mini?.lordIcon != null && mini.turtleIcon != null
            if (matchSec >= NO_TURTLE_SEC && (kind == MatchKind.Kind.FIVE_V_FIVE || !bothShown)) return LORD
        }
        val me = mini?.self
        val LORD_PIT = MapSide.lordPit(); val TURTLE_PIT = MapSide.turtlePit()
        if (me != null) {
            val dl = kotlin.math.hypot(me.first - LORD_PIT.first, me.second - LORD_PIT.second)
            val dt = kotlin.math.hypot(me.first - TURTLE_PIT.first, me.second - TURTLE_PIT.second)
            if (kotlin.math.min(dl, dt) < 0.25f || solo) return if (dl < dt) LORD else TURTLE
        }
        return if (matchSec >= 480) LORD else TURTLE
    }

    // body colour shares under the bar -> species (null = cannot tell)
    fun buff(orange: Float, blue: Float): Monster? = when {
        orange >= 0.12f && orange > blue -> MOLTEN_FIEND
        blue >= 0.25f -> THUNDER_FENRIR
        else -> null
    }
    fun camp(orange: Float, blue: Float, green: Float): Monster = when {
        blue >= 0.22f -> HORNED_LIZARD
        else -> CREEP
    }

    fun describe(): String = ALL.joinToString("\n") { m ->
        "${m.name}" + (if (m.aliases.isNotEmpty()) " (${m.aliases.first()})" else "") + " - ${m.kind.label}: ${m.reward}" +
            (if (m.gold > 0) "; ${m.gold} gold" else "") + (if (m.firstSec > 0) "; first at %d:%02d".format(m.firstSec / 60, m.firstSec % 60) else "") +
            (if (m.respawnSec > 0) "; respawns after %d:%02d".format(m.respawnSec / 60, m.respawnSec % 60) else "")
    }
}
