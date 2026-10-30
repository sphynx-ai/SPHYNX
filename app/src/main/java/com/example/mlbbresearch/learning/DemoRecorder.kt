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
    @Synchronized
    fun observe(v: VisionResult) {
        sumMx += v.hud.motionX
        sumMy += v.hud.motionY
        frames++
        for ((k, b) in v.skillBrightness) {
            val base = baseline[k] ?: b
            baseline[k] = if (b > base) b else base * 0.995f + b * 0.005f
            val prev = previous[k] ?: b
            previous[k] = b
            if (base > 40f && b < base * 0.72f && prev >= base * 0.82f) {
                val bit = Casts.bySlot[k] ?: continue
                pendingCasts = pendingCasts or bit
                if (k != "recall") CooldownTracker.markCast(k)
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
        val values = ContentValues().apply {
            put("ts", now)
            put("hero", s.ownHero ?: "unknown")
            put("features", f.joinToString(",") { "%.3f".format(Locale.US, it) })
            put("move_x", mx)
            put("move_y", my)
            put("casts", casts)
        }
        ExperienceRecorder.onDb({ db -> db.insert("demos", null, values) }) { id ->
            if (id != null && id > 0) { recorded++; total++ }
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

    private class Demo(val f: FloatArray, val mx: Float, val my: Float, val casts: Int)

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
            fun read(sql: String, args: Array<String>?): ArrayList<Demo> {
                val out = ArrayList<Demo>()
                db.rawQuery(sql, args).use { c ->
                    while (c.moveToNext()) {
                        val f = c.getString(0).split(",").mapNotNull { it.toFloatOrNull() }
                        if (f.size == Features.SIZE) out.add(Demo(f.toFloatArray(), c.getFloat(1), c.getFloat(2), c.getInt(3)))
                    }
                }
                return out
            }
            val cols = "SELECT features, move_x, move_y, casts FROM demos"
            var list = read("$cols WHERE hero = ? ORDER BY id DESC LIMIT 20000", arrayOf(h))
            var scope = h
            if (list.size < Cfg.imitationMinDemos) {
                list = read("$cols ORDER BY id DESC LIMIT 20000", null)
                scope = "all heroes"
            }
            list to scope
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
            var dist = 0f
            for (i in 0 until Features.SIZE) {
                val diff = (q[i] - d.f[i]) * w[i]
                dist += diff * diff
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
