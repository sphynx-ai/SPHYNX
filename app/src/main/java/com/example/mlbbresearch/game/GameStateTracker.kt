package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg
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
    @Volatile var guessedHero: String? = null      // recognised in game
    @Volatile private var items = -1
    @Volatile private var gold = -1
    @Volatile private var itemsGoldAt = 0L

    fun ownHero(): String? {
        val manual = HeroDb.match(Cfg.myHero)
        return manual ?: draftedHero ?: guessedHero
    }

    fun ownHeroSource(): String = when {
        HeroDb.match(Cfg.myHero) != null -> "set by you"
        draftedHero != null -> "from the draft"
        guessedHero != null -> "recognised by Gemma"
        else -> "unknown"
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
            now - lastSelfSeen < 3000 -> Phase.IN_GAME          // briefly hidden (effects)
            inMatch && v.saturation < 0.12f -> Phase.DEAD
            hint == ScreenHint.HERO_SELECT -> Phase.DRAFT
            hint == ScreenHint.LOADING -> Phase.LOADING
            now - gemmaScreenAt < 25_000 -> gemmaScreen
            else -> Phase.UNKNOWN
        }
        if (phase == Phase.SHOP || phase == Phase.SCOREBOARD || phase == Phase.MAP) lastInGame = now
        if (phase == Phase.DRAFT) {
            // first hero-select screen in 5 minutes = a new match: forget the old one
            if (now - lastSelectSeen > 300_000) newMatch()
            lastSelectSeen = now
            lastInGame = 0L
        }
        if (phase != state.phase) phaseSince = now
        if (phase == Phase.DEAD) lastInGame = now

        val s = GameState(
            ts = now,
            phase = phase,
            self = v.self,
            selfHp = v.self?.hp ?: -1f,
            allies = v.allies,
            enemies = v.enemyHeroes,
            enemyMinions = v.enemyMinions,
            allyMinions = v.allyMinions,
            minimapEnemies = v.minimap.enemies,
            aspect = v.aspect,
            ownHero = ownHero(),
            myTeam = myTeam,
            enemyTeam = enemyTeam,
            items = items,
            gold = gold,
            itemsGoldAgeMs = if (itemsGoldAt == 0L) Long.MAX_VALUE else now - itemsGoldAt,
            hud = v.hud
        )
        state = s
        CooldownTracker.observe(v)
        return s
    }

    // set by the drafter so it can reset its once-per-match steps
    @Volatile var onNewMatch: (() -> Unit)? = null

    // New match: forget the previous one
    fun newMatch() {
        onNewMatch?.invoke()
        myTeam = emptyList()
        enemyTeam = emptyList()
        draftedHero = null
        guessedHero = null
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
    fun ready(slot: String, p: HeroProfile): Boolean {
        val cd = ((p.cooldownSec[slot] ?: 8f) * 1000 * cdFactor).toLong()
        val timerOk = System.currentTimeMillis() - (lastCast[slot] ?: 0L) >= cd
        if (!timerOk) return false
        if (!Cfg.readCooldownsFromScreen) return true
        val b = current[slot] ?: return true
        val base = baseline[slot] ?: return true
        return b >= base * 0.72f
    }

    @Synchronized
    fun reset() {
        lastCast.clear()
    }
}
