package com.sphynx.ai

import com.sphynx.game.GameState

// THE GAME'S OWN QUESTIONS FOR THE STRATEGIC MODEL (Phase 2 adapter split, step 5b): StrategicAdvisor schedules
// the questions, asks them and applies the answers; WHAT it asks about a screen (the game's name, its draft,
// loading cards, battle spells, buttons...) and how a name in the answer maps to the game's heroes and spells
// comes from here. Mobile Legends' is adapters/mlbb/MlbbStrategy, set by the app at start (app/ProbesWiring).
interface StrategyGame {
    /** The in-match plan question (compact, JSON answer). */
    fun strategyPrompt(s: GameState, mapSummary: String): String
    /** The draft-screen question. */
    fun draftPrompt(ranked: Boolean, solo: Boolean, onlinePick: List<String>?, onlineBan: List<String>?, myTeam: List<String>): String
    /** Heroes to suggest when the model suggested none that are free: the missing role's meta picks, then defaults. */
    fun fallbackPicks(myTeam: List<String>, taken: Set<String>): List<String>
    /** The player's in-game name ("" when not set). */
    fun playerName(): String
    /** The line-up question (loading screen or scoreboard) and its reading. */
    fun lineupPrompt(loading: Boolean): String
    fun parseLineup(reply: String, loading: Boolean, green: Pair<Int, Int>?): com.sphynx.game.PlayerName.Lineup?
    /** "Which hero did I pick" on the loading screen (null when nothing identifies my card). */
    fun identifyPrompt(name: String, green: Pair<Int, Int>?): String
    fun buildPrompt(hero: String, enemies: List<String>): String
    val screenReadPrompt: String
    val matchModePrompt: String
    fun liveClockPrompt(needHero: Boolean): String
    /** A hero / spell name in an answer, matched to the game's own (null when none). */
    fun matchHero(raw: String?): String?
    fun matchSpell(raw: String?): String?
    /** The usual lane of a hero (its key, e.g. "exp"). */
    fun usualLane(hero: String): String
    /** The Build page's text (the game's builds, counters and online meta, plus the model's suggestion). */
    fun buildPageText(aiText: String): String

    /** No game: nothing is asked (every question is empty, nothing matches). */
    object NONE : StrategyGame {
        override fun strategyPrompt(s: GameState, mapSummary: String) = ""
        override fun draftPrompt(ranked: Boolean, solo: Boolean, onlinePick: List<String>?, onlineBan: List<String>?,
                                 myTeam: List<String>) = ""
        override fun fallbackPicks(myTeam: List<String>, taken: Set<String>) = emptyList<String>()
        override fun playerName() = ""
        override fun lineupPrompt(loading: Boolean) = ""
        override fun parseLineup(reply: String, loading: Boolean, green: Pair<Int, Int>?): com.sphynx.game.PlayerName.Lineup? = null
        override fun identifyPrompt(name: String, green: Pair<Int, Int>?) = ""
        override fun buildPrompt(hero: String, enemies: List<String>) = ""
        override val screenReadPrompt = ""
        override val matchModePrompt = ""
        override fun liveClockPrompt(needHero: Boolean) = ""
        override fun matchHero(raw: String?): String? = null
        override fun matchSpell(raw: String?): String? = null
        override fun usualLane(hero: String) = "exp"
        override fun buildPageText(aiText: String) = aiText
    }
}
