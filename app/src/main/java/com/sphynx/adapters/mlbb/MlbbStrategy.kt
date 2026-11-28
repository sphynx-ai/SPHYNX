package com.sphynx.adapters.mlbb

import com.sphynx.ai.StrategyGame
import com.sphynx.game.GameState
import com.sphynx.game.HeroDb
import com.sphynx.game.LaneLogic
import com.sphynx.game.MetaStore
import com.sphynx.game.PlayerName
import com.sphynx.game.Role
import com.sphynx.game.SpellDb

// Mobile Legends' questions for the strategic model (Phase 2 adapter split, step 5b). Every prompt is moved word for
// word from StrategicAdvisor, so the model is asked exactly what it was asked before.
object MlbbStrategy : StrategyGame {

    override fun strategyPrompt(s: GameState, mapSummary: String): String {
        val hero = s.ownHero ?: "unknown hero"
        val role = HeroDb.role(s.ownHero).name.lowercase()
        // Compact, low-token prompt for fast strategic reasoning response (v37.01)
        return "MLBB strategy for $hero ($role, ${s.lane.label}). " +
            "State: ${s.summary()}${mapSummary.let { if (it.isEmpty()) "" else "; map $it" }}. " +
            (if (s.matchSec >= 0) "Time ${s.matchSec / 60}:%02d. ".format(s.matchSec % 60) else "") +
            "Do not think; reply JSON only: " +
            "{\"plan\":\"farm|push|fight|retreat|group|defend\",\"aggression\":0.5,\"focus\":\"weakest|nearest\",\"note\":\"max 6 words\"}"
    }

    override fun draftPrompt(ranked: Boolean, solo: Boolean, onlinePick: List<String>?, onlineBan: List<String>?,
                             myTeam: List<String>): String {
        val mode = if (solo) "I am playing SOLO (no coordination): prefer heroes that can carry alone."
                   else "I am playing with a TEAM: prefer heroes that fit my team's composition."
        val known = myTeam
        val missing = Role.values().filter { r -> r != Role.UNKNOWN && known.none { HeroDb.role(it) == r } }
            .joinToString { it.name.lowercase() }
        val type = if (ranked) "This is a RANKED match with a ban stage and a pick stage. "
                   else "This is a STANDARD match: there is NO ban stage, each player just picks a hero, so leave ban empty. "
        val metaHint = if (onlinePick != null && onlineBan != null)
            "The current online meta recommends picking ${onlinePick.joinToString()} and banning ${onlineBan.joinToString()}: prefer these if available. "
        else MetaStore.general()?.let { g -> if (g.bans.isEmpty()) "" else "Strong heroes in the current patch: ${g.bans.joinToString()}. " } ?: ""
        return "This is a Mobile Legends: Bang Bang screenshot. $type$mode $metaHint" +
            (if (known.isNotEmpty()) "My team so far: ${known.joinToString()}. Roles still missing: $missing. " else "") +
            "Reply with ONLY one JSON object like this example, no other words: " +
            "{\"screen\":\"draft\",\"stage\":\"pick\",\"my_turn\":true,\"my_team\":[\"Layla\"],\"enemy_team\":[\"Tigreal\"]," +
            "\"my_hero\":null,\"ban\":[\"A\",\"B\",\"C\"],\"pick\":[\"A\",\"B\",\"C\"],\"tap\":{\"x\":50,\"y\":40},\"reason\":\"short\"} " +
            "screen is one of draft, loading, lobby, game, result, other. stage is ban, pick, waiting or none. " +
            "my_turn is true only if the game is asking ME to ban or pick right now. my_hero is the hero I locked in, or null. " +
            "ban: 3 strong heroes to ban. pick: 3 best heroes for me that counter the enemy picks. " +
            "tap: position of the first recommended hero's portrait as percent of the screen, or null if not visible."
    }

    override fun fallbackPicks(myTeam: List<String>, taken: Set<String>): List<String> {
        val need = Role.values().firstOrNull { r -> r != Role.UNKNOWN && myTeam.none { HeroDb.role(it) == r } }
        return ((need?.let { MetaStore.general()?.picks?.get(it) } ?: emptyList()) +
            (HeroDb.fallbackPicks[need] ?: emptyList())).distinct().filter { it !in taken }
    }

    override fun playerName() = PlayerName.get()
    override fun lineupPrompt(loading: Boolean) = PlayerName.lineupPrompt(loading)
    override fun parseLineup(reply: String, loading: Boolean, green: Pair<Int, Int>?) =
        PlayerName.parseLineup(reply, loading, green = green)

    override fun identifyPrompt(name: String, green: Pair<Int, Int>?): String {
        val where = "This is the LOADING screen: the cards are in random places. My card is the one whose player name is written in GREEN" +
            (if (green != null) " (card ${green.second + 1} from the left on the ${if (green.first == 0) "TOP" else "BOTTOM"} row)" else "") +
            (if (name.isNotBlank()) ", my player name \"$name\"" else "") + "; " +
            "my hero's name is written on it above my player name (a skin name, if any, is above the hero name - do not use it). " +
            "If no card has my player name, answer unknown."
        return "Mobile Legends: Bang Bang screenshot. $where " +
            "Reply with ONLY one JSON object, no other words: {\"hero\":\"my hero's name or unknown\",\"spell\":\"my battle spell name or unknown\"}"
    }

    override fun buildPrompt(hero: String, enemies: List<String>): String {
        val role = HeroDb.role(hero)
        return "Mobile Legends: Bang Bang. My hero is $hero (${role.name.lowercase()}). " +
            (if (enemies.isNotEmpty()) "Enemy heroes: ${enemies.joinToString()}. " else "") +
            "Reply with ONLY one JSON object, no other words: " +
            "{\"core\":[\"6 items in buying order\"],\"situational\":[\"2 items against these enemies\"],\"emblem\":\"emblem and talent\",\"note\":\"max 12 words\"}"
    }

    override val screenReadPrompt = "Mobile Legends in-game screenshot. Reply with ONLY one JSON object, no other words: " +
        "{\"hero\":\"name of the hero I control (green health bar near the centre) or unknown\"," +
        "\"spell\":\"the battle spell name written under the spell button (bottom middle, e.g. Retribution, Flicker)\"," +
        "\"items\":\"number of items I own 0-6, or -1\",\"gold\":\"my gold number, or -1\"}"

    override val matchModePrompt = "Mobile Legends: Bang Bang hero-select screenshot. Is this a RANKED draft with a ban stage " +
        "(slots for banned heroes, a 'Ban' prompt) or a STANDARD / classic pick with no bans? Reply with ONLY one word: ranked or standard."

    override fun liveClockPrompt(needHero: Boolean) = "Mobile Legends: Bang Bang screenshot of a live match. Read the match timer at the " +
        "top centre of the screen, between the two teams' kill counts. Reply with ONLY the time as mm:ss, for example 07:00." +
        (if (needHero) " Then, on a second line, the name of the hero the camera follows (in the centre of the screen; its " +
            "skills are shown at the bottom)." else "")

    override fun matchHero(raw: String?) = HeroDb.match(raw)
    override fun matchSpell(raw: String?) = SpellDb.match(raw)
    // its role: the usual lane of the hero (a Retribution hero is found jungling by its route)
    override fun usualLane(hero: String) = LaneLogic.effective(hero, null).first.name.lowercase()

    // Text for the Build page: primary build from your hero + counter items from the enemies (moved unchanged)
    override fun buildPageText(aiText: String): String {
        val hero = com.sphynx.game.GameStateTracker.ownHero()
        val role = HeroDb.role(hero)
        val sb = StringBuilder()
        sb.append("Your hero: ").append(hero ?: "unknown").append(" (").append(com.sphynx.game.GameStateTracker.ownHeroSource()).append(")")
        sb.append("\n\nPRIMARY build (").append(role.name.lowercase()).append("):\n")
        com.sphynx.game.BuildBook.primary(role).forEachIndexed { i, it -> sb.append("${i + 1}. $it\n") }
        val counters = com.sphynx.game.CounterRules.counters(hero, com.sphynx.game.GameStateTracker.enemyTeam)
        sb.append("\nSECONDARY / counter items")
        if (com.sphynx.game.GameStateTracker.enemyTeam.isEmpty()) {
            sb.append(": waiting for the enemy line-up (from the Drafter)\n")
        } else {
            sb.append(" vs ").append(com.sphynx.game.GameStateTracker.enemyTeam.joinToString()).append(":\n")
            if (counters.isEmpty()) sb.append("none needed, keep the primary build\n")
            counters.forEach { sb.append("- ${it.item}: ${it.reason}\n") }
        }
        MetaStore.hero(hero)?.let { m ->
            sb.append("\nONLINE META (").append(m.patch).append(", ").append("%.0f".format(MetaStore.ageDays(m.updatedAt))).append(" days old):\n")
            m.build.forEachIndexed { i, it -> sb.append("${i + 1}. $it\n") }
            // Phase 13 D2: emblems only from the Wiki, so the online meta's emblem is not given to the model
            if (m.spell != null) sb.append("Spell: ").append(m.spell).append('\n')
            if (m.counters.isNotEmpty()) sb.append("Countered by: ").append(m.counters.joinToString()).append('\n')
            if (m.changes.isNotBlank()) sb.append("Recent changes: ").append(m.changes).append('\n')
        }
        if (aiText.isNotBlank()) sb.append("\nGemma's suggestion:\n").append(aiText)
        return sb.toString().trim()
    }
}
