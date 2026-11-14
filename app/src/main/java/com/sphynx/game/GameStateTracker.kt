package com.sphynx.game

import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import com.sphynx.vision.IconMemory
import com.sphynx.vision.HudInfo
import com.sphynx.vision.PortraitMatch
import com.sphynx.vision.ScreenHint
import com.sphynx.vision.VisionResult

// Turns raw vision into a GameState and remembers what Gemma learned
// (which screen it is, the draft teams, your hero, items and gold).
object GameStateTracker {

    @Volatile var state: GameState = GameState.EMPTY
        private set

    private var lastSelfSeen = 0L
    private var lastInGame = 0L
    private var lastSelectSeen = 0L
    private var hintRun = 0
    private var lastHint = ScreenHint.NONE

    // when the current phase began (for the Phase display and the standard hero select)
    @Volatile var phaseSince = 0L
        private set

    // ---- facts reported by Gemma (StrategicAdvisor) ----
    @Volatile private var gemmaScreen: Phase = Phase.UNKNOWN
    @Volatile private var gemmaScreenAt = 0L
    @Volatile var myTeam: List<String> = emptyList()
    @Volatile var enemyTeam: List<String> = emptyList()
    // each enemy hero's battle spell (read on the loading screen / scoreboard)
    @Volatile var enemySpells: Map<String, String> = emptyMap()
    @Volatile var allySpells: Map<String, String> = emptyMap()
    @Volatile var lineupReadAt = 0L                 // when the 10 cards were last read (0 = not this match)
    @Volatile var loadingCardName: String? = null  // 1. Name- detected on my loading screen card (v37.01)
    @Volatile var loadingHero: String? = null      // 2. Hero- detected on my loading screen card (v36.82)
    @Volatile var scoreboardHero: String? = null   // my row on the SCOREBOARD (v36.82)
    @Volatile var minimapWikiHero: String? = null   // minimap portrait vs the wiki's pictures (last resort, v36.88)
    @Volatile var buttonSpell: String? = null      // 2nd priority: in-game spell button verified vs wiki pictures (v37.00)
    @Volatile var scoreboardSpell: String? = null  // 3rd priority: in-game scoreboard spell icon verified vs wiki pictures (v37.00)
    @Volatile var loadingSpell: String? = null     // 3. Spell- loading screen card spell icon verified vs wiki pictures (v37.00)
    @Volatile var gemmaLoadingSpell: String? = null // Gemma's loading card spell reading (used as hint for wiki icon verification)
    @Volatile var blessedRetribution: String? = null // "Bloody", "Ice", or "Flame" (Absolute priority: Upgraded Retribution > Normal Retribution)
    @Volatile var loadingGreen: Pair<Int, Int>? = null  // your card on the loading screen (green name): row, card
    @Volatile var nameMatch: String? = null         // your player name found on screen (floating panel, v36.85)
    @Volatile var verifiedPlayerName: String? = null // your verified player name found on loading screen / scoreboard / end board
    @Volatile var guessedHero: String? = null      // recognised in game by Gemma
    @Volatile var modelHero: String? = null        // recognised from the in-game model (IconMemory, v36.80)
    @Volatile var portraitHero: String? = null     // recognised from the minimap portrait (IconMemory, v36.80)
    @Volatile var iconHero: String? = null         // recognised from its minimap portrait + model (IconMemory)
    @Volatile var gemmaSpell: String? = null       // battle spell read by Gemma this match
    @Volatile var iconSpell: String? = null        // battle spell recognised from its learned icon
    @Volatile var lastHeroSig: List<FloatArray>? = null
        private set
    @Volatile var lastSpellIcon: FloatArray? = null
        private set
    @Volatile var matchStartAt = 0L
        private set
    @Volatile var spawnAt = 0L
        private set

    // K / D / A seen this match (counted from the moment the app started watching)
    @Volatile var kills = 0
        private set
    @Volatile var deaths = 0
        private set
    @Volatile var assists = 0
        private set
    @Volatile var lastKillAt = 0L
        private set
    @Volatile var lastDeathAt = 0L
        private set
    @Volatile var lastAssistAt = 0L
        private set
    // last frame on which the camera moved (no movement while walking = stuck on a wall)
    @Volatile var lastMotionAt = 0L
        private set
    @Volatile private var items = -1
    @Volatile private var gold = -1
    @Volatile private var itemsGoldAt = 0L

    // THE VERIFIED HERO (v37.01, your order):
    // Returns ONLY a verified hero (entered by you, locked at match end, read on your loading screen card,
    // verified by in-game skill icons, read on the scoreboard, or agreed on by 2+ independent in-game checks)
    fun verifiedHero(): String? {
        val entered = HeroDb.match(Cfg.manualHero) ?: Cfg.manualHero.trim().takeIf { it.isNotEmpty() }
        if (entered != null) return entered
        if (lockedHero != null) return lockedHero
        if (loadingHero != null) return loadingHero
        val sh = com.sphynx.vision.HeroVerifier.skillHero
        if (sh != null) return sh
        val sb = scoreboardHero?.takeIf { HeroKit.fitsScreen(it) }
        if (sb != null) return sb
        val pp = portraitHero?.takeIf { HeroKit.fitsScreen(it) }
        val mw = minimapWikiHero?.takeIf { HeroKit.fitsScreen(it) }
        val mm = modelHero?.takeIf { HeroKit.fitsScreen(it) }
        if (pp != null && (pp == mw || pp == mm)) return pp
        if (mw != null && mw == mm) return mw
        return null
    }

    fun isHeroVerified(): Boolean = verifiedHero() != null

    fun verifiedHeroPanel(): String {
        val vh = verifiedHero()
        return if (vh != null) "✓ $vh (${ownHeroSource()})" else "verifying…"
    }

    fun verifiedNamePanel(): String {
        val vp = verifiedPlayerName
        val entered = PlayerName.get()
        return when {
            vp != null -> "✓ $vp"
            entered.isNotBlank() && loadingGreen != null -> "✓ $entered (green card)"
            loadingGreen != null -> "✓ green-name card"
            entered.isNotBlank() -> "$entered (waiting to verify on screen)"
            else -> "-"
        }
    }

    // WHAT YOU ARE PLAYING - absolute priority (v37.01, your order):
    // Verified hero comes first; unverified single-source fallbacks are used only until verification completes.
    fun ownHero(): String? = verifiedHero()
        ?: portraitHero?.takeIf { HeroKit.fitsScreen(it) }
        ?: minimapWikiHero?.takeIf { HeroKit.fitsScreen(it) }
        ?: modelHero?.takeIf { HeroKit.fitsScreen(it) }
        ?: HeroDb.match(Cfg.myHero)?.takeIf { HeroKit.fitsScreen(it) }
        ?: guessedHero?.takeIf { HeroKit.fitsScreen(it) }

    fun ownHeroSource(): String = when {
        Cfg.manualHero.isNotBlank() -> "entered by you (absolute)" + if (HeroDb.match(Cfg.manualHero) == null) " - not in the hero list yet: role defaults until its skills are learned online" else ""
        lockedHero != null -> "locked at match end"
        loadingHero != null -> "named on the loading screen (your card)"
        com.sphynx.vision.HeroVerifier.skillHero != null -> "verified in game from its skill set"
        scoreboardHero != null && HeroKit.fitsScreen(scoreboardHero) -> "verified on the scoreboard"
        portraitHero != null && HeroKit.fitsScreen(portraitHero) && (portraitHero == minimapWikiHero || portraitHero == modelHero) -> "verified from portrait + model"
        portraitHero != null && HeroKit.fitsScreen(portraitHero) -> "recognised from its minimap portrait"
        minimapWikiHero != null && HeroKit.fitsScreen(minimapWikiHero) -> "minimap portrait vs the wiki pictures"
        modelHero != null && HeroKit.fitsScreen(modelHero) -> "recognised from its in-game model"
        HeroDb.match(Cfg.myHero) != null -> "the hero you played last"
        guessedHero != null -> "recognised by Gemma"
        else -> "unknown"
    }

    // THE VERIFIED SPELL (v37.01, your order):
    //  1. Entered spell (chosen by you on the Player page)
    //  2. Locked spell at match end
    //  3. Spell icon from the in-game spell button (verified vs wiki spell icons; Upgraded Retribution > Normal Retribution)
    //  4. Spell icon from the in-game scoreboard (verified vs wiki spell icons)
    //  5. Loading screen spell icon (verified vs wiki spell icons)
    //  6. Learned icon + Gemma agreement (or learned icon matching loading/scoreboard)
    fun verifiedSpell(): String? = SpellDb.match(Cfg.mySpell)
        ?: lockedSpell
        ?: buttonSpell
        ?: scoreboardSpell
        ?: loadingSpell
        ?: iconSpell?.takeIf { it == gemmaSpell || it == gemmaLoadingSpell || it == "Retribution" }

    fun isSpellVerified(): Boolean = verifiedSpell() != null

    fun spell(): String? = verifiedSpell()
        ?: iconSpell
        ?: gemmaSpell
        ?: gemmaLoadingSpell

    // Whether Retribution has already been detected (and not another verified spell)
    fun isRetributionDetected(): Boolean {
        val entered = SpellDb.match(Cfg.mySpell)
        if (entered != null) return entered == "Retribution"
        if (blessedRetribution != null) return true
        return buttonSpell == "Retribution" ||
            scoreboardSpell == "Retribution" ||
            loadingSpell == "Retribution" ||
            iconSpell == "Retribution" ||
            gemmaSpell == "Retribution" ||
            gemmaLoadingSpell == "Retribution"
    }

    // Absolute priority for Retribution:
    // If Retribution is detected (and not the other spells), Upgraded version of Retribution (Bloody, Ice, Flame) > Normal Retribution
    fun spellDisplay(): String? {
        val base = verifiedSpell() ?: spell() ?: return null
        val blessed = blessedRetribution
        return if (base == "Retribution" && blessed != null) upgradedText(blessed) else base
    }

    // Retribution was already known (loading screen, scoreboard, Gemma) and the in-game spell button
    // now shows a Blessed one: it is reported only as the upgrade, e.g. "Retribution Upgraded to Bloody"
    fun upgradedText(blessed: String): String = "Retribution Upgraded to $blessed"

    fun verifiedSpellPanel(): String {
        val vBase = verifiedSpell() ?: return "verifying…"
        val blessed = blessedRetribution
        val disp = if (vBase == "Retribution" && blessed != null) upgradedText(blessed) else vBase
        return "✓ $disp (${spellSource()})"
    }

    fun spellSource(): String = when {
        lockedSpell != null -> "locked at match end" + (if (blessedRetribution != null && lockedSpell == "Retribution") " · Upgraded > Normal Retribution" else "")
        blessedRetribution != null && (verifiedSpell() ?: spell()) == "Retribution" -> "in-game spell button (Upgraded $blessedRetribution Retribution > Normal Retribution)"
        SpellDb.match(Cfg.mySpell) != null -> "chosen by you (absolute)"
        buttonSpell != null -> "in-game spell button (verified vs wiki icon)"
        scoreboardSpell != null -> "in-game scoreboard (verified vs wiki icon)"
        loadingSpell != null -> "loading screen card (verified vs wiki icon)"
        iconSpell != null && (iconSpell == gemmaSpell || iconSpell == gemmaLoadingSpell || iconSpell == "Retribution") -> "verified by learned icon"
        iconSpell != null -> "recognised from learned icon"
        gemmaSpell != null || gemmaLoadingSpell != null -> "read by Gemma"
        else -> "unknown"
    }

    private fun refreshNameMatchSpell() {
        val nm = nameMatch ?: return
        val sp = spellDisplay() ?: return
        val idx = nm.lastIndexOf(" · ")
        nameMatch = if (idx > 0) nm.substring(0, idx) + " · $sp" else "$nm · $sp"
    }

    // 2nd priority: in-game spell button verified against the Library's wiki spell icons
    // Absolute priority for Retribution: Upgraded version of Retribution (Bloody, Ice, Flame) > Normal Retribution
    fun onButtonSpellVerified(rawSpell: String, score: Float) {
        val blessed = PortraitMatch.blessedKind(rawSpell)
        if (blessed != null) {
            // a Blessed icon exists only for Retribution: with NO spell known yet (the loading card and the
            // first button readings were missed) it proves Retribution, so the hero goes to the jungle
            // (bug 5, device test 2026-11-10: Retribution equipped, no jungling). Another spell known -> ignored.
            if (!isRetributionDetected() && spell() != null) return
            buttonSpell = "Retribution"
            iconSpell = "Retribution"
            if (blessedRetribution != blessed) {
                blessedRetribution = blessed
                Diag.info(upgradedText(blessed))
                refreshNameMatchSpell()
            }
            return
        }
        val canon = SpellDb.match(rawSpell) ?: return
        // If Upgraded Retribution is already active and normal Retribution is seen, keep the Upgraded version (Upgraded > Normal)
        if (canon == "Retribution" && blessedRetribution != null) {
            buttonSpell = "Retribution"
            return
        }
        // Do not let a single noisy reading overwrite Retribution if scoreboard/loading/learned icon already verified Retribution
        if (canon != "Retribution" && (scoreboardSpell == "Retribution" || loadingSpell == "Retribution" || blessedRetribution != null)) return
        if (buttonSpell != canon) {
            buttonSpell = canon
            iconSpell = canon
            Diag.info("Recognised your battle spell from in-game spell button: $canon (%.2f, wiki pictures)".format(score))
            refreshNameMatchSpell()
        }
    }

    // 3rd priority: in-game scoreboard spell icon verified against the 12 base wiki spell icons
    fun onScoreboardSpellVerified(rawSpell: String, score: Float) {
        val canon = SpellDb.match(rawSpell) ?: return
        // the spell BUTTON was already recognised: one scoreboard read that says otherwise does not replace it
        // (2026-11-13 log: "Vengeance" on the scoreboard in the middle of a Retribution match)
        if (buttonSpell != null && buttonSpell != canon) return
        if (scoreboardSpell != canon) {
            scoreboardSpell = canon
            if (buttonSpell == null) iconSpell = canon
            Diag.info("Recognised your battle spell on the scoreboard: $canon (%.2f, wiki pictures)".format(score))
            refreshNameMatchSpell()
        }
    }

    // 4th priority: loading screen spell icon verified against the 12 base wiki spell icons
    fun onLoadingSpellVerified(rawSpell: String, score: Float) {
        val canon = SpellDb.match(rawSpell) ?: return
        if (loadingSpell != canon) {
            loadingSpell = canon
            if (buttonSpell == null && scoreboardSpell == null) iconSpell = canon
            Diag.info("Recognised your battle spell on the loading screen: $canon (%.2f, wiki pictures)".format(score))
            refreshNameMatchSpell()
        }
    }

    // 1. Name, 2. Hero, 3. Spell on the loading screen:
    // Priority for loading screen spell:
    //  - If PortraitMatch already verified the loading card's spell icon against the wiki pictures, keep it;
    //  - Otherwise record Gemma's reading as gemmaLoadingSpell (and trigger wiki icon verification).
    fun onLoadingCardRead(cardPlayerName: String?, hero: String, spellFromCard: String?, via: String) {
        val h = HeroDb.match(hero) ?: return
        if (!cardPlayerName.isNullOrBlank()) {
            loadingCardName = cardPlayerName.trim()
            verifiedPlayerName = loadingCardName
        }
        loadingHero = h
        Cfg.myHero = h
        if (!spellFromCard.isNullOrBlank()) {
            // Gemma's reading is only a HINT (owner decision 2026-11-07): loadingSpell is set only when
            // the card's spell icon matches the wiki picture (onLoadingSpellVerified)
            SpellDb.match(spellFromCard)?.let { sp -> gemmaLoadingSpell = sp }
        }
        val dispSpell = spellDisplay()
        val dispName = loadingCardName ?: PlayerName.get().ifBlank { "you" }
        nameMatch = "$dispName → $h" + (if (dispSpell != null) " · $dispSpell" else "")
        Diag.info("Loading card verified ($via): 1. Name=$dispName · 2. Hero=$h · 3. Spell=${dispSpell ?: "checking icon…"}")
    }

    // Verified on the in-game scoreboard (or end-of-match scoreboard)
    fun onScoreboardVerified(playerNameFound: String?, hero: String, spellFromRow: String? = null, lockForMatch: Boolean = false) {
        val h = HeroDb.match(hero) ?: return
        if (!playerNameFound.isNullOrBlank()) {
            verifiedPlayerName = playerNameFound.trim()
        }
        scoreboardHero = h
        if (lockForMatch) {
            lockedHero = h
            spellFromRow?.let { SpellDb.match(it) }?.let { lockedSpell = it }
        }
        Cfg.myHero = h
        val dispSpell = spellDisplay()
        val dispName = verifiedPlayerName ?: PlayerName.get().ifBlank { "you" }
        nameMatch = "$dispName → $h" + (if (dispSpell != null) " · $dispSpell" else "")
    }

    // What lane / role you play:
    //  1. what you chose on the Player page
    //  2. Retribution -> Jungle (the jungler's spell)
    //  3. the hero's usual lane from the wiki (WikiLanes), else its role (HeroDb)
    fun lane(): Pair<Lane, String> =
        LaneLogic.effective(ownHero(), spell())

    // You just recalled to base: the walking-to-lane window starts again
    fun markSpawn() { spawnAt = System.currentTimeMillis() }

    // True only when the app saw the match start (loading screen): started mid-match, matchStartAt is
    // only the moment the app was turned on, not 0:00 of the game
    fun clockFromStart(): Boolean = loadingSeenAt != 0L && matchStartAt > 0L && matchStartAt - loadingSeenAt in 0..180_000
    private var loadingSeenAt = 0L
    // id of the match being recorded (stamped on every coaching sample); 0 = not in a match
    @Volatile var matchId = 0L
        private set
    @Volatile var lastMatchId = 0L
        private set

    // Remembered looks (IconMemory): both the minimap portrait AND the in-game model are checked on
    // their own (each needs 2 readings in a row), and when one is sure and the other is still
    // unknown it teaches the other - so learning on the Heroes page or from one source is enough.
    // Once the end-of-match scoreboard has verified your hero and spell, they are locked in for the
    // rest of the match and the looks on screen are saved under that truth (v36.87).
    private var spellLearnedAt = 0L
    private var modelCand: String? = null
    private var modelCandN = 0
    private var portraitCand: String? = null
    private var portraitCandN = 0
    private var lookLogged: String? = null
    private var spellLogged: String? = null
    @Volatile var lockedHero: String? = null
    @Volatile var lockedSpell: String? = null

    private fun recognise(v: VisionResult) {
        val enteredHero = HeroDb.match(Cfg.manualHero)
        val enteredSpell = SpellDb.match(Cfg.mySpell)
        v.heroSig?.let { sig ->
            lastHeroSig = sig
            val LOCK = lockedHero
            if (LOCK != null) {
                modelHero = LOCK; portraitHero = LOCK; iconHero = LOCK
                IconMemory.learnHero(LOCK, sig)
                return@let
            }
            val pHit = if (sig.isNotEmpty()) IconMemory.matchPortrait(sig) else null
            val mHit = if (sig.size >= 2) IconMemory.matchModel(sig) else null
            if (pHit != null && HeroKit.fitsScreen(pHit)) {
                if (pHit == portraitCand) portraitCandN++ else { portraitCand = pHit; portraitCandN = 1 }
                if (portraitCandN >= 2) portraitHero = pHit
            }
            if (mHit != null && HeroKit.fitsScreen(mHit)) {
                if (mHit == modelCand) modelCandN++ else { modelCand = mHit; modelCandN = 1 }
                if (modelCandN >= 2) modelHero = mHit
            }
            val hit = IconMemory.matchHero(sig)
            if (hit != null && HeroKit.fitsScreen(hit.first)) iconHero = hit.first
            // Teacher priority: only verified sources (manual entry, loading screen card, scoreboard, or skill verifier)
            // may teach IconMemory so an unverified guess never pollutes learned portraits.
            val teacher = enteredHero ?: loadingHero ?: scoreboardHero ?: com.sphynx.vision.HeroVerifier.skillHero
            if (teacher != null && HeroKit.fitsScreen(teacher)) {
                if (pHit == null || pHit != teacher || mHit == null || mHit != teacher) {
                    IconMemory.learnHero(teacher, sig)
                    if (lookLogged != teacher) {
                        lookLogged = teacher
                        Diag.info("Learned $teacher's look (portrait + in-game model)")
                    }
                }
            }
        }
        v.spellIcon?.let { sp ->
            lastSpellIcon = sp
            val LOCK = lockedSpell
            if (LOCK != null) {
                iconSpell = LOCK
                IconMemory.learnSpell(LOCK, sp)
                return@let
            }
            val hit = IconMemory.matchSpell(sp)
            if (hit != null) {
                // Only set iconSpell from IconMemory if a higher-priority wiki-verified spell (buttonSpell / scoreboardSpell / loadingSpell) has not already locked it
                if (buttonSpell == null && scoreboardSpell == null && loadingSpell == null) {
                    iconSpell = hit.first
                }
            }
            val teacher = enteredSpell ?: buttonSpell ?: scoreboardSpell ?: loadingSpell
            if (teacher != null && (hit == null || hit.first != teacher) && v.ts - spellLearnedAt > 4000) {
                IconMemory.learnSpell(teacher, sp)
                spellLearnedAt = v.ts
                if (spellLogged != teacher) {
                    spellLogged = teacher
                    Diag.info("Learned the $teacher icon")
                }
            }
        }
    }

    fun reportScreen(p: Phase) {
        gemmaScreen = p
        gemmaScreenAt = System.currentTimeMillis()
    }

    fun reportItemsGold(i: Int, g: Int) {
        if (i >= 0) items = i
        if (g >= 0) gold = g
        itemsGoldAt = System.currentTimeMillis()
    }

    fun update(v: VisionResult): GameState {
        val now = v.ts
        // watching someone's live match (and up to 4 s after its last frame)
        // (LIVE_DEAD: the watched hero is dead, nothing on screen is theirs)
        // (v36.94: within a minute of YOUR OWN match - its last seconds, the end screens - a "live match"
        // reading is wrong: your log showed a live clock and a watched hero read right after your match)
        val ownRecent = now - lastInGame < 60_000 || now - lastMatchEndAt < 60_000
        if (!ownRecent && (v.hint == ScreenHint.LIVE || v.hint == ScreenHint.LIVE_DEAD)) lastLiveAt = now
        if (!ownRecent && (v.hint == ScreenHint.LIVE || v.hint == ScreenHint.LIVE_DEAD || (now - lastLiveAt in 0..4000 && v.hint == ScreenHint.NONE)))
            return liveState(v, now, v.hint == ScreenHint.LIVE)
        val wasInMatch = now - lastInGame < 120_000
        val before = state.phase
        // (or the match clock is not running: a new match started less than 2 minutes after the last one
        // ended - quick solo practice restarts, owner 2026-11-10 - used to stay unidentified for good)
        if (v.self != null && (!wasInMatch || matchStartAt == 0L)) {
            // first look at your hero after hero select / loading: the match clock starts
            logMatch()                      // a match without a hero-select screen in between
            kills = 0; deaths = 0; assists = 0
            matchStartAt = now
            MapSide.reset()                 // a new match: its lane marks are read again
            com.sphynx.vision.BuffEmblems.reset()
            // (the loading screen was missed: the match is recorded from here)
            if (matchId == 0L) {
                matchId = now; Diag.info("Match recording started (hero seen; no loading screen)")
                enemySpells = emptyMap(); allySpells = emptyMap()      // (the last match's scoreboard spells are not this match's)
                com.sphynx.core.SessionRecorder.matchStarted()
            }
            com.sphynx.vision.ScreenReader.clearResult()
            spawnAt = now
            com.sphynx.vision.LevelReader.reset()
            com.sphynx.controller.ActionExecutor.resetMatchUpgrades()
        }
        recognise(v)
        // being hit: your HP fell 6%+ within half a second -> the AI reacts on this very frame
        v.self?.let { me ->
            hpHist.addLast(now to me.hp)
            while (hpHist.size > 1 && now - hpHist.first().first > 500) hpHist.removeFirst()
            if (hpHist.first().second - me.hp >= 0.06f) hurtAt = now
        }
        if (v.kda[0] > 0) { kills++; lastKillAt = now; Diag.info("Kill (K/D/A)") }
        if (v.kda[1] > 0) { deaths++; lastDeathAt = now }
        if (v.kda[2] > 0) { assists++; lastAssistAt = now }
        if (v.hud.motionX != 0f || v.hud.motionY != 0f) lastMotionAt = now
        if (v.self != null) {
            lastSelfSeen = now
            lastInGame = now
        }
        // a screen hint must be seen on 2 frames in a row, so one odd frame cannot flip the phase
        hintRun = if (v.hint == lastHint) hintRun + 1 else 1
        lastHint = v.hint
        val hint = if (hintRun >= 2) v.hint else ScreenHint.NONE
        val inMatch = now - lastInGame < 120_000
        val phase = when {
            hint == ScreenHint.MAP && inMatch -> Phase.MAP
            v.self != null -> Phase.IN_GAME
            hint == ScreenHint.SHOP && inMatch -> Phase.SHOP
            hint == ScreenHint.SCOREBOARD && inMatch -> Phase.SCOREBOARD
            hint == ScreenHint.DEAD -> Phase.DEAD                // the respawn countdown
            hint == ScreenHint.RESULT_SPLASH || hint == ScreenHint.RESULT_STATS || hint == ScreenHint.RESULT_BOARD -> Phase.RESULT
            now - lastSelfSeen < 3000 -> Phase.IN_GAME          // briefly hidden (effects)
            inMatch && v.saturation < 0.12f -> Phase.DEAD
            hint == ScreenHint.HERO_SELECT -> Phase.DRAFT
            hint == ScreenHint.HERO_LOCKED -> Phase.HERO_LOCKED
            hint == ScreenHint.LOADING -> Phase.LOADING
            hint == ScreenHint.HOME -> Phase.HOME
            hint == ScreenHint.LOBBY -> Phase.LOBBY
            hint == ScreenHint.QUEUE -> Phase.QUEUE
            now - gemmaScreenAt < 25_000 -> gemmaScreen
            else -> Phase.UNKNOWN
        }
        if (phase == Phase.SHOP || phase == Phase.SCOREBOARD || phase == Phase.MAP) lastInGame = now
        // match end (Victory / Defeat, MVP, final scoreboard): log the match once, with the result
        val endScreen = hint == ScreenHint.RESULT_SPLASH || hint == ScreenHint.RESULT_STATS || hint == ScreenHint.RESULT_BOARD
        if (endScreen && phase == Phase.RESULT && matchStartAt != 0L) {
            val result = com.sphynx.vision.ScreenReader.lastResultWin
            lastResultWin = result
            // recording ends at the match end screens; the end scoreboard may still lock the hero
            val recorded = matchId != 0L
            if (recorded) { lastMatchId = matchId; matchId = 0L; Diag.info("Match recording ended (match end)") }
            lastMatchEndAt = now
            com.sphynx.learning.TrainingResults.matchEnded()   // tactical engine result of this match
            logMatch()                       // (it clears lastResultWin: the message uses the copy)
            // (after the scorecard is saved: the recording's last part carries this match's scorecard, not the last one's)
            if (recorded) com.sphynx.core.SessionRecorder.matchEnded()
            matchStartAt = 0L
            Diag.info("Match end: " + when (result) { true -> "victory"; false -> "defeat"; null -> "result not read" })
        }
        if (phase == Phase.DRAFT || phase == Phase.HERO_LOCKED) {
            // first hero-select screen in 5 minutes = a new match: forget the old one
            if (now - lastSelectSeen > 300_000) newMatch()
            lastSelectSeen = now
            lastInGame = 0L
        }
        if (phase == Phase.LOADING) {
            // a loading screen after 2 minutes without one = a new match: recording starts here
            // (also right after a match: a loading screen once the last one ended / was played is a new match)
            if (now - loadingSeenAt > 120_000 || lastMatchEndAt > loadingSeenAt || lastInGame > loadingSeenAt) {
                matchId = now; Diag.info("Match recording started (loading screen)")
                enemySpells = emptyMap(); allySpells = emptyMap()      // (the last match's scoreboard spells are not this match's)
                com.sphynx.core.SessionRecorder.matchStarted()
            }
            loadingSeenAt = now
        }
        if (phase != state.phase) phaseSince = now
        if (phase == Phase.IN_GAME && before == Phase.DEAD) { spawnAt = now; com.sphynx.vision.BuffEmblems.reset() }   // (a death loses the buffs)
        if (phase == Phase.DEAD) lastInGame = now

        // big map open: its portraits and icons are not units on the field
        val onMap = phase == Phase.MAP

        val s = makeState(v, now, phase, onMap, ownHero(), spell(), lane().first,
            if (matchStartAt == 0L) -1 else ((now - matchStartAt) / 1000).toInt(),
            if (spawnAt == 0L) Long.MAX_VALUE else now - spawnAt)
        HeroKit.observeVisualState(s)
        state = s
        CooldownTracker.observe(v)
        Sustain.observe(s)          // HP back while hitting (lifesteal / spell vamp), learned per hero
        return s
    }

    private val hpGuard = HpGuard()

    private fun makeState(v: VisionResult, now: Long, phase: Phase, onMap: Boolean, hero: String?, spell: String?, lane: Lane,
                          matchSec: Int, sinceSpawnMs: Long): GameState {
        fun field(x: Float, y: Float) = !onMap || !(x < 0.45f && y < 0.85f)
        // the hero's kit decides which buttons s1 / s2 / ult are (2-, 3- or 4-skill layouts) and how
        // its HP bar is read (Masha: dark green, three bars)
        com.sphynx.core.Layout.kitSize = com.sphynx.vision.ScreenKit.kitFor(hero, HeroKit.count(hero), HeroKit.known(hero))
        com.sphynx.vision.HeroDetector.darkGreenSelf = HeroKit.barModel(hero) == HeroKit.BarModel.SEGMENTED
        com.sphynx.vision.HeroDetector.specialBar = HeroKit.barModel(hero).let { it != HeroKit.BarModel.NORMAL && it != HeroKit.BarModel.SEGMENTED }
        // the minimap first: the enemies the AI acts on must be backed by it (see MapIntel.screenEnemiesConfirmed)
        val seenEnemies = v.enemyHeroes.filter { field(it.x, it.y) }
        val mapInfo = if (onMap) MapIntel.Info.NONE else MapIntel.read(v.minimap, now, seenEnemies, v.self)
        val enemies = if (onMap) seenEnemies else MapIntel.screenEnemiesConfirmed(seenEnemies, v.minimap.self, v.self, v.aspect, now)
        // a sudden 0% reading right after a healthy one is not believed (HpGuard - owner, 2026-11-12)
        val hpRead = hpGuard.filter(v.self?.hp ?: -1f, now, v.hint == ScreenHint.DEAD)
        return GameState(
            ts = now,
            phase = phase,
            self = v.self,
            // the HP decisions use: HP + shield / armour (+ half the stored energy) by the hero's bar model
            selfHp = v.self?.let { HeroKit.effectiveHp(hero, hpRead, it.extra) } ?: -1f,
            selfExtra = v.self?.extra ?: 0f,
            selfHpRaw = hpRead,
            allies = v.allies.filter { field(it.x, it.y) },
            enemies = enemies,
            enemyMinions = v.enemyMinions.filter { field(it.x, it.y) },
            allyMinions = v.allyMinions.filter { field(it.x, it.y) },
            minimapEnemies = maxOf(v.minimap.enemies, v.enemyHeroes.count { field(it.x, it.y) }),
            aspect = v.aspect,
            ownHero = hero,
            myTeam = myTeam,
            enemyTeam = enemyTeam,
            items = items,
            gold = gold,
            itemsGoldAgeMs = if (itemsGoldAt == 0L) Long.MAX_VALUE else now - itemsGoldAt,
            hud = v.hud,
            monsters = v.monsters.filter { field(it.x, it.y) },
            spell = spell,
            lane = lane,
            matchSec = matchSec,
            sinceSpawnMs = sinceSpawnMs,
            minimapSelf = if (onMap) null else v.minimap.self,
            camps = if (onMap) emptyList() else v.minimap.camps,
            towers = if (onMap) emptyList() else v.minimap.towers,
            enemyBase = if (onMap) null else v.minimap.enemyBase,
            allyWaves = if (onMap) emptyList() else v.minimap.allyWaves,
            allyTowers = if (onMap) emptyList() else v.minimap.allyTowers,
            enemyWaves = if (onMap) emptyList() else v.minimap.enemyWaves,
            bossHp = v.bossHp,
            bushes = v.bushes,
            // on screen, or (alongside) the minimap shows your spot is bush ground
            inBush = v.inBush || !onMap && com.sphynx.vision.WallMap.inBush(v.minimap.self),
            screenWalls = v.screenWalls,
            level = v.level,
            xp = v.xp,
            enemyLevel = v.enemyLevel,
            towerRing = v.towerRing,
            map = mapInfo,
            skillBadgePct = if (onMap) -1f else v.skillBadgePct,
            activeSkillIcons = if (onMap) emptyMap() else v.activeSkillIcons,
            transformBarPct = if (onMap) -1f else v.transformBarPct,
            recallBarPct = if (onMap) -1f else v.recallBarPct
        )
    }

    // ---------------- live match (watching another player) ----------------
    // Their screen is read like a match of yours, but it never touches YOUR match: no match
    // start, no K/D/A, no cooldowns, and the AI never plays (the phase is LIVE, not IN_GAME).
    // The hero and role being watched come from the Learn page; the match clock from Gemma.
    @Volatile private var lastLiveAt = 0L
    @Volatile private var lastMatchEndAt = 0L
    @Volatile private var liveClockSec = -1
    @Volatile private var liveClockAt = 0L

    // the clock read from their screen (mm:ss, by Gemma): counted on from there
    fun setLiveClock(sec: Int) { liveClockSec = sec; liveClockAt = System.currentTimeMillis() }
    fun liveClockAge(): Long = if (liveClockAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - liveClockAt

    private fun liveState(v: VisionResult, now: Long, seenLive: Boolean): GameState {
        val wall = System.currentTimeMillis()        // the clock is stamped with this time too
        if (liveClockAt != 0L && wall - liveClockAt > 40 * 60_000L) { liveClockAt = 0L; liveClockSec = -1 }
        val sec = if (liveClockAt == 0L) -1 else liveClockSec + ((wall - liveClockAt) / 1000).toInt()
        // a frame right after a live one that was not recognised (a popup over the panel):
        // still the live screen, but nothing on it is trusted
        val vv = if (seenLive) v else VisionResult(v.ts, v.frameW, v.frameH, null, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), v.minimap, v.saturation, emptyMap(), v.hint, HudInfo.NONE, null, null, emptyList(), IntArray(3), -1f,
            emptyList(), false, v.screenWalls, processMs = v.processMs)
        val hero = HeroDb.match(Cfg.liveHero)
        val s = makeState(vv, now, Phase.LIVE, false, hero, null, Lane.live(), sec, Long.MAX_VALUE)
        if (state.phase != Phase.LIVE) phaseSince = now
        state = s
        return s
    }

    private val hpHist = ArrayDeque<Pair<Long, Float>>()
    @Volatile var hurtAt = 0L
        private set
    fun hurtRecently(now: Long) = now - hurtAt in 0..700

    // set by the drafter so it can reset its once-per-match steps
    @Volatile var onNewMatch: (() -> Unit)? = null

    // New match: forget the previous one
    // the match that just ended goes into the evaluation log
    // the last match end screen: true = victory, false = defeat, null = not seen
    @Volatile var lastResultWin: Boolean? = null
        private set

    // SCREEN CAPTURE STOPPED MID-MATCH (owner, 2026-11-13): the match end screen will never be seen, so after a minute
    // without capture the match is closed as unfinished - its scorecard and recording are saved, not left open.
    @Volatile private var captureStoppedAt = 0L
    fun captureStopped() {
        val at = System.currentTimeMillis(); captureStoppedAt = at
        Thread({
            val waited = try { Thread.sleep(CAPTURE_GRACE_MS); true } catch (e: InterruptedException) { false }
            if (waited && captureStoppedAt == at && !com.sphynx.capture.ScreenCaptureService.running)
                try { closeUnfinished() } catch (t: Throwable) { Diag.error("Closing the unfinished match", t) }
        }, "capture-grace").start()
    }
    const val CAPTURE_GRACE_MS = 60_000L
    private fun closeUnfinished() {
        if (matchStartAt == 0L && matchId == 0L) return
        Diag.info("Match closed as unfinished: screen capture stopped (no match end screen seen)")
        if (matchId != 0L) { lastMatchId = matchId; matchId = 0L }
        lastMatchEndAt = System.currentTimeMillis()
        com.sphynx.learning.TrainingResults.matchEnded()
        logMatch()
        com.sphynx.core.SessionRecorder.matchEnded()
        matchStartAt = 0L
    }

    private fun logMatch() {
        if (matchStartAt == 0L) { com.sphynx.learning.ABCompare.matchStart(); HeroKit.resetForm(); com.sphynx.vision.ScreenKit.reset(); return }
        MatchLog.save(ownHero(), lane().first, kills, deaths, assists, com.sphynx.vision.LevelReader.level,
            ((lastInGame - matchStartAt) / 1000).toInt(), com.sphynx.ai.AiRuntime.playerOn, lastResultWin)
        com.sphynx.learning.ABCompare.matchEnd(com.sphynx.ai.AiRuntime.playerOn, kills, deaths, assists,
            ((lastInGame - matchStartAt) / 1000).toInt(), lastResultWin)
        com.sphynx.research.ResearchMode.onMatchEnd(kills, deaths, assists, ((lastInGame - matchStartAt) / 1000).toInt(), lastResultWin)
        com.sphynx.research.ResearchMode.onBenchmarkEnd(ownHero(), lane().first.name, kills, deaths, assists,
            com.sphynx.vision.LevelReader.level, ((lastInGame - matchStartAt) / 1000).toInt(), lastResultWin)
        lastResultWin = null
    }

    fun newMatch() {
        Lithowanderer.reset()          // its kill / Stone Roamer belong to one match
        MapSide.reset()                // which lane is on top is read again in every match
        logMatch()
        enemySpells = emptyMap(); allySpells = emptyMap(); lineupReadAt = 0L
        onNewMatch?.invoke()
        myTeam = emptyList()
        enemyTeam = emptyList()
        loadingCardName = null
        loadingHero = null
        scoreboardHero = null
        nameMatch = null
        verifiedPlayerName = null
        buttonSpell = null
        scoreboardSpell = null
        loadingSpell = null
        gemmaLoadingSpell = null
        blessedRetribution = null
        lockedHero = null
        lockedSpell = null
        modelCand = null; modelCandN = 0; portraitCand = null; portraitCandN = 0; lookLogged = null
        spellLearnedAt = 0L; spellLogged = null
        minimapWikiHero = null
        loadingGreen = null
        com.sphynx.vision.DeathTimers.reset()
        com.sphynx.vision.PortraitMatch.reset()
        guessedHero = null
        iconHero = null
        modelHero = null
        portraitHero = null
        com.sphynx.vision.HeroVerifier.reset()
        gemmaSpell = null
        iconSpell = null
        matchStartAt = 0L
        kills = 0; deaths = 0; assists = 0
        com.sphynx.vision.KdaReader.reset()
        com.sphynx.vision.LevelReader.reset()
        com.sphynx.controller.ActionExecutor.resetMatchUpgrades()
        com.sphynx.vision.WallMap.reset()
        GameMemory.reset()
        items = -1
        gold = -1
        itemsGoldAt = 0L
    }
}

// Knows when each skill was last used. With "read cooldowns from screen" on, it also
// checks that the skill button is bright (ready) rather than dimmed (cooling down).
object CooldownTracker {
    private val lastCast = HashMap<String, Long>()
    private val baseline = HashMap<String, Float>()
    private val current = HashMap<String, Float>()
    @Volatile var cdFactor = 1f    // learning can stretch or shrink the estimates

    // the ultimate's real cooldown, MEASURED on screen: cast (button goes dark) -> bright again
    private var ultWentDark = false

    // 3-Stage Basic Attack button activation tracking (v37.10, calibrated on user screenshots 1000032040/41/42):
    //  - IDLE: normal unpressed state (score ~124.7 on 1000032040)
    //  - GLOWING: bright cyan-white glow while pressed (score ~259.7 on 1000032041)
    //  - DIMMED: darkened ring & emblem right after release / during swing (score ~100.9 on 1000032042)
    enum class AttackStage(val label: String) {
        IDLE("idle"),
        GLOWING("glowing (pressed)"),
        DIMMED("dimmed (after press)")
    }

    @Volatile var attackIdleBase = 0f
        private set
    @Volatile var attackCurrent = 0f
        private set
    @Volatile var attackStage: AttackStage = AttackStage.IDLE
        private set
    @Volatile var attackGlowing = false
        private set
    val attackDimmed: Boolean get() = attackStage != AttackStage.GLOWING

    @Synchronized
    fun observe(v: VisionResult) {
        v.skillBrightness["attack"]?.let { ab ->
            attackCurrent = ab
            if (ab in 108f..155f) {
                attackIdleBase = if (attackIdleBase <= 0f) ab else (attackIdleBase * 0.92f + ab * 0.08f).coerceIn(110f, 145f)
            } else if (attackIdleBase <= 0f && ab > 35f) {
                attackIdleBase = ab.coerceIn(110f, 140f)
            }
            attackStage = when {
                ab >= 160f || (attackIdleBase > 30f && ab >= attackIdleBase + 28f) -> AttackStage.GLOWING
                ab < 108f || (attackIdleBase > 30f && ab <= attackIdleBase - 10f) -> AttackStage.DIMMED
                else -> AttackStage.IDLE
            }
            attackGlowing = attackStage == AttackStage.GLOWING
        }
        for ((k, b) in v.skillBrightness) {
            if (k == "attack") continue
            current[k] = b
            // Cap baseline at 165f and decay faster (0.985f) so a bright level-up or skill flash
            // never inflates the baseline to 240+ and falsely marks ready skills as "cooling down" (v37.04)
            val clamped = b.coerceAtMost(165f)
            val base = baseline[k] ?: clamped
            baseline[k] = if (clamped > base) clamped else base * 0.985f + clamped * 0.015f
        }
        val cast = lastCast["ult"] ?: return
        val b = current["ult"] ?: return
        val base = baseline["ult"] ?: return
        if (base <= 40f) return
        val since = System.currentTimeMillis() - cast
        if (since in 300..4000 && b < base * 0.62f) ultWentDark = true
        if (ultWentDark && (b >= base * 0.66f || b >= 92f) && since > 3000) {
            ultWentDark = false
            val hero = GameStateTracker.ownHero() ?: return
            val sec = since / 1000f
            if (sec in 4f..200f) {
                val old = learnedUltCd(hero)
                val v2 = if (old == null) sec else old * 0.4f + sec * 0.6f
                Cfg.setRaw("ultcd_" + hero.lowercase(), "%.1f".format(java.util.Locale.US, v2))
            }
        }
    }

    fun learnedUltCd(hero: String?): Float? = hero?.let { Cfg.raw("ultcd_" + it.lowercase())?.toFloatOrNull() }

    // the ultimate's cooldown: measured in your matches, else the skill description, else unknown
    fun ultCooldown(hero: String?): Float? =
        learnedUltCd(hero) ?: com.sphynx.game.SkillBook.get(hero)["ult"]?.cooldownSec?.takeIf { it > 0f }

    // a LOW-cooldown ultimate (back within LOW_ULT_CD seconds) may be spent on farming and early in combos
    const val LOW_ULT_CD = 30f
    // Only true form-switch / 3-skill enhanced heroes (Julian, Roger, Selena) may use slot 3 ("ult") on creeps/waves;
    // standard Ultimates (especially Ultimates with dash) are strictly reserved for enemy hero fights! (v37.13)
    fun lowCdUlt(hero: String?): Boolean =
        hero in setOf("Julian", "Roger", "Selena")

    @Synchronized
    fun markCast(slot: String) {
        lastCast[slot] = System.currentTimeMillis()
        if (slot == "ult") ultWentDark = false
    }

    @Synchronized
    fun resetSlots(vararg slots: String) {
        for (sl in slots) lastCast.remove(sl)
    }

    @Synchronized
    // Ready = the button is bright on screen OR its cooldown timer has elapsed OR it is in a multi-phase recast state (v37.06).
    fun ready(slot: String, p: HeroProfile): Boolean {
        val since = System.currentTimeMillis() - (lastCast[slot] ?: 0L)
        val hero = GameStateTracker.ownHero()
        // If this slot is currently displaying its active continuous duration on its skill icon (and is not a recastable phase), do not re-tap it!
        val gs = GameStateTracker.state
        if (slot in gs.activeSkillIcons && !HeroKit.canRecastNow(hero, slot)) return false
        if (HeroKit.canRecastNow(hero, slot)) return since >= 260L
        if (since < 380L) return false
        val baseCd = if (slot == "spell") SpellInfo.cooldown[GameStateTracker.spell()] ?: p.cooldownSec[slot] ?: 100f else p.cooldownSec[slot] ?: 7f
        val cd = (baseCd * 1000 * cdFactor).toLong()
        if (Cfg.readCooldownsFromScreen) {
            val b = current[slot]
            val base = baseline[slot]
            if (b != null && base != null && base > 40f) {
                if (b >= base * 0.65f || b >= 88f) return true
                // If screen brightness looks dim (e.g. slight button offset), still allow once estimated cooldown has passed
                if (slot != "spell" && since >= cd) return true
                return false
            }
        }
        return since >= cd
    }

    @Synchronized
    fun screenKnown(slot: String): Boolean = Cfg.readCooldownsFromScreen && (baseline[slot] ?: 0f) > 40f && current[slot] != null

    @Synchronized
    fun lookReady(slot: String): Boolean {
        val b = current[slot] ?: return true
        val base = baseline[slot] ?: return b >= 85f
        return base > 40f && (b >= base * 0.65f || b >= 88f)
    }

    // for the Player page: what the buttons show right now (including Basic Attack glow/dim stage)
    @Synchronized
    fun screenState(): String {
        val gs = GameStateTracker.state
        val skills = listOf("s1", "s2", "ult", "spell").joinToString("  ") { k ->
            val actPct = gs.activeSkillIcons[k]
            if (actPct != null) "$k: active (${(actPct * 100).toInt()}%)"
            else {
                val b = current[k]; val base = baseline[k]
                k + ": " + if (b == null || base == null || base <= 40f) "?" else if (b >= base * 0.65f || b >= 88f) "ready" else "cooling down"
            }
        }
        val atk = if (attackCurrent <= 20f) "attack: ?" else "attack: ${attackStage.label}"
        val extras = buildList {
            if (gs.skillBadgePct >= 0f) add("badge: ${(gs.skillBadgePct * 100).toInt()}%")
            if (gs.transformBarPct >= 0f) add("transformBar: ${(gs.transformBarPct * 100).toInt()}%")
            if (gs.recallBarPct >= 0f) add("recallBar: ${(gs.recallBarPct * 100).toInt()}%")
        }.joinToString(" · ")
        return if (extras.isEmpty()) "$atk  |  $skills" else "$atk  |  $skills  |  $extras"
    }

    @Synchronized
    fun reset() {
        lastCast.clear()
    }
}
