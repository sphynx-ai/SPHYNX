package com.example.mlbbresearch.learning

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.models.AutoFolder
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// ONE-TAP TRAINING IN THE CLOUD (Kaggle Notebooks: free GPU, about 30 h a week).
// Google Colab cannot be started by another app; Kaggle can, through its API. A Train button:
//  1. gathers the inputs from Download/MLBB-Research (fresh CSV exports are made first)
//  2. uploads them to private Kaggle datasets (picture zips once each; the rest as a new version)
//  3. starts the training notebook from the app's GitHub repository on Kaggle (GPU where needed)
//  4. checks it every minute (also after the app restarts)
//  5. downloads the results straight into Download/MLBB-Research, and the folder scan loads them
// The notebooks run unattended there (their Kaggle mode) and keep what they learned: the last
// detector / networks go back up with the next run.
// Needs kaggle.json (kaggle.com > Settings > API > Create New Token) in the MLBB-Research folder;
// for the small model, accept the Gemma licence on kaggle.com once.
object CloudTrain {

    enum class Job(val key: String, val label: String, val notebook: String, val gpu: Boolean,
                   val results: List<String>, val needs: String) {
        MINIMAP("minimap", "Minimap detector", "train_mlbb_detector.ipynb", true,
            listOf("mlbb_detector.tflite", "labels.txt", "minimap_detector_best.pt"), "minimap picture zips (Learn page collector)"),
        SCREEN("screen", "Screen detector", "train_screen_detector.ipynb", true,
            listOf("mlbb_screen_detector.tflite", "screen_labels.txt", "screen_detector_best.pt"), "screen picture zips (Learn page collector)"),
        POLICY("policy", "Tactical networks", "train_tactical_policy.ipynb", false,
            listOf("mlbb_policies.zip"), "your recorded play (exported automatically)"),
        LLM("llm", "Small tactical model", "train_tactical_llm.ipynb", true,
            listOf("gemma3-270m-mlbb-tactical.litertlm", "tactical_llm_info.json"), "fight moments (exported automatically, 150+ usable)")
    }

    @Volatile internal var API = "https://api.kaggle.com/v1"        // (overridable for tests)
    private val REPO_REFS = listOf("main", "ccr-1c7a9f9f-sdupui")    // where the notebooks are read from (GitHub)
    @Volatile internal var REPO = "https://raw.githubusercontent.com/georgenaval1-blip/Ai-App"
    private const val MIN_PLAY = 300      // recorded moments before the tactical networks are worth training
    private const val MIN_FIGHTS = 150    // fight moments before the small model is worth training
    // one worker per training for preparing / uploading / downloading (all four at once) ...
    private val io = Executors.newFixedThreadPool(4) { r -> Thread(r, "cloud-train").apply { priority = Thread.MIN_PRIORITY } }
    // ... and one timer that checks every run on Kaggle once a minute (waiting holds no worker)
    private val checker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cloud-check").apply { isDaemon = true } }
    private val following = HashMap<Job, java.util.concurrent.ScheduledFuture<*>>()
    private val busy = HashSet<Job>()

    fun ready() = Cfg.kaggleUser.isNotBlank() && Cfg.kaggleKey.isNotBlank()
    fun status(j: Job): String = Cfg.raw("ct_status_${j.key}") ?: "not run yet"
    private fun setStatus(j: Job, s: String) { Cfg.setRaw("ct_status_${j.key}", s); Diag.info("Cloud training (${j.label}): $s") }
    fun running(j: Job) = synchronized(busy) { j in busy } || Cfg.raw("ct_run_${j.key}") != null
    fun kernelUrl(j: Job) = "https://www.kaggle.com/code/${Cfg.kaggleUser}/${slug(j)}"
    private fun slug(j: Job) = "mlbb-train-${j.key}"

    // ---------------- inputs ----------------

    private class Input(val name: String, val size: Long, val open: () -> InputStream?)

    private fun inputs(ctx: Context): List<Input> {
        val byName = LinkedHashMap<String, Input>()
        try { for (e in AutoFolder.files(ctx)) byName[e.name] = Input(e.name, e.size, e.open) } catch (e: Exception) { }
        // the app's own files in Download/MLBB-Research (exports, zips, earlier results)
        val base = Environment.DIRECTORY_DOWNLOADS + "/MLBB-Research/"
        try {
            ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.RELATIVE_PATH),
                MediaStore.Downloads.RELATIVE_PATH + " = ?", arrayOf(base), null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                    if (name !in byName) byName[name] = Input(name, c.getLong(2)) { ctx.contentResolver.openInputStream(uri) }
                }
            }
        } catch (e: Exception) { Diag.error("Cloud training: listing the folder", e) }
        return byName.values.toList()
    }
    private fun newest(all: List<Input>, prefix: String, ext: String) = all.filter { it.name.startsWith(prefix) && it.name.endsWith(ext) }.maxByOrNull { it.name }

    private fun isPlayCsv(n: String) = (n.startsWith("mlbb-my-play-") || n.startsWith("mlbb-live-")) && n.endsWith(".csv")
    private fun isFightCsv(n: String) = n.startsWith("mlbb-fights-") && n.endsWith(".csv")

    // rows in a CSV export (a quoted field may span lines: fight summaries do); cached by name + size
    private val rowCache = HashMap<String, Int>()
    private fun csvRows(f: Input): Int {
        val key = "${f.name}|${f.size}"
        synchronized(rowCache) { rowCache[key]?.let { return it } }
        var rows = 0; var quoted = false; var any = false
        try {
            f.open()?.use { st ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = st.read(buf); if (n < 0) break
                    for (k in 0 until n) {
                        val b = buf[k].toInt()
                        if (b == '"'.code) quoted = !quoted
                        else if (b == '\n'.code && !quoted) { rows++; any = false } else any = true
                    }
                }
            }
        } catch (e: Exception) { return 0 }
        if (any) rows++
        val r = (rows - 1).coerceAtLeast(0)                         // minus the header line
        synchronized(rowCache) { rowCache[key] = r }
        return r
    }
    // moments already in CSV files (exports from before a reinstall): play = newest my-play + newest
    // live file (each export holds everything recorded until then); fights = newest fights file
    @Volatile private var csvPlay = 0
    @Volatile private var csvFights = 0
    private fun countCsvs(all: List<Input>) {
        csvPlay = listOfNotNull(newest(all, "mlbb-my-play-", ".csv"), newest(all, "mlbb-live-", ".csv")).sumOf { csvRows(it) }
        csvFights = all.filter { isFightCsv(it.name) }.maxOfOrNull { csvRows(it) } ?: 0
    }

    // what a job would upload: (picture zips, one each in its own dataset) + (the job's other files)
    private fun plan(all: List<Input>, j: Job): Pair<List<Input>, List<Input>> = when (j) {
        Job.MINIMAP -> all.filter { it.name.startsWith("mlbb-minimap-dataset-") && it.name.endsWith(".zip") } to
            all.filter { it.name == "minimap_detector_best.pt" || it.name == "roboflow_key.txt" }
        Job.SCREEN -> all.filter { it.name.startsWith("mlbb-screen-dataset-") && it.name.endsWith(".zip") } to
            all.filter { it.name == "screen_detector_best.pt" }
        // (the fight CSVs too: the fight-choice network is trained in the same notebook)
        Job.POLICY -> emptyList<Input>() to (all.filter { isPlayCsv(it.name) || isFightCsv(it.name) } + listOfNotNull(
            all.firstOrNull { it.name == "mlbb_policies.zip" }))
        Job.LLM -> emptyList<Input>() to all.filter { isFightCsv(it.name) }
    }

    // ---------------- is there enough to train on? (the Train button turns green) ----------------
    const val MIN_PICS = 100               // new pictures (or one earlier zip) before a detector is worth training
    class Readiness(val ok: Boolean, val text: String)

    private var zipsSeen = HashMap<Job, Int>()
    private var zipsAt = 0L
    // the picture zips already in the folder (the folder listing is slow: every 15 s)
    private fun zipCount(ctx: Context, j: Job): Int {
        if (System.currentTimeMillis() - zipsAt > 15_000) {
            zipsAt = System.currentTimeMillis()
            val all = try { inputs(ctx) } catch (e: Exception) { emptyList() }
            for (k in listOf(Job.MINIMAP, Job.SCREEN)) zipsSeen[k] = plan(all, k).first.size
            countCsvs(all)
        }
        return zipsSeen[j] ?: 0
    }
    private fun kind(j: Job) = if (j == Job.MINIMAP) DatasetZipper.Kind.MINIMAP else DatasetZipper.Kind.SCREEN

    fun readiness(ctx: Context, j: Job): Readiness = when (j) {
        Job.MINIMAP, Job.SCREEN -> {
            val zips = zipCount(ctx, j); val pics = DatasetZipper.count(kind(j))
            Readiness(zips > 0 || pics >= MIN_PICS,
                "$pics new pictures" + (if (zips > 0) " + $zips zip(s)" else " (needs $MIN_PICS+)"))
        }
        Job.POLICY -> {
            zipCount(ctx, j)
            val db = (DemoRecorder.total + LiveRecorder.total).toInt(); val n = maxOf(db, csvPlay)
            Readiness(n >= MIN_PLAY, "$n recorded moments (needs $MIN_PLAY+)" +
                (if (csvPlay > 0) " · $csvPlay in CSV files" + (if (db > 0) ", $db new" else "") else ""))
        }
        Job.LLM -> {
            zipCount(ctx, j)
            val db = ExperienceRecorder.fights.coerceAtLeast(0).toInt(); val n = maxOf(db, csvFights)
            Readiness(n >= MIN_FIGHTS, "$n fight moments (needs $MIN_FIGHTS+)" +
                (if (csvFights > 0) " · $csvFights in CSV files" + (if (db > 0) ", $db new" else "") else ""))
        }
    }

    // the counts behind readiness (the databases are counted in the background)
    fun refreshCounts() { DemoRecorder.refreshTotal(); LiveRecorder.refreshTotal(); ExperienceRecorder.refreshFights() }

    // a line for the Learn page: what is there to train on
    fun inputsText(ctx: Context, j: Job): String {
        val (zips, rest) = plan(inputs(ctx), j)
        val mb = (zips + rest).sumOf { it.size } / 1e6
        return when (j) {
            Job.MINIMAP, Job.SCREEN -> if (zips.isEmpty()) "No picture zips yet (${j.needs})" else
                "${zips.size} picture zip(s), %.0f MB".format(mb) + (if (rest.any { it.name.endsWith(".pt") }) " + last detector" else " (first training)") +
                    (if (j == Job.MINIMAP) (if (rest.any { it.name == "roboflow_key.txt" }) " + Roboflow base set" else " (no roboflow_key.txt: your pictures only)") else "")
            Job.POLICY -> "Uses every play CSV in the folder, plus new moments (exported when you tap)" +
                (if (rest.any { it.name == "mlbb_policies.zip" }) "; continues from your last networks" else "")
            Job.LLM -> "Uses every fight CSV in the folder, plus new fight moments (exported when you tap)"
        }
    }

    // ---------------- the button ----------------

    fun start(ctx: Context, j: Job, done: (String) -> Unit) {
        if (!ready()) { done("Put kaggle.json in the MLBB-Research folder first (kaggle.com > Settings > API > Create New Token)"); return }
        synchronized(busy) { if (j in busy || Cfg.raw("ct_run_${j.key}") != null) { done("${j.label}: already training"); return }; busy.add(j) }
        val c = ctx.applicationContext
        done("${j.label}: preparing…")
        io.execute {
            try {
                // fresh exports first (they hold everything recorded so far)
                when (j) {
                    Job.POLICY -> {
                        // new moments since the install (the database) are exported; earlier CSVs are used as they are
                        countCsvs(inputs(c))
                        var n = 0
                        if (DemoRecorder.total > 0) { setStatus(j, "exporting your recorded play…"); n += exportAndWait { DatasetManager.exportDemos(c, it) } }
                        if (LiveRecorder.total > 0) n += exportAndWait { DatasetManager.exportLive(c, it) }
                        // the fight moments too (the fight-choice network is trained in the same run)
                        if (ExperienceRecorder.fights > 0) exportAndWait { DatasetManager.exportFights(c, it) }
                        n = maxOf(n, csvPlay)
                        if (n < MIN_PLAY) throw IllegalStateException("only $n recorded moments (needs $MIN_PLAY+): play a few matches with " +
                            "Learn on (your own play, Coach mode, or live matches you watch), then train")
                    }
                    Job.LLM -> {
                        countCsvs(inputs(c))
                        var n = 0
                        if (ExperienceRecorder.fights > 0) { setStatus(j, "exporting fight moments…"); n = exportAndWait { DatasetManager.exportFights(c, it) } }
                        n = maxOf(n, csvFights)
                        if (n < MIN_FIGHTS) throw IllegalStateException("only $n fight moments (needs $MIN_FIGHTS+): play matches with the AI " +
                            "and Smart fights on - every fight choice and how it went is saved - then train")
                    }
                    else -> {}
                }
                if ((j == Job.MINIMAP || j == Job.SCREEN) && DatasetZipper.count(kind(j)) > 0) {
                    setStatus(j, "zipping the new pictures…")
                    zipAndWait(c, kind(j))
                    zipsAt = 0L
                }
                val (zips, rest) = plan(inputs(c), j)
                when {
                    (j == Job.MINIMAP || j == Job.SCREEN) && zips.isEmpty() -> throw IllegalStateException("no ${j.label.lowercase().removeSuffix(" detector")} " +
                        "picture zips yet: turn on that picture collector (Learn page) and play; it zips by itself when full, or tap its Zip now")
                    j == Job.POLICY && rest.none { it.name.startsWith("mlbb-my-play-") } -> throw IllegalStateException("no recorded play yet")
                    j == Job.LLM && rest.isEmpty() -> throw IllegalStateException("no fight moments yet")
                }
                val sources = ArrayList<String>()
                // picture zips: one private dataset each, uploaded only once
                for ((i, z) in zips.withIndex()) {
                    // (after a failed run the zips go up again under a new name: a broken upload is never reused)
                    val base = dsSlug(z.name)
                    val gen = Cfg.raw("ct_dsgen_$base")?.toIntOrNull() ?: 0
                    val ds = if (gen == 0) base else "${base.take(46)}-r$gen"
                    if (Cfg.raw("ct_ds_$ds") == null) {
                        setStatus(j, "uploading pictures ${i + 1}/${zips.size} (${z.size / 1048576} MB)…")
                        createDataset(ds, "MLBB ${z.name.substringBeforeLast('.')}".take(50), listOf(z))
                        Cfg.setRaw("ct_ds_$ds", "1")
                    }
                    sources.add("${Cfg.kaggleUser}/$ds")
                }
                Cfg.setRaw("ct_zips_${j.key}", zips.joinToString("|") { dsSlug(it.name) })
                // the job's own files (exports, the last detector / networks, keys): a NEW dataset each run.
                // (A new version of one dataset was the problem: Kaggle said "ready" for the old version
                // and the run started without the new files.)
                if (rest.isNotEmpty()) {
                    val ds = "mlbb-${j.key}-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    setStatus(j, "uploading ${rest.joinToString { it.name }}…")
                    createDataset(ds, "MLBB ${j.label} files", rest)
                    sources.add("${Cfg.kaggleUser}/$ds")
                }
                for (s in sources) waitReady(j, s)
                setStatus(j, "starting the notebook on Kaggle…")
                val nb = notebook(c, j, sources)
                // a model the notebook needs is ATTACHED to the run (an unattended run may not fetch one itself)
                val models = if (j == Job.LLM) listOf(gemmaSource()) else emptyList()
                // Kaggle runs only a few GPU notebooks at once: if all are busy, wait and try again
                // (the uploads are done; up to 2 h)
                val waitUntil = System.currentTimeMillis() + 2 * 3600_000L
                var r: JSONObject
                while (true) {
                    r = try {
                        call("kernels.KernelsApiService", "SaveKernel", JSONObject()
                            .put("slug", "${Cfg.kaggleUser}/${slug(j)}").put("newTitle", slug(j).replace('-', ' '))
                            .put("text", nb).put("language", "python").put("kernelType", "notebook")
                            .put("isPrivate", true).put("enableGpu", j.gpu).put("enableInternet", true)
                            .put("datasetDataSources", JSONArray(sources)).apply { if (j.gpu) put("machineShape", "NvidiaTeslaT4") }
                            .apply { if (models.isNotEmpty()) put("modelDataSources", JSONArray(models)) })
                    } catch (e: Exception) { JSONObject().put("error", e.message ?: "") }
                    val err = r.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: break
                    if (!gpusBusy(err) || System.currentTimeMillis() > waitUntil) throw IllegalStateException("Kaggle: $err")
                    setStatus(j, "waiting for a free Kaggle ${if (j.gpu) "GPU" else "session"} (your other trainings use them)…")
                    Thread.sleep(120_000)
                }
                // a dataset Kaggle could not attach = a run without its files: do not start it
                r.optJSONArray("invalidDatasetSources")?.takeIf { it.length() > 0 }?.let {
                    throw IllegalStateException("Kaggle could not attach $it yet: tap Train again in a few minutes")
                }
                Cfg.setRaw("ct_seen_${j.key}", null)
                Cfg.setRaw("ct_run_${j.key}", System.currentTimeMillis().toString())
                setStatus(j, "queued on Kaggle (version ${r.optInt("versionNumber")})")
            } catch (e: Exception) {
                Diag.error("Cloud training (${j.label})", e)
                setStatus(j, "failed: ${e.message?.take(160)}")
            } finally { synchronized(busy) { busy.remove(j) } }
            if (Cfg.raw("ct_run_${j.key}") != null) follow(c, j)
        }
    }

    // "too many sessions / concurrent / GPU in use": wait for one to finish (a used-up weekly quota is not this)
    private fun gpusBusy(err: String): Boolean {
        val e = err.lowercase()
        return (e.contains("concurrent") || e.contains("maximum") || e.contains("too many") || e.contains("already running") ||
            e.contains("session limit") || e.contains("in use") || e.contains("429")) && !e.contains("quota")
    }

    // checks this run once a minute on the shared timer
    private fun follow(c: Context, j: Job) {
        synchronized(following) {
            if (following[j]?.isDone == false) return
            following[j] = checker.scheduleWithFixedDelay({
                val over = try { check(c, j) } catch (e: Exception) { setStatus(j, "checking… (${e.message?.take(60)})"); false }
                if (over) synchronized(following) { following.remove(j)?.cancel(false) }
            }, 0, 60, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    // after the app starts again: keep following the runs that were going
    fun resume(ctx: Context) {
        for (j in Job.values()) if (Cfg.raw("ct_run_${j.key}") != null) follow(ctx.applicationContext, j)
    }

    // one look at the run; true = it is over (finished, failed or given up)
    private fun check(c: Context, j: Job): Boolean {
        val started = Cfg.raw("ct_run_${j.key}")?.toLongOrNull() ?: return true
        val r = call("kernels.KernelsApiService", "GetKernelSessionStatus",
            JSONObject().put("userName", Cfg.kaggleUser).put("kernelSlug", slug(j)))
        val st = r.opt("status")?.toString()?.uppercase() ?: ""
        val mins = (System.currentTimeMillis() - started) / 60000
        val active = st.contains("QUEUED") || st.contains("RUNNING") || st == "0" || st == "1"
        if (active) Cfg.setRaw("ct_seen_${j.key}", "1")
        // Right after starting, Kaggle can still report the PREVIOUS run (finished or failed):
        // a final status only counts once this run was seen queued/running, or after 4 min
        val fresh = Cfg.raw("ct_seen_${j.key}") != null || mins >= 4
        when {
            (st.contains("COMPLETE") || st == "2") && fresh -> {
                // the download runs on a worker (big files), not on the timer
                Cfg.setRaw("ct_run_${j.key}", null)
                synchronized(busy) { busy.add(j) }
                io.execute {
                    try { fetch(c, j) } catch (e: Exception) { setStatus(j, "finished, but the download failed: ${e.message?.take(100)}") }
                    finally { synchronized(busy) { busy.remove(j) } }
                }
                return true
            }
            (st.contains("ERROR") || st.contains("CANCEL") || st == "3" || st == "5") && fresh -> {
                setStatus(j, "failed on Kaggle: " + (r.optString("failureMessage").takeIf { it.isNotBlank() && it != "null" }
                    ?: failReason(j) ?: "open the notebook on Kaggle to see why").take(220))
                renameZips(j)
                Cfg.setRaw("ct_run_${j.key}", null); return true
            }
            !active && !fresh -> setStatus(j, "starting on Kaggle…")
            else -> setStatus(j, (if (st.contains("QUEUED") || st == "0") "queued" else "training") + " on Kaggle, $mins min")
        }
        if (mins > 12 * 60) { setStatus(j, "stopped following it after 12 h: open it on Kaggle"); Cfg.setRaw("ct_run_${j.key}", null); return true }
        return false
    }

    // Why a run failed, read from its log (the last error line), in plain words where it is a known one
    private fun failReason(j: Job): String? = try {
        val log = call("kernels.KernelsApiService", "ListKernelSessionOutput",
            JSONObject().put("userName", Cfg.kaggleUser).put("kernelSlug", slug(j))).optString("log")
        reasonFromLog(log)
    } catch (e: Exception) { null }

    internal fun reasonFromLog(log: String?): String? {
        if (log.isNullOrBlank()) return null
        // the log is a list of {"stream_name","time","data"}; plain text also works
        val text = try {
            val a = JSONArray(log); (0 until a.length()).joinToString("") { a.optJSONObject(it)?.optString("data") ?: "" }
        } catch (e: Exception) { log }
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.any { it.contains("agree to the license", true) || it.contains("GEMMA LICENCE") })
            return "accept the Gemma licence first: open kaggle.com/models/google/gemma-3 signed in to your kaggle.json account, " +
                "tap Request access, then tap Train again"
        val err = lines.lastOrNull { it.startsWith("SystemExit:") } ?: lines.lastOrNull {
            Regex("^[A-Za-z_.]*(Error|Exception):").containsMatchIn(it) && !it.startsWith("PapermillExecutionError")
        } ?: return null
        return err.removePrefix("SystemExit:").trim()
    }

    // the results, straight into Download/MLBB-Research; then the folder scan loads them
    private fun fetch(c: Context, j: Job) {
        setStatus(j, "downloading the results…")
        val r = call("kernels.KernelsApiService", "ListKernelSessionOutput",
            JSONObject().put("userName", Cfg.kaggleUser).put("kernelSlug", slug(j)))
        val files = r.optJSONArray("files") ?: JSONArray()
        val got = ArrayList<String>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.optString("fileName").substringAfterLast('/')
            if (name !in j.results) continue
            val conn = URL(f.optString("url")).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000; conn.readTimeout = 120_000
            conn.inputStream.use { save(c, name, it) }
            got.add(name)
        }
        if (got.none { it.endsWith(".tflite") || it.endsWith(".zip") || it.endsWith(".litertlm") }) {
            setStatus(j, "finished, but no result file came out: open the notebook on Kaggle to see why"); return
        }
        setStatus(j, "done: ${got.joinToString()} saved in Download/MLBB-Research and loaded")
        AutoFolder.scan(c)
    }

    // ---------------- Kaggle API (the same calls as Kaggle's own command-line tool) ----------------

    private fun auth(): String {
        val k = Cfg.kaggleKey
        return if (k.startsWith("KGAT_")) "Bearer $k"
            else "Basic " + Base64.encodeToString("${Cfg.kaggleUser}:$k".toByteArray(), Base64.NO_WRAP)
    }

    private fun call(service: String, request: String, body: JSONObject): JSONObject {
        val conn = URL("$API/$service/$request").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.connectTimeout = 20_000; conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", auth())
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "kaggle-api/v1.7.0")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        if (code !in 200..299) throw IllegalStateException("Kaggle $request: HTTP $code ${text.take(200)}" +
            if (code == 401) " (check kaggle.json)" else if (code == 403 && request == "SaveKernel") " (phone-verify your Kaggle account to use GPUs / internet)" else "")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    // one file to Kaggle's upload storage; returns its token
    private fun upload(f: Input): String {
        val r = call("blobs.BlobApiService", "StartBlobUpload", JSONObject().put("type", "DATASET").put("name", f.name)
            .put("contentLength", f.size).put("lastModifiedEpochSeconds", System.currentTimeMillis() / 1000))
        val url = r.getString("createUrl"); val token = r.getString("token")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"; conn.doOutput = true
        conn.setFixedLengthStreamingMode(f.size)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("Content-Range", "bytes 0-${f.size - 1}/${f.size}")
        conn.connectTimeout = 30_000; conn.readTimeout = 300_000
        (f.open() ?: throw IllegalStateException("cannot read ${f.name}")).use { input -> conn.outputStream.use { input.copyTo(it, 1 shl 16) } }
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("upload of ${f.name} failed: HTTP $code")
        return token
    }

    // Gemma 3 270M (instruction-tuned) on Kaggle Models: owner/model/framework/variation/version.
    // The latest version is asked for; if that fails, version 1.
    private fun gemmaSource(): String {
        val v = try {
            call("models.ModelApiService", "GetModelInstance", JSONObject().put("ownerSlug", "google").put("modelSlug", "gemma-3")
                .put("framework", "transformers").put("instanceSlug", "gemma-3-270m-it")).optInt("versionNumber", 0)
        } catch (e: Exception) { 0 }
        return "google/gemma-3/transformers/gemma-3-270m-it/${if (v > 0) v else 1}"
    }

    // a new private dataset holding these files (never a new version of an old one)
    private fun createDataset(ds: String, title: String, files: List<Input>) {
        val tokens = JSONArray(files.map { JSONObject().put("token", upload(it)) })
        val r = call("datasets.DatasetApiService", "CreateDataset", JSONObject().put("ownerSlug", Cfg.kaggleUser).put("slug", ds)
            .put("title", title.padEnd(6, '_')).put("licenseName", "CC0-1.0").put("isPrivate", true).put("files", tokens))
        val err = r.optString("error")
        // (a picture zip uploaded by an older app version already exists under this name: use it)
        if (err.isNotBlank() && err != "null" && !err.contains("already", true) && !err.contains("exist", true))
            throw IllegalStateException("Kaggle dataset $ds: $err")
    }

    // after a failed run: the picture zips go up again under new names next time
    private fun renameZips(j: Job) {
        for (base in Cfg.raw("ct_zips_${j.key}")?.split("|").orEmpty().filter { it.isNotBlank() }) {
            val gen = (Cfg.raw("ct_dsgen_$base")?.toIntOrNull() ?: 0) + 1
            Cfg.setRaw("ct_dsgen_$base", gen.toString())
        }
    }

    // a new dataset (version) takes a little while before a notebook can use it
    // Kaggle processes a new upload (receives, unzips, copies) before a notebook can use it: wait
    // for READY (status 5). "1" is only BLOBS_RECEIVED - starting then gave the notebook no files.
    private fun waitReady(j: Job, ref: String) {
        val (owner, ds) = ref.split("/")
        val until = System.currentTimeMillis() + 20 * 60_000L
        while (System.currentTimeMillis() < until) {
            val st = try { call("datasets.DatasetApiService", "GetDatasetStatus", JSONObject().put("ownerSlug", owner).put("datasetSlug", ds))
                .opt("status")?.toString()?.uppercase() ?: "" } catch (e: Exception) { "" }
            if (st == "READY" || st == "5") return
            if (st == "FAILED" || st == "6" || st == "DELETED" || st == "7") throw IllegalStateException("Kaggle could not process the upload $ds ($st)")
            setStatus(j, "Kaggle is processing the upload… (${st.lowercase().replace('_', ' ').ifBlank { "waiting" }})")
            Thread.sleep(15_000)
        }
        throw IllegalStateException("Kaggle took over 20 min to process the upload $ds: tap Train again later (it is not uploaded again)")
    }

    private fun dsSlug(file: String): String =
        ("mlbb-" + file.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9]+"), "-").removePrefix("mlbb-")).trim('-').take(50)

    // the notebook from the app's GitHub repository (the version with the unattended Kaggle mode)
    // The notebook: the copy bundled in the app first (always the version this app expects, and it
    // works with a private GitHub repository), else the repository on GitHub (only if it is public).
    private fun notebook(c: Context?, j: Job, sources: List<String> = emptyList()): String {
        var last: Exception? = null
        val texts = ArrayList<() -> String?>()
        if (c != null) texts.add { try { c.assets.open("notebooks/${j.notebook}").use { it.readBytes().toString(Charsets.UTF_8) } } catch (e: Exception) { last = e; null } }
        for (ref in REPO_REFS) texts.add {
            val conn = URL("$REPO/$ref/notebooks/${j.notebook}").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            if (conn.responseCode != 200) { last = IllegalStateException("GitHub HTTP ${conn.responseCode} (a private repository cannot be read)"); null }
            else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
        for (get in texts) {
            try {
                val raw = get() ?: continue
                val nb = JSONObject(raw)
                val cells = nb.getJSONArray("cells")
                var kaggleMode = false
                for (i in 0 until cells.length()) {
                    val c = cells.getJSONObject(i)
                    // Kaggle wants each cell's source as one string, and no old outputs
                    val src = c.opt("source")
                    var text = if (src is JSONArray) (0 until src.length()).joinToString("") { src.getString(it) } else src?.toString() ?: ""
                    // the uploads this run needs: the notebook waits for them / fetches them itself
                    if ("APP_SOURCES = []" in text) text = text.replace("APP_SOURCES = []", "APP_SOURCES = " + JSONArray(sources).toString().replace("\\/", "/"))
                    c.put("source", text)
                    if (c.optString("cell_type") == "code") c.put("outputs", JSONArray())
                    if ("ON_KAGGLE" in text) kaggleMode = true
                }
                if (!kaggleMode) continue          // an older copy without the unattended mode
                return nb.toString()
            } catch (e: Exception) { last = e }
        }
        throw IllegalStateException("could not read ${j.notebook} from GitHub" + (last?.message?.let { ": $it" } ?: ""))
    }

    // ---------------- the phone side ----------------

    private fun zipAndWait(c: Context, k: DatasetZipper.Kind) {
        val lock = Object(); var msg: String? = null
        DatasetZipper.zipNew(c, k, "zipped for cloud training") { m -> synchronized(lock) { msg = m; lock.notifyAll() } }
        synchronized(lock) { val until = System.currentTimeMillis() + 300_000; while (msg == null && System.currentTimeMillis() < until) lock.wait(1000) }
    }

    // runs an export and waits for it; returns how many rows it wrote ("Exported N rows to ...")
    private fun exportAndWait(export: ((String) -> Unit) -> Unit): Int {
        val lock = Object(); var msg: String? = null
        export { m -> synchronized(lock) { msg = m; lock.notifyAll() } }
        synchronized(lock) { val until = System.currentTimeMillis() + 120_000; while (msg == null && System.currentTimeMillis() < until) lock.wait(1000) }
        return Regex("Exported (\\d+) rows").find(msg ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun save(c: Context, name: String, input: InputStream) {
        val cr = c.contentResolver
        val rel = Environment.DIRECTORY_DOWNLOADS + "/MLBB-Research/"
        val existing = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
            MediaStore.Downloads.DISPLAY_NAME + " = ? AND " + MediaStore.Downloads.RELATIVE_PATH + " = ?", arrayOf(name, rel), null)
            ?.use { if (it.moveToFirst()) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0)) else null }
        val uri = existing ?: cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, rel.trimEnd('/'))
        }) ?: throw IllegalStateException("cannot create $name")
        (cr.openOutputStream(uri, "wt") ?: throw IllegalStateException("cannot write $name")).use { input.copyTo(it, 1 shl 16) }
    }
}
