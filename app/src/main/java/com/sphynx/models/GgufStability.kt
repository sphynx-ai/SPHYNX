package com.sphynx.models

import android.content.Context
import com.sphynx.core.Diag
import com.sphynx.inference.GgufTuning
import org.json.JSONObject
import java.io.File

// GGUF STABILITY (Phase 8, step G9). Two records for the Debug data upload:
//  1. Load and unload one GGUF model 20 times in a row (Model page button). The app's memory is read after each unload;
//     it must come back (GgufTuning.leak). Rows in research/gguf_stability.jsonl.
//  2. During a 5v5 match with a GGUF model doing a job, one row a minute with the app's and the phone's memory, battery
//     temperature, heat status and the GGUF answer count (research/match_resources.jsonl). Nothing in Solo practice.
object GgufStability {

    const val CYCLES = 20
    @Volatile var running = false; private set
    @Volatile var line = ""; private set

    fun startCycles(ctx: Context, file: File, rolesUseGguf: Boolean, done: (String) -> Unit): String? {
        if (running || GgufBenchmark.running) return "A GGUF test is already running"
        if (rolesUseGguf) return "A job uses a GGUF model now: give both jobs a LiteRT-LM model for the test, then switch back"
        running = true
        Thread {
            val c = ctx.applicationContext
            val be = GgufLibrary.backend(c)
            val mem = ArrayList<Long>()
            var failed = ""
            for (i in 1..CYCLES) {
                line = "Load and unload: $i of $CYCLES…"
                val t0 = System.currentTimeMillis()
                val st = be.load(GgufLibrary.config(file))
                val loadMs = System.currentTimeMillis() - t0
                if (!st.ready) { failed = st.error?.message ?: "did not load"; row(c, file, i, loadMs, null, failed); break }
                be.unload()
                System.gc()
                Thread.sleep(300)
                val mb = appMb(c)
                mem.add(mb)
                row(c, file, i, loadMs, mb, "")
            }
            val msg = if (failed.isNotEmpty()) "Load and unload stopped: $failed" else GgufTuning.leakSummary(mem)
            line = msg; running = false
            Diag.info("GGUF stability (${file.name}): $msg")
            done(msg)
        }.apply { name = "gguf-stability" }.start()
        return null
    }

    private var lastMatchRow = 0L

    /** Called by the fast loop: one row a minute during a 5v5 while a job runs on a GGUF model. */
    fun matchTick(ggufJobs: List<String>, answers: Int) {
        if (ggufJobs.isEmpty() || !com.sphynx.learning.LearnGate.on()) return
        val now = System.currentTimeMillis()
        if (now - lastMatchRow < 60_000L) return
        lastMatchRow = now
        val c = com.sphynx.core.Probes.appContext() ?: return
        try {
            val snap = com.sphynx.core.DeviceStats.sample(c)
            val ph = com.sphynx.core.PhoneState.read()
            val o = JSONObject().put("t", now).put("app", com.sphynx.core.AppVersion.VERSION).put("ggufJobs", ggufJobs.joinToString())
                .put("ggufAnswers", answers)
            snap?.let { o.put("appRamMb", it.appRamMb).put("appNativeMb", it.appNativeMb).put("phoneAvailMb", it.ramAvailMb)
                .put("appCpuPct", it.appCpuPct.toDouble()) }
            ph.batteryC?.let { o.put("batteryC", it.toDouble()) }
            ph.thermal?.let { o.put("thermal", it) }
            File(File(c.filesDir, "research").apply { mkdirs() }, "match_resources.jsonl").appendText(o.toString() + "\n")
        } catch (e: Exception) { }
    }

    private fun appMb(c: Context): Long = try {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))[0].totalPss / 1024L
    } catch (e: Exception) { -1L }

    private fun row(c: Context, file: File, cycle: Int, loadMs: Long, mb: Long?, error: String) {
        try {
            val o = JSONObject().put("t", System.currentTimeMillis()).put("app", com.sphynx.core.AppVersion.VERSION)
                .put("model", file.name).put("cycle", cycle).put("loadMs", loadMs).put("error", error)
            mb?.let { o.put("appPssMbAfterUnload", it) }
            File(File(c.filesDir, "research").apply { mkdirs() }, "gguf_stability.jsonl").appendText(o.toString() + "\n")
        } catch (e: Exception) { }
    }
}
