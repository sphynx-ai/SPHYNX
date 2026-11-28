package com.sphynx.adapters.mlbb

import com.sphynx.game.JungleDb

/**
 * PHASE 13, step D6 (roadmap 13.5): recommended rotation concepts for the moment, checked against the app's own
 * objective timers (JungleDb, from the Wiki: Turtle at 2:00 and back 120 s after its kill; Lord from 8:00). Each plan
 * gives its opening, the lanes' responsibilities, target, timing window, objective priority, prerequisites, risks and
 * fallback. They are concepts to weigh, not universal rules: every plan says so, and none is "proven optimal".
 * Pure, unit-tested.
 */
object MlbbRotations {

    val TURTLE_FIRST = JungleDb.TURTLE.firstSec
    val TURTLE_RESPAWN = JungleDb.TURTLE.respawnSec
    val LORD_FIRST = JungleDb.LORD.firstSec

    data class Team(val engage: Int, val hasMarksman: Boolean, val splitPusher: Boolean, val magicJungler: Boolean)
    data class Moment(val matchSec: Int, val alliesDown: Int = 0, val enemiesDown: Int = 0, val turtleUp: Boolean? = null, val lordUp: Boolean? = null)

    data class Plan(val name: String, val window: String, val opening: String, val responsibilities: Map<String, String>,
                    val target: String, val objective: String, val prerequisites: List<String>, val risks: List<String>,
                    val fallback: String, val label: String = CONCEPT)

    const val CONCEPT = "a concept to weigh, not a universal rule"

    private fun mmss(s: Int) = "%d:%02d".format(s / 60, s % 60)

    /** The plans that apply to [m] for [t], most pressing first. */
    fun plans(t: Team, m: Moment): List<Plan> {
        val out = ArrayList<Plan>()
        val s = m.matchSec
        if (s < TURTLE_FIRST) {
            out.add(Plan("1-2-2 opening with a " + (if (t.magicJungler) "purple" else "orange") + " buff start", "0:00 – ${mmss(TURTLE_FIRST)}",
                "Jungler takes " + (if (t.magicJungler) "the purple buff (cooldown and mana) first" else "the orange buff (damage and slow) first") +
                    ", then the closest camps; the roamer helps the jungler's first buff, then gold lane",
                mapOf("Jungle" to "buff, camps, be at the turtle by ${mmss(TURTLE_FIRST - 15)}", "Roam" to "help the first buff, then gold lane",
                    "Mid" to "clear the wave, watch both river bushes", "Gold" to "farm safely", "EXP" to "hold the lane by the turtle pit"),
                "your own jungle", "first turtle at ${mmss(TURTLE_FIRST)}", listOf("lanes covered (1-2-2)", "the jungler has Retribution"),
                listOf("an enemy invade at the first buff"), "if invaded: give the buff, take the other side's camps"))
        }
        if (s in (TURTLE_FIRST - 30)..(LORD_FIRST - 1) && m.turtleUp != false) {
            out.add(Plan("Turtle set-up", "from ${mmss(maxOf(TURTLE_FIRST - 15, s))}; again ${TURTLE_RESPAWN} s after each kill",
                "Mid and roam push their waves, then join the jungler at the turtle pit (by the EXP lane)",
                mapOf("Jungle" to "secure with Retribution", "Roam" to "vision on the river bushes", "Mid" to "push first, then join",
                    "EXP" to "stay by the pit", "Gold" to "farm unless the enemy groups"),
                "turtle pit (EXP side)", "turtle", listOf("waves pushed in mid and EXP", "your jungler alive with Retribution"),
                listOf("the enemy jungler contests with Retribution", "a 5-man enemy collapse"), "if the enemy shows more heroes: let it go, take towers or camps instead"))
        }
        if (s >= LORD_FIRST - 30 && m.lordUp != false) {
            out.add(Plan("Lord after a won fight", "from ${mmss(LORD_FIRST)}", "Take the lord only when enemies are down or your team has the numbers",
                mapOf("All" to "group on the lord pit by the Gold lane", "Jungle" to "secure with Retribution", "Roam" to "vision first"),
                "lord pit (Gold side)", "lord, then push the lane it walks", listOf("${maxOf(1, 2 - m.alliesDown)}+ enemies more down than allies, or a clear numbers lead"),
                listOf("a steal by the enemy jungler", "being caught at half HP"), "no numbers lead: hold vision around the pit, wait for a pick"))
        }
        if (s >= 15 * 60) {
            if (t.splitPusher) out.add(Plan("1-3-1 split pressure", "late game", "Your split pusher takes a side lane, the other four hold mid",
                mapOf("Split pusher" to "push the side, leave before the enemy rotates", "Four" to "don't fight without the fifth"),
                "the side lane the enemy can't cover", "towers", listOf("a hero that clears waves fast and escapes", "vision on the enemy jungle"),
                listOf("the four get engaged while one is away"), "group as five when the enemy groups"))
            out.add(Plan("4-1 / deathball", "late game", "Group four or five, push one lane together after a pick or the lord",
                mapOf("All" to "stay together, follow the engage"), "one lane at a time", "base towers",
                listOf("a numbers lead or the lord", if (t.engage > 0) "your engage ready" else "a way to start fights (you have little engage)"),
                listOf("a bad engage loses the game", "chasing into the fog"), "back off and defend if the engage fails"))
        }
        if (m.alliesDown >= 2 && m.alliesDown > m.enemiesDown)
            out.add(0, Plan("Defend while allies are down", "now", "Stay under your towers until your allies are back",
                mapOf("All" to "clear waves under tower, no fights"), "your towers", "defend", listOf("allies down: ${m.alliesDown}"),
                listOf("diving into the enemy"), "if the enemy dives: fight at your tower"))
        return out
    }
}
