package com.sphynx.universal.pairing

/**
 * PHASE 11, step P1: the two coaching workloads and the phone classes the pairing method (docs/PHASE_11_PAIRING_METHOD.md)
 * works with. Game-free and pure, unit-tested. Budgets come from how the app uses the answers, not from a model.
 */
object Workloads {

    enum class Job { STRATEGIC, TACTICAL }

    /**
     * A workload: how often the job is asked, how long its answer is, and the speed budget it must meet.
     * [budgetMs] is checked against [budgetOn]: the usable-answer 90th percentile (tactical) or the whole-answer median
     * (strategic). [wantsPictures]: the job is better with a model that reads pictures.
     */
    data class Workload(val job: Job, val name: String, val askEveryMs: Long, val budgetMs: Long, val budgetOn: Budget,
                        val wantsPictures: Boolean, val about: String)

    enum class Budget(val label: String) { USABLE_P90("90% of usable answers"), WHOLE_MEDIAN("median whole answer") }

    /** A fight answer is used for 1 s (TacticalAdvisor.VALID_MS) and asked at most every 330 ms. */
    val TACTICAL = Workload(Job.TACTICAL, "tactical: frequent, short, latency-bound", 330, 1_000, Budget.USABLE_P90, false,
        "asked about 3 times a second in a fight; an answer older than 1 s is not used")

    /** The plan, the draft and the build: asked now and then, longer answers, pictures help (the draft and the screen). */
    val STRATEGIC = Workload(Job.STRATEGIC, "strategic: infrequent, long", 30_000, 20_000, Budget.WHOLE_MEDIAN, true,
        "asked when the situation changes (draft, build, plan); a long answer is fine, pictures help")

    fun of(job: Job) = if (job == Job.TACTICAL) TACTICAL else STRATEGIC

    enum class PhoneClass(val label: String) { ENTRY("entry-level"), MIDRANGE("midrange"), FLAGSHIP("flagship"), UNKNOWN("unknown") }

    /** The phone class from total RAM: under 6 GB entry, under 10 GB midrange, else flagship; 0 or less = unknown. */
    fun phoneClass(totalRamMb: Long): PhoneClass = when {
        totalRamMb <= 0 -> PhoneClass.UNKNOWN
        totalRamMb < 6 * 1024 - 512 -> PhoneClass.ENTRY          // a "6 GB" phone reports a little less
        totalRamMb < 10 * 1024 - 512 -> PhoneClass.MIDRANGE
        else -> PhoneClass.FLAGSHIP
    }

    /** Room for models next to the game, in MB: what the phone has, minus the game's reserve (core.MemoryFit's 2.5 GB). */
    fun modelRoomMb(availMb: Long, gameReserveMb: Long = 2_500): Long = (availMb - gameReserveMb).coerceAtLeast(0)

    /** What a loaded model needs: its file plus working memory (the same rule as core.MemoryFit). */
    fun neededMb(fileMb: Long, overheadMb: Long = 200): Long = fileMb + overheadMb

    /**
     * The phone class a model of [fileMb] declares, from the memory rule: the smallest class whose typical free memory
     * after the game holds it (entry 1.5 GB, midrange 3.5 GB, flagship 7 GB free for models). Declared, not measured.
     */
    fun declaredClass(fileMb: Long): PhoneClass {
        val need = neededMb(fileMb)
        return when { need <= 1_500 -> PhoneClass.ENTRY; need <= 3_500 -> PhoneClass.MIDRANGE; else -> PhoneClass.FLAGSHIP }
    }
}
