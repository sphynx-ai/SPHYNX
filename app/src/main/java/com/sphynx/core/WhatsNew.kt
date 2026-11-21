package com.sphynx.core

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
        val needsDeviceCheck: Boolean = false,  // merged, but not yet confirmed on a phone
        val version: String? = null             // the app version that brought it (from 37.20, owner 2026-11-14)
    )

    val ENTRIES: List<Entry> = listOf(
        Entry("2026-11-21", Kind.IMPROVED, "Development: review and feature plugins, and the rules for the Claude Code plugins",
            "Behind the scenes, for development only: the code-review, pr-review-toolkit, feature-dev, commit-commands, security-guidance and pyright-lsp plugins are turned on, and the project rules now say the superpowers plugin only helps the " +
                "existing way of working and never overrides it, and that you decide on the design, approvals and " +
                "merges. Nothing in the app changes.",
            pr = 68, needsDeviceCheck = true, version = "37.78"),
        Entry("2026-11-21", Kind.NEW, "Development: an Android build tool and a plugin for Claude Code",
            "Behind the scenes, for development only: the project now names an Android build helper (Gradle, adb, " +
                "logcat, UI automation) that Claude Code can use on a computer with the Android SDK and a connected " +
                "phone, and turns on the superpowers plugin for Claude Code. Nothing in the app changes.",
            pr = 67, needsDeviceCheck = true, version = "37.77"),
        Entry("2026-11-16", Kind.IMPROVED, "Phase 2 finished: Mobile Legends is the first game of a universal core",
            "The foundation work of Phase 2 is done: the app's core no longer names Mobile Legends; everything about " +
                "the game sits in its own part, checked by tests. Play should be exactly as before; please play a 5v5 " +
                "match and a Solo practice to confirm. Next: Phase 5, reliability on Android.",
            pr = 66, needsDeviceCheck = true, version = "37.76"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: a guide and a test kit for adding other games",
            "Behind the scenes: a guide now says what a second game would need (its names, rules, screen reading and " +
                "questions), how features it cannot provide behave, and how it is versioned and tested. A test kit checks " +
                "any game's part, and Mobile Legends passes it. Only Mobile Legends is supported; nothing in play changes.",
            pr = 65, needsDeviceCheck = true, version = "37.75"),
        Entry("2026-11-16", Kind.IMPROVED, "Safety: a last check on every decision",
            "Behind the scenes: after the trained network or \"Play like me\" has mixed into a decision, a last check " +
                "makes sure a retreat, recall or regen of the rules is never replaced, the joystick direction is a " +
                "valid one, and each button is pressed at most once. The learned parts already did this, so play should " +
                "not change; it is now guaranteed for any model.",
            pr = 64, needsDeviceCheck = true, version = "37.74"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the objectives' names come from the Mobile Legends rules",
            "Behind the scenes, for adding other games later: the last Mobile Legends words in the Player's reasons " +
                "(the Lord, the Turtle, the Purple Buff) now come from the Mobile Legends rules, and the check that keeps " +
                "the Player's decisions free of Mobile Legends names now covers them too. The reasons read exactly as " +
                "before.",
            pr = 63, needsDeviceCheck = true, version = "37.73"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the strategy questions come from the Mobile Legends rules",
            "Behind the scenes, for adding other games later: everything the app asks the strategic model about Mobile " +
                "Legends (the plan, the draft, the loading cards, your hero and battle spell, the build, ranked or " +
                "standard, the live match clock) and the Build page's text now come from the Mobile Legends part; the " +
                "strategy part only schedules the questions and applies the answers. The questions are word for word " +
                "the same, so the answers should not change.",
            pr = 62, needsDeviceCheck = true, version = "37.72"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the screen reading is split into a pipeline and Mobile Legends' readers",
            "Behind the scenes, for adding other games later: taking each captured frame, timing it and keeping the " +
                "latest reading is now one game-independent part; everything that reads Mobile Legends' screen (HP bars, " +
                "minimap, buttons, banners, bushes, the tower circle, the level badge, K/D/A) is Mobile Legends' screen " +
                "reader, plugged in at start. What the AI sees should be exactly the same, at the same speed.",
            pr = 61, needsDeviceCheck = true, version = "37.71"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the fight scores ask the Mobile Legends rules too",
            "Behind the scenes, for adding other games later: the scores the Player decides with (how safe you are, how " +
                "good a fight looks, which option wins) no longer name Mobile Legends heroes, skills, guides, towers or " +
                "buffs themselves; they ask the Mobile Legends rules, like the Player's decisions already do. The Player " +
                "decides exactly as before (all recorded situations unchanged).",
            pr = 60, needsDeviceCheck = true, version = "37.70"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the Player's decisions no longer contain Mobile Legends",
            "Behind the scenes, for adding other games later: the last Mobile Legends map data in the Player's decisions " +
                "(the lane corridors, the outer towers' places, the roam patrol) moved to the Mobile Legends rules, and a " +
                "test now fails if the Player's decisions ever name a Mobile Legends hero, spell, monster or map helper " +
                "again. The Player decides exactly as before (all recorded situations unchanged).",
            pr = 59, needsDeviceCheck = true, version = "37.69"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: jungle, towers and objectives move to the Mobile Legends rules",
            "Behind the scenes, for adding other games later: the jungle path and camps, the enemy towers, the turtle " +
                "and the lord, the learned map scenery, the map's mirroring and the role paths are now asked from the " +
                "Mobile Legends rules instead of being called by the Player's decisions directly. The Player decides " +
                "exactly as before (all recorded situations unchanged).",
            pr = 58, needsDeviceCheck = true, version = "37.68"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: battle spells move to the Mobile Legends rules",
            "Behind the scenes, for adding other games later: when to use Retribution, Flicker, Execute, Flameshot and " +
                "the other battle spells, and the Retribution percentages from your rulebook, are now asked from the " +
                "Mobile Legends rules instead of being written into the Player's decisions. The Player decides exactly " +
                "as before (all recorded situations unchanged).",
            pr = 57, needsDeviceCheck = true, version = "37.67"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the Player's hero special cases move to the Mobile Legends rules",
            "Behind the scenes, for adding other games later: the Player's decisions no longer name Mobile Legends " +
                "heroes themselves. Yu Zhong's dragon flight, Argus's and Thamuz's ultimates, Edith's ranged form, " +
                "skills that must not be interrupted, execute and multi-phase ultimates and dash skills are now asked " +
                "from the Mobile Legends rules. The Player decides exactly as before (all 25 recorded situations are " +
                "unchanged).",
            pr = 56, needsDeviceCheck = true, version = "37.66"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: more recorded fight situations to protect the Player",
            "Behind the scenes: nine more recorded situations that the Player's decisions are checked against on every " +
                "change, covering Mobile Legends' special cases (Retribution on a creep, a low lord after 8:00, Yu " +
                "Zhong's dragon flight, Argus's ultimate, the recall bar, Execute and Flicker kills, a 3-on-3 fight, a low " +
                "enemy near its tower). They guard the coming split of the Player's rules into a game-independent part " +
                "and a Mobile Legends part. Nothing changes in play.",
            pr = 55, needsDeviceCheck = true, version = "37.65"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the game's heroes, lanes and items through one adapter",
            "Behind the scenes, for adding other games later: the game-independent part of the app can now ask the " +
                "game's adapter for its characters, roles, lanes, battle spells, items and objectives, instead of " +
                "knowing Mobile Legends' lists itself. The Mobile Legends adapter reads the same lists the app already " +
                "uses. Nothing should look or behave differently.",
            pr = 54, needsDeviceCheck = true, version = "37.64"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the layering rule now has no exceptions",
            "Behind the scenes, for adding other games later: the code that translates the AI's advice into the shared " +
                "decision format moved next to the AI, so the game-independent part of the app no longer uses any " +
                "Mobile Legends code. A test now checks that this stays true. Nothing should look or behave differently.",
            pr = 53, version = "37.63"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the model files no longer call the AI directly",
            "Behind the scenes, for adding other games later: finding, copying and loading the model files (Gemma, " +
                "the small model, the tactical networks, the detectors, the online backups) now reaches the parts that " +
                "run them through one plugged-in list instead of calling them itself. Nothing should look or behave " +
                "differently: Scan the folder, loading the models and the automatic loading at start work as before.",
            pr = 52, version = "37.62"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: screen capture and picture matching no longer reach into the app",
            "Behind the scenes, for adding other games later: the screen capture service now reports when it starts " +
                "and stops (and the notification's emergency stop) instead of calling the AI and game code itself, and " +
                "the end-of-match and loading-screen readers ask for hero and item pictures the same way. Nothing should " +
                "look or behave differently: learning still starts with screen capture, an open match still closes a " +
                "minute after capture stops, and the emergency stop still stops everything.",
            pr = 51, version = "37.61"),
        Entry("2026-11-16", Kind.IMPROVED, "A marksman pokes a hurt enemy instead of waiting for the wave",
            "Your decision: when you play a marksman and a single enemy hero at 60% HP or less is within poke reach, " +
                "the Player now attacks it and uses its skills from range, keeping its distance, instead of standing at " +
                "the lane waiting for the minion wave. It still does not poke when more enemies are around, when the " +
                "enemy is under its tower, when your HP is low, or when the plan says retreat.",
            pr = 50, version = "37.60"),
        Entry("2026-11-16", Kind.IMPROVED, "The new look is finished; work on the app's foundation resumes",
            "Every step of the redesign (themes, the five sections, Play's Running now and Ready check, Hero and Play " +
                "style, status words, empty and offline states, the floating panel's colours) has been tested on the " +
                "phone, so that phase is complete. Work now goes back to the app's foundation: separating what any MOBA " +
                "needs from what is specific to Mobile Legends, so another game can be added later. Nothing changes on " +
                "screen in this version.",
            pr = 49, version = "37.59"),
        Entry("2026-11-16", Kind.IMPROVED, "Library says when it last learned; enemy slots can be set by tap",
            "Knowledge > Library now shows when the whole database was last learned and where from (the Mobile " +
                "Legends wiki and the online guides), or that it has not been learned as a whole yet. On Hero & match > " +
                "Hero, tapping one of the five enemy slots opens a hero list with a search box: pick the hero, or Clear " +
                "the slot. A hero read later from the loading screen or the scoreboard can still replace it.",
            pr = 48, version = "37.58"),
        Entry("2026-11-16", Kind.IMPROVED, "Setup and the first-run guide share one Permissions card",
            "Settings > Setup now shows the same Permissions card as the first-run guide, instead of its own copy of " +
                "the buttons: Accessibility service, Allow restricted settings, Display over other apps, Notifications " +
                "and All files access (which Setup did not have), each ending with Granted or Missing, and a chip that " +
                "counts how many are done.",
            pr = 47, version = "37.57"),
        Entry("2026-11-16", Kind.IMPROVED, "Clear empty, loading and offline states",
            "Pages now say plainly when there is nothing to show and what fills it: What the AI sees offers Start screen " +
                "capture when there is no capture, Recent actions says nothing has happened yet, and a Library search with " +
                "no result says so. Learning the whole database shows a moving bar while it runs (its button is the Stop). " +
                "Buttons that need the internet (learn the database or a hero's skills, update the meta, upload to GitHub) " +
                "are greyed out while the phone is offline, with a line saying why; everything else keeps working offline.",
            pr = 46, version = "37.56"),
        Entry("2026-11-16", Kind.IMPROVED, "Ready check lists only what is missing; a failed start says why",
            "The Ready check on Play now lists only what is not ready yet, each with its Fix, Start or Open button, the " +
                "needed ones first; when everything is ready it shows one line, Ready to play. While the strategic model " +
                "loads it shows a bar with the model's name. If an engine in Running now cannot start, a red line under it " +
                "says why, with a Try again button, instead of a message that disappears.",
            pr = 45, version = "37.55"),
        Entry("2026-11-16", Kind.FIXED, "Short labels no longer break inside a word; pictures have spoken names",
            "Names under hero and item pictures and the Library tabs used to break a long word across two lines " +
                "(\"Equipmen/t\"). They now take the largest size at which every word fits on its line, or end with " +
                "\"...\" if none does. Every hero and item picture now has its name for TalkBack (the phone's screen " +
                "reader), so it says \"Layla\" instead of \"image\".",
            pr = 44, version = "37.54"),
        Entry("2026-11-15", Kind.IMPROVED, "The floating panel takes the app's theme",
            "The floating panel in the game now uses the colours of the theme chosen on Settings > Setup > Theme: its " +
                "ground, buttons, borders and text, and the AI ON button filled with the theme's colour (teal in Signal, " +
                "cyan in Arcane, amber in Ember). Red STOP and the status colours stay the same in every theme. A panel " +
                "that is already open takes a new theme the next time it opens.",
            pr = 43, version = "37.53"),
        Entry("2026-11-15", Kind.IMPROVED, "Play is shorter: research tests on Practice, What's new under Settings",
            "The research tests (test the small model on the practice moments, clear the arbiter comparison, delete the " +
                "practice moments) moved from Play to AI & models > Practice, under Research tests. The What's new card " +
                "left Play too: the full list is on Settings > What's new. Play now shows what runs, what is missing, " +
                "what the AI sees and its recent actions.",
            pr = 42, version = "37.52"),
        Entry("2026-11-15", Kind.IMPROVED, "Meta: when it was updated and where it came from",
            "On Knowledge > Meta, the patch meta and the hero meta now start with one line: \"Updated <date> (<days> " +
                "ago) from <sources>\", so you can see at once how old the downloaded meta is and where it was read. " +
                "It replaces the Sources line at the bottom of each card.",
            pr = 41, version = "37.51"),
        Entry("2026-11-15", Kind.IMPROVED, "Hero: battle spell in one tap, enemy team as five portraits",
            "On Hero & match > Hero, the battle spell is no longer a button you tap through one spell at a time: all of " +
                "them are shown as buttons (Detect and the twelve spells) and one tap picks yours. The enemy team shows " +
                "five slots, each with the hero's portrait and name once it is read on the loading screen or the " +
                "scoreboard, and a question mark until then.",
            pr = 40, version = "37.50"),
        Entry("2026-11-15", Kind.IMPROVED, "Player is split into Hero and Play style; lane and Team / Solo are one-tap choices",
            "Hero & match now has four pages: Hero, Play style, Draft and Build. Hero holds your hero, player name, battle " +
                "spell, lane, the enemy team and what the app knows about the hero (game knowledge, how to play it, its " +
                "skills). Play style holds how the AI plays it: skill combos, behaviour, jungle, turtle and lord, level, " +
                "items, regen and recall, Coach and Data & resets. The lane is now a choice of six buttons (Auto, EXP, " +
                "Gold, Mid, Roam, Jungle) instead of six switches, and Draft's ranked Team / Solo is a two-button choice.",
            pr = 39, version = "37.49"),
        Entry("2026-11-15", Kind.NEW, "Play: Running now and Ready check",
            "Play now has one place that shows and switches what runs: the Running now card, with a switch for the " +
                "Player, the Drafter, the Builder and Learning, each with its state in words (On, On · Dry run, Off), and " +
                "the Dry run and Autonomous switches. Its badge says All off, Dry run or Live. Below it, the Ready check " +
                "lists the permissions (Granted or Missing, with a Fix button), screen capture (Start) and the strategic " +
                "model (Open), and says Ready or how many things are left to fix. The running switches on Player, Draft " +
                "and Build are replaced by a line saying whether each one runs. The research tests stay on Play under " +
                "Practice research for now.",
            pr = 38, version = "37.48"),
        Entry("2026-11-15", Kind.IMPROVED, "Player: all the reset buttons together under Data & resets",
            "The five buttons on Player that delete or reset something were spread over the page. They are now together " +
                "in one section at its end, Data & resets: Clear manual combo, Forget the remembered level badges, Forget " +
                "what Coach learned, Delete my recorded play and Reset learned aggression. Each still asks first.",
            pr = 37, version = "37.47"),
        Entry("2026-11-15", Kind.NEW, "Badges on features that are experimental or need the internet",
            "A section that is not fully working yet, or needs a connection, now says so with a small word badge at the " +
                "end of its header: Experimental on Autonomous ranked draft (Draft) and GGUF models (AI & models), Needs " +
                "internet on Train in the cloud (Learning) and Upload to GitHub (Debug data). Everything else works " +
                "offline and carries no badge.",
            pr = 36, version = "37.46"),
        Entry("2026-11-15", Kind.IMPROVED, "Setup is shorter: no more typing button positions by hand",
            "The long \"Type positions by hand\" list at the end of Setup (an x,y field for every button, shop item " +
                "and hero-select spot) is gone, with its \"Show markers\" switch. Every one of those positions is set " +
                "in the in-game calibration from the floating panel, which covers all the same groups. Presets and " +
                "\"Reset all positions to defaults\" stay.",
            pr = 35, version = "37.45"),
        Entry("2026-11-15", Kind.IMPROVED, "Every state says it in words, not only in colour",
            "The permission buttons on Setup and in the first-run setup now end with Granted or Missing, and the model " +
                "button with Loaded, Loading or Not loaded, besides turning green. On AI & models, each line of the files " +
                "card starts with a dot and a word in its colour: Ready (full dot), Partial (half dot: loading, or loaded but " +
                "switched off) or Missing (empty dot).",
            pr = 34, version = "37.44"),
        Entry("2026-11-15", Kind.IMPROVED, "Help for a group of switches sits on its header; even combo buttons",
            "The \"?\" that explains a group of switches (Theme, Heading font, Lane, Draft Team / Solo and the other " +
                "one-of-several choices) now sits at the end of the group's header instead of next to its last switch. " +
                "On Player, the six combo buttons (+ S1 ... + Spell) are now two rows of three equal buttons, each label " +
                "on one line.",
            pr = 33, version = "37.43"),
        Entry("2026-11-15", Kind.IMPROVED, "Buttons that delete or reset ask first, and are red",
            "Every button that deletes, clears, forgets, resets or removes something now has red text and a red outline, " +
                "and asks before it acts. Eleven of them used to act at once: Clear manual combo, Forget the level badges, " +
                "Forget what Coach learned, Reset learned aggression, Clear the comparison, Remove a model file, Clear the " +
                "match log, Clear the icon samples, Reset the sync offsets, Clear the map scenery and Reset all positions.",
            pr = 32, version = "37.42"),
        Entry("2026-11-15", Kind.FIXED, "Switches line up and buttons are the same width again",
            "A control with a \"?\" used to give up room for it, so its switch sat further left than the others and its " +
                "button was narrower. Now every switch sits at the right edge, with the \"?\" between its name and the " +
                "switch, and a button keeps its full width with the \"?\" inside its right end. Tapping a switch's name " +
                "still turns it on or off.",
            pr = 31, version = "37.41"),
        Entry("2026-11-15", Kind.NEW, "Three colour themes to choose from, with icons and a banner",
            "You said the grey look felt dead. The app now has three themes, chosen on Settings > Setup > Theme: " +
                "Signal (the brand's teal, the default), Arcane (violet with cyan) and Ember (amber). Each one gives a " +
                "colour to what is selected or switched on, the main buttons and the switches; the logo's diamond takes " +
                "the theme's colour too, in the app and on the floating panel. The bottom bar has a drawn icon for each " +
                "section, and the top card on Play is a banner in the theme's colour. Text sizes and the red STOP are " +
                "the same in every theme. The app icon on your phone's home screen stays teal for now.",
            pr = 30, version = "37.40"),
        Entry("2026-11-15", Kind.IMPROVED, "Five sections in a bar at the bottom instead of 14 tabs",
            "The 14 tabs you had to scroll sideways are now five sections, always visible at the bottom: Play, " +
                "Hero & match, Knowledge, AI & models and Settings. A section with several pages shows them in a row " +
                "under the header: Hero & match has Player, Draft and Build; Knowledge has Library and Meta; AI & models " +
                "has Models, Learning, Practice and Performance; Settings has Setup, Debug data, Logs and What's new. " +
                "Each section opens where you left it, and swiping left or right still walks through the pages. Nothing " +
                "was removed; the pages themselves are unchanged for now.",
            pr = 29, version = "37.39"),
        Entry("2026-11-15", Kind.IMPROVED, "The new look: brand colours, the app's own fonts, bigger text",
            "Phase 4 has started, with the redesign first as you asked. The main screen now uses the brand colours " +
                "(one set, checked for contrast by a test); main buttons are light with dark text and STOP is a deeper " +
                "red, both easy to read; green / amber / red buttons have dark text too. The app brings its own fonts: " +
                "a pixel font for the app name, the tabs and the headings, and Inter for everything else, so it no " +
                "longer depends on your phone's font. Choose between two pixel fonts on Setup (Heading font). Text is " +
                "at least 13, tabs, buttons and switches are at least 48 high. The pages are still the same 14: the " +
                "five sections come next.",
            pr = 28, version = "37.38"),
        Entry("2026-11-15", Kind.IMPROVED, "Development plan: Phase 3 (the new design) is complete",
            "You approved the design for all five sections: Play, Hero & match, Knowledge, AI & models, Settings & help. " +
                "The plan and mockups for the last three are written down (docs/PHASE_3_SCREEN_SPECS.md), with the list " +
                "of what gets built (docs/PHASE_3_DESIGN_BACKLOG.md). Your order: the new look first (colours, sizes, " +
                "fonts), then the bottom bar with five sections, a question before every delete, and one place that " +
                "shows what is running. The app itself does not change in this step: that is Phase 4.",
            pr = 27, version = "37.37"),
        Entry("2026-11-15", Kind.IMPROVED, "Long information texts go behind the \"?\" too",
            "From your screenshots: the long texts the app keeps updating (the practice moments and readiness test on " +
                "Home, the match timeline and jungle facts on Player, and others) were still on the page. Any such text " +
                "longer than 140 letters or 3 lines now sits behind the \"?\" of the row above it; the bubble shows " +
                "what it says at that moment. Short status lines (AI off, fps) stay on the page.",
            pr = 26, version = "37.36"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: first screens of the new design (Play, Hero & match)",
            "Phase 3: how the Play and Hero & match sections will look, card by card (docs/PHASE_3_SCREEN_SPECS.md), " +
                "with mockups of Play, Hero and Play style for your review: one card that shows what is running, a " +
                "ready check that lists only what is missing, the lane as one choice, the help \"?\" next to each " +
                "setting, and all resets in one card that asks first. They are drafts until you approve them. The app " +
                "itself does not change yet (Phase 4).",
            pr = 25, version = "37.35"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: what every screen shows when loading, empty or failing",
            "Phase 3: one way for each situation, used everywhere (docs/PHASE_3_STATE_MATRIX.md) - loading and progress " +
                "(with Stop where it can stop), empty pages with one next step, errors with Try again, missing " +
                "permissions with a Fix button, and a question before every delete or reset. It also lists the 11 " +
                "delete / reset buttons that act today without asking. The app itself does not change yet (Phase 4).",
            pr = 24, version = "37.34"),
        Entry("2026-11-14", Kind.IMPROVED, "Help \"?\" next to each setting, like the game",
            "As in the game's own settings: a small \"?\" now sits at the end of each button or switch that has an " +
                "explanation. Tap it and the explanation opens in a bubble next to it; tap anywhere else to close it. " +
                "The \"?\" in the header (hold to see every description of the page) is gone.",
            pr = 23, version = "37.33"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: the new look (colours, fonts, sizes)",
            "Phase 3: the design rules for the new look (docs/PHASE_3_DESIGN_TOKENS.md): one set of brand colours for the " +
                "app and the floating panel; your choice of fonts - a pixel-style font for headings and numbers and Inter " +
                "for longer text, both inside the app so every phone looks the same; bigger text (descriptions at least " +
                "14); buttons that are easy to read (dark text on light, a darker red STOP); every tap target at least " +
                "48; states always with a word, not only a colour. The app itself does not change yet (Phase 4).",
            pr = 22, version = "37.32"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: the new layout of the app (5 sections)",
            "Phase 3: from your screenshots and your yes, the plan for the app's layout: five sections in a bar at the " +
                "bottom - Play, Hero & match, Knowledge, AI & models, Settings & help - instead of 14 tabs; one place " +
                "that shows what is running; the Player page split in two; every delete / reset asks first " +
                "(docs/PHASE_3_INFORMATION_ARCHITECTURE.md). The audit now includes what your screenshots showed. The " +
                "app itself does not change yet: the new layout is built in Phase 4.",
            pr = 21, version = "37.31"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: Phase 3 (the app's design) has started",
            "Phase 2 is paused for now (what is done and where it continues is written down). Phase 3 is about how the " +
                "app looks and works: its first step is an audit of every page and the floating panel - what is on " +
                "them, what is hard to find, which texts are too small or low in contrast, which actions have no " +
                "confirmation (docs/PHASE_3_UX_AUDIT.md). The new design comes next, for your approval. No change to " +
                "the app itself.",
            pr = 20, version = "37.30"),
        Entry("2026-11-14", Kind.IMPROVED, "Clean-up: the session recorder no longer reaches into the AI",
            "Phase 2, second clean-up step: what a session recording writes about the match (positions, enemies, " +
                "the decision, the minimap...) is now put together by the AI loop and handed to the recorder, which only " +
                "writes it. The recording format is the same. Also through the app: whether a match may be recorded by " +
                "itself (5v5 only), the screen capture check, and the debug export's scenery picture. 6 more of the " +
                "known exceptions gone (14 left). Please record one session and check it uploads as before.",
            pr = 19, needsDeviceCheck = true, version = "37.29"),
        Entry("2026-11-14", Kind.IMPROVED, "Clean-up: the phone services no longer reach into the AI",
            "Phase 2, first clean-up step: the speed pages, the screen-to-touch log, the phone state, the session video " +
                "and the debug upload now get what they show (frame rates, model speeds, where the enemies are) from the " +
                "app when it starts, instead of reaching into the AI and game code themselves. The build checks this " +
                "direction from now on (11 of the 31 known exceptions gone). Nothing should look or work differently: " +
                "please check the Speed page, a session video and the debug upload once.",
            pr = 18, needsDeviceCheck = true, version = "37.28"),
        Entry("2026-11-14", Kind.FIXED, "Turning the AI off and on forgets the old fight",
            "When the AI was paused and started again, the Player's fast rules still remembered parts of what came " +
                "before: the last jungle monster (it could stand still \"holding position\" at a monster that was gone), " +
                "a Turtle / Lord rotation it had given up on (blocked for 60 s), the decision it was committed to, the " +
                "invade and travel timers. Starting again now begins clean. Found by the new MLBB safety net.",
            pr = 17, needsDeviceCheck = true, version = "37.27"),
        Entry("2026-11-14", Kind.IMPROVED, "A safety net for how the Player plays MLBB",
            "Phase 2: 16 fixed MLBB situations (a minion wave, a weak enemy in reach, low HP alone, outnumbered, an " +
                "enemy dashing at you, a jungle camp, the Turtle, an enemy tower, before the ultimate, dead, shop open...) " +
                "now go through the Player's fast rules in a test, and the decisions are compared with the recorded " +
                "ones. The coming clean-ups of the code must keep MLBB play exactly the same; any change shows up here. " +
                "Also: the new app id and the com.sphynx code name are confirmed on your phone. No change to the app itself.",
            pr = 16, version = "37.26"),
        Entry("2026-11-14", Kind.IMPROVED, "The code is now called com.sphynx inside too",
            "The app's code still carried its first name (com.example.mlbbresearch) inside; it is now com.sphynx, " +
                "the same as the app id. You see no difference, with one exception: Android lists the Accessibility " +
                "service under its new inside name, so turn Sphynx on again once in Accessibility. Please check that a " +
                "GGUF model still loads (Model page): its native bridge carries the name. New tests now check the names " +
                "the compiler cannot see (native functions, shrinker rules, manifest).",
            pr = 15, version = "37.25"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: a written contract for the game state",
            "Phase 2, step 3: the game-neutral description of a match moment (what the decision checks and the " +
                "decision log read) now has a version number and written rules (docs/contracts/UNIVERSAL_MATCH_STATE.md): " +
                "an unknown value is empty, never a guess or -1; health and progress are 0..1; MLBB-only details stay in " +
                "their own list. New tests check that the MLBB adapter always keeps these rules. No change to how the " +
                "app plays.",
            pr = 14, version = "37.24"),
        Entry("2026-11-14", Kind.IMPROVED, "New app id: com.sphynx",
            "The app id is now com.sphynx (was com.sphynx.autopilot). Android sees it as a new app, so it installs " +
                "next to the old one. Give the new one its permissions again (Accessibility, display over other apps, " +
                "All files access, screen capture). Your button and minimap calibration, scenery sync, learned data and " +
                "most settings come back by themselves from the backups in Sphynx-Storage/Output once All files access " +
                "is on (restart the app once); models and keys are read from Sphynx-Storage/Input as before. Check " +
                "the switches once. When the new app works, uninstall the old one.",
            pr = 13, version = "37.23"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: the target design for the app's parts",
            "Phase 2, step 2: a written decision (docs/adr/0001-layered-architecture.md) splits the app into four " +
                "layers - phone services, the game-neutral core, one adapter per game (MLBB first) and the app " +
                "screens - and says which may use which. A new check runs with every build and stops any change that " +
                "adds a forbidden link; the 31 links that exist today are listed and will be removed step by step. " +
                "No change to the app itself.",
            pr = 11, version = "37.22"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: Phase 2 has started (architecture map)",
            "Phase 2 of the development plan makes the app's core work for any MOBA, with MLBB as its first game. Its " +
                "first step maps the app as it is today: which parts are game-neutral and which assume MLBB, how the " +
                "parts depend on each other, the path from a screen frame to a touch, and how the AI models are kept " +
                "within the safety rules (docs/PHASE_2_ARCHITECTURE_MAP.md). No change to the app itself.",
            pr = 10, version = "37.21"),
        Entry("2026-11-14", Kind.IMPROVED, "Version numbers go up with every change (now 37.20)",
            "The app was still called version 37.19, as on 2026-11-08, although dozens of changes had come since, so " +
                "two builds could not be told apart on the Home page. From now on the version goes up by one step " +
                "with every change merged into the test branch: 37.20 is this one, the next is 37.21. Each What's new " +
                "entry shows its version. Also: git now ignores the native build folder (app/.cxx), so it no longer " +
                "shows as an untracked file on your phone; and Phase 1 of the development plan is complete.",
            pr = 9, version = "37.20"),
        Entry("2026-11-14", Kind.IMPROVED, "Sphynx-Storage has three folders: Input, Output, Recordings",
            "Inside /CodeOnTheGoProjects/Sphynx-Storage: Input is where you put models and keys (.litertlm, .gguf, " +
                ".tflite, groq_key.txt, kaggle.json, github_token.txt); Output holds what the app makes (training " +
                "pictures, CSV exports, picture zips, Kaggle extras such as best.pt, the calibration and online-data " +
                "backups); Recordings holds the debug recordings and logs (uploaded, then deleted). On the next start " +
                "the app moves the files it recognises into their folder; anything it does not recognise stays where " +
                "it is, and nothing is overwritten. A file you put at the top by hand is still found. Also: the " +
                "Phase 1 report of the development plan (docs/PHASE_1_BASELINE.md).",
            pr = 8, needsDeviceCheck = true),
        Entry("2026-11-14", Kind.FIXED, "The Home page no longer shows an empty box for the power symbol",
            "The Home page line about the floating icon used the power symbol as text, and your phone's font has no " +
                "power symbol, so it showed an empty box. It now says \"the power button on the floating icon\".",
            pr = 7, needsDeviceCheck = true),
        Entry("2026-11-14", Kind.IMPROVED, "New Sphynx icon; the floating panel has drawn icons and a new look",
            "The app has its new symbol: two interlocking halves around one point of focus (launcher icon, themed " +
                "icon, the header). On the floating panel the AI button had no icon: the panel used text symbols, and " +
                "your phone's font has no power symbol. Every icon on the panel and the round floating icon is now " +
                "drawn, so it looks the same on every phone. The panel is restyled to match: the symbol and a status " +
                "word in the title, evenly sized buttons, and the AI button filled teal while the AI is on. The round " +
                "icon shows the symbol with ON / DRY / ERR / OFF under it and a ring in the status colour.",
            pr = 6, needsDeviceCheck = true),
        Entry("2026-11-14", Kind.NEW, "GGUF models: add, load and ask them on the Model page",
            "The GGUF engine (llama.cpp) is now a real part of the app instead of a one-off test. On the Model page, " +
                "add a .gguf model (it is copied into the app), load it, and ask the test question: the answer streams " +
                "in as it is written, Stop ends it, and the speed is shown. The model stays loaded until you unload it. " +
                "It runs on the CPU. A phone whose processor is too old is told so instead of the app closing. Your " +
                "2026-11-14 tests: Qwen2.5 0.5B answered at 25.5 tokens per second, Llama 3.2 1B at 13.4. The AI " +
                "does not use GGUF models yet.",
            pr = 5, needsDeviceCheck = true),
        Entry("2026-11-14", Kind.IMPROVED, "The app is called Sphynx; descriptions show only while you hold \"?\"",
            "The app's new name is Sphynx: the launcher, the header, the notifications and the pages. Its folder is now " +
                "/CodeOnTheGoProjects/Sphynx-Storage (Download/Sphynx-Storage without All files access), the debug uploads " +
                "and the training notebooks come from the sphynx-ai/SPHYNX repository, and the test branch is " +
                "sphynx-test-branch. The app id stays the same, so the app updates in place and keeps its calibration and " +
                "permissions. The descriptions under the buttons and switches are now hidden (the names stay): press and " +
                "hold the small \"?\" circle next to STOP to see them, and they hide again when you let go. Lines that " +
                "show a live reading stay visible.",
            pr = 4, needsDeviceCheck = true),
        Entry("2026-11-13", Kind.NEW, "The app reads which buff you hold",
            "While you hold a jungle buff, emblems of its colour circle your hero: orange for the orange buff, purple for " +
                "the purple buff (you can hold both). The app now reads them: the floating panel shows \"Buffs held\", the " +
                "log notes each change, and every recording row carries it. The orange reading is measured on your " +
                "2026-11-13 recording (22 of 22 screenshots right). The purple colour is a first guess - that recording has " +
                "no frame with it - so a screenshot is saved with the recording each time a buff reading changes, to " +
                "confirm it. The jungle route does not use the reading yet.",
            pr = 84, needsDeviceCheck = true),
        Entry("2026-11-13", Kind.FIXED, "Phantom 3% monsters, and camp monsters read as enemy heroes",
            "From your 2026-11-13 recording. Small orange / red things next to dark ground (the emblems that circle your hero " +
                "while you hold the orange buff, damage numbers) were read as monsters with 3-5% HP, and the Player \"finished\" them on empty ground: a " +
                "monster or minion bar at 6% or less now only counts where a fuller bar was seen in the last 2 seconds. And " +
                "the first sighting of a camp monster's bar (right above your own bar) was taken for an enemy hero for a " +
                "moment, so the Player backed off in the middle of a camp: while you stand at a jungle camp or buff spot, a " +
                "bar is an enemy hero only when its level number can be read.",
            pr = 82, needsDeviceCheck = true),
        Entry("2026-11-13", Kind.FIXED, "The AI remembers where it got stuck",
            "When the hero walks into a wall the minimap did not show, that place used to be forgotten at the start of " +
                "every match, so it got stuck at the same walls again. Now each stuck place is counted across matches: " +
                "stuck there twice (in any matches) and it is a wall for the path finder from the first second of every " +
                "later match. A place the hero later stands on is forgotten again. Setup page (scenery section): how many " +
                "places are remembered, and a button to forget them.",
            pr = 81, needsDeviceCheck = true),
        Entry("2026-11-13", Kind.FIXED, "Gemma on the GPU; training circles for the AI's fights and yours",
            "Turning the GPU switch on (Model page or the first-run setup) now reloads the model on it at once: in your " +
                "2026-11-13 match Gemma stayed on the CPU (line-up read 122 s, strategy answers 12-17 s). The GPU switch is " +
                "ON on a new install (a speed setting). Learn page: the tactical engine circle now counts only the AI's " +
                "fights, and a new circle counts your own fights (the first 84% was yours). The detector results show how " +
                "many pictures they were tested on.",
            pr = 80, needsDeviceCheck = true),
        Entry("2026-11-13", Kind.FIXED, "Misreads and log noise from the 2026-11-13 recording",
            "The K / D / A counter at the top is no longer read as a jungle monster at 3-5% HP. Recording screenshots: at " +
                "most one \"monster\" a minute and one \"enemy hero\" every 20 s (30 of 39 were monsters). The learned enemy " +
                "tower range no longer jumps (it was 6% to 16% in one match): misreads outside 4-10% are ignored and it " +
                "moves one point at a time. The scoreboard's spell list starts empty in every match, and one scoreboard " +
                "read no longer contradicts the spell button already recognised. An online answer cut off in the middle " +
                "keeps its complete part (the meta update failed on one). The Coach's \"observed your sequence\" line is " +
                "logged only when it changes.",
            pr = 79, needsDeviceCheck = true),
        Entry("2026-11-13", Kind.IMPROVED, "No learning in Solo practice; learning from you by itself in 5v5",
            "Solo practice vs Computer is for calibration only (skill buttons, scenery): nothing is learned or recorded " +
                "there - no recorded moments, no practice benchmark, no minimap or screen training pictures (the tour-stop " +
                "pictures too), no fight scoring and no automatic recording. In a 5v5 match (vs Computer or real players) " +
                "with the Research player off or only advising, the app learns from you by itself, and the floating panel " +
                "says so (or says why it is not learning). Solo or 5v5 is now read from the loading screen and the " +
                "scoreboard even with the Research player off. In the first-run setup the skill button calibration comes " +
                "before the scenery calibration.",
            pr = 78, needsDeviceCheck = true),
        Entry("2026-11-13", Kind.FIXED, "Recordings cover the whole match, without duplicate files",
            "Your 2026-11-13 recording covered only about half of the match: each part stopped at 25 MB (~70 s) and the next " +
                "one had to be started by hand. Now a full part is closed and the next one starts at once, always (not only " +
                "with the automatic upload); the video uses half the data and a part is 50 MB, about 4 minutes. The settings, " +
                "the log, the scenery picture and the research files are saved once per recording (with its last part), not " +
                "with every part, and the CSV copies are gone. The match's own scorecard is saved before the recording is " +
                "closed. When screen capture stops in the middle of a match, the match is closed as unfinished after a " +
                "minute, so its scorecard and recording are still saved.",
            pr = 77, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.FIXED, "The small tactical model keeps learning between trainings",
            "On Kaggle each training of the small tactical model started again from plain Gemma 3 270M. Now the notebook " +
                "also saves a trainable copy (tactical_llm_trainable.zip, ~500 MB) that the app downloads and sends with the " +
                "next training, which continues from it (use Wi-Fi). The app's own copy of that notebook was also out of date: " +
                "it never wrote the quality report, so the Model page could not tell a weak model and the Learn page had no " +
                "score for it. Fixed.",
            pr = 76, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.NEW, "First-run setup",
            "A new install opens a setup screen with six steps that turn green when done: permissions (accessibility, " +
                "display over apps, notifications, all files access), the app's folder, loading the models, choosing what " +
                "runs (every switch starts off; Dry run stays on), scenery calibration and skill button calibration. " +
                "Finish setup goes to Home; Setup > Open the first-run setup opens it again. Every calibration is now on the " +
                "Setup page: the scenery calibration and minimap synchronization moved there from the Player page.",
            pr = 75, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.NEW, "Training progress circles, and training data in the GitHub upload",
            "The Learn page shows a circle for each training (minimap detector, screen detector, tactical networks, small " +
                "tactical model, tactical engine): how much better (green) or worse (red) the last training is than the one " +
                "before. The detector notebooks now report their score. The Debug data page's upload also sends the training " +
                "results and the minimap / screen pictures taken since the last upload (zips of up to 95 MB). Every file is " +
                "deleted from the phone once it is on GitHub; the picture zips used for training are deleted after a " +
                "successful training (the detector keeps what it learned in its best.pt). New buttons: Delete all minimap " +
                "pictures and Delete all screen pictures (Learn page).",
            pr = 74, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.NEW, "New app icon and logo",
            "A sphynx cat head (big ears, amber eyes) with an arrow for a nose and an AI spark, on the dark " +
                "background - Sphynx. It is the launcher icon (also the one-colour themed icon on Android 13+) and " +
                "the logo at the top of the app.",
            pr = 73, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.IMPROVED, "Every switch starts off on a new install",
            "On a fresh install every feature switch starts OFF, so nothing runs until you turn it on (the first-run setup " +
                "helps). Safety guards keep their safe position: Dry run ON and the Quick buy guards ON. A phone that already " +
                "had the app keeps how it worked: switches you never changed stay on.",
            pr = 72, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.IMPROVED, "Minimap and screen picture collectors tuned",
            "Both collectors skip near-duplicate pictures (nothing moved since the last one). The screen collector also takes " +
                "a clean picture at every calibration tour stop (walls, bushes, pits and towers all over the map), keeps one " +
                "monster from filling the set, and skips moments when your HP bar is covered; its labels follow the new rules " +
                "(real enemy heroes only, monsters named by their spots on both lane layouts). Minimap picture names now tell " +
                "the lane layout and Solo / 5v5 (mm_<time>_gs.png).",
            pr = 66, needsDeviceCheck = true),
        Entry("2026-11-12", Kind.NEW, "Recordings continue in a new file at the size limit",
            "Your last recording stopped by itself every ~68 s (the 25 MB video limit) and about 2 minutes of the match were " +
                "lost between files. With the automatic GitHub upload on (and a token), a full recording is now saved and " +
                "uploaded while the next part starts by itself, so the whole match is covered. Without the upload it stops and " +
                "saves at the limit, as before.",
            pr = 65, needsDeviceCheck = true),
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
        Entry("2026-11-11", Kind.IMPROVED, "Without All files access, files go to Download/Sphynx-Storage",
            "Recordings (\u25cf REC), CSV exports, training pictures and zips, cloud-training results and backups are saved in " +
                "Download/Sphynx-Storage when the app has no All files access. The old Download/MLBB-Research folder is " +
                "ignored completely - not written and not read (backups there no longer restore); delete it yourself once you " +
                "have moved anything you want to keep. With All files access nothing changes: " +
                "/CodeOnTheGoProjects/Sphynx-Storage.",
            pr = 52),
        Entry("2026-11-11", Kind.FIXED, "Solo calibration no longer waits for lane marks",
            "Solo practice never shows the \"$\" / \"E\" lane marks (only 5v5 does), so the calibration tour waited forever. In " +
                "Solo, which lane is on top is now read from the Lord and Turtle icons on the minimap: the Lord's pit is on the Gold " +
                "lane's side (Lord top-left = Gold on top). Both icons must show in their pits. Also: the floating panel's title is " +
                "now SPHYNX AI.",
            pr = 51),
        Entry("2026-11-11", Kind.FIXED, "Your lane corrections for 10 heroes",
            "Alpha: Jungle, then EXP · Chou: EXP, then Roam · Lapu-Lapu: EXP only · Lunox: Mid, then Gold · Masha: EXP, then Roam · Minsitthar: EXP, " +
                "then Roam · Paquito: EXP, then Jungle · Phoveus: EXP only · Valentina: Mid, then EXP · Yi Sun-shin: Jungle, then " +
                "Gold. These come before the online guide and the wiki (your own per-hero setting still comes first).",
            pr = 50),
        Entry("2026-11-11", Kind.NEW, "Recommended lanes per hero: primary, secondary and flex pick",
            "Every hero now has a recommended primary lane, a secondary lane, and whether it is a flex pick (playable in almost " +
                "any lane) - from its online guide (now asked for both lanes and flex) and every wiki laning list it is in. Shown " +
                "on the Library's hero list and hero page. The automatic lane uses it: the primary lane; without Retribution never " +
                "the Jungle (the secondary instead); a flex pick takes its secondary when an ally's primary is the same lane. Your own " +
                "setting per hero comes first (Player page, under the selected hero: e.g. \"exp, jungle\" or \"exp, jungle, flex\"), " +
                "then lanes you confirmed (Alice: EXP, then Jungle - not Roam or Mid), then the online guide, then the wiki.",
            pr = 49),
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
            pr = 48),
        Entry("2026-11-11", Kind.IMPROVED, "Every jungle monster named; the Stone Roamer is not counted as an ally",
            "From your 20 jungle screenshots: each monster is named from where you stand on the minimap and its bar's size - " +
                "Thunder Fenrir, Molten Fiend, Lava Golem, Fire Beetle, Horned Lizard, Scavenger Crab (both river ends) and the " +
                "Lithowanderer (in the river by the mid crossing), on both sides of the map. After a Lithowanderer kill, the Stone " +
                "Roamer (blue bar, no level badge) is a friendly patrol for 45 s: not counted as an ally hero or minion. The Walkie " +
                "Grass is never a target. The jungle route uses these camp positions too (the Rockursa is replaced by the Lava Golem; the Lizard, " +
                "Fire Beetle and Lithowanderer were in the wrong places), and the scenery calibration tour visits the real pits.",
            pr = 47),
        Entry("2026-11-11", Kind.IMPROVED, "Scenery calibration tour reaches the enemy base; never into the enemy fountain",
            "The calibration tour now also visits the enemy inner and base towers of all three lanes and the enemy base itself " +
                "(placed from the towers on your minimap), then walks back to your base. Meant for 1v1 vs Computer with god mode on. " +
                "In every mode the Research player never enters the enemy fountain: inside it walks out, at its edge no step or skill leads in.",
            pr = 46),
        Entry("2026-11-11", Kind.NEW, "Scenery calibration ends with a walk back to base",
            "After the last stop of the calibration tour the Research player walks back to its base (base gate, then the " +
                "fountain), still without fighting. The scenery keeps being read on the way and is re-synced and saved at both " +
                "stops, so the walk back revalidates what the tour learned. The panel shows \"returning to base 1/2\".",
            pr = 45),
        Entry("2026-11-11", Kind.IMPROVED, "Performance page redesigned",
            "Laid out like a phone performance screen: the phone's name, then one card per topic with a big headline number " +
                "and label / value rows - memory (ring of RAM in use, available and total, 5-minute line), storage, CPU (one tile " +
                "per core with its clock and maximum), graphics processor, NPU drivers, temperature and battery, local models. " +
                "The repeated phone figures are gone; the AI's speed and the speed recorder follow in the same style.",
            pr = 44),
        Entry("2026-11-11", Kind.IMPROVED, "Research player; shorter floating panel; calibration without fighting; Performance page",
            "The Player switch is now \"Research player\" and the floating panel says so (no more \"Player + Research + Learning " +
                "from you\"; learning is named only while coach mode is on). The panel shows less: what runs, the practice check and " +
                "its last reading, what the Player does, the match in one line. Scenery calibration starts and stops from the panel " +
                "(CALIBRATE / STOP CALIB) and the Player does not fight while it runs. The Speed page is now the Performance page: " +
                "the phone's memory, storage, CPU and cores, graphics processor, NPU drivers, temperature and battery, and the local models.",
            pr = 43),
        Entry("2026-11-10", Kind.FIXED, "Practice check reads \"Computer\" at the app's capture size",
            "The app reads the screen at 800x359, where \"Computer\" is only about 49x9 pixels: the practice check read it as " +
                "unclear (0.66) and never unlocked the Player. It now matches the word at that size (0.92 on the scoreboard, " +
                "0.81-0.89 on loading cards; real players' names 0.50 at most), and finds the scoreboard rows with a player there too.",
            pr = 42),
        Entry("2026-11-10", Kind.NEW, "Practice check: 5v5 loading screen read without Gemma",
            "A 5v5 practice match vs Computer is verified on its loading screen from the picture alone, like solo practice: " +
                "the 9 other cards must read \"Computer\" (measured 0.90-0.94 on your screenshot; real players' names 0.30 at most).",
            pr = 41),
        Entry("2026-11-10", Kind.FIXED, "Practice check: solo loading card read without Gemma; last reading on the panel; vibration",
            "Solo practice is verified from the enemy card's \"Computer\" on the loading screen, from the picture alone (Gemma " +
                "not needed). The floating panel shows the practice check's last reading (what it saw and its scores), and the " +
                "phone vibrates once when the Player is unlocked and three times when a real player locks the match.",
            pr = 40),
        Entry("2026-11-10", Kind.FIXED, "Solo practice is identified; scenery calibration waits for the unlocked Player",
            "Solo practice (you vs one Computer) was never identified as a match - its loading screen was not recognised and a " +
                "quick restart was not seen as a new match - so the practice check never unlocked the Player. Both are fixed. " +
                "The scenery calibration tour now starts only once the practice check has unlocked the Player.",
            pr = 39),
        Entry("2026-11-10", Kind.FIXED, "Practice check from the loading screen; overlay hidden on loading and scoreboard; one Player switch",
            "The floating window and the boxes are hidden on the loading screen and the scoreboard (they covered the names, so a " +
                "practice match was locked). A practice match is now verified on the loading screen when the 9 others are " +
                "\"Computer\", and a real player must be seen twice in a row to lock it. The Player and research mode are one switch. " +
                "Solo practice (you and one Computer enemy) is verified too: empty scoreboard rows are skipped. " +
                "A Blessed Retribution icon now counts as Retribution, so the hero goes to the jungle.",
            pr = 38),
        Entry("2026-11-10", Kind.NEW, "Speed recorder: both language models and screen to touch",
            "Speed page: every answer of the strategist and the tactical model is recorded (time, engine, GPU or CPU, with or " +
                "without a picture, the phone's temperature and free memory), and while the Player plays, the screen-to-touch " +
                "time every 5 s. Optional detailed figures (tokens per second, time to the first token), a speed test with the " +
                "same questions every time, and a CSV export to send. It only measures.",
            pr = 35),
        Entry("2026-11-10", Kind.NEW, "Practice benchmark: a scorecard for every practice match",
            "New Practice page. Every practice match vs Computer that the Player plays gets a scorecard: result, K/D/A, level at " +
                "5 and 10 minutes, how long the AI stood idle or got stuck, and every death with what the AI was doing just before " +
                "(fighting, farming, escaping; outnumbered or not). The page shows the trend (last 5 matches vs the 5 before), " +
                "per hero, and the most common death causes; export as CSV. It only measures - it never touches the game.",
            pr = 31),
        Entry("2026-11-10", Kind.IMPROVED, "Player mode is for practice vs Computer only",
            "Your decision: the Player plays the hero only in a match verified as practice against Computer (open the scoreboard " +
                "once). In Ranked, Classic or any other match it becomes your companion: the strategist and the tactical advisor " +
                "keep working and show their advice on the floating panel, including which skill to level up next, but nothing is " +
                "touched (skills are leveled for you only in practice). Drafter and Builder stay automatic in every match, and the " +
                "Builder now also presses item actives (Frozen, Wind Chant, Conceal) when the Player is not playing.",
            pr = 30),
        Entry("2026-11-09", Kind.IMPROVED, "Small tactical model is no longer experimental",
            "Your decision: Gemma 3 270M is a full engine now. Its fight advice can be used like the other engines' - in the decision " +
                "arbiter too - but it still has to pass the app's Kotlin checks and the validator (no all-in at low HP, etc.).",
            pr = 29),
        Entry("2026-11-08", Kind.FIXED, "Skills released where they were aimed; no more chasing the Lithowanderer",
            "From your 13 recordings: a skill was re-aimed at release with the AI's newest decision - a third of skills went 90 degrees " +
                "off (\"aimed right, cast elsewhere\"). Now it releases at the target it was aimed at. On-screen \"enemies\" the minimap " +
                "never shows (the Lithowanderer's thin bar, companions) are dropped, the in-match scoreboard is recognised reliably " +
                "(the AI no longer plays on it), and the floating window's buttons stay one line and the same size.",
            pr = 28, issue = 2),
        Entry("2026-11-08", Kind.NEW, "Session recorder: data file + video for bug reports",
            "Logs page > Start recording. It saves what the AI saw and decided (about 10 times a second, every aimed skill " +
                "as planned and as released, the log) and a video of the AI's view with its targets, joystick and aim drawn on " +
                "top, both stamped with the clock and match timer. Files go to Sphynx-Storage/recordings. " +
                "It stops and saves by itself when the video reaches 25 MB (a GitHub website upload) or 95 MB (git). " +
                "Also on the floating window: ● REC starts, ■ REC stops and saves; the round icon gets a red ring while recording. " +
                "Research mode is recorded too (verified practice, the arbiter / normal group, each arbiter choice and scored fight), " +
                "and saving also copies the R5 comparison, the practice moments and the readiness report next to the recording.",
            pr = 27),
        Entry("2026-11-08", Kind.FIXED, "Jungle fights: no more leaving mid-fight, getting stuck or phantom enemies",
            "From your device test: the hero no longer walks to the next camp while the monster still has HP (hidden HP bars " +
                "no longer count as a kill), stands still and attacks when a big monster's body stops it, and a jungle monster's " +
                "red HP bar is no longer taken for an enemy hero on the map.", pr = 26, issue = 2),
        Entry("2026-11-08", Kind.NEW, "Sphynx is its own app now",
            "New app name everywhere (launcher, header, Accessibility list, notification) and a new app id, " +
                "com.sphynx.autopilot: it installs next to the old MLBB Research app. Give it its permissions again; " +
                "with All files access on it also finds the old app's exports, results and backups in Download/MLBB-Research.",
            pr = 25),
        Entry("2026-11-08", Kind.IMPROVED, "One app folder: /CodeOnTheGoProjects/Sphynx-Storage (v37.19)",
            "Models, detectors, networks and keys are read from it, and CSV exports, training pictures and zips, Kaggle results " +
                "and backups are saved in it. Turn on \"All files access\" once on the Model page. Until then everything works " +
                "as before (the folder you choose, and Download/MLBB-Research for the app's own files).",
            pr = 24),
        Entry("2026-11-08", Kind.NEW, "Arbiter vs normal comparison in practice (research R5)",
            "Each verified practice match is randomly put in an arbiter or a normal group; fight scores, deaths, K/D/A and results " +
                "are compared under Research mode, with \"not enough data\" until each group has 5 matches.",
            pr = 21),
        Entry("2026-11-08", Kind.FIXED, "Enemies under your green minimap ring; monsters out of range",
            "Enemy icons half covered by your own green ring no longer turn into phantom or duplicate enemies, and a covered " +
                "enemy is kept for a moment instead of vanishing (minimap and screen are combined into one target list). " +
                "Against jungle monsters the hero now walks into its own attack range and never steps out of the monster's aggro zone.",
            pr = 22, issue = 2),
        Entry("2026-11-08", Kind.NEW, "Small model readiness test (research R4)",
            "In verified practice, fight moments are recorded (the situation, what the app played, which options were dangerous). " +
                "A button under Research mode tests the small tactical model on them against the approved criteria. " +
                "A pass does not switch the model on.", pr = 20),
        Entry("2026-11-08", Kind.NEW, "Arbiter picks the fight option in practice (research R3)",
            "Only in a verified practice match against Computer, with Research and Player mode on, the decision arbiter chooses " +
                "the fight option. Retreat wins any fight-or-retreat conflict, and every pick must pass the app's own checks.",
            pr = 19),
        Entry("2026-11-08", Kind.NEW, "Research mode switch with live practice check (research R2)",
            "A Research mode switch next to Player, Drafter and Builder. It reads the in-match scoreboard and shows " +
                "\"verified practice\" only when all 9 other players are Computer; any human locks the match.",
            pr = 18),
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

    fun ref(e: Entry): String = (e.version?.let { "v$it · " } ?: "") + "#${e.pr}" + (e.issue?.let { " · issue #$it" } ?: "")
}
