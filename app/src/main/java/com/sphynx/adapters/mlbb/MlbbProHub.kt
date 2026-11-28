package com.sphynx.adapters.mlbb

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * PHASE 13, step D7 (roadmap 13.7, 13.8): the Professional Match Hub's model and its evidence pipeline. Competitions
 * (MPL Philippines and the other MPL regions, M-Series, Esports World Cup, SEA Games), matches with every state a
 * schedule can have, local-time display, official watch links only, and the source and time of every record. The
 * pipeline turns completed games with a full draft into draft-level evidence for the D5 hypotheses: draft and result
 * only, never movement or timing the data does not hold. No live source is connected yet (plan section 6): the
 * [NotConnected] provider says so. Pure, unit-tested.
 */
object MlbbProHub {

    enum class Scope { REGIONAL, INTERNATIONAL }
    data class Competition(val id: String, val name: String, val scope: Scope, val region: String?)

    /** The competitions the hub groups matches by. More are added as a source lists them. */
    val COMPETITIONS = listOf(
        Competition("mpl-ph", "MPL Philippines", Scope.REGIONAL, "Philippines"),
        Competition("mpl-id", "MPL Indonesia", Scope.REGIONAL, "Indonesia"),
        Competition("mpl-my", "MPL Malaysia", Scope.REGIONAL, "Malaysia"),
        Competition("m-series", "M-Series World Championship", Scope.INTERNATIONAL, null),
        Competition("ewc", "Esports World Cup (MLBB)", Scope.INTERNATIONAL, null),
        Competition("sea-games", "SEA Games (MLBB)", Scope.INTERNATIONAL, null),
    )

    enum class State(val label: String) { UPCOMING("upcoming"), LIVE("live"), COMPLETED("completed"), DELAYED("delayed"),
        POSTPONED("postponed"), RESCHEDULED("rescheduled"), CANCELLED("cancelled") }

    data class Link(val label: String, val url: String, val official: Boolean)
    data class Draft(val picks: List<String>, val bans: List<String>)
    data class Game(val number: Int, val winner: String?, val patch: String?, val draftA: Draft?, val draftB: Draft?, val durationSec: Int?)
    data class Match(val id: String, val competition: String, val stage: String?, val teamA: String, val teamB: String,
                     val startUtcMs: Long?, val state: State, val scoreA: Int?, val scoreB: Int?, val games: List<Game>,
                     val watch: List<Link>, val source: String, val sourceLink: String?, val fetchedAtMs: Long)

    interface Source { val name: String; fun status(): String }
    /** No esports source is connected (owner decision pending, docs/PHASE_13_MLBB_PLAN.md section 6). */
    object NotConnected : Source {
        override val name = "none"
        override fun status() = "No esports source is connected yet: the hub can't show schedules or results until one is chosen (an official feed, or Liquipedia from the phone)."
    }

    /** "Sat 6 Dec, 17:00 (Asia/Manila)" in the phone's zone; "time not announced" without a time. */
    fun localTime(utcMs: Long?, zone: ZoneId): String = utcMs?.let {
        DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.US).withZone(zone).format(Instant.ofEpochMilli(it)) + " (${zone.id})"
    } ?: "time not announced"

    /** Only official watch links are shown (roadmap: official streams; embed only where permitted, else link). */
    fun watchLinks(m: Match): List<Link> = m.watch.filter { it.official }

    /** The hub's grouping: live first, then upcoming (soonest first), then changed schedules, then completed (newest first). */
    fun grouped(ms: List<Match>): List<Pair<State, List<Match>>> {
        val order = listOf(State.LIVE, State.UPCOMING, State.DELAYED, State.RESCHEDULED, State.POSTPONED, State.COMPLETED, State.CANCELLED)
        return order.mapNotNull { st ->
            val l = ms.filter { it.state == st }.let { if (st == State.COMPLETED) it.sortedByDescending { m -> m.startUtcMs ?: 0 } else it.sortedBy { m -> m.startUtcMs ?: Long.MAX_VALUE } }
            if (l.isEmpty()) null else st to l
        }
    }

    /** A team's five picks as one composition key ("Chou+Ling+…", sorted), the D5 hypothesis it supports. */
    fun compositionKey(d: Draft): String? = d.picks.takeIf { it.size == 5 }?.sorted()?.joinToString("+")

    /**
     * Draft-level evidence (roadmap 13.8): every completed game with a full draft and a winner becomes one record per side,
     * keyed by its composition. Games without a full draft, a winner or a patch are left out (incomplete), never guessed.
     */
    fun draftEvidence(ms: List<Match>): Map<String, List<MlbbStrategyEvidence.Match>> {
        val out = HashMap<String, MutableList<MlbbStrategyEvidence.Match>>()
        for (m in ms.filter { it.state == State.COMPLETED }) for (g in m.games) {
            val patch = g.patch ?: continue
            val winner = g.winner ?: continue
            for ((team, d) in listOf(m.teamA to g.draftA, m.teamB to g.draftB)) {
                val key = d?.let { compositionKey(it) } ?: continue
                out.getOrPut(key) { ArrayList() }.add(MlbbStrategyEvidence.Match("${m.id}#${g.number}:$team", m.source, m.sourceLink, patch,
                    m.startUtcMs ?: m.fetchedAtMs, team, m.competition, winner == team, complete = true))
            }
        }
        return out
    }
}
