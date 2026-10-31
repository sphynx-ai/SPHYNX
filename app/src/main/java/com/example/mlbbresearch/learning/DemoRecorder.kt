package com.example.mlbbresearch.learning

import android.content.ContentValues
import com.example.mlbbresearch.ai.Intent
import com.example.mlbbresearch.ai.TacticalDecision
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.game.CooldownTracker
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.HeroDb
import com.example.mlbbresearch.vision.VisionResult
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min

// "Learn from me": while YOU play (Player AI off), the app watches the screen and saves
// what the situation was and what you did, about 5 times a second:
//   movement  = which way the camera (always centred on your hero) moved
//   skills    = a skill / spell / recall button suddenly going dark (its cooldown started)
//   attack    = standing still next to an enemy or minions
// ImitationPolicy then plays by finding the moments most like the current one and doing
// what you did then.
object Features {
    const val SIZE = 17

    private fun clip(v: Float) = v.coerceIn(-1f, 1f)

    fun of(s: GameState): FloatArray {
        val f = FloatArray(SIZE)
        val self = s.self
        f[0] = if (s.selfHp >= 0f) s.selfHp else 0.5f
        val near = s.enemiesWithin(0.9f)
        f[1] = min(near.size, 5) / 5f
        val e = near.minByOrNull { s.distToSelf(it.x, it.y) }
        if (e != null && self != null) {
            f[2] = clip((e.x - self.x) * s.aspect)
            f[3] = clip(e.y - self.y)
            f[4] = (s.distToSelf(e.x, e.y) / 1.5f).coerceIn(0f, 1f)
            f[5] = e.hp
            f[6] = 1f
        } else {
            f[4] = 1f; f[5] = 1f
        }
        val minions = s.minionsWithin(0.9f)
        f[7] = min(minions.size, 8) / 8f
        if (minions.isNotEmpty() && self != null) {
            f[8] = clip((minions.map { it.x }.average().toFloat() - self.x) * s.aspect)
            f[9] = clip(minions.map { it.y }.average().toFloat() - self.y)
        }
        val allies = s.alliesWithin(0.9f)
        f[10] = min(allies.size, 4) / 4f
        if (allies.isNotEmpty() && self != null) {
            f[11] = clip((allies.map { it.x }.average().toFloat() - self.x) * s.aspect)
            f[12] = clip(allies.map { it.y }.average().toFloat() - self.y)
        }
        val p = HeroDb.profile(s.ownHero)
        f[13] = if (CooldownTracker.ready("s1", p)) 1f else 0f
        f[14] = if (CooldownTracker.ready("s2", p)) 1f else 0f
        f[15] = if (CooldownTracker.ready("ult", p)) 1f else 0f
        f[16] = min(s.minimapEnemies, 5) / 5f
        return f
    }

    // how much each feature matters when comparing two moments
    val weight = floatArrayOf(2f, 1.5f, 2f, 2f, 1.5f, 1f, 1.5f, 1f, 1.2f, 1.2f, 0.8f, 0.6f, 0.6f, 0.7f, 0.7f, 0.9f, 0.5f)
}

// The richer situation for the trained tactical network (notebooks/train_tactical_policy.ipynb):
// the 17 numbers above plus level, EXP, survival score, jungle monsters, bush, the enemy tower
// circle, enemies near you on the minimap (detector), lane, match time and the battle spell.
object Features2 {
    const val SIZE = 40
    const val SIZE_V1 = 32      // recordings from v25-v31 (without the last 8 numbers)

    // prev: the direction you (or the AI) were already moving, 0,0 = standing
    fun of(s: GameState, base: FloatArray = Features.of(s), prev: Pair<Float, Float> = 0f to 0f): FloatArray {
        val f = FloatArray(SIZE)
        System.arraycopy(base, 0, f, 0, Features.SIZE)
        val self = s.self
        f[17] = s.level / 15f
        f[18] = if (s.xp >= 0f) s.xp else 0f
        f[19] = com.example.mlbbresearch.ai.Utility.survival(s, HeroDb.profile(s.ownHero)) / 100f
        f[20] = min(s.creeps.size, 4) / 4f
        val c = s.creeps.minByOrNull { s.distToSelf(it.x, it.y) }
        if (c != null && self != null) {
            f[21] = ((c.x - self.x) * s.aspect).coerceIn(-1f, 1f)
            f[22] = (c.y - self.y).coerceIn(-1f, 1f)
            f[23] = c.hp
        }
        f[24] = if (s.inBush) 1f else 0f
        val ring = s.towerRing
        f[25] = if (ring.zone == com.example.mlbbresearch.vision.TowerRing.Zone.RED && ring.inside) 1f else 0f
        f[26] = if (ring.zone == com.example.mlbbresearch.vision.TowerRing.Zone.YELLOW) 1f else 0f
        f[27] = if (ring.zone == com.example.mlbbresearch.vision.TowerRing.Zone.GREEN) 1f else 0f
        f[28] = min(com.example.mlbbresearch.game.MapIntel.enemiesNear(s, 0.2f), 5) / 5f
        f[29] = if (s.lane == com.example.mlbbresearch.game.Lane.JUNGLE) 1f else 0f
        f[30] = (s.matchSec.coerceAtLeast(0) / 1200f).coerceAtMost(1f)
        f[31] = if (CooldownTracker.ready("spell", HeroDb.profile(s.ownHero))) 1f else 0f
        // WHERE you are going (most walking is about that, not about the units around you):
        // your place on the minimap, the way to the camp you head for (else the nearest one),
        // the way to the nearest enemy tower, and the way you were already moving
        val me = s.minimapSelf
        f[32] = me?.first ?: 0.5f
        f[33] = me?.second ?: 0.5f
        fun dir(to: Pair<Float, Float>?, a: Int) {
            if (me == null || to == null) return
            val dx = to.first - me.first; val dy = to.second - me.second
            val l = hypot(dx, dy)
            if (l > 1e-3f) { f[a] = dx / l; f[a + 1] = dy / l }
        }
        dir(com.example.mlbbresearch.game.JungleNav.targetPos ?: s.camps.minByOrNull { me?.let { m -> hypot(it.x - m.first, it.y - m.second) } ?: 0f }?.let { it.x to it.y }, 34)
        dir(s.towers.minByOrNull { me?.let { m -> hypot(it.x - m.first, it.y - m.second) } ?: 0f }?.let { it.x to it.y }, 36)
        f[38] = prev.first; f[39] = prev.second
        return f
    }
}

object Casts {
    const val ATTACK = 1
    const val S1 = 2
    const val S2 = 4
    const val ULT = 8
    const val SPELL = 16
    const val RECALL = 32
    val bySlot = linkedMapOf("attack" to ATTACK, "s1" to S1, "s2" to S2, "ult" to ULT, "spell" to SPELL, "recall" to RECALL)

    fun names(mask: Int) = bySlot.filter { (mask and it.value) != 0 }.keys.toList()
}

object DemoRecorder {

    private val baseline = HashMap<String, Float>()
    private val previous = HashMap<String, Float>()
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

    fun refreshTotal() {
        ExperienceRecorder.onDb({ db ->
            db.rawQuery("SELECT COUNT(*) FROM demos", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }) { n -> if (n != null) total = n }
    }

    // every frame while learning: spot the moment a button goes dark = you just used it
    // aiPlaying: the Player AI is on. Its own casts are already known, and a button that only
    // LOOKS dark (shop, death screen, effects) must not be taken as a cast: that marked skills as
    // cooling down and the AI stopped using them while "Learn from me" was on.
    @Synchronized
    fun observe(v: VisionResult, aiPlaying: Boolean = false, s: GameState? = null) {
        sumMx += v.hud.motionX
        sumMy += v.hud.motionY
        frames++
        for ((k, b) in v.skillBrightness) {
            val base = baseline[k] ?: b
            baseline[k] = if (b > base) b else base * 0.995f + b * 0.005f
            val prev = previous[k] ?: b
            previous[k] = b
            // a cast: the button goes dark. While the AI plays, only one the AI did NOT press is
            // yours (coach mode); never on the shop / death screens (no hero = no buttons to read)
            if (v.self != null && base > 40f && b < base * 0.72f && prev >= base * 0.82f &&
                (!aiPlaying || com.example.mlbbresearch.controller.ActionExecutor.aiCastAgo(k) > 800)
            ) {
                val bit = Casts.bySlot[k] ?: continue
                pendingCasts = pendingCasts or bit
                if (k != "recall") CooldownTracker.markCast(k)
                if (s != null) Coach.onUserCast(k, s)       // Player off: every cast is yours
            }
        }
    }

    // about 5 times a second while learning and you are in a match
    fun record(s: GameState) {
        val now = System.currentTimeMillis()
        if (now - lastRecord < 180) return
        lastRecord = now
        if (s.self == null) return

        // movement: the camera follows your hero, so its motion is where you walked.
        // Averaged over the frames since the last row; mostly one way = walking that way.
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
            val target = s.enemiesWithin(p.attackRange * 1.3f).isNotEmpty() || s.minionsWithin(p.attackRange * 1.4f).isNotEmpty()
            if (target) casts = casts or Casts.ATTACK
        }
        val f = Features.of(s)
        lastAction = describe(mx, my, casts)
        Brain.observeUser(s, mx, my, casts)
        val f2 = Features2.of(s, f, lastMx to lastMy)
        lastMx = mx; lastMy = my
        val values = ContentValues().apply {
            put("ts", now)
            put("hero", s.ownHero ?: "unknown")
            put("features", f.joinToString(",") { "%.3f".format(Locale.US, it) })
            put("features2", f2.joinToString(",") { "%.3f".format(Locale.US, it) })
            put("move_x", mx)
            put("move_y", my)
            put("casts", casts)
        }
        ExperienceRecorder.onDb({ db -> db.insert("demos", null, values) }) { id ->
            if (id != null && id > 0) { recorded++; total++ }
        }
    }

    // ---- learning from the heroes you watch (allies when you are dead, enemies on screen) ----
    //  - dead and watching an ally: the camera follows THEM, so the camera's motion is where
    //    they walked, and the screen is their situation (their bar sits where yours would)
    //  - alive, camera still: an enemy hero's own movement on screen is its decision; its
    //    situation is seen from its side (you and your allies are its enemies)
    // Saved as "~watched" / "~enemy" moments: Play like me uses them too, a bit less than yours.
    @Volatile var watched = 0L
        private set
    private var lastWatched = 0L
    private var lastEnemyRec = 0L

    fun recordWatched(s: GameState) {
        val now = System.currentTimeMillis()
        if (s.matchSec < 0) return
        if (s.self == null) {
            if (now - lastWatched < 180) return
            lastWatched = now
            // an ally hero bar where your own would be = the camera is on that ally
            val a = s.allies.firstOrNull { kotlin.math.abs(it.barLeft - 0.471f) < 0.02f && it.barTop in 0.35f..0.44f } ?: run {
                synchronized(this) { sumMx = 0f; sumMy = 0f; frames = 0 }
                return
            }
            var mx: Float; var my: Float
            synchronized(this) {
                mx = if (frames > 0) sumMx / frames else 0f
                my = if (frames > 0) sumMy / frames else 0f
                sumMx = 0f; sumMy = 0f; frames = 0
            }
            val me = com.example.mlbbresearch.vision.Seen(com.example.mlbbresearch.vision.Team.SELF, true, a.x, a.y, a.hp,
                a.barLeft, a.barTop, a.barRight, a.barBottom)
            val view = GameState(now, s.phase, me, a.hp, s.allies.filter { it !== a }, s.enemies, s.enemyMinions, s.allyMinions,
                s.minimapEnemies, s.aspect, null, emptyList(), emptyList(), -1, -1, Long.MAX_VALUE)
            save(view, "~watched", mx, my)
            return
        }
        // alive: enemy heroes, only while your camera stands still (their screen motion is their own)
        if (now - lastEnemyRec < 400 || s.hud.motionX * s.hud.motionX + s.hud.motionY * s.hud.motionY > 0.04f) return
        lastEnemyRec = now
        for (e in s.enemies) {
            if (now - e.lastSeen > 300 || now - e.firstSeen < 600) continue
            val me = com.example.mlbbresearch.vision.Seen(com.example.mlbbresearch.vision.Team.SELF, true, e.x, e.y, e.hp,
                e.x - 0.04f, e.y - 0.09f, e.x + 0.04f, e.y - 0.08f)
            // from its side: you and your allies are its enemies, your minions its targets
            val foes = (listOfNotNull(s.self) + s.allies).mapIndexed { i, u ->
                com.example.mlbbresearch.vision.Track(1000 + i, u.x, u.y, u.hp, 0f, 0f, now, now, u.hp)
            }
            val friends = s.enemies.filter { it.id != e.id }.map {
                com.example.mlbbresearch.vision.Seen(com.example.mlbbresearch.vision.Team.ALLY, true, it.x, it.y, it.hp, it.x, it.y, it.x, it.y)
            }
            val view = GameState(now, s.phase, me, e.hp, friends, foes, s.allyMinions, s.enemyMinions,
                0, s.aspect, null, emptyList(), emptyList(), -1, -1, Long.MAX_VALUE)
            val sp = hypot(e.vx * s.aspect, e.vy)
            val (mx, my) = if (sp > 0.05f) (e.vx * s.aspect / sp) to (e.vy / sp) else (0f to 0f)
            save(view, "~enemy", mx, my)
        }
    }

    private fun save(view: GameState, who: String, mx0: Float, my0: Float) {
        var mx = mx0; var my = my0
        val ml = hypot(mx, my)
        if (ml < 0.4f) { mx = 0f; my = 0f } else { mx /= ml; my /= ml }
        var casts = 0
        // standing next to someone to fight = attacking (their skill buttons cannot be seen)
        if (mx == 0f && my == 0f && (view.enemiesWithin(0.25f).isNotEmpty() || view.minionsWithin(0.25f).isNotEmpty())) casts = Casts.ATTACK
        val f = Features.of(view)
        f[13] = 1f; f[14] = 1f; f[15] = 1f        // their cooldowns are unknown
        val f2 = Features2.of(view, f)
        f2[17] = 0.5f; f2[18] = 0f; f2[31] = 1f    // their level, EXP and spell are unknown
        val values = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("hero", who)
            put("features", f.joinToString(",") { "%.3f".format(Locale.US, it) })
            put("features2", f2.joinToString(",") { "%.3f".format(Locale.US, it) })
            put("move_x", mx)
            put("move_y", my)
            put("casts", casts)
        }
        ExperienceRecorder.onDb({ db -> db.insert("demos", null, values) }) { id ->
            if (id != null && id > 0) { watched++; total++ }
        }
    }

    fun describe(mx: Float, my: Float, casts: Int): String {
        val dir = if (mx == 0f && my == 0f) "stand" else "move " + compass(mx, my)
        val c = Casts.names(casts)
        return if (c.isEmpty()) dir else "$dir + ${c.joinToString("+")}"
    }

    fun compass(x: Float, y: Float): String {
        val a = Math.toDegrees(kotlin.math.atan2(-y.toDouble(), x.toDouble()))
        val names = listOf("right", "up-right", "up", "up-left", "left", "down-left", "down", "down-right")
        val i = (((a + 360 + 22.5) % 360) / 45).toInt().coerceIn(0, 7)
        return names[i]
    }

    @Synchronized
    fun resetSession() {
        baseline.clear()
        previous.clear()
        pendingCasts = 0
        sumMx = 0f; sumMy = 0f; frames = 0
    }

    fun clear(done: () -> Unit) {
        ExperienceRecorder.onDb({ db -> db.delete("demos", null, null) }) {
            total = 0
            recorded = 0
            ImitationPolicy.unload()
            done()
        }
    }
}

// Plays like you: k nearest saved moments (same hero if there are enough), weighted vote.
object ImitationPolicy {

    private class Demo(val f: FloatArray, val mx: Float, val my: Float, val casts: Int, val watched: Boolean = false)

    @Volatile private var demos: List<Demo> = emptyList()
    @Volatile private var loadedHero: String? = null
    @Volatile private var loadedAt = 0L
    @Volatile private var loading = false
    @Volatile var status = "not loaded"
        private set
    @Volatile var lastMatch = 0f      // how similar the best saved moment was (1 = identical)
        private set

    val size: Int get() = demos.size

    fun unload() {
        demos = emptyList()
        loadedHero = null
        status = "not loaded"
    }

    // (Re)load in the background when the hero changes or you recorded more since
    fun ensureLoaded(hero: String?) {
        val h = hero ?: "unknown"
        val stale = loadedHero != h || System.currentTimeMillis() - loadedAt > 120_000
        if (!stale || loading) return
        loading = true
        ExperienceRecorder.onDb({ db ->
            fun read(sql: String, args: Array<String>?, watched: Boolean = false): ArrayList<Demo> {
                val out = ArrayList<Demo>()
                db.rawQuery(sql, args).use { c ->
                    while (c.moveToNext()) {
                        val f = c.getString(0).split(",").mapNotNull { it.toFloatOrNull() }
                        if (f.size == Features.SIZE) out.add(Demo(f.toFloatArray(), c.getFloat(1), c.getFloat(2), c.getInt(3), watched))
                    }
                }
                return out
            }
            val cols = "SELECT features, move_x, move_y, casts FROM demos"
            var list = read("$cols WHERE hero = ? ORDER BY id DESC LIMIT 20000", arrayOf(h))
            var scope = h
            if (list.size < Cfg.imitationMinDemos) {
                list = read("$cols WHERE hero NOT LIKE '~%' ORDER BY id DESC LIMIT 20000", null)
                scope = "all heroes"
            }
            // plus what the heroes you watched did (allies while you were dead, enemies on screen)
            val seen = read("$cols WHERE hero LIKE '~%' ORDER BY id DESC LIMIT 10000", null, watched = true)
            (list + seen) to "$scope + ${seen.size} watched"
        }) { r ->
            loading = false
            loadedAt = System.currentTimeMillis()
            loadedHero = h
            if (r != null) {
                demos = r.first
                status = "${r.first.size} moments loaded (${r.second})"
                Diag.info("Play like me: $status")
            } else status = "could not load (see Logs)"
        }
    }

    // "Play like me" mixed INTO the AI's decision instead of replacing it. Replacing it froze the
    // hero: a remembered "stand still, press nothing" moment took over the joystick and skills.
    //  - the AI keeps its goal (camp, wave, tower, target) and its skills
    //  - your way of moving is used while walking and fighting, when the moment is clearly like
    //    one you played, and only if it actually moves
    //  - skills you used in such moments are added (if ready)
    fun blend(d: TacticalDecision, s: GameState): TacticalDecision {
        val im = decide(s) ?: return d
        val sure = lastMatch >= 0.55f
        val styleOk = d.intent == Intent.ENGAGE || d.intent == Intent.AMBUSH || d.intent == Intent.GROUP ||
            d.intent == Intent.PUSH && (d.moveX != 0f || d.moveY != 0f)
        val useMove = sure && styleOk && (im.moveX != 0f || im.moveY != 0f)
        val casts = (d.casts + if (sure) im.casts.filter { it != "recall" } else emptyList()).distinct()
        if (!useMove && casts == d.casts) return d
        return TacticalDecision(d.intent, if (useMove) im.moveX else d.moveX, if (useMove) im.moveY else d.moveY, casts,
            d.targetX, d.targetY, d.targetLabel, d.targetHp, d.targetId,
            d.reason + " | like you (${(lastMatch * 100).toInt()}%)")
    }

    // null = not enough data or nothing similar: the normal rules play instead
    fun decide(s: GameState): TacticalDecision? {
        val all = demos
        if (all.size < Cfg.imitationMinDemos || s.self == null) return null
        val q = Features.of(s)
        val w = Features.weight
        val k = 9
        val bestD = FloatArray(k) { Float.MAX_VALUE }
        val best = arrayOfNulls<Demo>(k)
        for (d in all) {
            // a watched hero's moment counts a bit less than one of yours
            val pen = if (d.watched) 2.25f else 1f
            var dist = 0f
            for (i in 0 until Features.SIZE) {
                val diff = (q[i] - d.f[i]) * w[i]
                dist += diff * diff * pen
                if (dist >= bestD[k - 1]) break
            }
            if (dist < bestD[k - 1]) {
                var j = k - 1
                while (j > 0 && bestD[j - 1] > dist) { bestD[j] = bestD[j - 1]; best[j] = best[j - 1]; j-- }
                bestD[j] = dist; best[j] = d
            }
        }
        if (best[0] == null) return null
        lastMatch = 1f / (1f + bestD[0])
        if (bestD[0] > 1.5f) return null    // never seen anything like this

        var sw = 0f; var mx = 0f; var my = 0f
        val votes = FloatArray(6)
        for (i in 0 until k) {
            val d = best[i] ?: break
            val wt = 1f / (0.05f + bestD[i])
            sw += wt
            mx += d.mx * wt; my += d.my * wt
            var bit = 1
            for (b in 0 until 6) { if ((d.casts and bit) != 0) votes[b] += wt; bit = bit shl 1 }
        }
        mx /= sw; my /= sw
        val l = hypot(mx, my)
        if (l < 0.35f) { mx = 0f; my = 0f } else { mx /= l; my /= l }

        val p = HeroDb.profile(s.ownHero)
        val casts = ArrayList<String>()
        for ((b, slot) in Casts.bySlot.keys.withIndex()) {
            if (votes[b] / sw < 0.5f) continue
            if (slot != "attack" && slot != "recall" && !CooldownTracker.ready(slot, p)) continue
            casts.add(slot)
        }
        val e = s.enemiesWithin(0.9f).minByOrNull { s.distToSelf(it.x, it.y) }
        return TacticalDecision(
            Intent.IMITATE, mx, my, casts,
            e?.x ?: -1f, e?.y ?: -1f, if (e != null) "enemy #${e.id}" else "-", e?.hp ?: -1f, e?.id ?: -1,
            "like you: ${DemoRecorder.describe(mx, my, 0)} (match ${(lastMatch * 100).toInt()}%)"
        )
    }
}
