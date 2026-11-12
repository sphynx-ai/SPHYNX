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
        Entry("2026-11-12", Kind.NEW, "Debug data page: every export in one place, uploaded to GitHub by a button",
            "A new Debug data page holds everything needed for debugging: the session recorder (moved from Logs), the speed / " +
                "scorecard exports (moved from Performance and Practice), a full-log export, and \"Upload the debug data to GitHub " +
                "now\" - it commits what is in the recordings folder to the branch recordings-for-debugging (all non-video files " +
                "in one commit, each video in its own), and by itself after each recording if you leave that switch on. Put a " +
                "fine-grained GitHub token (this repository only, Contents: read and write) in github_token.txt in the app's " +
                "folder. The training data for the notebooks is not part of it.",
            pr = 64, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.NEW, "Recordings now carry the debug data",
            "Every recording also saves: clean screenshots at key moments (the gate's readings, the lane side decided, an enemy " +
                "hero or monster first seen, your HP read as 0%, the pictures Gemma was given), everything the app saw each frame " +
                "(allies, minions, towers, the minimap reading, the gate, the lane side, Solo / 5v5), every touch sent and how long " +
                "it waited, Gemma's prompts and replies, the calibration tour's log, your settings (no keys or tokens), the full " +
                "log with crashes, and a picture of the saved scenery map.",
            pr = 63, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.NEW, "Recording starts by itself at the start of every match",
            "The session recorder now starts on its own when a match starts (the loading screen, or your hero first seen) and " +
                "stops and saves at the match end - switch it off on the Logs page if you don't need it (a recording you start " +
                "yourself is never stopped by the match end). The video's time / decision banner moved to the BOTTOM (at the top " +
                "it covered the minimap's lane mark), and every recording's start row names the build's git commit.",
            pr = 62, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.IMPROVED, "Gemma freed: no practice check by Gemma, line-up read once",
            "Your recording: the Gemma line-up read took 50 s on the loading screen and 70 s on the scoreboard (Gemma could do " +
                "nothing else meanwhile), and its practice check misread the Computer cards. Now the practice gate uses only its " +
                "pixel check, and Gemma reads the line-up once per match on the loading screen - never on the scoreboard (the " +
                "enemy spells are read there from the icons) and never in Solo practice.",
            pr = 61, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.IMPROVED, "Faster touches: joystick slices 120 ms instead of 320 ms",
            "Your recording measured about 280 ms from screen to touch, ~240 ms of it waiting in the touch queue: every joystick " +
                "slice was held 320 ms and the next decision waited for it to end. A joystick that is already held now continues in " +
                "120 ms slices (the first press still holds 320 ms so the game registers it), and skills / taps are no longer " +
                "stretched to the joystick's 320 ms. Check the Performance page: the touch queue should drop.",
            pr = 60, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.FIXED, "Paths keep out of enemy tower range; no regen while being hit",
            "Your recording had 198 frames of \"inside the enemy tower range with no minions: getting out\" - the jungle and gank " +
                "paths cut through enemy towers' range - and at 2:29 the Player stood still to regenerate while taking hits. Now the " +
                "paths go around enemy towers' range when there is a way (a goal inside it can still be reached), and Regen is " +
                "neither started nor continued while your HP is dropping (being hit).",
            pr = 59, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.FIXED, "A sudden 0% HP reading is ignored",
            "Your recording had 94 frames where your HP read 0% while the bar was full (effects and the buff's bars covered it), " +
                "which made the Player stand \"waiting\". Now a 0% reading right after a healthy one is ignored (the last good reading " +
                "is kept) unless the death screen shows or the 0% lasts 1.5 s.",
            pr = 58, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.FIXED, "Commits to the Turtle / Lord instead of flip-flopping",
            "In your recording \"turtle is up: rotating there\" ran from 2:07 to 7:18 and the Player never reached the pit. Now, once " +
                "it starts rotating to the Turtle or Lord, that goes first (before the jungle route, ganks, waves) until it " +
                "arrives - unless enemies are near or its HP is under 40%. Not there after 45 s: it gives up on the objective " +
                "for 60 s. The panel shows \"(committed: N s)\". The Lord still needs 3 or more allies.",
            pr = 57, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.FIXED, "Research experiment (R3) is safety-first",
            "In your recording the research experiment changed the fight choice 82 times, always to \"keep farming\" because the " +
                "strategy's generic plan said farm - 19 times over a retreat and 30 times over an all-in. Now it never overrides a " +
                "retreat, and the strategic plan's generic \"farm\" alone cannot turn an all-in into farming.",
            pr = 56, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.FIXED, "Buffs and camps are finished, not left alive",
            "In your recording (0:36-0:46) the Molten Fiend's bar was read as enemy minions (\"clearing the wave\", \"last hit: minion\") " +
                "in a pit with no minions, so the Player walked off to \"a jungle camp\" while the buff was still alive. Now, while " +
                "you stand at a known jungle camp and no enemy minion wave is near you on the minimap, those bars are the camp's " +
                "monster: the Player keeps hitting it until it dies (and uses Retribution on the buff). Low-HP enemy heroes are now " +
                "real ones only (the ghost \"enemies at 3%\" were fixed in the previous update), so real kills are taken.",
            pr = 55, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.FIXED, "The Lord only with 3 or more allies",
            "The Research player kept hitting the Lord with no ally near and was killed by it. Now it starts, finishes or rotates " +
                "to the Lord only with 3 or more allied heroes near it (on the screen, or on the minimap around you); with fewer it " +
                "walks away from the Lord. The Turtle is unchanged.",
            pr = 54, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.FIXED, "Jungle monsters no longer taken for low-HP enemy heroes",
            "Your recording (0:36) showed the Molten Fiend's bar read as two enemy heroes at 3% and 25% - the Player left the buff to " +
                "\"chase a kill\" and spent Retribution on ghosts. A red bar now counts as an enemy hero only with its own level badge " +
                "(your own badge next to it no longer counts) AND an enemy hero icon near you on the minimap; while a camp is being " +
                "fought, a bar where the monster was just seen stays the monster unless its level number can be read.",
            pr = 53, needsDeviceCheck = true),
        Entry("2026-11-11", Kind.IMPROVED, "Without All files access, files go to Download/Sphynx-Auto-Pilot-Storage",
            "Recordings (\u25cf REC), CSV exports, training pictures and zips, cloud-training results and backups are saved in " +
                "Download/Sphynx-Auto-Pilot-Storage when the app has no All files access. The old Download/MLBB-Research folder is " +
                "ignored completely - not written and not read (backups there no longer restore); delete it yourself once you " +
                "have moved anything you want to keep. With All files access nothing changes: " +
                "/CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage.",
            pr = 52, needsDeviceCheck = true),
        Entry("2026-11-11", Kind.FIXED, "Solo calibration no longer waits for lane marks",
            "Solo practice never shows the \"$\" / \"E\" lane marks (only 5v5 does), so the calibration tour waited forever. In " +
                "Solo, which lane is on top is now read from the Lord and Turtle icons on the minimap: the Lord's pit is on the Gold " +
                "lane's side (Lord top-left = Gold on top). Both icons must show in their pits. Also: the floating panel's title is " +
                "now SPHYNX AI.",
            pr = 51, needsDeviceCheck = true),
        Entry("2026-11-11", Kind.FIXED, "Your lane corrections for 10 heroes",
            "Alpha: Jungle, then EXP · Chou: EXP, then Roam · Lapu-Lapu: EXP only · Lunox: Mid, then Gold · Masha: EXP, then Roam · Minsitthar: EXP, " +
                "then Roam · Paquito: EXP, then Jungle · Phoveus: EXP only · Valentina: Mid, then EXP · Yi Sun-shin: Jungle, then " +
                "Gold. These come before the online guide and the wiki (your own per-hero setting still comes first).",
            pr = 50, needsDeviceCheck = true),
        Entry("2026-11-11", Kind.NEW, "Recommended lanes per hero: primary, secondary and flex pick",
            "Every hero now has a recommended primary lane, a secondary lane, and whether it is a flex pick (playable in almost " +
                "any lane) - from its online guide (now asked for both lanes and flex) and every wiki laning list it is in. Shown " +
                "on the Library's hero list and hero page. The automatic lane uses it: the primary lane; without Retribution never " +
                "the Jungle (the secondary instead); a flex pick takes its secondary when an ally's primary is the same lane. Your own " +
                "setting per hero comes first (Player page, under the selected hero: e.g. \"exp, jungle\" or \"exp, jungle, flex\"), " +
                "then lanes you confirmed (Alice: EXP, then Jungle - not Roam or Mid), then the online guide, then the wiki.",
            pr = 49, needsDeviceCheck = true),
        Entry("2026-11-11", Kind.NEW, "Gold and EXP lanes swapped between matches: read from the minimap's lane marks",
            "Some matches put the Gold lane on top, others the EXP lane, with the Lord and Turtle pits swapped too: the Turtle is " +
                "always by the EXP lane, the Lord by the Gold lane. At the start of each match the minimap's top-left lane mark (gold " +
                "\"$\" or lavender \"E\") is read and shown on the panel. Lane walking and tower targets follow the lanes; the Lord / " +
                "Turtle pits and the jungle camps (measured in your Gold-on-top matches, Lord top-left) are mirrored when the EXP lane " +
                "is on top, where scenery is not learned and the calibration tour waits for a Gold-on-top match. Also: only one " +
                "Lithowanderer is up at a time (either side of the river, back 120 s after its kill), the enemy buffs' spots are " +
                "measured from your screenshots. Solo practice or 5v5 is told from the loading screen / scoreboard (shown on the " +
                "panel): in solo practice (calibration) both the Lord and the Turtle are up and named by their pit; in 5v5 the " +
                "wiki's timers apply (Turtle at 2:00, no Turtle from 9:00, the Lord then in its pit). The scenery calibration tour " +
                "runs only in Solo mode vs computer (god mode is only there), on either lane layout once the lane mark is read: " +
                "what it sees on a mirrored map is saved mirrored back. In Solo every jungle monster is up from 0:00.",
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
