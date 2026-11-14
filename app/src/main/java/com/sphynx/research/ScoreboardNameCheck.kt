package com.sphynx.research

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

    data class Result(val verdict: Verdict, val selfRow: Int, val ally: List<Float>, val enemy: List<Float>, val note: String,
                      val players: Int = 0)   // players in the match (you included); 0 = not read

    // EMPTY ROWS (solo practice, owner 2026-11-10: only you and one Computer enemy): a row with a player
    // always shows its gold number in yellow; an empty row has none. Measured on the owner's 2712x1220
    // scoreboards: 85..475 gold pixels in a player's row (yours, highlighted, is the lowest), 0 in an
    // empty one. Empty rows are not players, so they are not checked.
    const val ALLY_GOLD_X0 = 0.450f; const val ALLY_GOLD_X1 = 0.492f
    const val ENEMY_GOLD_X0 = 0.508f; const val ENEMY_GOLD_X1 = 0.550f
    // (device test 2026-11-10: at the app's 800x359 capture the digits are blurred into the dark row, so
    // the colour test is looser. Measured at 800x359: 1.9..14% in a player's row, 0 in an empty one)
    const val GOLD_MIN_FRACTION = 0.008f

    fun check(px: IntArray, w: Int, h: Int, m: ComputerNameMatcher): Result =
        decide(highlightedRow(px, w, h),
            NAME_TOP.map { m.score(px, w, h, ALLY_X, it) }, NAME_TOP.map { m.score(px, w, h, ENEMY_X, it) },
            NAME_TOP.indices.map { occupied(px, w, h, true, it) }, NAME_TOP.indices.map { occupied(px, w, h, false, it) })

    // the decision alone (unit tested): scores per row, and which rows hold a player
    fun decide(self: Int, ally: List<Float>, enemy: List<Float>, allyHere: List<Boolean>, enemyHere: List<Boolean>): Result {
        if (self < 0) return Result(Verdict.UNREADABLE, -1, ally, enemy, "your highlighted row not found")
        val others = ally.filterIndexed { i, _ -> i != self && allyHere[i] } + enemy.filterIndexed { i, _ -> enemyHere[i] }
        if (others.isEmpty()) return Result(Verdict.UNREADABLE, self, ally, enemy, "no other player's row found")
        val human = others.count { it <= IS_NAME }
        val unclear = others.count { it > IS_NAME && it < IS_COMPUTER }
        val players = others.size + 1
        return when {
            human > 0 -> Result(Verdict.HUMAN_PRESENT, self, ally, enemy, players = players, note = "$human row(s) are not 'Computer'")
            unclear > 0 -> Result(Verdict.UNREADABLE, self, ally, enemy, players = players, note = "$unclear row(s) unclear")
            else -> Result(Verdict.ALL_COMPUTER, self, ally, enemy, players = players, note = "all ${others.size} other player(s) are 'Computer'" +
                if (others.size < 9) " (${others.size + 1} players in this match)" else "")
        }
    }

    // does this row hold a player? (its yellow gold number)
    fun occupied(px: IntArray, w: Int, h: Int, allySide: Boolean, row: Int): Boolean {
        val x0 = ((if (allySide) ALLY_GOLD_X0 else ENEMY_GOLD_X0) * w).toInt()
        val x1 = ((if (allySide) ALLY_GOLD_X1 else ENEMY_GOLD_X1) * w).toInt()
        val y0 = ((NAME_TOP[row] - 0.01f) * h).toInt().coerceAtLeast(0)
        val y1 = ((NAME_TOP[row] + 0.035f) * h).toInt().coerceAtMost(h)
        var n = 0; var all = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = px[y * w + x]; val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            if (r >= 130 && r - b >= 45 && g - b >= 20 && r - g in 8..75) n++
            all++
        }
        return all > 0 && n.toFloat() / all >= GOLD_MIN_FRACTION
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
