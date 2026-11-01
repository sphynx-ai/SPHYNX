package com.example.mlbbresearch.learning

import android.content.ContentValues
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.HeroDb
import com.example.mlbbresearch.game.Phase
import com.example.mlbbresearch.vision.ScreenHint
import com.example.mlbbresearch.vision.ScreenReader
import com.example.mlbbresearch.vision.VisionResult
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min

// LEARNING FROM LIVE MATCHES: while you watch another player's live match in MLBB, their
// screen is read like one of your own matches and saved as training moments for the tactical
// network (exported as mlbb-live-*.csv, same columns as mlbb-my-play-*.csv).
//
//  - the situation: their hero = the blue bar in the centre (the camera follows them), its HP
//    from the panel's green line, enemies / minions / monsters / bushes / tower circles on their
//    screen, and the minimap (all enemies are shown there in a live match)
//  - what they did:
//      walking  = the camera's motion (it follows their hero; there is no joystick on screen)
//      skills   = a skill / spell icon in the panel suddenly greying out (its cooldown started)
//      attack   = standing still next to an enemy / minion / monster (basic attacks cannot be seen)
//  - their cooldowns (ready or not) come from the same icons, not from yours
// Nothing here touches your own match: the phase is LIVE, the AI never plays on this screen.
object LiveRecorder {

    private val baseline = HashMap<String, Float>()
    private val previous = HashMap<String, Float>()
    private val ready = HashMap<String, Boolean>()
    @Volatile private var pendingCasts = 0
    private var sumMx = 0f
    private var sumMy = 0f
    private var frames = 0
    private var lastRecord = 0L
    private var lastMx = 0f
    private var lastMy = 0f

    @Volatile var recorded = 0L        // this session
        private set
    @Volatile var total = 0L           // in the database
        private set
    @Volatile var lastAction = "-"
        private set
    @Volatile var panel = "-"          // what the panel shows (for the Learn page)
        private set

    fun refreshTotal() {
        ExperienceRecorder.onDb({ db ->
            db.rawQuery("SELECT COUNT(*) FROM live_demos", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }) { n -> if (n != null) total = n }
    }

    // ---- the icons' "ready" colour, remembered per watched hero (so a skill that is already
    // cooling when you start watching is read right from the first frame) ----
    private var baseHero = ""
    private val savedAt = HashMap<String, Float>()

    private fun loadBaselines() {
        val hero = Cfg.liveHero.trim().lowercase()
        if (hero == baseHero) return
        baseHero = hero
        baseline.clear(); previous.clear(); ready.clear(); savedAt.clear()
        if (hero.isEmpty()) return
        for (k in ScreenReader.LIVE_ICONS.keys) Cfg.raw("live_icon_${hero}_$k")?.toFloatOrNull()?.let { baseline[k] = it; savedAt[k] = it }
    }

    private fun saveBaseline(k: String, v: Float) {
        if (baseHero.isEmpty()) return
        val last = savedAt[k]
        if (last == null || kotlin.math.abs(v - last) > last * 0.03f) { savedAt[k] = v; Cfg.setRaw("live_icon_${baseHero}_$k", "%.1f".format(Locale.US, v)) }
    }

    // ---- deaths of the watched hero: the panel disappears (LIVE_DEAD) ----
    private var lastAliveAt = 0L
    private var deadSince = 0L
    private var deathMarked = false
    @Volatile var deaths = 0
        private set

    // every frame of the live screen: camera motion and the moment an icon goes dark
    @Synchronized
    fun observe(v: VisionResult) {
        if (v.hint == ScreenHint.LIVE_DEAD) {
            // dead for 2 s in a row (not one odd frame): the moments before it led to the death
            if (deadSince == 0L) deadSince = v.ts
            if (!deathMarked && lastAliveAt > 0 && v.ts - deadSince >= 2000 && deadSince - lastAliveAt < 3000) {
                deathMarked = true; deaths++
                markDeath(lastAliveAt)
            }
            sumMx = 0f; sumMy = 0f; frames = 0
            return
        }
        if (v.hint != ScreenHint.LIVE) return
        if (v.self != null) { lastAliveAt = v.ts; deadSince = 0L; deathMarked = false }
        loadBaselines()
        sumMx += v.hud.motionX
        sumMy += v.hud.motionY
        frames++
        val sb = StringBuilder()
        for ((k, b) in v.skillBrightness) {
            val base = baseline[k] ?: b
            val prev = previous[k] ?: b
            // the ready colour: rises when two frames in a row are more colourful (not on one
            // flash), sinks only very slowly (a 2-minute cooldown must not become "normal")
            val nb = if (b > base && prev > base) min(b, prev) else base * 0.99995f + b * 0.00005f
            baseline[k] = nb
            if (nb > base) saveBaseline(k, nb)
            previous[k] = b
            // colour strength of the icon: below 88% of its usual = cooling, 94%+ = ready
            ready[k] = b >= base * 0.94f || (ready[k] == true && b >= base * 0.88f)
            if (base > 20f && b < base * 0.88f && prev >= base * 0.94f) {
                val bit = Casts.bySlot[k] ?: continue
                pendingCasts = pendingCasts or bit
            }
            sb.append(k).append(' ').append(if (ready[k] == true) "ready" else "cooling").append("  ")
        }
        panel = sb.toString().trim()
    }

    // about 5 times a second while their hero is on screen
    fun record(s: GameState) {
        if (s.phase != Phase.LIVE || s.self == null) return
        val now = System.currentTimeMillis()
        if (now - lastRecord < 180) return
        lastRecord = now
        var mx: Float
        var my: Float
        var casts: Int
        synchronized(this) {
            mx = if (frames > 0) sumMx / frames else 0f
            my = if (frames > 0) sumMy / frames else 0f
            sumMx = 0f; sumMy = 0f; frames = 0
            casts = pendingCasts
            pendingCasts = 0
        }
        val ml = hypot(mx, my)
        if (ml < 0.4f) { mx = 0f; my = 0f } else { mx /= ml; my /= ml }
        if (mx == 0f && my == 0f) {
            val p = HeroDb.profile(s.ownHero)
            val target = s.enemiesWithin(p.attackRange * 1.3f).isNotEmpty() || s.minionsWithin(p.attackRange * 1.4f).isNotEmpty() ||
                s.monsters.any { s.distToSelf(it.x, it.y) < p.attackRange * 1.3f }
            if (target) casts = casts or Casts.ATTACK
        }
        val f = Features.of(s)
        // THEIR cooldowns, from the panel icons (not yours)
        f[13] = if (ready["s1"] != false) 1f else 0f
        f[14] = if (ready["s2"] != false) 1f else 0f
        f[15] = if (ready["ult"] != false) 1f else 0f
        val f2 = Features2.of(s, f, lastMx to lastMy)
        f2[31] = if (ready["spell"] != false) 1f else 0f
        lastMx = mx; lastMy = my
        lastAction = DemoRecorder.describe(mx, my, casts)
        val values = ContentValues().apply {
            put("ts", now)
            put("hero", s.ownHero ?: "unknown")
            put("features", f.joinToString(",") { "%.3f".format(Locale.US, it) })
            put("features2", f2.joinToString(",") { "%.3f".format(Locale.US, it) })
            put("move_x", mx)
            put("move_y", my)
            put("casts", casts)
            put("source", "live")
            put("lane", s.lane.name)
            put("match_sec", s.matchSec)
        }
        ExperienceRecorder.onDb({ db -> db.insert("live_demos", null, values) }) { id ->
            if (id != null && id > 0) { recorded++; total++ }
        }
    }

    @Synchronized
    fun resetSession() {
        baseline.clear(); previous.clear(); ready.clear()
        pendingCasts = 0
        sumMx = 0f; sumMy = 0f; frames = 0
    }

    // the watched hero died at (about) time t: the moments of the 6 s before were the ones that
    // led to it (the notebook can learn less from them)
    private fun markDeath(t: Long) {
        ExperienceRecorder.onDb({ db ->
            db.execSQL("UPDATE live_demos SET died = 1 WHERE ts BETWEEN ? AND ?", arrayOf<Any>(t - 6000, t + 500))
        }) { }
        com.example.mlbbresearch.core.Diag.info("Live match: the watched hero died (moments before it marked)")
    }

    fun clear(done: () -> Unit) {
        ExperienceRecorder.onDb({ db -> db.delete("live_demos", null, null) }) {
            total = 0; recorded = 0
            done()
        }
    }
}
