package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.vision.IconMemory
import com.example.mlbbresearch.vision.HudInfo
import com.example.mlbbresearch.vision.ScreenHint
import com.example.mlbbresearch.vision.VisionResult

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
    @Volatile var draftedHero: String? = null      // locked in during the draft
    @Volatile var guessedHero: String? = null      // recognised in game by Gemma
    @Volatile var iconHero: String? = null         // recognised from its minimap portrait + model (IconMemory)
    @Volatile var gemmaSpell: String? = null       // battle spell read by Gemma this match
    @Volatile var iconSpell: String? = null        // battle spell recognised from its icon
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

    // What you are really playing comes first: the icons on screen, then the hero picked this
    // match, then the name you typed, then Gemma's in-game guess.
    fun ownHero(): String? = iconHero ?: draftedHero ?: HeroDb.match(Cfg.myHero) ?: guessedHero

    fun ownHeroSource(): String = when {
        iconHero != null -> "recognised from its portrait and model"
        draftedHero != null -> "picked this match"
        HeroDb.match(Cfg.myHero) != null -> "typed by you"
        guessedHero != null -> "recognised by Gemma"
        else -> "unknown"
    }

    // Your battle spell: chosen by you, else read by Gemma, else recognised from the icon
    fun spell(): String? = SpellDb.match(Cfg.mySpell) ?: gemmaSpell ?: iconSpell

    fun spellSource(): String = when {
        SpellDb.match(Cfg.mySpell) != null -> "chosen by you"
        gemmaSpell != null -> "read by Gemma"
        iconSpell != null -> "recognised from the icon"
        else -> "unknown"
    }

    fun lane(): Pair<Lane, String> = LaneLogic.effective(ownHero(), spell())

    // you are back in base (respawn, recall): the lane route starts again
    fun markSpawn() { spawnAt = System.currentTimeMillis() }

    // "This is my hero / spell now": label what the icons look like right now
    fun teachHero(name: String): Boolean {
        val sig = lastHeroSig ?: return false
        IconMemory.learnHero(name, sig)
        iconHero = name
        return true
    }

    fun teachSpell(name: String): Boolean {
        val p = lastSpellIcon ?: return false
        IconMemory.learnSpell(name, p)
        iconSpell = name
        return true
    }

    private fun recognise(v: VisionResult) {
        // only on the normal map (the big map hides your minimap icon)
        if (state.phase != Phase.MAP) v.heroSig?.let { sig ->
            lastHeroSig = sig
            val m = IconMemory.matchHero(sig)
            if (m != null && m.second < IconMemory.HERO_MATCH) {
                if (iconHero != m.first) Diag.info("Recognised your hero from its portrait and model: ${m.first}")
                iconHero = m.first
            }
            // learn the icons of a hero seen picked / read THIS match (never against the icons;
            // never from the typed name, which may be from an older match)
            val teacher = draftedHero ?: guessedHero
            if (teacher != null && (iconHero == null || iconHero == teacher) && IconMemory.learnHero(teacher, sig)) {
                Diag.info("Learned how $teacher looks")
            }
        }
        v.spellIcon?.let { p ->
            lastSpellIcon = p
            val m = IconMemory.matchSpell(p)
            if (m != null && m.second < IconMemory.SPELL_MATCH) iconSpell = m.first
            val teacher = gemmaSpell
            if (teacher != null && (iconSpell == null || iconSpell == teacher) && IconMemory.learnSpell(teacher, p)) {
                Diag.info("Learned the $teacher icon")
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
        if (v.hint == ScreenHint.LIVE || v.hint == ScreenHint.LIVE_DEAD) lastLiveAt = now
        if (v.hint == ScreenHint.LIVE || v.hint == ScreenHint.LIVE_DEAD || (now - lastLiveAt in 0..4000 && v.hint == ScreenHint.NONE))
            return liveState(v, now, v.hint == ScreenHint.LIVE)
        val wasInMatch = now - lastInGame < 120_000
        val before = state.phase
        if (v.self != null && !wasInMatch) {
            // first look at your hero after hero select / loading: the match clock starts
            logMatch()                      // a match without a hero-select screen in between
            kills = 0; deaths = 0; assists = 0
            matchStartAt = now
            com.example.mlbbresearch.vision.ScreenReader.clearResult()
            spawnAt = now
            com.example.mlbbresearch.vision.LevelReader.reset()
        }
        recognise(v)
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
            val result = com.example.mlbbresearch.vision.ScreenReader.lastResultWin
            lastResultWin = result
            logMatch()                       // (it clears lastResultWin: the message uses the copy)
            matchStartAt = 0L
            Diag.info("Match end: " + when (result) { true -> "victory"; false -> "defeat"; null -> "result not read" })
        }
        if (phase == Phase.DRAFT || phase == Phase.HERO_LOCKED) {
            // first hero-select screen in 5 minutes = a new match: forget the old one
            if (now - lastSelectSeen > 300_000) newMatch()
            lastSelectSeen = now
            lastInGame = 0L
        }
        if (phase != state.phase) phaseSince = now
        if (phase == Phase.IN_GAME && before == Phase.DEAD) spawnAt = now
        if (phase == Phase.DEAD) lastInGame = now

        // big map open: its portraits and icons are not units on the field
        val onMap = phase == Phase.MAP

        val s = makeState(v, now, phase, onMap, ownHero(), spell(), lane().first,
            if (matchStartAt == 0L) -1 else ((now - matchStartAt) / 1000).toInt(),
            if (spawnAt == 0L) Long.MAX_VALUE else now - spawnAt)
        state = s
        CooldownTracker.observe(v)
        return s
    }

    private fun makeState(v: VisionResult, now: Long, phase: Phase, onMap: Boolean, hero: String?, spell: String?, lane: Lane,
                          matchSec: Int, sinceSpawnMs: Long): GameState {
        fun field(x: Float, y: Float) = !onMap || !(x < 0.45f && y < 0.85f)
        return GameState(
            ts = now,
            phase = phase,
            self = v.self,
            selfHp = v.self?.hp ?: -1f,
            allies = v.allies.filter { field(it.x, it.y) },
            enemies = v.enemyHeroes.filter { field(it.x, it.y) },
            enemyMinions = v.enemyMinions.filter { field(it.x, it.y) },
            allyMinions = v.allyMinions.filter { field(it.x, it.y) },
            minimapEnemies = v.minimap.enemies,
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
            inBush = v.inBush,
            screenWalls = v.screenWalls,
            level = v.level,
            xp = v.xp,
            enemyLevel = v.enemyLevel,
            towerRing = v.towerRing,
            map = if (onMap) MapIntel.Info.NONE else MapIntel.read(v.minimap, now)
        )
    }

    // ---------------- live match (watching another player) ----------------
    // Their screen is read like a match of yours, but it never touches YOUR match: no match
    // start, no K/D/A, no cooldowns, and the AI never plays (the phase is LIVE, not IN_GAME).
    // The hero and role being watched come from the Learn page; the match clock from Gemma.
    @Volatile private var lastLiveAt = 0L
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
        val s = makeState(vv, now, Phase.LIVE, false, hero, null, Cfg.liveLane, sec, Long.MAX_VALUE)
        if (state.phase != Phase.LIVE) phaseSince = now
        state = s
        return s
    }

    // set by the drafter so it can reset its once-per-match steps
    @Volatile var onNewMatch: (() -> Unit)? = null

    // New match: forget the previous one
    // the match that just ended goes into the evaluation log
    // the last match end screen: true = victory, false = defeat, null = not seen
    @Volatile var lastResultWin: Boolean? = null
        private set

    private fun logMatch() {
        if (matchStartAt == 0L) return
        MatchLog.save(ownHero(), lane().first, kills, deaths, assists, com.example.mlbbresearch.vision.LevelReader.level,
            ((lastInGame - matchStartAt) / 1000).toInt(), com.example.mlbbresearch.ai.AiRuntime.playerOn, lastResultWin)
        lastResultWin = null
    }

    fun newMatch() {
        logMatch()
        onNewMatch?.invoke()
        myTeam = emptyList()
        enemyTeam = emptyList()
        draftedHero = null
        guessedHero = null
        iconHero = null
        gemmaSpell = null
        iconSpell = null
        matchStartAt = 0L
        kills = 0; deaths = 0; assists = 0
        com.example.mlbbresearch.vision.KdaReader.reset()
        com.example.mlbbresearch.vision.LevelReader.reset()
        com.example.mlbbresearch.vision.WallMap.reset()
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

    @Synchronized
    fun observe(v: VisionResult) {
        for ((k, b) in v.skillBrightness) {
            current[k] = b
            val base = baseline[k] ?: b
            baseline[k] = if (b > base) b else base * 0.999f + b * 0.001f
        }
    }

    @Synchronized
    fun markCast(slot: String) {
        lastCast[slot] = System.currentTimeMillis()
    }

    @Synchronized
    // Ready = the button is bright again on screen (a skill on cooldown is dark with a countdown,
    // a skill not learned yet is dark too). The screen beats the estimated timer, which is only
    // used when the buttons cannot be read.
    fun ready(slot: String, p: HeroProfile): Boolean {
        val since = System.currentTimeMillis() - (lastCast[slot] ?: 0L)
        if (Cfg.readCooldownsFromScreen) {
            val b = current[slot]
            val base = baseline[slot]
            if (b != null && base != null && base > 40f) return since > 400 && b >= base * 0.72f
        }
        // the battle spell's real cooldown (wiki), whatever the hero profile says
        val base = if (slot == "spell") SpellInfo.cooldown[GameStateTracker.spell()] ?: p.cooldownSec[slot] ?: 100f else p.cooldownSec[slot] ?: 8f
        val cd = (base * 1000 * cdFactor).toLong()
        return since >= cd
    }

    @Synchronized
    fun screenKnown(slot: String): Boolean = Cfg.readCooldownsFromScreen && (baseline[slot] ?: 0f) > 40f && current[slot] != null

    // what the button shows right now: bright = ready (true), dark = cooling down / used
    // (false); unknown buttons count as not ready so nothing is pressed twice by mistake
    @Synchronized
    fun lookReady(slot: String): Boolean {
        val b = current[slot] ?: return false
        val base = baseline[slot] ?: return false
        return base > 40f && b >= base * 0.72f
    }

    // for the Player page: what the buttons show right now
    @Synchronized
    fun screenState(): String = listOf("s1", "s2", "ult", "spell").joinToString("  ") { k ->
        val b = current[k]; val base = baseline[k]
        k + ": " + if (b == null || base == null || base <= 40f) "?" else if (b >= base * 0.72f) "ready" else "cooling down"
    }

    @Synchronized
    fun reset() {
        lastCast.clear()
    }
}
