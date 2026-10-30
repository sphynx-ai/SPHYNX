package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.vision.IconMemory
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
    @Volatile var iconHero: String? = null         // recognised from the skill icons (IconMemory)
    @Volatile var gemmaSpell: String? = null       // battle spell read by Gemma this match
    @Volatile var iconSpell: String? = null        // battle spell recognised from its icon
    @Volatile var lastHeroIcons: List<FloatArray>? = null
        private set
    @Volatile var lastSpellIcon: FloatArray? = null
        private set
    @Volatile var matchStartAt = 0L
        private set
    @Volatile var spawnAt = 0L
        private set
    @Volatile private var items = -1
    @Volatile private var gold = -1
    @Volatile private var itemsGoldAt = 0L

    // What you are really playing comes first: the icons on screen, then the hero picked this
    // match, then the name you typed, then Gemma's in-game guess.
    fun ownHero(): String? = iconHero ?: draftedHero ?: HeroDb.match(Cfg.myHero) ?: guessedHero

    fun ownHeroSource(): String = when {
        iconHero != null -> "recognised from the skill icons"
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
        val sig = lastHeroIcons ?: return false
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
        v.heroIcons?.let { sig ->
            lastHeroIcons = sig
            val m = IconMemory.matchHero(sig)
            if (m != null && m.second < IconMemory.HERO_MATCH) {
                if (iconHero != m.first) Diag.info("Recognised your hero from the skill icons: ${m.first}")
                iconHero = m.first
            }
            // learn the icons of a hero seen picked / read THIS match (never against the icons;
            // never from the typed name, which may be from an older match)
            val teacher = draftedHero ?: guessedHero
            if (teacher != null && (iconHero == null || iconHero == teacher) && IconMemory.learnHero(teacher, sig)) {
                Diag.info("Learned the skill icons of $teacher")
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
        val wasInMatch = now - lastInGame < 120_000
        val before = state.phase
        if (v.self != null && !wasInMatch) {
            // first look at your hero after hero select / loading: the match clock starts
            matchStartAt = now
            spawnAt = now
        }
        recognise(v)
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
        if (phase == Phase.IN_GAME && before == Phase.DEAD) spawnAt = now
        if (phase == Phase.DEAD) lastInGame = now

        // big map open: its portraits and icons are not units on the field
        val onMap = phase == Phase.MAP
        fun field(x: Float, y: Float) = !onMap || !(x < 0.45f && y < 0.85f)

        val s = GameState(
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
            ownHero = ownHero(),
            myTeam = myTeam,
            enemyTeam = enemyTeam,
            items = items,
            gold = gold,
            itemsGoldAgeMs = if (itemsGoldAt == 0L) Long.MAX_VALUE else now - itemsGoldAt,
            hud = v.hud,
            monsters = v.monsters.filter { field(it.x, it.y) },
            spell = spell(),
            lane = lane().first,
            matchSec = if (matchStartAt == 0L) -1 else ((now - matchStartAt) / 1000).toInt(),
            sinceSpawnMs = if (spawnAt == 0L) Long.MAX_VALUE else now - spawnAt,
            minimapSelf = if (onMap) null else v.minimap.self,
            camps = if (onMap) emptyList() else v.minimap.camps,
            towers = if (onMap) emptyList() else v.minimap.towers,
            enemyBase = if (onMap) null else v.minimap.enemyBase,
            allyWaves = if (onMap) emptyList() else v.minimap.allyWaves,
            allyTowers = if (onMap) emptyList() else v.minimap.allyTowers,
            enemyWaves = if (onMap) emptyList() else v.minimap.enemyWaves
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
        iconHero = null
        gemmaSpell = null
        iconSpell = null
        matchStartAt = 0L
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
