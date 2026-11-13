package com.example.mlbbresearch.learning

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.GameStateTracker
import com.example.mlbbresearch.game.Phase
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.sqrt

// What to do when an enemy hero is close. The tactical engine knows HOW to do each one;
// the brain learns WHICH one works best in which situation.
enum class FightOption(val label: String) {
    RULES("normal rules"),
    ALL_IN("all in (full combo + ultimate)"),
    POKE("poke from range"),
    FARM("ignore them, keep farming"),
    RETREAT("back off"),
    GROUP("fall back to allies")
}

// The learning AI: a contextual bandit (LinUCB, Li et al. 2010, "A contextual-bandit
// approach to personalized news article recommendation"). Simple, fast and it learns from
// a handful of matches on the phone:
//  1. When an enemy is near it describes the moment as 18 numbers (HP, enemies, allies,
//     distances, skills ready, ...).
//  2. For every option it keeps a small linear model that predicts the reward of that option
//     in such a moment, plus how UNSURE it still is. It picks the best "prediction + unsureness"
//     so it tries options it knows little about (exploring) and repeats what worked.
//  3. 4 seconds later it scores the result: damage dealt to enemies, kills, HP kept or lost,
//     dying (big minus). That reward updates the model of the option it used.
// It also learns from YOUR play ("Learn from me" on): what you chose in a fight and how it went.
// One model per hero, plus a shared one used until a hero has enough fights of its own.
object Brain {

    private const val D = Features.SIZE + 4          // features + 3 comparisons + constant
    private const val HOLD_MS = 2500L                // keep a choice this long
    private const val EVAL_MS = 4000L                // judge it after this long
    private const val OWN_MODEL_AFTER = 40           // fights before a hero uses its own model

    private class Arm(var ainv: Array<FloatArray>, val b: FloatArray, var n: Int, var rewardSum: Float)
    private class Model(val arms: Array<Arm>) { val total: Int get() = arms.sumOf { it.n } }

    private class Pending(
        val at: Long, val hero: String, val option: FightOption, val x: FloatArray, val byYou: Boolean,
        val selfHp: Float, val enemyHp: Map<Int, Float>, val minions: Int,
        val summary: String = "", val lane: String = ""
    ) {
        // lowest HP each enemy was seen at during the fight (an enemy that vanishes after
        // being brought low was most likely killed)
        val lowest = HashMap(enemyHp)
    }

    private val models = HashMap<String, Model>()
    private val rnd = java.util.Random()
    private val pending = ArrayList<Pending>()
    private var current: FightOption? = null
    private var currentUntil = 0L
    private var lastUserSample = 0L

    @Volatile var lastChoice = "-"
        private set
    @Volatile var lastScores = ""
        private set
    @Volatile var learnedThisSession = 0
        private set
    private val events = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ---------------- choosing ----------------

    // null = no enemy close, the normal rules play
    @Synchronized
    fun choose(s: GameState): FightOption? {
        if (!Cfg.smartFights || s.self == null) return null
        val near = s.enemiesWithin(0.9f)
        if (near.isEmpty()) { current = null; return null }
        val now = s.ts
        current?.let { if (now < currentUntil) return it }

        val hero = s.ownHero ?: "unknown"
        val x = context(s)
        val allowed = FightOption.values().filter { o ->
            when (o) {
                FightOption.GROUP -> s.allies.isNotEmpty()
                // only ranged heroes can poke; a melee hero has to be up close to hit
                FightOption.POKE -> com.example.mlbbresearch.game.SkillBook.canPoke(s.ownHero)
                FightOption.FARM -> s.enemyMinions.isNotEmpty() || s.monsters.isNotEmpty()
                else -> true
            }
        }
        val alpha = when (Cfg.exploreLevel) { 0 -> 0.15f; 2 -> 1.0f; else -> 0.45f }
        val m = modelFor(hero)
        val scored = allowed.map { o ->
            val arm = m.arms[o.ordinal]
            val theta = mul(arm.ainv, arm.b)
            val mean = dot(theta, x)
            val unsure = sqrt(dot(x, mul(arm.ainv, x)).coerceAtLeast(0f))
            Triple(o, mean, unsure)
        }
        // a little pure exploration on top (more while the model is young), so one unlucky
        // early fight can never rule an option out for good
        val eps = (if (Cfg.exploreLevel == 0) 0.02f else 0.05f) + 0.15f * (1f - m.total / 200f).coerceIn(0f, 1f)
        val random = rnd.nextFloat() < eps
        val best = if (random) scored[rnd.nextInt(scored.size)] else scored.maxByOrNull { it.second + alpha * it.third }!!
        lastScores = scored.sortedByDescending { it.second }.joinToString("  ") {
            "${it.first.name.lowercase()} ${"%+.2f".format(it.second)}"
        }
        lastChoice = "${best.first.label} (expects ${"%+.2f".format(best.second)}" +
            (if (random || alpha * best.third > 0.3f) ", trying it out" else "") + ")"
        current = best.first
        currentUntil = now + HOLD_MS
        queue(s, best.first, x, false)
        return best.first
    }

    // "Learn from me": what did YOU just choose in a fight? (from where you walked)
    @Synchronized
    fun observeUser(s: GameState, mx: Float, my: Float, casts: Int) {
        if (!Cfg.smartFights || !Cfg.brainLearnsFromYou || s.self == null) return
        observeChoice(s, mx, my, casts, null)
    }

    // A LIVE MATCH you watch: the same reading of what that player chose in each fight (often a
    // strong player), scored by how it went - more and better fight moments for the small model.
    // ready = THEIR skills ready (from the live panel), for the summary the model is shown.
    @Synchronized
    fun observeWatched(s: GameState, mx: Float, my: Float, casts: Int, ready: List<String>) {
        if (!Cfg.smartFights || !Cfg.liveLearn || s.self == null) return
        observeChoice(s, mx, my, casts, ready)
    }

    private fun observeChoice(s: GameState, mx: Float, my: Float, casts: Int, ready: List<String>?) {
        val now = s.ts
        if (now - lastUserSample < HOLD_MS) return
        val e = s.enemiesWithin(0.9f).minByOrNull { s.distToSelf(it.x, it.y) } ?: return
        lastUserSample = now
        val self = s.self ?: return
        val ex = (e.x - self.x) * s.aspect
        val ey = e.y - self.y
        val el = hypot(ex, ey).coerceAtLeast(1e-3f)
        val toward = (mx * ex + my * ey) / el
        val skills = (casts and (Casts.S1 or Casts.S2 or Casts.ULT)) != 0
        val allies = s.allies
        val toAllies = if (allies.isNotEmpty()) {
            val ax = (allies.map { it.x }.average().toFloat() - self.x) * s.aspect
            val ay = allies.map { it.y }.average().toFloat() - self.y
            val al = hypot(ax, ay).coerceAtLeast(1e-3f)
            (mx * ax + my * ay) / al
        } else 0f
        // CLEAR choices only: a moment that does not show one is not labelled at all (before, every
        // unclear moment near minions became FARM - two thirds of the moments, and the small model
        // learned to answer FARM to everything)
        val p = com.example.mlbbresearch.game.HeroDb.profile(s.ownHero)
        val enemyInReach = s.distToSelf(e.x, e.y) <= p.attackRange * 1.2f
        val attacking = (casts and Casts.ATTACK) != 0
        val farmInReach = s.minionsWithin(p.attackRange * 1.4f).isNotEmpty() || s.monsters.any { s.distToSelf(it.x, it.y) <= p.attackRange * 1.4f }
        val option = when {
            toward > 0.5f -> FightOption.ALL_IN
            toward < -0.5f && toAllies > 0.5f -> FightOption.GROUP
            toward < -0.5f -> FightOption.RETREAT
            skills && !enemyInReach -> FightOption.POKE                     // skills from range
            (skills || attacking) && enemyInReach -> FightOption.ALL_IN     // trading blows up close
            attacking && farmInReach && !enemyInReach -> FightOption.FARM   // hitting minions / monsters, enemy not in reach
            else -> return
        }
        queue(s, option, context(s), true, ready)
    }

    // another adviser (the small tactical model) picked this option and the checks allowed it:
    // play it, and learn from how it goes like any other choice
    @Synchronized
    fun adopt(s: GameState, o: FightOption) {
        if (current == o) return
        if (pending.lastOrNull()?.at == s.ts) pending.removeAt(pending.size - 1)
        current = o
        currentUntil = s.ts + HOLD_MS
        lastChoice = "${o.label} (small tactical model)"
        queue(s, o, context(s), false)
    }

    private fun queue(s: GameState, o: FightOption, x: FloatArray, byYou: Boolean, ready: List<String>? = null) {
        // the same summary the small tactical model is shown: the fight moments it trains on
        val summary = try { com.example.mlbbresearch.ai.TacticalAdvisor.summary(s, com.example.mlbbresearch.game.HeroDb.profile(s.ownHero), ready) } catch (e: Exception) { "" }
        pending.add(Pending(s.ts, s.ownHero ?: "unknown", o, x, byYou, s.selfHp,
            s.enemiesWithin(1.2f).associate { it.id to it.hp }, s.enemyMinions.size, summary, s.lane.name))
        if (pending.size > 50) pending.removeAt(0)
    }

    // ---------------- learning ----------------

    // called from the fast loop: score the choices made 4 s ago
    @Synchronized
    fun evaluateDue(s: GameState) {
        if (pending.isEmpty()) return
        // no learning at all in Solo practice (LearnGate): the fights there are not scored
        if (!LearnGate.on()) { pending.clear(); return }
        val now = s.ts
        val it = pending.iterator()
        var changed = false
        while (it.hasNext()) {
            val p = it.next()
            for (e in s.enemies) p.lowest[e.id]?.let { if (e.hp < it) p.lowest[e.id] = e.hp }
            val died = s.phase == Phase.DEAD || GameStateTracker.lastDeathAt in p.at..now
            if (now - p.at < EVAL_MS && !died) continue
            it.remove()
            val r = reward(p, s, died)
            ABCompare.fight(r, died, p.byYou)            // small model on vs off comparison
            TrainingResults.fightScored(r, p.byYou)               // the Learn page's tactical engine circle
            com.example.mlbbresearch.research.ResearchMode.onFightScored(r, died, p.byYou)   // research R5 (verified practice only)
            for (key in listOf(p.hero, "all")) update(modelFor(key, own = true), p.option, p.x, r)
            learnedThisSession++
            changed = true
            note("${if (p.byYou) "you" else "AI"}: ${p.option.label} -> ${"%+.2f".format(r)}" + if (died) " (died)" else "")
            if (p.summary.isNotEmpty()) ExperienceRecorder.recordFight(p.at, p.hero, p.lane, p.summary, p.option.name, p.byYou, r, died)
        }
        if (changed && learnedThisSession % 10 == 0) saveAll()
    }

    // + damage to enemies, + kills, + HP kept, - HP lost, -- dying
    private fun reward(p: Pending, s: GameState, died: Boolean): Float {
        var r = 0f
        for ((id, hp0) in p.enemyHp) {
            val t = s.enemies.firstOrNull { it.id == id }
            val low = p.lowest[id] ?: hp0
            r += when {
                t != null -> (hp0 - t.hp) * 1.5f
                low < 0.3f -> (hp0 - low) * 1.5f + 0.5f   // gone after being brought low (kill not confirmed)
                else -> 0f                                // walked out of sight
            }
        }
        // the K/D/A numbers confirm what really happened during the fight
        val w = p.at..s.ts
        if (GameStateTracker.lastKillAt in w) r += 2f
        if (GameStateTracker.lastAssistAt in w) r += 1f
        val hpNow = if (died) 0f else if (s.selfHp >= 0f) s.selfHp else p.selfHp
        r += (hpNow - p.selfHp) * 1.2f
        if (died) r -= 2.5f
        if (p.option == FightOption.FARM && s.enemyMinions.size < p.minions) r += 0.1f * (p.minions - s.enemyMinions.size)
        return r.coerceIn(-3f, 3f)
    }

    // LinUCB update with Sherman-Morrison: A^-1 -= (A^-1 x)(A^-1 x)^T / (1 + x^T A^-1 x); b += r x
    private fun update(m: Model, o: FightOption, x: FloatArray, r: Float) {
        val arm = m.arms[o.ordinal]
        val ax = mul(arm.ainv, x)
        val denom = 1f + dot(x, ax)
        for (i in 0 until D) for (j in 0 until D) arm.ainv[i][j] -= ax[i] * ax[j] / denom
        for (i in 0 until D) arm.b[i] += r * x[i]
        arm.n++
        arm.rewardSum += r
    }

    // ---------------- context ----------------

    // The 17 situation numbers plus the comparisons that decide fights, so a simple linear
    // model can learn "go in when you are ahead, back off when you are behind".
    private fun context(s: GameState): FloatArray {
        val f = Features.of(s)
        val x = FloatArray(D)
        System.arraycopy(f, 0, x, 0, f.size)
        val n = Features.SIZE
        val hasEnemy = f[6]
        x[n] = (f[0] - f[5]) * hasEnemy                                       // my HP minus the nearest enemy's
        x[n + 1] = (s.alliesWithin(0.9f).size + 1 - s.enemiesWithin(0.9f).size) / 5f   // numbers advantage
        x[n + 2] = f[0] * f[15]                                               // healthy AND ultimate ready
        x[D - 1] = 1f
        return x
    }

    // ---------------- models & storage ----------------

    private fun fresh(): Model = Model(Array(FightOption.values().size) {
        Arm(Array(D) { i -> FloatArray(D) { j -> if (i == j) 1f else 0f } }, FloatArray(D), 0, 0f)
    })

    // a hero's own model once it has enough fights, else the shared one
    private fun modelFor(hero: String, own: Boolean = false): Model {
        val m = models.getOrPut(hero) { load(hero) ?: fresh() }
        if (own || hero == "all" || m.total >= OWN_MODEL_AFTER) return m
        return models.getOrPut("all") { load("all") ?: fresh() }
    }

    private fun key(hero: String) = "brain_" + hero.lowercase().filter { it.isLetterOrDigit() }

    private fun load(hero: String): Model? = try {
        Cfg.raw(key(hero))?.let { txt ->
            val o = JSONObject(txt)
            val arr = o.getJSONArray("arms")
            if (arr.length() != FightOption.values().size) null else Model(Array(arr.length()) { k ->
                val a = arr.getJSONObject(k)
                val ai = a.getJSONArray("ainv")
                val b = a.getJSONArray("b")
                if (b.length() != D) throw IllegalStateException("old format")
                Arm(Array(D) { i -> FloatArray(D) { j -> ai.getDouble(i * D + j).toFloat() } },
                    FloatArray(D) { b.getDouble(it).toFloat() }, a.getInt("n"), a.getDouble("sum").toFloat())
            })
        }
    } catch (e: Exception) {
        null
    }

    // read again from storage (after the learned backup was restored)
    @Synchronized
    fun forgetCache() = models.clear()

    @Synchronized
    fun saveAll() {
        try {
            for ((hero, m) in models) {
                val arr = JSONArray()
                for (a in m.arms) {
                    val ai = JSONArray()
                    for (row in a.ainv) for (v in row) ai.put(Math.round(v * 1e5) / 1e5)
                    arr.put(JSONObject().put("ainv", ai).put("b", JSONArray(a.b.map { Math.round(it * 1e4) / 1e4 }))
                        .put("n", a.n).put("sum", a.rewardSum.toDouble()))
                }
                Cfg.setRaw(key(hero), JSONObject().put("arms", arr).toString())
                if (hero != "all") rememberHero(hero)
            }
        } catch (e: Exception) {
            Diag.error("Brain: could not save", e)
        }
    }

    private fun rememberHero(hero: String) {
        val list = (Cfg.raw("brain_heroes") ?: "").split("\n").filter { it.isNotBlank() }
        if (hero !in list) Cfg.setRaw("brain_heroes", (list + hero).joinToString("\n"))
    }

    @Synchronized
    fun reset(hero: String?) {
        val heroes = if (hero == null) (Cfg.raw("brain_heroes") ?: "").split("\n").filter { it.isNotBlank() } + "all" else listOf(hero)
        for (h in heroes) { models.remove(h); Cfg.setRaw(key(h), null) }
        if (hero == null) Cfg.setRaw("brain_heroes", null)
        pending.clear()
        current = null
    }

    // ---------------- for the Learn page ----------------

    class OptionStats(val option: FightOption, val tries: Int, val avgReward: Float)

    @Synchronized
    fun stats(hero: String?): List<OptionStats> {
        val m = modelFor(hero ?: "unknown", own = true)
        return m.arms.mapIndexed { i, a -> OptionStats(FightOption.values()[i], a.n, if (a.n == 0) 0f else a.rewardSum / a.n) }
    }

    @Synchronized
    fun fights(hero: String?): Int = modelFor(hero ?: "unknown", own = true).total

    @Synchronized
    fun sharedFights(): Int = modelFor("all").total

    @Synchronized
    fun recent(n: Int): List<String> = events.take(n)

    private fun note(line: String) {
        events.addFirst("${fmt.format(Date())}  $line")
        while (events.size > 40) events.removeLast()
    }

    // ---------------- small maths ----------------

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun mul(m: Array<FloatArray>, v: FloatArray): FloatArray = FloatArray(m.size) { i -> dot(m[i], v) }
}
