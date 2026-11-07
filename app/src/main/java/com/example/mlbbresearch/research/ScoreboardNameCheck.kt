package com.example.mlbbresearch.research

// Reads the MLBB in-match scoreboard (Equipment tab) and decides whether every player except
// you is "Computer". Layout measured on the owner's 2712x1220 practice scoreboards
// (MLBB-SCREEN-014 / -015): five rows per team; ally names left-aligned, enemy names
// right-aligned. Your row is the highlighted one (same rule as PortraitMatch.observeScoreboard).
// Only call this with frames where the scoreboard is open (Phase.SCOREBOARD).
object ScoreboardNameCheck {

    // top of the name text per row, and where the "Computer" word starts (screen fractions)
    val NAME_TOP = floatArrayOf(0.2754f, 0.4033f, 0.5311f, 0.6598f, 0.7877f)
    const val ALLY_X = 0.1984f
    const val ENEMY_X = 0.7363f
    // portrait row centres, for finding your highlighted row
    private val ROWS = floatArrayOf(0.328f, 0.456f, 0.584f, 0.712f, 0.84f)

    const val IS_COMPUTER = 0.80f        // at or above: the word "Computer"
    const val IS_NAME = 0.60f            // at or below: something else (a player's name)

    enum class Verdict { ALL_COMPUTER, HUMAN_PRESENT, UNREADABLE }

    data class Result(val verdict: Verdict, val selfRow: Int, val ally: List<Float>, val enemy: List<Float>, val note: String)

    fun check(px: IntArray, w: Int, h: Int, m: ComputerNameMatcher): Result {
        val self = highlightedRow(px, w, h)
        val ally = NAME_TOP.map { m.score(px, w, h, ALLY_X, it) }
        val enemy = NAME_TOP.map { m.score(px, w, h, ENEMY_X, it) }
        if (self < 0) return Result(Verdict.UNREADABLE, -1, ally, enemy, "your highlighted row not found")
        val others = ally.filterIndexed { i, _ -> i != self } + enemy
        val human = others.count { it <= IS_NAME }
        val unclear = others.count { it > IS_NAME && it < IS_COMPUTER }
        return when {
            human > 0 -> Result(Verdict.HUMAN_PRESENT, self, ally, enemy, "$human row(s) are not 'Computer'")
            unclear > 0 -> Result(Verdict.UNREADABLE, self, ally, enemy, "$unclear row(s) unclear")
            else -> Result(Verdict.ALL_COMPUTER, self, ally, enemy, "all 9 other players are 'Computer'")
        }
    }

    fun highlightedRow(px: IntArray, w: Int, h: Int): Int {
        val lum = ROWS.map { fy -> band(px, w, h, 0.44f, 0.49f, fy - 0.06f, fy - 0.045f) }
        val best = lum.indices.maxByOrNull { lum[it] } ?: return -1
        val others = lum.filterIndexed { i, _ -> i != best }.sorted()
        return if (lum[best] > others[others.size / 2] + 20f) best else -1
    }

    private fun band(px: IntArray, w: Int, h: Int, x0: Float, x1: Float, y0: Float, y1: Float): Float {
        var s = 0L; var n = 0
        var y = (y0 * h).toInt()
        while (y < (y1 * h).toInt()) {
            var x = (x0 * w).toInt()
            while (x < (x1 * w).toInt()) { val p = px[y * w + x]; s += ((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255); n += 3; x += 3 }
            y += 2
        }
        return if (n == 0) 0f else s.toFloat() / n
    }
}
