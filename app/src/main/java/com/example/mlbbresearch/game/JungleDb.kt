package com.example.mlbbresearch.game

import com.example.mlbbresearch.vision.MinimapInfo

// THE JUNGLE: every neutral monster of the current map, by the names the game uses, plus the
// three kinds of turrets. "Blue buff" / "red buff" are old names: the buffs are now the PURPLE
// buff (Thunder Fenrir) and the ORANGE buff (Fiery Hound).
//
// How the app tells them apart on screen (measured on 20 screenshots, 2712 x 1220):
//   HP bar (red, orange frame, no level badge): full width 0.095+ of the screen and 5 rows thick
//   = Lord / Turtle; 0.078-0.095 = a buff; less = a small camp or river monster. (A hero's bar is
//   about 0.077 but always has a level badge.)
//   Lord or Turtle: which pit you stand by on the minimap (Lord top-left, Turtle bottom-right).
//   Buffs: the body under the bar - orange (Fiery Hound, measured 21-33% orange) or blue /
//   lavender (Thunder Fenrir, 46-54% blue). Horned Lizard is blue too but has a small bar.
//   The other small camps look too alike by colour: the trained screen detector tells them apart
//   (the training pictures label them "jungle_creep" until you fix the name in Roboflow).
// Descriptions are from your screenshots and notes; the numbers of the wiki
// (mobile-legends.fandom.com/wiki/Jungle: HP, gold, spawn times) could not be read from here.
enum class MonsterKind(val label: String) { BOSS("objective"), BUFF("buff"), CAMP("jungle camp"), RIVER("river") }

class Monster(
    val id: String,              // the class name in the screen detector / training pictures
    val name: String,
    val aliases: List<String>,
    val kind: MonsterKind,
    val look: String,            // what it looks like (for labelling pictures)
    val reward: String           // what killing it gives
)

object JungleDb {

    val LORD = Monster("lord", "Lord", emptyList(), MonsterKind.BOSS,
        "a human-like giant holding a trident (blue body, gold armour), in the Lord pit",
        "the Lord joins your team and pushes a lane: the main way to break the enemy base")
    val TURTLE = Monster("turtle", "Turtle", emptyList(), MonsterKind.BOSS,
        "a giant turtle (blue shell with gold edges), in the Turtle pit",
        "gold and EXP for the whole team")
    val FIERY_HOUND = Monster("fiery_hound", "Fiery Hound", listOf("orange buff", "red buff"), MonsterKind.BUFF,
        "a big fire monster with orange flames, by a red cave",
        "the orange buff: your basic attacks burn and slow")
    val THUNDER_FENRIR = Monster("thunder_fenrir", "Thunder Fenrir", listOf("purple buff", "blue buff"), MonsterKind.BUFF,
        "a big purple / lavender wolf-dragon with a small cub beside it",
        "the purple buff: faster skill cooldowns and mana / energy regeneration")
    val LAVA_GOLEM = Monster("lava_golem", "Lava Golem", emptyList(), MonsterKind.CAMP,
        "a human-like rock with glowing orange cracks", "gold and EXP")
    val HORNED_LIZARD = Monster("horned_lizard", "Horned Lizard", emptyList(), MonsterKind.CAMP,
        "a blue lizard with a pink horned head", "gold and EXP")
    val FIRE_BEETLE = Monster("fire_beetle", "Fire Beetle", emptyList(), MonsterKind.CAMP,
        "a small red / magenta beetle with orange-tipped wings", "gold and EXP")
    val LITHOWANDERER = Monster("lithowanderer", "Lithowanderer", emptyList(), MonsterKind.RIVER,
        "a small walking green rock (green crystal top) in the river", "gold and EXP")
    val CRAB = Monster("crab", "Crab", emptyList(), MonsterKind.RIVER,
        "a crab with a purple crystal shell and orange legs, by the river", "gold and EXP")
    val CYCLONE_EYE = Monster("cyclone_eye", "Cyclone Eye", emptyList(), MonsterKind.RIVER,
        "(no screenshot yet)", "gold and EXP")
    // a small monster whose kind the colour vision cannot tell (training pictures: fix the name)
    val CREEP = Monster("jungle_creep", "jungle monster", emptyList(), MonsterKind.CAMP, "any small jungle monster", "gold and EXP")

    val ALL = listOf(LORD, TURTLE, FIERY_HOUND, THUNDER_FENRIR, LAVA_GOLEM, HORNED_LIZARD, FIRE_BEETLE, LITHOWANDERER, CRAB, CYCLONE_EYE)
    val IDS: Set<String> = (ALL + CREEP).map { it.id }.toSet()

    fun byId(id: String?): Monster? = (ALL + CREEP).firstOrNull { it.id == id }
    fun isMonster(label: String) = label in IDS || label == "creep" || label == "buff"     // old class names too
    fun isBoss(label: String) = label == "lord" || label == "turtle"
    fun isBuff(label: String) = label == FIERY_HOUND.id || label == THUNDER_FENRIR.id || label == "buff"

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

    // Lord or Turtle: the pit you stand next to on the minimap (else the pit icon, else the time)
    private val LORD_PIT = 0.31f to 0.26f
    private val TURTLE_PIT = 0.67f to 0.68f
    fun boss(mini: MinimapInfo?, matchSec: Int): Monster {
        val me = mini?.self
        if (me != null) {
            val dl = kotlin.math.hypot(me.first - LORD_PIT.first, me.second - LORD_PIT.second)
            val dt = kotlin.math.hypot(me.first - TURTLE_PIT.first, me.second - TURTLE_PIT.second)
            if (kotlin.math.min(dl, dt) < 0.25f) return if (dl < dt) LORD else TURTLE
        }
        if (mini?.lordIcon != null && mini.turtleIcon == null) return LORD
        if (mini?.turtleIcon != null && mini.lordIcon == null) return TURTLE
        return if (matchSec >= 480) LORD else TURTLE
    }

    // body colour shares under the bar -> species (null = cannot tell)
    fun buff(orange: Float, blue: Float): Monster? = when {
        orange >= 0.12f && orange > blue -> FIERY_HOUND
        blue >= 0.25f -> THUNDER_FENRIR
        else -> null
    }
    fun camp(orange: Float, blue: Float, green: Float): Monster = when {
        blue >= 0.22f -> HORNED_LIZARD
        else -> CREEP
    }

    fun describe(): String = ALL.joinToString("\n") { m ->
        "${m.name}" + (if (m.aliases.isNotEmpty()) " (${m.aliases.first()})" else "") + " - ${m.kind.label}: ${m.look}. ${m.reward}."
    }
}
