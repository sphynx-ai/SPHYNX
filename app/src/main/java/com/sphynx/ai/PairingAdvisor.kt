package com.sphynx.ai

import android.content.Context
import com.sphynx.universal.eval.EvalRecord
import com.sphynx.universal.eval.EvalReport
import com.sphynx.universal.eval.EvalSustained
import com.sphynx.universal.pairing.PairRecommender
import com.sphynx.universal.pairing.Workloads
import java.io.File

// PHASE 11, step P3: the app's side of the pair recommender. It turns what the phone has into candidates: every model in
// the Model Lab for each job it can take, and every model of the download list not yet on the phone, each with the
// newest rankable Phase 10 run of that file in that job as its evidence (none = declared only). The room for models is
// the phone's free memory plus what the two jobs hold now, minus the game's reserve. It only recommends: using a pair is
// the owner's tap (MainActivity), through the usual job rules.
object PairingAdvisor {

    /** A candidate with the job value that gives it the job (RoleChoice), or null when it can't be given directly. */
    class Option(val candidate: PairRecommender.Candidate, val choiceValue: String?)

    private val SET_FOR = mapOf(Workloads.Job.TACTICAL to "mlbb-tactical", Workloads.Job.STRATEGIC to "mlbb-strategic")

    private fun jobOf(r: String) = if (r == "tactical") Workloads.Job.TACTICAL else Workloads.Job.STRATEGIC

    /** The newest rankable set run of [fileName] (and its sha when known) in [job], as evidence. */
    fun measured(runs: List<Pair<EvalRecord.RunInfo, List<EvalRecord.Sample>>>, reviewed: Map<String, Boolean>,
                 fileName: String, sha: String?, job: Workloads.Job): PairRecommender.Measured? {
        fun same(r: EvalRecord.RunInfo) = (sha != null && r.modelSha == sha) || (r.modelSha == null && r.fileName == fileName) ||
            (sha == null && r.fileName == fileName)
        val sums = runs.filter { (r, _) -> same(r) && jobOf(r.role) == job }.map { (r, s) -> r to EvalReport.summarize(r, s) }
        val set = sums.filter { (r, s) -> r.kind == EvalRecord.KIND_SET && r.setId == SET_FOR[job] && s.rankable }
            .maxByOrNull { it.first.startedAt }?.second ?: return null
        val heat = runs.filter { (r, _) -> same(r) && r.kind == EvalRecord.KIND_SUSTAINED && r.conditions.ok }
            .maxByOrNull { it.first.startedAt }?.let { (r, s) -> EvalSustained.analyse(s, r.conditions.charging).slowdown }
        return PairRecommender.Measured(set.meanScore, set.scoreCi.first, set.scoreCi.second, set.answers,
            set.usable.p90, set.total.median, reviewed[set.run.setId] == true, heat)
    }

    fun options(ctx: Context): List<Option> {
        val app = ctx.applicationContext
        val runs = try { EvalHarness.runs(app) } catch (t: Throwable) { emptyList() }
        val reviewed = EvalHarness.SETS.mapNotNull { EvalHarness.loadSet(app, it).getOrNull() }.associate { it.id to it.reviewed }
        val rc = com.sphynx.inference.roles.RoleChoice
        val stratSlot = com.sphynx.models.ModelManager.modelFile(app).name
        val tactSlot = com.sphynx.models.ModelManager.tacticalFile(app).name
        val lib = com.sphynx.models.AutoFolder.liteLibrary(app)
        val out = ArrayList<Option>()
        val recs = try { com.sphynx.models.ModelRegistry.refresh(app) } catch (t: Throwable) { com.sphynx.models.ModelRegistry.records }
        for (r in recs) {
            if (r.format == com.sphynx.models.ModelRecord.Format.MMPROJ) continue
            val gguf = r.format == com.sphynx.models.ModelRecord.Format.GGUF
            for (job in Workloads.Job.values()) {
                val value = when {
                    gguf -> rc.gguf(r.fileName)
                    File(lib, r.fileName).isFile -> rc.lite(r.fileName)
                    r.fileName == (if (job == Workloads.Job.STRATEGIC) stratSlot else tactSlot) -> rc.LITERT
                    else -> continue                    // the other job's own file: it can't be given to this job
                }
                out.add(Option(PairRecommender.Candidate(r.fileName, r.fileName, job, r.sizeBytes / 1_048_576, r.pictures, gguf, true,
                    measured(runs, reviewed, r.fileName, r.sha256, job)), value))
            }
        }
        // the download list's models that are not on the phone yet: declared only, for the job the list names
        val have = recs.mapNotNull { it.sha256 }.toSet()
        for (e in com.sphynx.models.ModelDownloader.catalog(app)) {
            if (e.model.sha256 in have) continue
            val job = if (e.job == "tactical") Workloads.Job.TACTICAL else Workloads.Job.STRATEGIC
            out.add(Option(PairRecommender.Candidate("dl:" + e.id, e.title + " (download)", job, e.totalBytes / 1_048_576, e.pictures,
                e.model.format == com.sphynx.models.ModelRecord.Format.GGUF, false, null), null))
        }
        return out
    }

    /** Room for models next to the game: free memory plus what the two jobs hold now, minus the reserve; -1 = unknown. */
    fun roomMb(ctx: Context): Long {
        val st = com.sphynx.core.DeviceStats.sample(ctx) ?: return -1
        val held = listOf(Roles.STRATEGIC, Roles.TACTICAL).filter { Roles.ready(it) }
            .sumOf { Workloads.neededMb(com.sphynx.models.ModelManager.fileFor(ctx, it).length() / 1_048_576) }
        return Workloads.modelRoomMb(st.ramAvailMb + held, com.sphynx.core.MemoryFit.GAME_RESERVE_MB)
    }

    class Result(val pairs: List<PairRecommender.Pair>, val values: Map<String, String?>, val roomMb: Long, val phoneClass: Workloads.PhoneClass)

    fun recommend(ctx: Context): Result {
        val opts = options(ctx)
        val room = roomMb(ctx)
        val total = com.sphynx.core.DeviceStats.sample(ctx)?.ramTotalMb ?: -1
        val pairs = PairRecommender.recommend(opts.map { it.candidate }, room) { a, b -> !(a.gguf && b.gguf) }
        return Result(pairs, opts.associate { it.candidate.job.name + ":" + it.candidate.id to it.choiceValue }, room,
            Workloads.phoneClass(total))
    }
}
