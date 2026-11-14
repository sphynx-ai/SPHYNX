package com.example.mlbbresearch.learning

import android.content.Context
import com.example.mlbbresearch.ai.Intent
import com.example.mlbbresearch.ai.TacticalDecision
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.game.CooldownTracker
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.HeroProfile
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The trained TACTICAL NETWORK (policy.tflite from notebooks/train_tactical_policy.ipynb):
// a small neural network that learned from YOUR recorded play what to do in a situation -
// which of the 8 joystick directions (or stand still) and which buttons (attack, skills,
// spell, recall). It runs in about a millisecond on every decision, 5-10 times a second.
//
// It does not replace the rules, it is mixed into them:
//  - the safety rules always win: retreating, recall / Regen, the enemy tower circle,
//    finishing a monster, no duels before level 4
//  - in fights and while lining up on a minion wave its joystick choice is used when it is
//    confident (55%+); walking routes (jungle path, lane, objectives) stay with the planner
//  - buttons it is sure about (60%+, attack 50%+) are added when the skill is ready; the
//    skill rules (ultimate only for heroes, range, ...) still filter them afterwards
// Gemma stays the strategist (plan + aggression every few seconds); this is the fast part.
// SEVERAL NETWORKS (v36.9): the notebook makes one network per hero you recorded enough of
// (policy_<Hero>.tflite) plus one for all heroes (policy_all.tflite, it also gets the hero's
// TYPE as input - HeroType), all in mlbb_policies.zip. They live in files/policies/<key>.tflite
// and the one for the hero you play is loaded when your hero changes: your hero's own network,
// else an older single policy.tflite made for that hero, else the all-heroes one.
object HeroType {
    const val SIZE = 11
    // role (6), ranged, starts fights, protects, late-game, early-game - from HeroDb and the wiki
    fun of(hero: String?): FloatArray {
        val f = FloatArray(SIZE)
        val h = hero?.takeIf { it.isNotBlank() && !it.startsWith("~") } ?: return f
        val role = com.example.mlbbresearch.game.HeroDb.role(h)
        val i = listOf(com.example.mlbbresearch.game.Role.TANK, com.example.mlbbresearch.game.Role.FIGHTER, com.example.mlbbresearch.game.Role.ASSASSIN,
            com.example.mlbbresearch.game.Role.MAGE, com.example.mlbbresearch.game.Role.MARKSMAN, com.example.mlbbresearch.game.Role.SUPPORT).indexOf(role)
        if (i >= 0) f[i] = 1f
        if (com.example.mlbbresearch.game.HeroDb.isRanged(h)) f[6] = 1f
        if (com.example.mlbbresearch.game.WikiLanes.engager(h)) f[7] = 1f
        if (com.example.mlbbresearch.game.WikiLanes.protector(h)) f[8] = 1f
        if (com.example.mlbbresearch.game.WikiLanes.lateGame(h)) f[9] = 1f
        if (com.example.mlbbresearch.game.WikiLanes.earlyGame(h)) f[10] = 1f
        return f
    }
}

object PolicyNet {

    @Volatile private var interpreter: Interpreter? = null
    @Volatile var inputs = 0
        private set
    @Volatile var state = "no tactical network loaded"
        private set
    @Volatile var last = "-"
        private set
    @Volatile var agreeRate = -1f       // how often its joystick choice matches the rules' (running)
        private set
    private var inBuf: ByteBuffer? = null
    private var outBuf: ByteBuffer? = null
    @Volatile private var withType = false     // the all-heroes network: hero type appended to the inputs
    @Volatile var loadedKey = ""                // which network is loaded ("all", a hero, "_single")
        private set
    private var appCtx: Context? = null

    val ready: Boolean get() = interpreter != null

    // ---- what is reliable (policy_info*.json from the notebook) ----
    // joystick: 40%+ with every move counted equally, on 300+ checked moments with 15+ of each move;
    // a button: 20+ presses checked and 40%+ of its presses right. Without the file: all used.
    @Volatile var moveTrusted = true
        private set
    @Volatile var trustedButtons: Set<String> = setOf("attack", "s1", "s2", "ult", "spell", "recall")
        private set
    @Volatile var qualityText = "no policy_info.json: everything it suggests is used"
        private set
    @Volatile var trainedHero = ""
        private set

    fun applyInfo(json: String?) {
        if (json == null) {
            moveTrusted = true; trustedButtons = setOf("attack", "s1", "s2", "ult", "spell", "recall"); trainedHero = ""
            qualityText = "no policy_info.json: everything it suggests is used"
            return
        }
        try {
            val o = org.json.JSONObject(json)
            trainedHero = o.optString("hero", "").trim()
            val bal = o.optDouble("move_balanced", 0.0); val checked = o.optInt("move_checked", 0); val fewest = o.optInt("move_fewest", 0)
            moveTrusted = bal >= 0.40 && checked >= 300 && fewest >= 15
            val b = o.optJSONObject("buttons")
            val ok = HashSet<String>()
            if (b != null) for (k in b.keys()) {
                val q = b.getJSONObject(k)
                if (q.optInt("checked", 0) >= 20 && q.optDouble("right", 0.0) >= 0.4) ok.add(k)
            }
            trustedButtons = ok
            qualityText = (if (trainedHero.isNotEmpty()) "for $trainedHero; " else "for all heroes; ") + "joystick " +
                (if (moveTrusted) "used" else "not yet (${(bal * 100).toInt()}% on $checked moments)") +
                "; buttons used: " + (if (ok.isEmpty()) "none yet" else ok.joinToString())
        } catch (t: Throwable) {
            Diag.error("policy_info.json", t)
            qualityText = "policy_info.json could not be read"
        }
    }

    // ---- the networks on the phone ----
    fun key(hero: String?): String = (hero ?: "").lowercase().filter { it.isLetterOrDigit() }
    fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "policies").apply { mkdirs() }
    fun fileFor(ctx: Context, key: String) = File(dir(ctx), "$key.tflite")
    // the older single network (policy.tflite + policy_info.json)
    fun modelFile(ctx: Context) = File(ctx.applicationContext.filesDir, "policy.tflite")
    fun infoFor(key: String): String? = if (key == "_single") Cfg.raw("policy_info") else Cfg.raw("policy_info_$key")
    fun saveInfo(key: String, json: String) = if (key == "_single") Cfg.setRaw("policy_info", json) else Cfg.setRaw("policy_info_$key", json)

    // every network on the phone, by name ("all heroes", "Chou", ...)
    fun available(ctx: Context): List<String> {
        val out = dir(ctx).listFiles()?.filter { it.name.endsWith(".tflite") }?.map { it.name.removeSuffix(".tflite") }?.sorted()?.map { k ->
            if (k == "all") "all heroes" else (infoFor(k)?.let { runCatching { org.json.JSONObject(it).optString("hero") }.getOrNull() }?.takeIf { it.isNotBlank() } ?: k)
        }?.toMutableList() ?: mutableListOf()
        if (modelFile(ctx).exists()) out.add("older single network" + (singleHero().let { if (it.isNotEmpty()) " ($it)" else "" }))
        return out
    }
    private fun singleHero() = Cfg.raw("policy_info")?.let { runCatching { org.json.JSONObject(it).optString("hero") }.getOrNull() } ?: ""

    // which network to use for this hero ("" = none)
    fun pick(ctx: Context, hero: String?): String {
        val k = key(hero)
        if (k.isNotEmpty() && fileFor(ctx, k).exists()) return k
        if (modelFile(ctx).exists() && key(singleHero()) == k && k.isNotEmpty()) return "_single"
        if (fileFor(ctx, "all").exists()) return "all"
        if (modelFile(ctx).exists() && singleHero().isEmpty()) return "_single"
        return ""
    }

    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    // load the network for this hero if it is not the loaded one; false = none for this hero
    @Synchronized
    fun ensure(hero: String?): Boolean {
        val c = appCtx ?: return ready
        val k = pick(c, hero)
        if (k.isEmpty()) { if (ready) close(); return false }
        if (k == loadedKey && ready) return true
        val f = if (k == "_single") modelFile(c) else fileFor(c, k)
        val err = loadFile(f, k)
        if (err != null) return false
        applyInfo(infoFor(k))
        return true
    }

    // older entry point: load whatever fits the hero last seen (or the single file)
    @Synchronized
    fun load(ctx: Context): String? {
        init(ctx)
        loadedKey = ""
        val k = pick(ctx, com.example.mlbbresearch.game.GameStateTracker.ownHero())
        if (k.isEmpty()) return "No tactical network yet (put mlbb_policies.zip in the Sphynx-Storage folder)"
        val err = loadFile(if (k == "_single") modelFile(ctx) else fileFor(ctx, k), k)
        if (err == null) applyInfo(infoFor(k))
        return err
    }

    @Synchronized
    private fun loadFile(f: File, k: String): String? {
        if (!f.exists()) return "missing ${f.name}"
        return try {
            close()
            val it = Interpreter(f, Interpreter.Options().setNumThreads(1))
            val n = it.getInputTensor(0).shape().last()
            val out = it.getOutputTensor(0).shape().last()
            val plain = setOf(Features.SIZE, Features2.SIZE_V1, Features2.SIZE)
            val base = if (n !in plain && (n - HeroType.SIZE) in plain) n - HeroType.SIZE else n
            if (base != Features.SIZE && base != Features2.SIZE_V1 && base != Features2.SIZE)
                throw IllegalStateException("it expects $n inputs (17, 32, 40, or those + ${HeroType.SIZE} hero-type numbers)")
            if (out != 15) throw IllegalStateException("it gives $out outputs, not 15")
            // the all-heroes network has the hero-type numbers on top of the situation
            withType = n == base + HeroType.SIZE
            inputs = base
            inBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            outBuf = ByteBuffer.allocateDirect(15 * 4).order(ByteOrder.nativeOrder())
            interpreter = it
            loadedKey = k
            state = "ready: " + (if (k == "all") "all heroes" else if (k == "_single") "single network" else k) + " ($n inputs)"
            Diag.info("Tactical network loaded: $k, $n inputs")
            null
        } catch (t: Throwable) {
            state = "load failed (see Logs)"
            Diag.error("Tactical network load failed", t)
            t.message ?: t.toString()
        }
    }

    @Synchronized
    fun close() {
        try { interpreter?.close() } catch (t: Throwable) { }
        interpreter = null
        loadedKey = ""
        state = "no tactical network loaded"
    }

    // true when a network for this hero is (now) loaded
    fun usableFor(hero: String?): Boolean = ensure(hero)

    // move: 0..7 = the 8 directions (0 right, 2 down, 4 left, 6 up), 8 = stand still
    class Out(val move: Int, val moveP: Float, val casts: FloatArray)

    @Volatile var lastMs = 0f      // one run of the network (Speed page)

    @Synchronized
    fun predict(s: GameState): Out? {
        val it = interpreter ?: return null
        val ib = inBuf ?: return null
        val ob = outBuf ?: return null
        val base = Features.of(s)
        // the way the AI is already steering = "the way you were moving" in your recordings
        val full = if (inputs > Features.SIZE) Features2.of(s, base, com.example.mlbbresearch.controller.ActionExecutor.steering()) else base
        val sit = if (inputs == full.size) full else full.copyOf(inputs)
        val f = if (withType) sit + HeroType.of(s.ownHero) else sit
        ib.rewind(); for (v in f) ib.putFloat(v); ib.rewind()
        ob.rewind()
        it.run(ib, ob)
        ob.rewind()
        val o = FloatArray(15) { ob.getFloat(it * 4) }
        var best = 0
        for (k in 1 until 9) if (o[k] > o[best]) best = k
        return Out(best, o[best], o.copyOfRange(9, 15))
    }

    private val SLOTS = listOf("attack", "s1", "s2", "ult", "spell", "recall")
    private fun dirName(m: Int) = if (m == 8) "stand" else listOf("right", "down-right", "down", "down-left", "left", "up-left", "up", "up-right")[m]

    fun blend(d: TacticalDecision, s: GameState, p: HeroProfile): TacticalDecision {
        if (!Cfg.policyOn || s.self == null) return d
        if (!ensure(s.ownHero)) { last = "no network for ${s.ownHero ?: "this hero"}: rules only"; return d }
        val t0 = System.nanoTime()
        val o = try { predict(s) } catch (t: Throwable) { Diag.error("Tactical network run", t); null } ?: return d
        lastMs = (System.nanoTime() - t0) / 1e6f
        last = "${dirName(o.move)} ${(o.moveP * 100).toInt()}%" +
            SLOTS.indices.filter { o.casts[it] >= 0.5f }.joinToString("") { " + " + SLOTS[it] }
        // how often it agrees with the rules' joystick (one of the 8, or both standing)
        val ruleMove = if (d.moveX == 0f && d.moveY == 0f) 8 else
            (Math.round(kotlin.math.atan2(d.moveY, d.moveX) / (Math.PI / 4)).toInt() + 8) % 8
        val same = if (ruleMove == o.move) 1f else 0f
        agreeRate = if (agreeRate < 0f) same else agreeRate * 0.98f + same * 0.02f

        // safety first: these decisions are never changed
        val safety = d.intent == Intent.RETREAT || d.intent == Intent.RECALL || d.intent == Intent.REGEN ||
            listOf("tower range", "circle", "finishing the monster", "no duels", "chasing a kill").any { d.reason.contains(it) }
        if (safety) return d

        var mx = d.moveX; var my = d.moveY
        val moveOk = moveTrusted && o.moveP >= Cfg.policyMinConf && when (d.intent) {
            Intent.ENGAGE, Intent.AMBUSH, Intent.GROUP, Intent.IMITATE, Intent.LANE_CLEAR -> true
            else -> false
        }
        if (moveOk) {
            if (o.move == 8) { mx = 0f; my = 0f } else {
                val a = o.move * Math.PI / 4
                mx = Math.cos(a).toFloat(); my = Math.sin(a).toFloat()
            }
        }
        val casts = ArrayList(d.casts)
        for ((k, slot) in SLOTS.withIndex()) {
            val need = when (slot) { "attack" -> 0.5f; "recall" -> 0.85f; else -> 0.6f }
            if (o.casts[k] < need || slot in casts || slot !in trustedButtons) continue
            if (slot == "recall" && s.enemiesWithin(0.9f).isNotEmpty()) continue
            if (slot != "attack" && slot != "recall" && !CooldownTracker.ready(slot, p)) continue
            casts.add(slot)
        }
        if (!moveOk && casts == d.casts) return d
        return TacticalDecision(d.intent, mx, my, casts, d.targetX, d.targetY, d.targetLabel, d.targetHp, d.targetId,
            d.reason + " | net: $last")
    }
}
