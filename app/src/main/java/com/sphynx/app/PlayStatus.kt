package com.sphynx.app

// The words of Play's two cards (Phase 3 screen specs, Play cards 1-2; backlog item 3): what runs, and what is
// missing before it can. Pure logic, so it is unit-tested; MainActivity draws it.
object PlayStatus {

    // the badge of "Running now"
    fun runningBadge(anyOn: Boolean, dryRun: Boolean): String = when {
        !anyOn -> "All off"
        dryRun -> "Dry run"
        else -> "Live"
    }

    // the one-word state under an engine's name. Dry run only matters to engines that touch the screen.
    fun engineWord(on: Boolean, dryRun: Boolean, touches: Boolean): String = when {
        !on -> "Off"
        dryRun && touches -> "On · Dry run"
        else -> "On"
    }

    // one line of the Ready check
    data class Item(val key: String, val label: String, val ok: Boolean, val okWord: String, val missingWord: String,
                    val needed: Boolean = true) {
        val word: String get() = if (ok) okWord else missingWord
        val needsFix: Boolean get() = !ok
    }

    // the Ready check's header: ready when every NEEDED item is ok; optional ones (models) are counted apart
    fun readySummary(items: List<Item>): String {
        val missing = items.count { it.needed && !it.ok }
        val optional = items.count { !it.needed && !it.ok }
        return when {
            missing == 0 && optional == 0 -> "Ready"
            missing == 0 -> "Ready · $optional optional"
            else -> "$missing to fix"
        }
    }

    fun allNeededOk(items: List<Item>): Boolean = items.all { it.ok || !it.needed }

    // the Ready check lists only what is not ready, the needed ones first (screen specs, Play card 2)
    fun notReady(items: List<Item>): List<Item> = items.filter { !it.ok }.sortedBy { if (it.needed) 0 else 1 }
}
