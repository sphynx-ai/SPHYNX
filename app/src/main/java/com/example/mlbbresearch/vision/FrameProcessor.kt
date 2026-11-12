package com.example.mlbbresearch.vision

import com.example.mlbbresearch.capture.FrameHub
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Layout

// Everything the fast vision layer found in one frame.
class VisionResult(
    val ts: Long,
    val frameW: Int,
    val frameH: Int,
    val self: Seen?,
    val allies: List<Seen>,          // ally heroes
    val enemyHeroes: List<Track>,    // tracked, with ids
    val enemyMinions: List<Seen>,
    val allyMinions: List<Seen>,
    val all: List<Seen>,             // for drawing boxes
    val minimap: MinimapInfo,
    val saturation: Float,
    val skillBrightness: Map<String, Float>,
    val hint: ScreenHint,            // which MLBB screen the colours say this is
    val hud: HudInfo,                // upgrade "+", quick-buy icon, camera motion
    val heroSig: List<FloatArray>?,    // your hero's look: minimap portrait + in-game model colours (about once a second)
    val spellIcon: FloatArray?,        // fingerprint of the battle spell button
    val monsters: List<Seen>,          // jungle creeps (hero = false) and turtle / lord (hero = true)
    val kda: IntArray,                 // kills, deaths, assists gained on this frame (0 or 1 each)
    val bossHp: Float,                 // turtle / lord HP (0..1) from the bar under the scoreboard, -1 = none
    val bushes: List<Bush>,            // bushes on screen (tall grass: hide / ambush)
    val inBush: Boolean,               // you are standing in a bush (hidden from enemies)
    val screenWalls: BooleanArray,     // rock / cliff / tree cells on screen (40 x 20 grid)
    val level: Int = 1,                // your hero level 1..15 (LevelReader)
    val xp: Float = -1f,               // how full the EXP ring is, 0..1 (-1 unknown)
    val enemyLevel: Int = -1,          // nearest enemy hero's level, -1 unknown
    val towerRing: TowerRing.Ring = TowerRing.NONE,   // an enemy tower's range circle on screen and its colour
    val skillBadgePct: Float = -1f,    // 0..1 remaining skill duration displayed on the Level Badge, -1 = normal level badge (v37.15)
    val activeSkillIcons: Map<String, Float> = emptyMap(), // slot -> 0..1 remaining active duration displayed on the skill icon (v37.15)
    val transformBarPct: Float = -1f,  // 0..1 remaining duration on the red/orange center-bottom Transformation/Enhancement bar, -1 = none (v37.15)
    val recallBarPct: Float = -1f,     // 0..1 progress on the blue/cyan center-bottom Recall execution bar, -1 = none (v37.15)
    val processMs: Long
) {
    val aspect: Float get() = if (frameH == 0) 1.78f else frameW.toFloat() / frameH
}

// Rectangles drawn by our own overlay (floating icon/panel) so vision ignores them
object ScreenMasks {
    @Volatile var overlay: List<FloatArray> = emptyList()
}

object FrameProcessor {

    // HUD areas where HP-bar-like colours are never units: scoreboard, buttons, minimap...
    private val hud = listOf(
        floatArrayOf(0.30f, 0.00f, 0.70f, 0.09f),   // top scoreboard
        floatArrayOf(0.80f, 0.00f, 1.00f, 0.14f),   // top-right buttons
        floatArrayOf(0.62f, 0.52f, 1.00f, 1.00f),   // skill buttons
        floatArrayOf(0.76f, 0.40f, 0.92f, 0.52f),   // the ultimate button's top (it reaches above the others)
        floatArrayOf(0.42f, 0.04f, 0.59f, 0.125f),  // turtle / lord HP bar under the scoreboard (read separately)
        floatArrayOf(0.18f, 0.00f, 0.30f, 0.06f),   // battery / ping (a low battery icon is red)
        floatArrayOf(0.00f, 0.58f, 0.32f, 1.00f),   // joystick
        floatArrayOf(0.00f, 0.38f, 0.10f, 0.62f),   // shop / quick-buy column
        floatArrayOf(0.90f, 0.14f, 1.00f, 0.55f),   // right column (shop, signals, chat)
        floatArrayOf(0.45f, 0.82f, 0.66f, 1.00f),   // recall / regen / battle spell row and labels
        MinimapAnalyzer.rect
    )

    private val enemyTracker = EnemyDetector()
    @Volatile private var lastMini: MinimapInfo? = null
    @Volatile private var lastMiniLive = false
    private var lastId = 0L
    private var frameNo = 0L
    private var prevSmall: List<Seen> = emptyList()
    private var lastBush: BushDetector.Result? = null
    private var ring = TowerRing.NONE
    private var lastRingRaw = TowerRing.NONE
    private var buffer: IntArray? = null

    @Volatile var latest: VisionResult? = null
        private set
    // "Learn from me" needs the camera motion and every button's brightness
    @Volatile var trackPlayer = false
    // the Player needs the camera motion too (to notice it is stuck on a wall)
    @Volatile var trackMotion = false
    @Volatile var processedFps = 0f
    private var fpsCount = 0
    private var fpsStart = 0L

    // Returns null when there is no new frame yet
    fun processNext(): VisionResult? {
        val snap = FrameHub.snapshot(lastId, buffer) ?: return null
        buffer = snap.pixels
        lastId = snap.id
        val t0 = System.currentTimeMillis()
        val w = snap.width
        val h = snap.height
        val px = snap.pixels

        frameNo++
        HeroDetector.neutralOn = Cfg.detectMonsters
        // a kill / first blood banner is up: ignore that area (its red lines are not enemies)
        val banner = ScreenReader.announcement(px, w, h)
        val hint = ScreenReader.classify(px, w, h)
        // the end-of-match scoreboard: your highlighted row's hero verifies and locks the detection (v36.87)
        if (hint == ScreenHint.RESULT_BOARD && frameNo % 5 == 0L) try { EndBoard.observe(px, w, h) } catch (e: Exception) { }
        // the loading screen: your card = the one with the GREEN name (v36.92), and its spell icon vs the 12 wiki pictures (v37.00)
        // the practice gate: solo practice is verified from its loading card (no Gemma needed)
        if (hint == ScreenHint.LOADING) try { com.example.mlbbresearch.research.ResearchMode.observeLoadingFrame(px, w, h) } catch (e: Exception) { }
        if (hint == ScreenHint.LOADING && frameNo % 5 == 1L) try {
            val green = com.example.mlbbresearch.game.PlayerName.greenCard(px, w, h)
                ?.also { com.example.mlbbresearch.game.GameStateTracker.loadingGreen = it }
                ?: com.example.mlbbresearch.game.GameStateTracker.loadingGreen
            if (green != null) PortraitMatch.observeLoadingSpell(px, w, h, green)
        } catch (e: Exception) { }
        // the match-end screens show your hero's portrait: Victory / Defeat (centre) and MVP (bottom) - v36.92
        if (hint == ScreenHint.RESULT_SPLASH && frameNo % 10 == 2L) try { EndBoard.observePortrait(px, w, h, 0.5f, 0.264f, 0.022f, "Victory/Defeat screen") } catch (e: Exception) { }
        if (hint == ScreenHint.RESULT_STATS && frameNo % 10 == 2L) try { EndBoard.observePortrait(px, w, h, 0.4525f, 0.761f, 0.014f, "MVP screen") } catch (e: Exception) { }
        // watching someone's live match: their screen has no buttons of yours, but its own overlays
        val live = hint == ScreenHint.LIVE || hint == ScreenHint.LIVE_DEAD
        // Full minimap scan every 3rd frame; fast self-ring refresh on every intermediate frame so 3D scenery & minimap stay 100% synchronized (v37.11)
        val mini = lastMini.takeIf { it != null && frameNo % 3 != 0L && live == lastMiniLive }
            ?.let { if (live) it else MinimapAnalyzer.fastSelfUpdate(px, w, h, it) }
            ?: MinimapAnalyzer.analyze(px, w, h, live)
        lastMini = mini; lastMiniLive = live
        val masks = if (live) ScreenReader.LIVE_MASKS else hud
        val seen0 = if (live) liveUnits(px, w, h, mini, banner, hint == ScreenHint.LIVE) else monstersFromRedBars(withoutAttachedBars(ownBars(hint, HeroDetector.scan(
            px, w, h, hud + ScreenMasks.overlay + (if (banner) listOf(ScreenReader.bannerRect) else emptyList()),
            Cfg.heroBarMinWidthPct / 100f, Cfg.bodyBelowBarPct / 100f
        ), px, w, h)), px, w, h, mini)
        // the Lithowanderer: is it on screen (and how low)? After its kill the Stone Roamer is not an ally hero
        val seen = if (live) seen0 else lithoAndRoamer(seen0, mini, t0)
        // optional trained detector: runs on its own thread a few times a second
        ObjectDetector.maybeRun(px, w, h)
        if (Cfg.detectorOn) ScreenDetector.maybeRun(px, w, h)
        // you: the green hero bar closest to the centre of the screen
        val self = seen.filter { it.team == Team.SELF }
            .minByOrNull { (it.x - 0.5f) * (it.x - 0.5f) + (it.y - 0.5f) * (it.y - 0.5f) }
        // coloured bits of your own hero (e.g. a blue skin) are not units
        fun onSelf(u: Seen) = self != null && u !== self && !u.hero &&
            kotlin.math.abs(u.x - self.x) < 0.05f && u.barTop > self.barBottom - 0.01f && u.barTop < self.y + 0.14f
        // a minion / monster must be seen on two frames in a row at about the same place:
        // a single-frame flash (effects, skill colours) is not a target
        // Neutral monsters (creeps/buffs) are accepted immediately on frame 1 so there is zero delay starting camp attacks
        fun steady(u: Seen) = u.team == Team.NEUTRAL || prevSmall.any { it.team == u.team && kotlin.math.abs(it.x - self?.x.let { _ -> u.x }) < 0.04f && kotlin.math.abs(it.y - u.y) < 0.05f }
        val small = seen.filter { !it.hero && it.team != Team.SELF && !onSelf(it) }
        val steadySmall = small.filter { u -> u.team == Team.NEUTRAL || prevSmall.any { it.team == u.team && kotlin.math.abs(it.x - u.x) < 0.04f && kotlin.math.abs(it.y - u.y) < 0.05f } }
        prevSmall = small
        val aspect = w.toFloat() / h
        val tracks = enemyTracker.update(seen.filter { it.team == Team.ENEMY && it.hero }, t0, aspect)

        // the active item / Conceal buttons: showing and lit? (ActiveItems presses them)
        if (hint == ScreenHint.NONE && self != null && frameNo % 3 == 1L) com.example.mlbbresearch.controller.ActiveItems.observe(px, w, h)
        // which lane is on top (the minimap's lane marks, shown only at the start of the match - game.MapSide)
        if (hint == ScreenHint.NONE && self != null && frameNo % 5 == 2L && com.example.mlbbresearch.game.MapSide.goldTop == null &&
            com.example.mlbbresearch.game.GameStateTracker.state.matchSec in 0..com.example.mlbbresearch.game.MapSide.READ_UNTIL_SEC)
            com.example.mlbbresearch.game.MapSide.observe(com.example.mlbbresearch.game.MapSide.mark(px, w, h))
        // ... and in Solo practice (no lane marks there - owner, 2026-11-11) from the Lord / Turtle icons' pits
        if (hint == ScreenHint.NONE && self != null && frameNo % 5 == 3L && com.example.mlbbresearch.game.MapSide.goldTop == null &&
            (com.example.mlbbresearch.game.MatchKind.current() == com.example.mlbbresearch.game.MatchKind.Kind.SOLO ||
                com.example.mlbbresearch.game.GameStateTracker.state.matchSec in 0 until 480)) {
            val (l, t) = Layout.get("mapTL"); val (r, b) = Layout.get("mapBR")
            fun mm(box: FloatArray?) = box?.let { (((it[0] + it[2]) / 2f - l) / (r - l).coerceAtLeast(0.01f)) to (((it[1] + it[3]) / 2f - t) / (b - t).coerceAtLeast(0.01f)) }
            com.example.mlbbresearch.game.MapSide.observe(com.example.mlbbresearch.game.MapSide.fromPits(mm(mini.lordIcon), mm(mini.turtleIcon)))
        }
        // CLEAN SCREENSHOTS FOR THE RECORDING at key moments (owner, 2026-11-12): the lane side decided, an enemy
        // hero or a monster first seen, your HP read as 0%
        if (com.example.mlbbresearch.core.SessionRecorder.on && hint == ScreenHint.NONE && self != null) {
            val rec = com.example.mlbbresearch.core.SessionRecorder
            val side = com.example.mlbbresearch.game.MapSide.goldTop
            if (side != snapSide) { snapSide = side; if (side != null) rec.snap("lane-side", px, w, h, 1000L) }
            val enemyNow = tracks.isNotEmpty()
            if (enemyNow && !snapEnemy) rec.snap("enemy-hero", px, w, h, 6000L)
            snapEnemy = enemyNow
            val monNow = seen.any { it.team == Team.NEUTRAL }
            if (monNow && !snapMon) rec.snap("monster", px, w, h, 6000L)
            snapMon = monNow
            if (self.hp <= 0.02f) rec.snap("hp0", px, w, h, 10_000L)
        }
        // which skill buttons the screen shows (normal / large HUD, 2-3-4 skills)
        if (hint == ScreenHint.NONE && self != null && frameNo % 10 == 5L) ScreenKit.observe(px, w, h)
        // which hero you play: its skill icons against learned looks and the wiki pictures (HeroVerifier)
        if (hint == ScreenHint.NONE && self != null && frameNo % 15 == 7L) try { HeroVerifier.observe(px, w, h) } catch (e: Exception) { }
        // which battle spell you have: its button icon against the 12 wiki spell pictures + 3 Blessed Retributions (v37.00)
        if (hint == ScreenHint.NONE && self != null && frameNo % 12 == 6L && com.example.mlbbresearch.game.GameStateTracker.matchStartAt != 0L) try {
            PortraitMatch.observeSpellButton(px, w, h)
        } catch (e: Exception) { }
        // how many enemies / allies are dead: the red respawn countdowns next to the score (v36.86)
        if ((hint == ScreenHint.NONE || hint == ScreenHint.LIVE) && frameNo % 5 == 3L) try { DeathTimers.observe(px, w, h) } catch (e: Exception) { }
        // portraits and spell icons against the wiki's pictures (v36.88 / v37.00): the in-game scoreboard (only while match is running), and the minimap
        // icon as a last resort while neither the model nor the learned portrait knows the hero
        if (hint == ScreenHint.SCOREBOARD && frameNo % 10 == 4L && com.example.mlbbresearch.game.GameStateTracker.matchStartAt != 0L) try {
            PortraitMatch.observeScoreboard(px, w, h)
        } catch (e: Exception) { }
        // research mode (R2): prove from the scoreboard that all 9 others are "Computer" (reads only)
        if (hint == ScreenHint.SCOREBOARD && com.example.mlbbresearch.research.ResearchMode.gateWatching()) try {
            com.example.mlbbresearch.research.ResearchMode.observeScoreboard(px, w, h)
        } catch (e: Exception) { }
        if (hint == ScreenHint.NONE && self != null && frameNo % 30 == 11L && com.example.mlbbresearch.game.GameStateTracker.modelHero == null &&
            com.example.mlbbresearch.game.GameStateTracker.portraitHero == null) mini.selfIcon?.let { ic -> try { PortraitMatch.observeMinimap(px, w, h, ic) } catch (e: Exception) { } }
        val brightness = HashMap<String, Float>()
        if (hint == ScreenHint.LIVE) {
            // their skill / spell icons in the panel (they darken while cooling down)
            if (frameNo % 5 == 2L) ScreenReader.readLiveKit(px, w, h)
            for ((k, c) in ScreenReader.liveIcons(ScreenReader.liveKit)) brightness[k] = ScreenReader.iconColour(px, w, h, c.first, c.second)
            // which hero is being watched: its skill icons in the panel (HeroVerifier)
            if (frameNo % 15 == 7L) try { HeroVerifier.observeLive(px, w, h) } catch (e: Exception) { }
        } else {
            // Always measure the Basic Attack button glow/dim state on every frame so continuous basic-attack heroes know when it dims
            val (ax, ay) = Layout.get("attack")
            brightness["attack"] = attackRingBrightness(px, w, h, ax, ay)
            if (Cfg.readCooldownsFromScreen || trackPlayer) {
                for (k in listOf("s1", "s2", "s3", "ult", "spell", "recall", "regen")) {
                    val (fx, fy) = Layout.get(k)
                    brightness[k] = ringBrightness(px, w, h, fx, fy)
                }
            }
        }

        // walls from the minimap, every couple of seconds while the normal minimap shows
        if (self != null && (hint == ScreenHint.NONE || live) && frameNo % 25 == 3L) WallMap.update(px, w, h)

        // bushes every 3rd frame (they do not move; the camera does, slowly)
        val bush = if (self != null && (frameNo % 3 == 0L || lastBush == null)) {
            BushDetector.detect(px, w, h, masks + ScreenMasks.overlay, self, seen.filter { it.team != Team.SELF }).also { lastBush = it }
        } else if (self != null) lastBush!! else BushDetector.Result(emptyList(), false)

        val hudInfo = if (live) ScreenReader.liveHud(px, w, h)
            else if (self != null || trackPlayer) ScreenReader.hud(px, w, h, trackPlayer || trackMotion) else HudInfo.NONE
        // Skill Casted Duration Badge, Skill Icon Duration & Center-Bottom Transformation/Recall Bars (v37.15)
        val skillBadgePct = if (!live && self != null) ScreenReader.badgeSkillDuration(px, w, h, self) else -1f
        val activeSkillIcons = if (!live && self != null && hint == ScreenHint.NONE) ScreenReader.skillIconDurations(px, w, h) else emptyMap()
        val centerBars = if (!live && self != null && hint == ScreenHint.NONE) ScreenReader.centerBars(px, w, h) else CenterBars.NONE
        // your level from the badge (frozen while a skill duration icon occupies the badge), and the nearest enemy hero's
        if (self != null) LevelReader.update(px, w, h, self, hudInfo.upgradeReady.isNotEmpty(), t0, skillBadgePct >= 0f)
        val enemyLevel = if (self == null) -1 else seen.filter { it.team == Team.ENEMY && it.hero }
            .minByOrNull { kotlin.math.abs(it.x - self.x) + kotlin.math.abs(it.y - self.y) }
            ?.let { LevelReader.levelOf(px, w, h, it) } ?: -1

        // detector training pictures (every 6 s in a match, Learn page switch)
        com.example.mlbbresearch.learning.DatasetCollector.maybeSave(px, w, h, mini, self != null && hint == ScreenHint.NONE)

        // an enemy tower's range circle (every 2nd frame; must show the same colour twice in a row)
        if (self != null && (hint == ScreenHint.NONE || live) && frameNo % 2 == 0L) {
            val r = TowerRing.detect(px, w, h, masks + ScreenMasks.overlay, seen, self.x, self.y)
            ring = if (r.zone == lastRingRaw.zone) r else ring
            lastRingRaw = r
        } else if (self == null || (hint != ScreenHint.NONE && !live)) ring = TowerRing.NONE

        // what the whole-screen detector adds (when loaded): monsters nobody has hit yet (no HP
        // bar), bushes and walls the colour finder missed, an enemy tower when its circle is not shown
        val scr = ScreenIntel.merge(t0, self, steadySmall.filter { it.team == Team.NEUTRAL } + seen.filter { it.team == Team.NEUTRAL && it.hero },
            bush.bushes, bush.walls, ring, steadySmall.filter { it.team == Team.ALLY })

        val kdaNow = if (self != null && !live && frameNo % 2 == 0L) KdaReader.read(px, w, h) else IntArray(3)
        if (kdaNow[0] > 0 || kdaNow[2] > 0) enemyTracker.onKillOrAssist(t0)

        val result = VisionResult(
            ts = t0, frameW = w, frameH = h,
            self = self,
            allies = seen.filter { it.team == Team.ALLY && it.hero },
            enemyHeroes = tracks,
            enemyMinions = steadySmall.filter { it.team == Team.ENEMY },
            allyMinions = steadySmall.filter { it.team == Team.ALLY },
            all = seen,
            minimap = mini,
            saturation = HeroDetector.saturation(px, w, h),
            skillBrightness = brightness,
            hint = hint,
            hud = hudInfo,
            heroSig = if (!live && self != null && frameNo % 12 == 0L) heroSig(px, w, h, self, mini) else null,
            spellIcon = if (!live && self != null && frameNo % 12 == 6L) IconMemory.print(px, w, h, "spell", 0.016f) else null,
            monsters = scr.monsters,
            bossHp = if (self != null && !live) ScreenReader.bossBar(px, w, h) else -1f,
            kda = kdaNow,
            bushes = scr.bushes,
            inBush = bush.inBush,
            screenWalls = scr.walls,
            level = LevelReader.level,
            xp = LevelReader.xp,
            enemyLevel = enemyLevel,
            towerRing = scr.ring,
            skillBadgePct = skillBadgePct,
            activeSkillIcons = activeSkillIcons,
            transformBarPct = centerBars.transformPct,
            recallBarPct = centerBars.recallPct,
            processMs = System.currentTimeMillis() - t0
        )
        // whole-screen detector training pictures (every 10 s in a match)
        com.example.mlbbresearch.learning.ScreenDatasetCollector.maybeSave(px, w, h, result, mini, self != null && hint == ScreenHint.NONE,
            com.example.mlbbresearch.game.JungleNav.atBuff, com.example.mlbbresearch.game.GameStateTracker.state.matchSec,
            steadySmall.filter { it.team == Team.NEUTRAL } + seen.filter { it.team == Team.NEUTRAL && it.hero }, ring)
        latest = result
        com.example.mlbbresearch.core.Speed.vision(result.processMs)
        fpsCount++
        if (fpsStart == 0L) fpsStart = t0
        if (t0 - fpsStart >= 1000) {
            processedFps = fpsCount * 1000f / (t0 - fpsStart)
            fpsCount = 0
            fpsStart = t0
        }
        return result
    }

    // LIVE MATCH: the units on the watched player's screen. Their own HP bar is BLUE (they are
    // an ally of themselves here) and the camera follows them, so the watched hero is the blue
    // hero bar at the centre; its HP comes from the panel's green line. A green bar is never them.
    // panel = false: the watched hero is dead (the camera follows someone else): no "self"
    private fun liveUnits(px: IntArray, w: Int, h: Int, mini: MinimapInfo, banner: Boolean, panel: Boolean = true): List<Seen> {
        val raw = HeroDetector.scan(px, w, h, ScreenReader.LIVE_MASKS + ScreenMasks.overlay +
            (if (banner) listOf(ScreenReader.bannerRect) else emptyList()), Cfg.heroBarMinWidthPct / 100f, Cfg.bodyBelowBarPct / 100f)
            .filter { it.team != Team.SELF }
        val units = monstersFromRedBars(withoutAttachedBars(raw), px, w, h, mini)
        if (!panel) return units
        val me = units.filter { it.team == Team.ALLY && it.hero && kotlin.math.abs(it.barLeft - 0.47f) < 0.045f && it.barTop in 0.33f..0.45f }
            .minByOrNull { (it.x - 0.5f) * (it.x - 0.5f) + (it.y - 0.47f) * (it.y - 0.47f) } ?: return units
        val panelHp = ScreenReader.liveHp(px, w, h)
        return units.map { if (it === me) it.copy(team = Team.SELF, hp = if (panelHp >= 0f) panelHp else it.hp) else it }
    }

    // YOUR bars. The camera keeps your hero in the middle, so your HP bar always sits at about
    // 47% of the width and 38-39% of the height, and the thin line right under it (40-41%) is NOT
    // HP: red = passive build-up, blue = mana, yellow = energy (each hero has one kind). That
    // line is never a unit (a blue one looked like an ally standing on you, a red one could
    // stand in for your HP). When the green part is too thin to be found (very low HP), your HP
    // is read straight from the bar: green share of the bar's width.
    private fun ownBars(hint: ScreenHint, all: List<Seen>, px: IntArray, w: Int, h: Int): List<Seen> {
        val out = all.filter { u -> !(u.team != Team.SELF && kotlin.math.abs(u.barLeft - 0.471f) < 0.02f && u.barTop in 0.394f..0.416f) }
        if (out.any { it.team == Team.SELF } || hint != ScreenHint.NONE) return out
        // no green bar found: is your (nearly empty) HP bar there?
        val y = (0.388f * h).toInt().coerceIn(0, h - 1)
        val x0 = (0.473f * w).toInt(); val x1 = (0.546f * w).toInt()
        var green = 0; var grey = 0; var n = 0
        for (x in x0..x1) {
            val p = px[y * w + x]
            val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            n++
            if (g >= 140 && g - maxOf(r, b) >= 40) green++ else if (mx < 110 && mx - mn < 45) grey++
        }
        if (n == 0 || (green + grey) < n * 0.7f || grey < n * 0.5f) return out
        val hp = (green.toFloat() / n).coerceIn(0.02f, 0.3f)
        val me = Seen(Team.SELF, true, 0.51f, (0.395f + Cfg.bodyBelowBarPct / 100f).coerceAtMost(1f), hp,
            0.471f, 0.382f, 0.548f, 0.394f)
        // and your level badge with its white number next to it (or an active skill duration badge, e.g. Argus Eternal Evil at 1% HP! - v37.15)
        return if (LevelReader.glyph(px, w, h, me) != null || ScreenReader.badgeSkillDuration(px, w, h, me) >= 0f) out + me else out
    }

    @Volatile private var barsWithSubBar: Set<Seen> = emptySet()

    // Many heroes have a second bar right under their HP bar (passive, energy, mana, e.g. Thamuz's
    // red passive bar). It is part of that hero, not a unit: drop any bar that starts inside a
    // hero bar's width and sits just below it, and record `barsWithSubBar` so `monstersFromRedBars`
    // knows that bar belongs to a hero (jungle monsters and turrets never have a mana/energy sub-bar!).
    private fun withoutAttachedBars(all: List<Seen>): List<Seen> {
        val heroes = all.filter { it.hero || it.team == Team.SELF }
        val confirmedHeroes = HashSet<Seen>()
        val filtered = all.filter { b ->
            val parent = heroes.firstOrNull { a ->
                a !== b && b.barTop >= a.barBottom - 0.002f && b.barTop - a.barBottom <= 0.03f &&
                    b.barLeft >= a.barLeft - 0.01f && b.barLeft <= a.barRight
            }
            if (parent != null) {
                confirmedHeroes.add(parent)
                false
            } else true
        }
        barsWithSubBar = confirmedHeroes
        return filtered
    }

    // Brightness of a ring inside a button, around (not at) its centre: a cooling-down skill is
    // dark there, while its countdown digits sit in the very middle and would look bright.
    // 3-Stage Basic Attack button activation detector (v37.10, calibrated on user screenshots 1000032040/41/42):
    // Samples 5 concentric rings across the inner emblem and outer glow rim around Layout.get("attack"):
    //  - Stage 1 (Idle, 1000032040): meanLum ~112.8, glowPct ~1.7%, cyanPct ~41.7% -> score ~124.7
    //  - Stage 2 (Pressed/Glowing, 1000032041): meanLum ~186.6, glowPct ~62.5%, cyanPct ~67.5% -> score ~259.7
    //  - Stage 3 (Dimmed after release, 1000032042): meanLum ~98.6, glowPct ~0.0%, cyanPct ~9.2% -> score ~100.9
    private fun attackRingBrightness(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        var sum = 0f
        var n = 0
        var glowPx = 0
        var cyanPx = 0
        for (r in floatArrayOf(0.008f, 0.015f, 0.022f, 0.030f, 0.038f)) for (i in 0 until 24) {
            val a = i * Math.PI * 2 / 24
            val x = (fx * w + Math.cos(a) * r * w).toInt()
            val y = (fy * h + Math.sin(a) * r * w).toInt()
            if (x !in 0 until w || y !in 0 until h) continue
            val p = px[y * w + x]
            val pr = (p shr 16) and 0xff; val pg = (p shr 8) and 0xff; val pb = p and 0xff
            val lum = (pr * 3 + pg * 6 + pb) / 10
            sum += lum
            if (pb >= 215 && pg >= 205 && pr >= 120) glowPx++
            if (pb >= 155 && pg >= 140 && pb > pr + 40) cyanPx++
            n++
        }
        if (n == 0) return 0f
        val meanLum = sum / n
        val glowPct = glowPx * 100f / n
        val cyanPct = cyanPx * 100f / n
        return meanLum + glowPct * 1.0f + cyanPct * 0.25f
    }

    private fun ringBrightness(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        var sum = 0
        var n = 0
        for (r in floatArrayOf(0.012f, 0.017f)) for (i in 0 until 24) {
            val a = i * Math.PI * 2 / 24
            val x = (fx * w + Math.cos(a) * r * w).toInt()
            val y = (fy * h + Math.sin(a) * r * w).toInt()
            if (x !in 0 until w || y !in 0 until h) continue
            val p = px[y * w + x]
            sum += (((p shr 16) and 0xff) * 3 + ((p shr 8) and 0xff) * 6 + (p and 0xff)) / 10
            n++
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    // Jungle monsters, turtle and lord have RED HP bars like enemy heroes. An enemy hero's bar has
    // a dark level badge on its left (measured: 0.4-0.8 dark) and the hero shows on the minimap
    // near you (red-ringed portrait); a monster's bar has no badge (0.0-0.17). Wide red bars
    // without a badge, or with no enemy near you on the minimap, are monsters. Small red pieces
    // right next to a monster bar (its tick segments) are dropped.
    // THE LITHOWANDERER'S KILL AND THE STONE ROAMER (owner, 2026-11-11; game.Lithowanderer): the kill is noted
    // from the Lithowanderer vanishing at low HP; for 45 s after it, near its spot, blue "ally heroes" on the
    // screen beyond the ally icons around you on the minimap are the Stone Roamer - a friendly patrol, not
    // backup and not a minion: it is left out of the units altogether. (Its bar is not measured yet, so the
    // narrowest blue bars are taken as the Roamer.)
    private fun lithoAndRoamer(seen: List<Seen>, mini: MinimapInfo, nowMs: Long): List<Seen> {
        val litho = com.example.mlbbresearch.game.Lithowanderer
        litho.observe(nowMs, mini.self, seen.firstOrNull { it.team == Team.NEUTRAL && it.species == com.example.mlbbresearch.game.JungleDb.LITHOWANDERER.id }?.hp)
        val me = mini.self ?: return seen
        if (!litho.roamerActive(nowMs, me)) return seen
        val r = MinimapAnalyzer.rect
        val (l, t) = Layout.get("mapTL"); val (rr, b) = Layout.get("mapBR")
        val alliesNear = mini.allyDots.count { (dx, dy) ->
            val mx = ((r[0] + dx * (r[2] - r[0])) - l) / (rr - l).coerceAtLeast(0.01f)
            val my = ((r[1] + dy * (r[3] - r[1])) - t) / (b - t).coerceAtLeast(0.01f)
            kotlin.math.hypot(mx - me.first, my - me.second) <= 0.15f
        }
        val screenAllies = seen.filter { it.team == Team.ALLY && it.hero }
        val drop = litho.roamerCount(screenAllies.size, alliesNear)
        val roamers = screenAllies.sortedBy { it.barRight - it.barLeft }.take(drop).toMutableSet()
        // its bar is narrower than a hero's (0.067 of the screen, no level badge): it can also be read as an ally
        // MINION. The spot is in the river, away from the lanes: right at it, a blue bar of that size is the Roamer.
        if (litho.atSpot(me)) roamers += seen.filter { it.team == Team.ALLY && !it.hero && (it.barRight - it.barLeft) in 0.055f..0.08f }
        if (roamers.isEmpty()) return seen
        return seen.filter { it !in roamers }
    }

    private fun monstersFromRedBars(all: List<Seen>, px: IntArray, w: Int, h: Int, mini: MinimapInfo): List<Seen> {
        val enemyNearOnMap = enemyNearOnMinimap(mini, w, h)
        val nearEnemyTower = mini.self?.let { me ->
            (mini.towers.map { it.x to it.y } + listOfNotNull(mini.enemyBase)).any { kotlin.math.hypot(it.first - me.first, it.second - me.second) < 0.12f }
        } ?: false
        val out = ArrayList<Seen>(all.size)
        val monsters = ArrayList<Seen>()
        val nowMs = System.currentTimeMillis()
        val aspect = if (h == 0) 1.78f else w.toFloat() / h
        val selfBar = all.firstOrNull { it.team == Team.SELF }
        for (s in all) {
            // a red bar where yours sits is YOUR passive bar, not a unit (see ownBars)
            if (s.team == Team.ENEMY && kotlin.math.abs(s.barLeft - 0.471f) < 0.015f && s.barTop in 0.36f..0.43f) continue
            // AN ENEMY HERO NEEDS ITS OWN LEVEL BADGE AND AN ENEMY ON THE MINIMAP (owner, 2026-11-12 - recording
            // 2026-11-11 0:36: the Molten Fiend's bar was taken for enemy heroes "#182 3%" / "#181 25%" because YOUR
            // level badge's dark circle sat right next to it, and the enemy tracker then kept the ghost "confirmed"):
            //  - the badge: a dark badge left of the bar that is not your own badge, a skill-duration icon in its
            //    place (Cici s1, Thamuz ult...), the hero sub-bar, or a readable level number;
            //  - and an enemy hero icon near you on the minimap (HeroBarRule);
            //  - at a monster seen in the last 2.5 s (a camp being fought) the bar stays the monster unless its
            //    level number is readable.
            val glyph = s.team == Team.ENEMY && s.hero && LevelReader.glyph(px, w, h, s) != null
            val badge = s.team == Team.ENEMY && s.hero && (
                (badgeDark(px, w, h, s) >= 0.20f && !onOwnBadge(s, selfBar)) ||
                    s in barsWithSubBar ||
                    ScreenReader.badgeSkillDuration(px, w, h, s) >= 0f ||
                    glyph
                )
            val confirmedEnemyHero = s.team == Team.ENEMY && s.hero &&
                HeroBarRule.isEnemyHero(badge, enemyNearOnMap, atRecentMonster(s, nowMs, aspect), glyph)
            if (s.team == Team.ENEMY && s.hero && !confirmedEnemyHero) {
                // an enemy TOWER's bar is red without a badge too: near an enemy tower on the
                // minimap it is the tower (a structure), not a monster
                if (nearEnemyTower) { out.add(s.copy(hero = false)); continue }
                // what kind of monster: the bar's FULL width (its orange frame, measured past the
                // hero-width cap) and thickness; then the body's colour (see JungleDb)
                // a turret's bar has a RED emblem on its left (lord / turtle: a purple skull): not a monster
                val icon = iconLeft(px, w, h, s)
                if (icon[0] >= 0.04f && icon[1] < 0.03f) { out.add(s.copy(hero = false)); continue }
                val full = monsterBarWidth(px, w, h, s)
                val thick = (s.barBottom - s.barTop) * h >= 3.5f          // lord / turtle bars: 5 rows
                val col = bodyColour(px, w, h, s.barLeft, s.barBottom, minOf(full, 0.095f))
                // a buff-coloured body on a thin bar is a buff even if dark ground made the bar look long
                val buffBody = com.example.mlbbresearch.game.JungleDb.buff(col[0], col[1]) != null
                val kind = if (!thick && buffBody && full >= com.example.mlbbresearch.game.JungleDb.BUFF_WIDTH) com.example.mlbbresearch.game.MonsterKind.BUFF
                    else com.example.mlbbresearch.game.JungleDb.kindOf(full, thick)
                // lord / turtle bars always carry the purple skull (measured 0.08): a long red bar
                // without it is something else (a skill bar, an effect) - not a monster
                if (kind == com.example.mlbbresearch.game.MonsterKind.BOSS && icon[1] < 0.03f) continue
                val boss = kind == com.example.mlbbresearch.game.MonsterKind.BOSS
                val fullW = if (kind == com.example.mlbbresearch.game.MonsterKind.BUFF) minOf(full, 0.095f) else full
                // HP = the red part over the FULL bar (the hero-width cap cut long bars short)
                val hp = (s.hp * (s.barRight - s.barLeft) / fullW).coerceIn(0f, 1f)
                // which monster: its spot first (owner's 20 jungle screenshots - game.JungleSpots), then its colour
                // (Solo practice: every monster is up from 0:00 - MatchKind.jungleSec)
                val sec = com.example.mlbbresearch.game.MatchKind.jungleSec(com.example.mlbbresearch.game.GameStateTracker.state.matchSec)
                val species = when (kind) {
                    com.example.mlbbresearch.game.MonsterKind.BOSS -> com.example.mlbbresearch.game.JungleDb.boss(mini, sec).id
                    com.example.mlbbresearch.game.MonsterKind.BUFF -> (com.example.mlbbresearch.game.JungleSpots.at(mini.self, kind, sec)
                        ?: com.example.mlbbresearch.game.JungleDb.buff(col[0], col[1]) ?: com.example.mlbbresearch.game.JungleDb.CREEP).id
                    // (only one Lithowanderer is up at a time - nowMs lets it rule out the other spot)
                    else -> (com.example.mlbbresearch.game.JungleSpots.at(mini.self, kind, sec, System.currentTimeMillis())
                        ?: com.example.mlbbresearch.game.JungleDb.camp(col[0], col[1], col[2])).id
                }
                val m = Seen(Team.NEUTRAL, boss, s.x, s.y, hp, s.barLeft, s.barTop, s.barLeft + fullW, s.barBottom, species)
                monsters.add(m); out.add(m)
            } else out.add(s)
        }
        // at a jungle camp with no enemy wave near on the minimap, minion-sized enemy bars are the camp (CampRule)
        mini.self?.let { me ->
            val sec = com.example.mlbbresearch.game.MatchKind.jungleSec(com.example.mlbbresearch.game.GameStateTracker.state.matchSec)
            val camp = com.example.mlbbresearch.game.JungleSpots.campAt(me, sec, nowMs)
            if (CampRule.minionIsTheCamp(camp != null, CampRule.waveNear(me, mini.enemyWaves))) for (i in out.indices) {
                val u = out[i]
                if (u.team == Team.ENEMY && !u.hero) {
                    val m = u.copy(team = Team.NEUTRAL, species = camp!!.id)
                    out[i] = m; monsters.add(m)
                }
            }
        }
        for (m in monsters) recentMonsters.add(Triple(m.x, m.y, nowMs))
        recentMonsters.removeAll { nowMs - it.third > HeroBarRule.MONSTER_MEMORY_MS }
        if (monsters.isEmpty()) return out
        // the Thunder Fenrir's cub: a small blue monster right next to the big one is the same camp
        val fenrir = monsters.filter { it.species == com.example.mlbbresearch.game.JungleDb.THUNDER_FENRIR.id }
        if (fenrir.isNotEmpty()) for (i in out.indices) {
            val u = out[i]
            if (u.team == Team.NEUTRAL && !u.hero && u.species != com.example.mlbbresearch.game.JungleDb.THUNDER_FENRIR.id &&
                fenrir.any { f -> kotlin.math.abs(f.x - u.x) < 0.15f && kotlin.math.abs(f.y - u.y) < 0.3f })
                out[i] = u.copy(species = com.example.mlbbresearch.game.JungleDb.THUNDER_FENRIR.id)
        }
        return out.filter { u ->
            !(u.team == Team.ENEMY && !u.hero && monsters.any { m ->
                u.barLeft >= m.barLeft - 0.03f && u.barRight <= m.barRight + 0.03f && kotlin.math.abs(u.barTop - m.barTop) < 0.06f
            })
        }
    }

    // A monster bar's full width: from its left end along the middle row, red (HP) then the grey
    // empty part (thin tick marks stepped over), up to 14% of the screen width.
    private fun monsterBarWidth(px: IntArray, w: Int, h: Int, s: Seen): Float {
        val y = (((s.barTop + s.barBottom) / 2) * h).toInt().coerceIn(0, h - 1)
        val x0 = (s.barLeft * w).toInt().coerceIn(0, w - 1)
        val x1 = minOf(w - 1, x0 + (0.14f * w).toInt())
        // stops at the orange frame's end, at 3 odd pixels in a row, or where the grey changes shade
        // (dark ground right after the bar is not "missing HP")
        var end = x0; var gap = 0; var x = x0; var grey = -1
        while (x <= x1) {
            val p = px[y * w + x]
            val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val lum = (r * 3 + g * 6 + b) / 10
            val red = r >= 150 && g <= 100 && b <= 100
            val orange = r >= 170 && g in 90..200 && b <= 130 && r - b >= 70
            if (orange && x > x0 + 4) break
            val empty = mx < 95 && mx - mn < 35 && (grey < 0 || kotlin.math.abs(lum - grey) <= 18)
            if (red) { end = x; gap = 0 }
            else if (empty) { if (grey < 0) grey = lum; end = x; gap = 0 }
            else if (++gap > 2) break
            x++
        }
        return maxOf(s.barRight - s.barLeft, (end - x0 + 1).toFloat() / w)
    }

    // colour shares of the icon just left of a bar: [red (turret emblem), purple (boss skull / buff dot)]
    // measured: turret 0.07 / 0; lord, turtle 0 / 0.08
    private fun iconLeft(px: IntArray, w: Int, h: Int, s: Seen): FloatArray {
        val cy = (s.barTop + s.barBottom) / 2
        val x0 = ((s.barLeft - 0.03f) * w).toInt().coerceIn(0, w - 1); val x1 = ((s.barLeft - 0.003f) * w).toInt().coerceIn(0, w - 1)
        val y0 = ((cy - 0.03f) * h).toInt().coerceIn(0, h - 1); val y1 = ((cy + 0.03f) * h).toInt().coerceIn(0, h - 1)
        var n = 0; var red = 0; var pur = 0
        for (y in y0..y1) for (x in x0..x1) {
            val p = px[y * w + x]
            val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
            n++
            // Recognise both red turret shield emblems AND gold-plated outer turret shield emblems (v37.16)
            if ((r > 150 && g < 95 && b < 110) || (r > 175 && g in 120..215 && b < 120 && r - b >= 65)) red++
            if (r > 110 && b > 140 && g < 110) pur++
        }
        return if (n == 0) floatArrayOf(0f, 0f) else floatArrayOf(red.toFloat() / n, pur.toFloat() / n)
    }

    // colour shares of the body under a monster bar: [orange, blue / lavender, green]
    private fun bodyColour(px: IntArray, w: Int, h: Int, left: Float, bottom: Float, width: Float): FloatArray {
        val x0 = (left * w).toInt().coerceIn(0, w - 1); val x1 = ((left + width) * w).toInt().coerceIn(0, w - 1)
        val y0 = ((bottom + 0.03f) * h).toInt().coerceIn(0, h - 1); val y1 = ((bottom + 0.22f) * h).toInt().coerceIn(0, h - 1)
        var n = 0; var o = 0; var bl = 0; var gr = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val p = px[y * w + x]
                val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
                n++
                if (r > 180 && g in 81..189 && b < 110 && r - b > 90) o++
                if ((b > r + 60 && b > g + 20 && b > 140) || (b > 170 && r > 140 && g > 120 && b >= g)) bl++
                if (g > r + 40 && g > b + 15 && g > 120) gr++
                x += 2
            }
            y += 2
        }
        return if (n == 0) floatArrayOf(0f, 0f, 0f) else floatArrayOf(o.toFloat() / n, bl.toFloat() / n, gr.toFloat() / n)
    }

    private var snapSide: Boolean? = null
    private var snapEnemy = false
    private var snapMon = false

    // where monsters were seen in the last moments (a camp being fought): bars there stay monsters
    private val recentMonsters = ArrayList<Triple<Float, Float, Long>>()
    private fun atRecentMonster(s: Seen, nowMs: Long, aspect: Float): Boolean =
        recentMonsters.any { nowMs - it.third <= HeroBarRule.MONSTER_MEMORY_MS && kotlin.math.hypot((it.first - s.x) * aspect, it.second - s.y) <= HeroBarRule.MONSTER_NEAR }

    // the bar's badge spot is YOUR level badge (left of your own bar, same height)
    private fun onOwnBadge(s: Seen, self: Seen?): Boolean = self != null &&
        HeroBarRule.overlapsOwnBadge(s.barLeft, (s.barTop + s.barBottom) / 2, self.barLeft, (self.barTop + self.barBottom) / 2)

    // dark level badge just left of a bar
    private fun badgeDark(px: IntArray, w: Int, h: Int, s: Seen): Float {
        val cy = ((s.barTop + s.barBottom) / 2 * h).toInt()
        val x0 = ((s.barLeft - 0.022f) * w).toInt()
        val x1 = (s.barLeft * w).toInt()
        var dark = 0; var n = 0
        for (y in cy - 6..cy + 6) for (x in x0 until x1) {
            if (x !in 0 until w || y !in 0 until h) continue
            val q = px[y * w + x]
            n++
            if (((q shr 16) and 255) < 50 && ((q shr 8) and 255) < 50 && (q and 255) < 50) dark++
        }
        return if (n == 0) 0f else dark.toFloat() / n
    }

    // an enemy hero portrait on the minimap close to your own icon (no minimap: assume yes)
    private fun enemyNearOnMinimap(mini: MinimapInfo, w: Int, h: Int): Boolean {
        val icon = mini.selfIcon ?: return true
        val r = MinimapAnalyzer.rect
        val (l, _) = Layout.get("mapTL")
        val (rr, _) = Layout.get("mapBR")
        val reach = 0.2f * (rr - l)                     // about what the screen shows, in screen widths
        val sx = icon[0]; val sy = icon[1]              // y is a fraction of the width
        // a half-covered enemy right next to your icon is near too
        return (mini.enemyDots + mini.occludedDots).any { (dx, dy) ->
            val ex = r[0] + dx * (r[2] - r[0])
            val ey = (r[1] + dy * (r[3] - r[1])) * h / w
            kotlin.math.hypot(ex - sx, ey - sy) < reach
        }
    }

    private fun heroSig(px: IntArray, w: Int, h: Int, self: Seen, mini: MinimapInfo): List<FloatArray>? {
        val icon = mini.selfIcon ?: return null
        val portrait = IconMemory.portrait(px, w, h, icon) ?: return null
        return listOf(portrait, IconMemory.model(px, w, h, self))
    }

    fun reset() {
        enemyTracker.clear()
        recentMonsters.clear()
        latest = null
        processedFps = 0f
    }
}
