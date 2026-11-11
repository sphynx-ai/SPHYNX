package com.example.mlbbresearch.game

// WHICH LANE IS ON TOP (owner, 2026-11-11): the Gold lane and the EXP lane can be swapped from one match to
// the next, and the Lord and Turtle pits with them (the Lord is on the EXP lane's side, the Turtle on the Gold
// lane's). The minimap shows it at the start of a match: a lane mark at its top-left corner - a gold "$"
// (Gold lane on top) or a lavender "E" (EXP lane on top) - and the other at the bottom-right corner.
// Measured on the owner's screenshots (2712x1220; same result at the app's 800x359): the "$" at x 0.048-0.057,
// y 0.006-0.031, almost white with a warm tint (blue - red about -45); the "E" (bottom-right, x 0.173-0.182,
// y 0.285-0.309) cool (blue - red about +13). They fade after the first seconds, so they are read only while
// the match is young, on the normal game view, and only the TOP-LEFT mark decides (the bottom-right corner
// also catches gold text on other screens). Until it is known, the usual layout is used: EXP lane on top.
// All the lane / pit geometry goes through geo() and the pits below. Pure logic, unit tested.
object MapSide {

    // the top-left mark's box (screen fractions)
    const val X0 = 0.045f; const val X1 = 0.060f; const val Y0 = 0.0f; const val Y1 = 0.035f
    const val NEED = 3                       // readings in a row before it is believed
    const val READ_UNTIL_SEC = 90

    @Volatile var goldTop: Boolean? = null; private set
    private var run = 0
    private var runValue: Boolean? = null

    @Synchronized fun reset() { goldTop = null; run = 0; runValue = null }

    enum class Mark { GOLD, EXP, NONE }

    // the mark in a box: bright, little-saturated pixels (the glyph), warm = "$", cool = "E"
    fun mark(px: IntArray, w: Int, h: Int, x0: Float = X0, x1: Float = X1, y0: Float = Y0, y1: Float = Y1): Mark {
        var n = 0; var bright = 0; var sumBR = 0L
        for (y in (y0 * h).toInt()..(y1 * h).toInt().coerceAtMost(h - 1)) for (x in (x0 * w).toInt()..(x1 * w).toInt().coerceAtMost(w - 1)) {
            val p = px[y * w + x]; val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            n++
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            if (mx >= 200 && mx - mn <= 90) { bright++; sumBR += b - r }
        }
        if (n == 0 || bright < n * 0.12f) return Mark.NONE
        val d = sumBR.toFloat() / bright
        return when { d <= -15f -> Mark.GOLD; d >= 5f -> Mark.EXP; else -> Mark.NONE }
    }

    @Synchronized fun observe(m: Mark) {
        if (goldTop != null || m == Mark.NONE) return
        val v = m == Mark.GOLD
        if (v == runValue) run++ else { runValue = v; run = 1 }
        if (run >= NEED) {
            goldTop = v
            com.example.mlbbresearch.core.Diag.info("Map: " + (if (v) "Gold lane on top (the Turtle's pit top-left)" else "EXP lane on top (the Lord's pit top-left)"))
        }
    }

    // the lane whose GEOMETRY (top / bottom route, towers, corridors) a role lane uses: the code's map
    // geometry is drawn for "EXP on top"; with the Gold lane on top the two are swapped
    fun geo(lane: Lane, goldOnTop: Boolean? = goldTop): Lane = if (goldOnTop != true) lane else when (lane) {
        Lane.EXP -> Lane.GOLD
        Lane.GOLD -> Lane.EXP
        else -> lane
    }

    // THE MIRRORED MAP: with the Gold lane on top the whole map is mirrored across the line through both bases
    // (your base stays bottom-left; the top-left corner and the bottom-right corner trade places) - that is
    // what swaps the lanes and the two boss pits. view() turns a position of the usual map (EXP on top) into
    // this match's map, and back (it is its own inverse); viewDir() does the same for a direction.
    fun mirrored(goldOnTop: Boolean? = goldTop): Boolean = goldOnTop == true
    fun view(p: Pair<Float, Float>, goldOnTop: Boolean? = goldTop): Pair<Float, Float> =
        if (goldOnTop == true) (1f - p.second) to (1f - p.first) else p
    fun viewDir(d: Pair<Float, Float>, goldOnTop: Boolean? = goldTop): Pair<Float, Float> =
        if (goldOnTop == true) (-d.second) to (-d.first) else d

    val TOP_LEFT_PIT = 0.31f to 0.26f
    val BOTTOM_RIGHT_PIT = 0.67f to 0.68f
    fun lordPit(goldOnTop: Boolean? = goldTop) = if (goldOnTop == true) BOTTOM_RIGHT_PIT else TOP_LEFT_PIT
    fun turtlePit(goldOnTop: Boolean? = goldTop) = if (goldOnTop == true) TOP_LEFT_PIT else BOTTOM_RIGHT_PIT

    fun text(): String = when (goldTop) { true -> "Gold lane on top"; false -> "EXP lane on top"; null -> "not read yet (EXP on top assumed)" }
}
