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

    val ready: Boolean get() = interpreter != null

    fun modelFile(ctx: Context) = File(ctx.applicationContext.filesDir, "policy.tflite")

    @Synchronized
    fun load(ctx: Context): String? {
        val f = modelFile(ctx)
        if (!f.exists()) return "Choose the tactical network file (.tflite) first"
        return try {
            close()
            val it = Interpreter(f, Interpreter.Options().setNumThreads(1))
            val n = it.getInputTensor(0).shape().last()
            val out = it.getOutputTensor(0).shape().last()
            if (n != Features.SIZE && n != Features2.SIZE_V1 && n != Features2.SIZE) throw IllegalStateException("it expects $n inputs (17, 32 or 40)")
            if (out != 15) throw IllegalStateException("it gives $out outputs, not 15")
            inputs = n
            inBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            outBuf = ByteBuffer.allocateDirect(15 * 4).order(ByteOrder.nativeOrder())
            interpreter = it
            state = "ready ($n inputs)"
            Diag.info("Tactical network loaded: $n inputs")
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
        state = "no tactical network loaded"
    }

    // move: 0..7 = the 8 directions (0 right, 2 down, 4 left, 6 up), 8 = stand still
    class Out(val move: Int, val moveP: Float, val casts: FloatArray)

    @Synchronized
    fun predict(s: GameState): Out? {
        val it = interpreter ?: return null
        val ib = inBuf ?: return null
        val ob = outBuf ?: return null
        val base = Features.of(s)
        // the way the AI is already steering = "the way you were moving" in your recordings
        val full = if (inputs > Features.SIZE) Features2.of(s, base, com.example.mlbbresearch.controller.ActionExecutor.steering()) else base
        val f = if (inputs == full.size) full else full.copyOf(inputs)
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
        if (!Cfg.policyOn || !ready || s.self == null) return d
        val o = try { predict(s) } catch (t: Throwable) { Diag.error("Tactical network run", t); null } ?: return d
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
        val moveOk = o.moveP >= Cfg.policyMinConf && when (d.intent) {
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
            if (o.casts[k] < need || slot in casts) continue
            if (slot == "recall" && s.enemiesWithin(0.9f).isNotEmpty()) continue
            if (slot != "attack" && slot != "recall" && !CooldownTracker.ready(slot, p)) continue
            casts.add(slot)
        }
        if (!moveOk && casts == d.casts) return d
        return TacticalDecision(d.intent, mx, my, casts, d.targetX, d.targetY, d.targetLabel, d.targetHp, d.targetId,
            d.reason + " | net: $last")
    }
}
