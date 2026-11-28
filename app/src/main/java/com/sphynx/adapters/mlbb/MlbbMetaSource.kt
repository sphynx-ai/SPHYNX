package com.sphynx.adapters.mlbb

/**
 * PHASE 13, step D3 (roadmap 13.2): where MLBB meta numbers come from, behind a replaceable provider layer, with an
 * honest state for each source. The official pick, win and ban statistics may come only from the official rank page;
 * that page loads its numbers from an undocumented API (docs/PHASE_13_MLBB_PLAN.md section 2), so the official provider
 * reports UNAVAILABLE with the reason and the page link, and never invents a number. The app's own interpretation (the
 * online meta update) is a separate source, always labelled "app-generated, not official statistics", with its age.
 * Pure, unit-tested.
 */
object MlbbMetaSource {

    const val OFFICIAL_RANK_PAGE = "https://www.mobilelegends.com/rank"
    const val STALE_DAYS = 14.0

    enum class State(val label: String) { AVAILABLE("available"), STALE("out of date"), MISSING("nothing yet"), UNAVAILABLE("not available to the app") }

    /** One source's state, as the meta page shows it. [official] separates source-reported numbers from interpretation. */
    data class Status(val source: String, val official: Boolean, val state: State, val reason: String, val link: String?,
                      val period: String? = null, val lastRefreshMs: Long? = null, val heroes: Int = 0)

    interface Provider { fun status(nowMs: Long): Status }

    /** The official rank page: unavailable until a documented or permitted access exists (owner decision, plan section 6). */
    object OfficialRank : Provider {
        override fun status(nowMs: Long) = Status("Official MLBB rank page", official = true, state = State.UNAVAILABLE,
            reason = "the page loads its pick, win and ban rates from an undocumented API; Sphynx does not scrape it. Open the official page to see them.",
            link = OFFICIAL_RANK_PAGE)
    }

    /** The app's own interpretation from the online meta update: how many heroes it covers and how old the newest is. */
    fun appGenerated(heroes: Int, newestMs: Long?, patch: String?, nowMs: Long): Status {
        if (heroes == 0 || newestMs == null) return Status("App-generated meta (not official statistics)", false, State.MISSING,
            "no online meta update yet", null)
        val days = (nowMs - newestMs) / 86_400_000.0
        val st = if (days > STALE_DAYS) State.STALE else State.AVAILABLE
        return Status("App-generated meta (not official statistics)", false, st,
            String.format(java.util.Locale.US, "%d heroes; newest %.0f days old%s", heroes, days, if (st == State.STALE) " (over $STALE_DAYS days: out of date)" else ""),
            null, patch?.let { "patch $it" }, newestMs, heroes)
    }

    /** The line the meta page shows for a source. */
    fun line(s: Status): String = "${s.source}: ${s.state.label} — ${s.reason}" + (s.period?.let { " · $it" } ?: "")
}
