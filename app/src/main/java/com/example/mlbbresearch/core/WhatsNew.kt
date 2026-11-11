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
        Entry("2026-11-11", Kind.NEW, "Gold and EXP lanes swapped between matches: read from the minimap's lane marks",
            "Some matches put the Gold lane on top and the EXP lane at the bottom, with the Lord and Turtle pits swapped too. " +
                "At the start of each match the minimap's top-left lane mark (gold \"$\" or lavender \"E\") is read; with the Gold " +
                "lane on top (a mirrored map), lane walking, tower targets, the Lord / Turtle pits and the jungle camps follow the mirror; " +
                "scenery is not learned in such a match and the calibration tour waits for one with the EXP lane on top. Until the mark " +
                "is read, EXP on top is assumed.",
            pr = 48, needsDeviceCheck = true),
        Entry("2026-11-11", Kind.IMPROVED, "Every jungle monster named; the Stone Roamer is not counted as an ally",
            "From your 20 jungle screenshots: each monster is named from where you stand on the minimap and its bar's size - " +
                "Thunder Fenrir, Molten Fiend, Lava Golem, Fire Beetle, Horned Lizard, Scavenger Crab (both river ends) and the " +
                "Lithowanderer (in the river by the mid crossing), on both sides of the map. After a Lithowanderer kill, the Stone " +
                "Roamer (blue bar, no level badge) is a friendly patrol for 45 s: not counted as an ally hero or minion. The Walkie " +
                "Grass is never a target. The jungle route uses these camp positions too (the Rockursa is replaced by the Lava Golem; the Lizard, " +
                "Fire Beetle and Lithowanderer were in the wrong places), and the scenery calibration tour visits the real pits.",
            pr = 47, needsDeviceCheck = true),
        Entry("2026-11-11", Kind.IMPROVED, "Scenery calibration tour reaches the enemy base; never into the enemy fountain",
            "The calibration tour now also visits the enemy inner and base towers of all three lanes and the enemy base itself " +
                "(placed from the towers on your minimap), then walks back to your base. Meant for 1v1 vs Computer with god mode on. " +
                "In every mode the Research player never enters the enemy fountain: inside it walks out, at its edge no step or skill leads in.",
            pr = 46, needsDeviceCheck = true),
        Entry("2026-11-11", Kind.NEW, "Scenery calibration ends with a walk back to base",
            "After the last stop of the calibration tour the Research player walks back to its base (base gate, then the " +
                "fountain), still without fighting. The scenery keeps being read on the way and is re-synced and saved at both " +
                "stops, so the walk back revalidates what the tour learned. The panel shows \"returning to base 1/2\".",
            pr = 45, needsDeviceCheck = true),
        Entry("2026-11-11", Kind.IMPROVED, "Performance page redesigned",
            "Laid out like a phone performance screen: the phone's name, then one card per topic with a big headline number " +
                "and label / value rows - memory (ring of RAM in use, available and total, 5-minute line), storage, CPU (one tile " +
                "per core with its clock and maximum), graphics processor, NPU drivers, temperature and battery, local models. " +
                "The repeated phone figures are gone; the AI's speed and the speed recorder follow in the same style.",
            pr = 44, needsDeviceCheck = true),
        Entry("2026-11-11", Kind.IMPROVED, "Research player; shorter floating panel; calibration without fighting; Performance page",
            "The Player switch is now \"Research player\" and the floating panel says so (no more \"Player + Research + Learning " +
                "from you\"; learning is named only while coach mode is on). The panel shows less: what runs, the practice check and " +
                "its last reading, what the Player does, the match in one line. Scenery calibration starts and stops from the panel " +
                "(CALIBRATE / STOP CALIB) and the Player does not fight while it runs. The Speed page is now the Performance page: " +
                "the phone's memory, storage, CPU and cores, graphics processor, NPU drivers, temperature and battery, and the local models.",
            pr = 43, needsDeviceCheck = true),
        Entry("2026-11-10", Kind.FIXED, "Practice check reads \"Computer\" at the app's capture size",
            "The app reads the screen at 800x359, where \"Computer\" is only about 49x9 pixels: the practice check read it as " +
                "unclear (0.66) and never unlocked the Player. It now matches the word at that size (0.92 on the scoreboard, " +
                "0.81-0.89 on loading cards; real players' names 0.50 at most), and finds the scoreboard rows with a player there too.",
            pr = 42, needsDeviceCheck = true),
        Entry("2026-11-10", Kind.NEW, "Practice check: 5v5 loading screen read without Gemma",
            "A 5v5 practice match vs Computer is verified on its loading screen from the picture alone, like solo practice: " +
                "the 9 other cards must read \"Computer\" (measured 0.90-0.94 on your screenshot; real players' names 0.30 at most).",
            pr = 41, needsDeviceCheck = true),
        Entry("2026-11-10", Kind.FIXED, "Practice check: solo loading card read without Gemma; last reading on the panel; vibration",
            "Solo practice is verified from the enemy card's \"Computer\" on the loading screen, from the picture alone (Gemma " +
                "not needed). The floating panel shows the practice check's last reading (what it saw and its scores), and the " +
                "phone vibrates once when the Player is unlocked and three times when a real player locks the match.",
            pr = 40, needsDeviceCheck = true),
        Entry("2026-11-10", Kind.FIXED, "Solo practice is identified; scenery calibration waits for the unlocked Player",
            "Solo practice (you vs one Computer) was never identified as a match - its loading screen was not recognised and a " +
                "quick restart was not seen as a new match - so the practice check never unlocked the Player. Both are fixed. " +
                "The scenery calibration tour now starts only once the practice check has unlocked the Player.",
            pr = 39, needsDeviceCheck = true),
        Entry("2026-11-10", Kind.FIXED, "Practice check from the loading screen; overlay hidden on loading and scoreboard; one Player switch",
            "The floating window and the boxes are hidden on the loading screen and the scoreboard (they covered the names, so a " +
                "practice match was locked). A practice match is now verified on the loading screen when the 9 others are " +
                "\"Computer\", and a real player must be seen twice in a row to lock it. The Player and research mode are one switch. " +
                "Solo practice (you and one Computer enemy) is verified too: empty scoreboard rows are skipped. " +
                "A Blessed Retribution icon now counts as Retribution, so the hero goes to the jungle.",
            pr = 38, needsDeviceCheck = true),
        Entry("2026-11-10", Kind.NEW, "Speed recorder: both language models and screen to touch",
            "Speed page: every answer of the strategist and the tactical model is recorded (time, engine, GPU or CPU, with or " +
                "without a picture, the phone's temperature and free memory), and while the Player plays, the screen-to-touch " +
                "time every 5 s. Optional detailed figures (tokens per second, time to the first token), a speed test with the " +
                "same questions every time, and a CSV export to send. It only measures.",
            pr = 35, needsDeviceCheck = true),
        Entry("2026-11-10", Kind.NEW, "Practice benchmark: a scorecard for every practice match",
            "New Practice page. Every practice match vs Computer that the Player plays gets a scorecard: result, K/D/A, level at " +
                "5 and 10 minutes, how long the AI stood idle or got stuck, and every death with what the AI was doing just before " +
                "(fighting, farming, escaping; outnumbered or not). The page shows the trend (last 5 matches vs the 5 before), " +
                "per hero, and the most common death causes; export as CSV. It only measures - it never touches the game.",
            pr = 31, needsDeviceCheck = true),
        Entry("2026-11-10", Kind.IMPROVED, "Player mode is for practice vs Computer only",
            "Your decision: the Player plays the hero only in a match verified as practice against Computer (open the scoreboard " +
                "once). In Ranked, Classic or any other match it becomes your companion: the strategist and the tactical advisor " +
                "keep working and show their advice on the floating panel, including which skill to level up next, but nothing is " +
                "touched (skills are leveled for you only in practice). Drafter and Builder stay automatic in every match, and the " +
                "Builder now also presses item actives (Frozen, Wind Chant, Conceal) when the Player is not playing.",
            pr = 30, needsDeviceCheck = true),
        Entry("2026-11-09", Kind.IMPROVED, "Small tactical model is no longer experimental",
            "Your decision: Gemma 3 270M is a full engine now. Its fight advice can be used like the other engines' - in the decision " +
                "arbiter too - but it still has to pass the app's Kotlin checks and the validator (no all-in at low HP, etc.).",
            pr = 29, needsDeviceCheck = true),
        Entry("2026-11-08", Kind.FIXED, "Skills released where they were aimed; no more chasing the Lithowanderer",
            "From your 13 recordings: a skill was re-aimed at release with the AI's newest decision - a third of skills went 90 degrees " +
                "off (\"aimed right, cast elsewhere\"). Now it releases at the target it was aimed at. On-screen \"enemies\" the minimap " +
                "never shows (the Lithowanderer's thin bar, companions) are dropped, the in-match scoreboard is recognised reliably " +
                "(the AI no longer plays on it), and the floating window's buttons stay one line and the same size.",
            pr = 28, issue = 2, needsDeviceCheck = true),
        Entry("2026-11-08", Kind.NEW, "Session recorder: data file + video for bug reports",
            "Logs page > Start recording. It saves what the AI saw and decided (about 10 times a second, every aimed skill " +
                "as planned and as released, the log) and a video of the AI's view with its targets, joystick and aim drawn on " +
                "top, both stamped with the clock and match timer. Files go to Sphynx-Auto-Pilot-Storage/recordings. " +
                "It stops and saves by itself when the video reaches 25 MB (a GitHub website upload) or 95 MB (git). " +
                "Also on the floating window: ● REC starts, ■ REC stops and saves; the round icon gets a red ring while recording. " +
                "Research mode is recorded too (verified practice, the arbiter / normal group, each arbiter choice and scored fight), " +
                "and saving also copies the R5 comparison, the practice moments and the readiness report next to the recording.",
            pr = 27, needsDeviceCheck = true),
        Entry("2026-11-08", Kind.FIXED, "Jungle fights: no more leaving mid-fight, getting stuck or phantom enemies",
            "From your device test: the hero no longer walks to the next camp while the monster still has HP (hidden HP bars " +
                "no longer count as a kill), stands still and attacks when a big monster's body stops it, and a jungle monster's " +
                "red HP bar is no longer taken for an enemy hero on the map.", pr = 26, issue = 2, needsDeviceCheck = true),
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
