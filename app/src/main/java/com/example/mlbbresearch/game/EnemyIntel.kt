package com.example.mlbbresearch.game

import com.example.mlbbresearch.vision.Track
import kotlin.math.hypot

// KNOWING THE ENEMY: what the enemy heroes can do and what they just did.
//  - THE ENEMY TEAM (read on the draft / loading screen): each hero's skills are learned like your
//    own (SkillBook: online, else role defaults), so the app knows their crowd control and reach,
//    their dashes, and which skill each will most likely open with (its playbook: opener, crowd
//    control, finisher, ultimate rule)
//  - WHAT THEY JUST DID (on screen): an enemy that moved far faster than walking just used a dash /
//    blink / Flicker - for a few seconds it cannot use it again (a good moment to fight it: it
//    cannot escape), and if it came AT you it is engaging (step aside, or out of its reach)
// The screen does not say which enemy on screen is which hero (no names over their bars), so the
// reach to respect is the team's: the longest crowd-control reach among the enemy heroes.
object EnemyIntel {

    private var lastFetch = 0L
    @Volatile var status = "-"
        private set

    // called by the slow loop: learn the enemy heroes' skills, one at a time (the free Groq tier)
    fun learnEnemies() {
        if (!com.example.mlbbresearch.core.Cfg.autoOnline) return
        val now = System.currentTimeMillis()
        if (now - lastFetch < 60_000) return
        val todo = GameStateTracker.enemyTeam.firstOrNull { !SkillBook.known(it) } ?: return
        lastFetch = now
        SkillBook.autoFetch(todo)
    }

    // the longest reach of the enemy team's crowd control (screen heights); stay outside it when
    // you are not fighting. Role defaults until their skills are learned.
    fun dangerRange(): Float {
        val team = GameStateTracker.enemyTeam
        if (team.isEmpty()) return 0.35f
        return team.maxOf { h ->
            val cc = SkillBook.get(h).skills.values.filter { it.hardCc }.maxOfOrNull { it.range } ?: 0.3f
            // Flicker carries their crowd control further (blink in, then the stun)
            cc + if (GameStateTracker.enemySpells[h] == "Flicker") 0.1f else 0f
        }.coerceIn(0.25f, 0.65f)
    }

    // what an enemy's battle spell means for you (read on the loading screen / scoreboard)
    fun spellNote(spell: String?): String = when (spell) {
        "Flicker" -> "can blink in or out"
        "Execute" -> "finishes low-HP heroes: stay above ~25% near it"
        "Retribution" -> "their jungler: steals buffs, Turtle, Lord"
        "Petrify" -> "stuns everyone around it"
        "Vengeance" -> "reflects damage: do not burst into it while it glows"
        "Aegis" -> "shield at low HP: harder to finish"
        "Purify" -> "breaks crowd control"
        "Sprint" -> "fast chase / escape"
        "Inspire" -> "attack-speed burst"
        "Flameshot" -> "long-range finisher, pushes you back"
        "Revitalize" -> "heals itself and allies"
        "Arrival" -> "teleports to a tower / minion"
        null -> "spell not read"
        else -> spell
    }

    fun dashedRecently(t: Track, now: Long) = t.dashAt > 0 && now - t.dashAt in 0..6000

    // an enemy dashed AT you in the last half second (its speed points at you)
    fun dashAtMe(s: com.example.mlbbresearch.game.GameState): Track? {
        val self = s.self ?: return null
        return s.enemies.firstOrNull { e ->
            e.dashAt > 0 && s.ts - e.dashAt in 0..500 && s.distToSelf(e.x, e.y) < 0.6f &&
                ((self.x - e.x) * e.vx * s.aspect * s.aspect + (self.y - e.y) * e.vy) > 0f
        }
    }

    // for the Player page: each enemy hero, what it will most likely use, and its reach
    fun text(): String {
        val team = GameStateTracker.enemyTeam
        if (team.isEmpty()) return "Enemy team: not read yet (read on the loading screen and the scoreboard; needs the Gemma model)."
        val sb = StringBuilder("Enemy team (crowd-control reach to respect: ${(dangerRange() * 100).toInt()}% of the screen):")
        for (h in team) {
            val kit = SkillBook.get(h)
            val pb = HeroPlaybook.of(h)
            val cc = kit.skills.values.filter { it.hardCc }.joinToString { it.slot }
            val sp = GameStateTracker.enemySpells[h]
            sb.append("\n• $h (${HeroDb.role(h).name.lowercase()}" + (sp?.let { ", $it: ${spellNote(it)}" } ?: "") +
                "): opens with ${pb.opener ?: "-"}, crowd control ${cc.ifEmpty { "none" }}" +
                (kit.escape?.let { ", dash ${it.slot}" } ?: "") + ", ultimate ${pb.ult.name.lowercase()}" +
                (if (!pb.learned) " (role defaults: skills not learned yet)" else ""))
        }
        return sb.toString()
    }
}
