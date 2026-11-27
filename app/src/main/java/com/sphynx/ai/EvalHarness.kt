package com.sphynx.ai

import android.content.Context
import com.sphynx.core.Diag
import com.sphynx.inference.ModelRole
import com.sphynx.universal.eval.EvalRecord
import com.sphynx.universal.eval.EvalScoring
import com.sphynx.universal.eval.EvalSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

// THE EVALUATION HARNESS (Phase 10, step E4). Runs one evaluation set (assets/eval) on the model that does the set's job
// now, LiteRT-LM or GGUF, by the protocol (docs/PHASE_10_BENCHMARK_PROTOCOL.md): one warm-up answer that is kept apart,
// then every item [repeats] times in the same order, greedy (temperature 0). Each answer is timed (first word, first
// usable text, whole answer), scored (EvalScoring, quality only) and recorded with the app's memory, the heat status and
// the battery. The conditions at the start are recorded; a run outside them is kept but marked. It only starts when the
// owner taps it, can be stopped, and never sends a touch or changes a setting.
object EvalHarness {

    const val FILE = "eval_runs.jsonl"
    const val WARMUP = "_warmup"
    val SETS = listOf("mlbb_tactical.json", "mlbb_strategic.json", "mlbb_review.json")

    @Volatile var running = false; private set
    @Volatile var status = ""; private set
    @Volatile private var stop = false
    @Volatile private var job: Job? = null

    fun file(ctx: Context): File = File(File(ctx.applicationContext.filesDir, "research").apply { mkdirs() }, FILE)

    fun loadSet(ctx: Context, asset: String): Result<EvalSet> = try {
        EvalSet.parse(ctx.assets.open("eval/$asset").bufferedReader().use { it.readText() })
    } catch (e: Exception) { Result.failure(e) }

    fun roleOf(set: EvalSet): ModelRole = if (set.role == "tactical") Roles.TACTICAL else Roles.STRATEGIC

    fun conditions(ctx: Context): EvalRecord.Conditions {
        val p = com.sphynx.core.DeviceInfo.power(ctx)
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val capture = com.sphynx.capture.ScreenCaptureService.running
        return EvalRecord.Conditions(inMatch = capture && com.sphynx.game.GameStateTracker.state.phase.inMatch, captureOn = capture,
            batteryPct = p.batteryPct, charging = p.charging, thermalStatus = thermal(pm), batteryC = p.batteryC)
    }

    private fun thermal(pm: android.os.PowerManager?): Int = try { pm?.currentThermalStatus ?: -1 } catch (e: Exception) { -1 }

    /** When a CHOICE answer is usable (the option named) or a PLAN answer (a JSON object there); null = when complete. */
    fun usable(item: EvalSet.Item): ((String) -> Boolean)? = when (item.kind) {
        EvalSet.Kind.CHOICE -> { t -> !t.contains("<think", true) && EvalScoring.firstOption(t, item.options) != null }
        EvalSet.Kind.PLAN -> { t -> EvalScoring.firstJson(t) != null }
        EvalSet.Kind.RUBRIC -> null
    }

    /** The run's description, from the model doing [role] now; null when no model is loaded for it. */
    fun runInfo(ctx: Context, set: EvalSet, role: ModelRole, repeats: Int, kind: String = EvalRecord.KIND_SET): EvalRecord.RunInfo? {
        if (!Roles.ready(role)) return null
        val f = com.sphynx.models.ModelManager.fileFor(ctx, role)
        val rec = com.sphynx.models.ModelRegistry.records.firstOrNull { it.fileName == f.name && it.sizeBytes == f.length() }
        val backend = Roles.backend(role).name
        val runtime = if (backend == "LLAMA_CPP") "llama.cpp " + com.sphynx.inference.GgufArchitectures.LLAMA_TAG
            else "litertlm " + Roles.LITERT_LM_VERSION
        val now = System.currentTimeMillis()
        return EvalRecord.RunInfo(
            runId = "${set.id}-${role.name.lowercase()}-$now", setId = set.id, setVersion = set.version, role = role.name.lowercase(),
            modelSha = rec?.sha256, fileName = f.name, format = rec?.format?.name ?: (if (backend == "LLAMA_CPP") "GGUF" else "LITERT_LM"),
            quantization = rec?.quantization, backend = backend, processor = Roles.processor(role),
            phone = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
            android = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            appVersion = com.sphynx.core.AppVersion.VERSION, runtimeVersion = runtime,
            settings = com.sphynx.models.ModelRegistry.settingsFor(f), repeats = repeats, conditions = conditions(ctx),
            startedAt = now, kind = kind)
    }

    fun stop() { stop = true; job?.cancel() }

    /** Runs [asset] [repeats] times per item in the background; [done] gets the last message. */
    fun start(ctx: Context, asset: String, repeats: Int, done: (String) -> Unit) {
        if (running) { done("An evaluation is already running"); return }
        val app = ctx.applicationContext
        val set = loadSet(app, asset).getOrElse { done("The evaluation set can't be read: ${it.message}"); return }
        val role = roleOf(set)
        val info = runInfo(app, set, role, repeats) ?: run { done("Load a model for the ${set.role} job first"); return }
        running = true; stop = false
        job = AiRuntime.scope.launch {
            val msg = try { runSet(app, set, role, info) } catch (t: Throwable) { "Evaluation stopped: ${t.message ?: t}" }
            status = msg; running = false
            Diag.info("Evaluation ${info.runId}: $msg")
            done(msg)
        }
    }

    /**
     * Phase 10 E8: the sustained run. The tactical set again and again at the in-match pace (a question at most every
     * 330 ms, as TacticalAdvisor asks in a fight) for [minutes]; it stops at the severe heat status.
     */
    fun startSustained(ctx: Context, minutes: Int, done: (String) -> Unit) {
        if (running) { done("An evaluation is already running"); return }
        val app = ctx.applicationContext
        val set = loadSet(app, SETS[0]).getOrElse { done("The fight test can't be read: ${it.message}"); return }
        val info = runInfo(app, set, Roles.TACTICAL, 0, EvalRecord.KIND_SUSTAINED) ?: run { done("Load a model for the tactical job first"); return }
        running = true; stop = false
        job = AiRuntime.scope.launch {
            val msg = try { runSustained(app, set, info, minutes) } catch (t: Throwable) { "Heat test stopped: ${t.message ?: t}" }
            status = msg; running = false
            Diag.info("Heat test ${info.runId}: $msg")
            done(msg)
        }
    }

    private suspend fun runSustained(ctx: Context, set: EvalSet, info: EvalRecord.RunInfo, minutes: Int): String {
        val t0 = System.currentTimeMillis()
        val end = t0 + minutes * 60_000L
        val samples = ArrayList<EvalRecord.Sample>()
        var i = 0
        try {
            samples.add(sample(ctx, set, set.items.first(), Roles.TACTICAL, -1, true, t0).copy(itemId = WARMUP))
            while (!stop && System.currentTimeMillis() < end) {
                val askAt = System.currentTimeMillis()
                val item = set.items[i % set.items.size]
                samples.add(sample(ctx, set, item, Roles.TACTICAL, i / set.items.size, false, t0))
                i++
                val left = (end - System.currentTimeMillis()) / 1000
                status = "Heat test: ${samples.size - 1} answers, ${left / 60}:%02d left".format(left % 60)
                if (thermal(ctx.getSystemService(android.os.PowerManager::class.java)) >= com.sphynx.universal.eval.EvalSustained.HOT) {
                    stop = true; Diag.info("Heat test stopped: the phone is too hot")
                }
                val wait = com.sphynx.universal.eval.EvalSustained.PACE_MS - (System.currentTimeMillis() - askAt)
                if (wait > 0) kotlinx.coroutines.delay(wait)
            }
        } finally {
            save(ctx, info, samples)
        }
        return com.sphynx.universal.eval.EvalSustained.analyse(samples, info.conditions.charging).text()
    }

    // one sample: ask, time, score, measure
    private suspend fun sample(ctx: Context, set: EvalSet, item: EvalSet.Item, role: ModelRole, repeat: Int, cold: Boolean, t0: Long): EvalRecord.Sample {
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val u = usable(item)
        return try {
            val r = Roles.askTimed(role, item.prompt, 0.0, set.maxTokens, u, stopAtUsable = item.kind == EvalSet.Kind.CHOICE)
            val sc = EvalScoring.score(item, r.text)
            val p = com.sphynx.core.DeviceInfo.power(ctx)
            EvalRecord.Sample(item.id, repeat, cold, System.currentTimeMillis() - t0, r.firstWordMs, r.usableMs, r.totalMs, r.text.length,
                com.sphynx.core.DeviceStats.sample(ctx)?.appRamMb ?: -1, thermal(pm), p.batteryC, p.batteryPct,
                r.text.take(400), sc.score, sc.verdict.name)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            EvalRecord.Sample(item.id, repeat, cold, System.currentTimeMillis() - t0, -1, -1, -1, 0, -1, thermal(pm), null, null,
                "", null, null, e.message ?: e.toString())
        }
    }

    private suspend fun runSet(ctx: Context, set: EvalSet, role: ModelRole, info: EvalRecord.RunInfo): String {
        val t0 = System.currentTimeMillis()
        val samples = ArrayList<EvalRecord.Sample>()
        val total = set.items.size * info.repeats
        val marked = info.conditions.problems()
        try {
            status = "Warm-up answer…"
            samples.add(sample(ctx, set, set.items.first(), role, -1, true, t0).copy(itemId = WARMUP))
            for (rep in 0 until info.repeats) for (item in set.items) {
                if (stop) break
                status = "${set.id}: ${samples.size} of $total answers" + (if (marked.isNotEmpty()) " (marked: ${marked.first()})" else "")
                samples.add(sample(ctx, set, item, role, rep, false, t0))
                if (thermal(ctx.getSystemService(android.os.PowerManager::class.java)) >= android.os.PowerManager.THERMAL_STATUS_SEVERE) {
                    stop = true; Diag.info("Evaluation stopped: the phone is too hot")
                }
            }
        } finally {
            save(ctx, info, samples)
        }
        val counted = samples.filter { it.itemId != WARMUP }
        val best = counted.count { it.verdict == EvalScoring.Verdict.BEST.name }
        val unsafe = counted.count { it.verdict == EvalScoring.Verdict.UNSAFE.name }
        return (if (stop) "Stopped after ${counted.size} of $total answers. " else "Done: ${counted.size} answers. ") +
            "Best $best, unsafe $unsafe." + (if (marked.isNotEmpty()) " Marked, not ranked: ${marked.joinToString()}." else "")
    }

    @Synchronized
    private fun save(ctx: Context, info: EvalRecord.RunInfo, samples: List<EvalRecord.Sample>) {
        val text = EvalRecord.lines(info, samples).joinToString("\n", postfix = "\n")
        try { file(ctx).appendText(text) } catch (e: Exception) { Diag.error("Evaluation not saved", e) }
        // Phase 10 E5: each run also goes into Recordings at once, so the automatic upload takes it (debug-data rule)
        try { exportText(ctx, "diagnostics-eval-${info.runId}.jsonl", text) } catch (e: Exception) { Diag.error("Evaluation export", e) }
    }

    private fun exportText(ctx: Context, name: String, text: String): String {
        val f = File(ctx.cacheDir, name)
        f.writeText(text)
        return try { com.sphynx.core.SessionRecorder.exportFile(ctx, f, "application/json") } finally { f.delete() }
    }

    /** Phase 10 E5: every run kept on the phone, as one file in Recordings (the Debug data page's button). */
    fun exportAll(ctx: Context): String {
        val f = file(ctx)
        if (!f.isFile || f.length() == 0L) return "No test results yet"
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        return "${runs(ctx).size} test runs. " + exportText(ctx, "diagnostics-eval-all-$stamp.jsonl", f.readText())
    }

    /** Every run kept on the phone, with its samples. */
    fun runs(ctx: Context): List<Pair<EvalRecord.RunInfo, List<EvalRecord.Sample>>> = try {
        file(ctx).takeIf { it.isFile }?.readLines()?.let { EvalRecord.read(it) } ?: emptyList()
    } catch (e: Exception) { emptyList() }
}
