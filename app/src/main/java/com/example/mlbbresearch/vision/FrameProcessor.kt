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
        // watching someone's live match: their screen has no buttons of yours, but its own overlays
        val live = hint == ScreenHint.LIVE || hint == ScreenHint.LIVE_DEAD
        val mini = MinimapAnalyzer.analyze(px, w, h, live)
        val masks = if (live) ScreenReader.LIVE_MASKS else hud
        val seen = if (live) liveUnits(px, w, h, mini, banner, hint == ScreenHint.LIVE) else monstersFromRedBars(withoutAttachedBars(ownBars(hint, HeroDetector.scan(
            px, w, h, hud + ScreenMasks.overlay + (if (banner) listOf(ScreenReader.bannerRect) else emptyList()),
            Cfg.heroBarMinWidthPct / 100f, Cfg.bodyBelowBarPct / 100f
        ), px, w, h)), px, w, h, mini)
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
        fun steady(u: Seen) = prevSmall.any { it.team == u.team && kotlin.math.abs(it.x - u.x) < 0.04f && kotlin.math.abs(it.y - u.y) < 0.05f }
        val small = seen.filter { !it.hero && it.team != Team.SELF && !onSelf(it) }
        val steadySmall = small.filter { steady(it) }
        prevSmall = small
        val aspect = w.toFloat() / h
        val tracks = enemyTracker.update(seen.filter { it.team == Team.ENEMY && it.hero }, t0, aspect)

        val brightness = HashMap<String, Float>()
        if (hint == ScreenHint.LIVE) {
            // their skill / spell icons in the panel (they darken while cooling down)
            for ((k, c) in ScreenReader.LIVE_ICONS) brightness[k] = ScreenReader.iconColour(px, w, h, c.first, c.second)
        } else if (Cfg.readCooldownsFromScreen || trackPlayer) {
            for (k in listOf("s1", "s2", "ult", "spell", "recall", "regen")) {
                val (fx, fy) = Layout.get(k)
                brightness[k] = ringBrightness(px, w, h, fx, fy)
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
        // your level from the badge, and the nearest enemy hero's (from the badges remembered)
        if (self != null) LevelReader.update(px, w, h, self, hudInfo.upgradeReady.isNotEmpty(), t0)
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
            kda = if (self != null && !live) KdaReader.read(px, w, h) else IntArray(3),
            bushes = scr.bushes,
            inBush = bush.inBush,
            screenWalls = scr.walls,
            level = LevelReader.level,
            xp = LevelReader.xp,
            enemyLevel = enemyLevel,
            towerRing = scr.ring,
            processMs = System.currentTimeMillis() - t0
        )
        // whole-screen detector training pictures (every 10 s in a match)
        com.example.mlbbresearch.learning.ScreenDatasetCollector.maybeSave(px, w, h, result, mini, self != null && hint == ScreenHint.NONE,
            com.example.mlbbresearch.game.JungleNav.atBuff, com.example.mlbbresearch.game.GameStateTracker.state.matchSec,
            steadySmall.filter { it.team == Team.NEUTRAL } + seen.filter { it.team == Team.NEUTRAL && it.hero }, ring)
        latest = result
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
        // and your level badge with its white number next to it (a dimmed shop screen has none)
        return if (LevelReader.glyph(px, w, h, me) != null) out + me else out
    }

    // Many heroes have a second bar right under their HP bar (passive, energy, mana, e.g. Thamuz's
    // red passive bar). It is part of that hero, not a unit: drop any bar that starts inside a
    // hero bar's width and sits just below it.
    private fun withoutAttachedBars(all: List<Seen>): List<Seen> {
        val heroes = all.filter { it.hero || it.team == Team.SELF }
        return all.filter { b ->
            heroes.none { a ->
                a !== b && b.barTop >= a.barBottom - 0.002f && b.barTop - a.barBottom <= 0.03f &&
                    b.barLeft >= a.barLeft - 0.01f && b.barLeft <= a.barRight
            }
        }
    }

    // Brightness of a ring inside a button, around (not at) its centre: a cooling-down skill is
    // dark there, while its countdown digits sit in the very middle and would look bright.
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
    private fun monstersFromRedBars(all: List<Seen>, px: IntArray, w: Int, h: Int, mini: MinimapInfo): List<Seen> {
        val enemyNearOnMap = enemyNearOnMinimap(mini, w, h)
        val nearEnemyTower = mini.self?.let { me ->
            (mini.towers.map { it.x to it.y } + listOfNotNull(mini.enemyBase)).any { kotlin.math.hypot(it.first - me.first, it.second - me.second) < 0.12f }
        } ?: false
        val out = ArrayList<Seen>(all.size)
        val monsters = ArrayList<Seen>()
        for (s in all) {
            // a red bar where yours sits is YOUR passive bar, not a unit (see ownBars)
            if (s.team == Team.ENEMY && kotlin.math.abs(s.barLeft - 0.471f) < 0.015f && s.barTop in 0.36f..0.43f) continue
            if (s.team == Team.ENEMY && s.hero && !(badgeDark(px, w, h, s) >= 0.3f && enemyNearOnMap)) {
                // an enemy TOWER's bar is red without a badge too: near an enemy tower on the
                // minimap it is the tower (a structure), not a monster
                if (nearEnemyTower) { out.add(s.copy(hero = false)); continue }
                // the lord's / turtle's bar is longer than a hero's (heroes: 7.7-7.9% of the width;
                // the lord's measured 8.4% in a live match)
                val boss = s.barRight - s.barLeft >= 0.082f
                val m = Seen(Team.NEUTRAL, boss, s.x, s.y, s.hp, s.barLeft, s.barTop, s.barRight, s.barBottom)
                monsters.add(m); out.add(m)
            } else out.add(s)
        }
        if (monsters.isEmpty()) return out
        return out.filter { u ->
            !(u.team == Team.ENEMY && !u.hero && monsters.any { m ->
                u.barLeft >= m.barLeft - 0.03f && u.barRight <= m.barRight + 0.03f && kotlin.math.abs(u.barTop - m.barTop) < 0.06f
            })
        }
    }

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
        return mini.enemyDots.any { (dx, dy) ->
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
        latest = null
        processedFps = 0f
    }
}
