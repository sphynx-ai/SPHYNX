package com.example.mlbbresearch.core

// WHAT'S NEW: the app's own change list, shown on the "What's new" page and on Home.
// One line per merged change, newest first. Add an entry to the top in the same pull request as
// the change itself (the pull request number goes in `pr`, an issue it fixes in `issue`).
// Only changes that are IN this build belong here - nothing that is still waiting to be merged.
object WhatsNew {

    enum class Kind(val label: String, val color: String) {
        NEW("New", "#6D8BFF"),
        FIXED("Fixed", "#3DD68C"),
        IMPROVED("Improved", "#F4C25B")
    }

    data class Entry(
        val date: String,        // yyyy-mm-dd, when it was merged
        val kind: Kind,
        val title: String,
        val details: String,
        val pr: Int,
        val issue: Int? = null,
        val needsDeviceCheck: Boolean = false   // merged, but not yet confirmed on a phone
    )

    val ENTRIES: List<Entry> = listOf(
        Entry("2026-11-08", Kind.NEW, "Sphynx Auto Pilot is its own app now",
            "New app name everywhere (launcher, header, Accessibility list, notification) and a new app id, " +
                "com.sphynx.autopilot: it installs next to the old MLBB Research app. Give it its permissions again; " +
                "with All files access on it also finds the old app's exports, results and backups in Download/MLBB-Research.",
            pr = 25, needsDeviceCheck = true),
        Entry("2026-11-08", Kind.IMPROVED, "One app folder: /CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage (v37.19)",
            "Models, detectors, networks and keys are read from it, and CSV exports, training pictures and zips, Kaggle results " +
                "and backups are saved in it. Turn on \"All files access\" once on the Model page. Until then everything works " +
                "as before (the folder you choose, and Download/MLBB-Research for the app's own files).",
            pr = 24, needsDeviceCheck = true),
        Entry("2026-11-08", Kind.NEW, "Arbiter vs normal comparison in practice (research R5)",
            "Each verified practice match is randomly put in an arbiter or a normal group; fight scores, deaths, K/D/A and results " +
                "are compared under Research mode, with \"not enough data\" until each group has 5 matches.",
            pr = 21, needsDeviceCheck = true),
        Entry("2026-11-08", Kind.FIXED, "Enemies under your green minimap ring; monsters out of range",
            "Enemy icons half covered by your own green ring no longer turn into phantom or duplicate enemies, and a covered " +
                "enemy is kept for a moment instead of vanishing (minimap and screen are combined into one target list). " +
                "Against jungle monsters the hero now walks into its own attack range and never steps out of the monster's aggro zone.",
            pr = 22, issue = 2, needsDeviceCheck = true),
        Entry("2026-11-08", Kind.NEW, "Small model readiness test (research R4)",
            "In verified practice, fight moments are recorded (the situation, what the app played, which options were dangerous). " +
                "A button under Research mode tests the small tactical model on them against the approved criteria. " +
                "A pass does not switch the model on.", pr = 20, needsDeviceCheck = true),
        Entry("2026-11-08", Kind.NEW, "Arbiter picks the fight option in practice (research R3)",
            "Only in a verified practice match against Computer, with Research and Player mode on, the decision arbiter chooses " +
                "the fight option. Retreat wins any fight-or-retreat conflict, and every pick must pass the app's own checks.",
            pr = 19, needsDeviceCheck = true),
        Entry("2026-11-08", Kind.NEW, "Research mode switch with live practice check (research R2)",
            "A Research mode switch next to Player, Drafter and Builder. It reads the in-match scoreboard and shows " +
                "\"verified practice\" only when all 9 other players are Computer; any human locks the match.",
            pr = 18, needsDeviceCheck = true),
        Entry("2026-11-08", Kind.NEW, "Map of the 5v5 battlefield",
            "Bases, turrets, buff and monster camps, and the Lord and Turtle pits as a small data file, with map regions " +
                "(lanes, river, jungle). Not used by the AI yet.", pr = 17),
        Entry("2026-11-07", Kind.NEW, "Practice-only safety gate (research R1)",
            "Recognises the word \"Computer\" on the scoreboard. Real ranked boards read \"human present\"; " +
                "the gate can never be switched on by hand.", pr = 16),
        Entry("2026-11-07", Kind.NEW, "Decision logging",
            "Every engine's advice is logged next to what was actually played, so the engines can be compared. " +
                "It never changes what is played.", pr = 15),
        Entry("2026-11-07", Kind.FIXED, "Battle spell from the loading card is only a hint",
            "Gemma's reading of the loading card no longer counts as your verified battle spell.", pr = 14),
        Entry("2026-11-07", Kind.NEW, "Shared decision format and safety arbiter",
            "All engines state their advice the same way; a validator rejects advice that contradicts what is seen " +
                "(for example, all in at very low health) and an arbiter settles conflicts, safety first.", pr = 13),
        Entry("2026-11-07", Kind.NEW, "Universal MOBA foundation",
            "A game-neutral match model and a list of what the app can and cannot read, with Mobile Legends as the first game.",
            pr = 12),
        Entry("2026-11-07", Kind.IMPROVED, "Reliable builds",
            "The on-device model library is pinned to one version, and every change is built and unit tested automatically.",
            pr = 8),
        Entry("2026-11-07", Kind.NEW, "Upgraded Retribution and Blessed icons",
            "Reads \"Retribution Upgraded to ...\" and the Blessed icons on your scoreboard row.", pr = 6),
        Entry("2026-11-07", Kind.IMPROVED, "Repository clean-up (v37.18)",
            "Build outputs are no longer stored with the source; first automatic tests.", pr = 3)
    )

    fun latest(n: Int): List<Entry> = ENTRIES.take(n)

    // entries grouped by date, newest date first (the list is already newest first)
    fun byDate(): List<Pair<String, List<Entry>>> = ENTRIES.groupBy { it.date }.toList()

    fun counts(): Map<Kind, Int> = ENTRIES.groupingBy { it.kind }.eachCount()

    fun ref(e: Entry): String = "#${e.pr}" + (e.issue?.let { " · issue #$it" } ?: "")
}
