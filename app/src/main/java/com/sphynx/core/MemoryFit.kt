package com.sphynx.core

// WILL THIS MODEL FIT NEXT TO THE GAME? (Phase 7, step L3; owner decision 2026-11-23: warn, let me load.)
// Before a model loads by hand, the app compares what it will probably need with the phone's free memory, keeping room
// for the game. When it probably won't fit, the owner is asked; a load at start only writes a warning in the log. The
// estimate is rough on purpose and never blocks: the Phase 5 snapshot (v37.92) showed the app at 2,700 MB with Gemma 4
// E2B (about 2.6 GB file) and the small model (about 0.3 GB) loaded, so a model needs about its file size plus a little.
// Pure, unit-tested.
object MemoryFit {

    /** Room kept for the game (MLBB needs about 2-4 GB by third-party guides: docs/UNIVERSAL_COMPATIBILITY.md A.9.1). */
    const val GAME_RESERVE_MB = 2_500L
    /** Working memory a loaded model needs beyond its file (buffers, the context). */
    const val OVERHEAD_MB = 200L

    fun neededMb(fileBytes: Long): Long = (fileBytes / 1_048_576L) + OVERHEAD_MB

    /**
     * A warning when the model will probably not fit next to the game, else null. [availMb] is the phone's free memory
     * now; [alreadyLoadedMb] is what the same job's current model holds and gives back when it is replaced.
     */
    fun warning(fileName: String, fileBytes: Long, availMb: Long, alreadyLoadedMb: Long = 0L): String? {
        if (fileBytes <= 0 || availMb <= 0) return null                // unknown: do not guess
        val need = neededMb(fileBytes)
        val leftForGame = availMb + alreadyLoadedMb - need
        if (leftForGame >= GAME_RESERVE_MB) return null
        return "$fileName needs about ${gb(need)} and the phone has ${gb(availMb + alreadyLoadedMb)} free: " +
            "the game would be left with about ${gb(maxOf(0, leftForGame))} (it needs about ${gb(GAME_RESERVE_MB)}). " +
            "It may be slow or be closed by Android during a match."
    }

    private fun gb(mb: Long) = String.format(java.util.Locale.US, "%.1f GB", mb / 1024f)
}
