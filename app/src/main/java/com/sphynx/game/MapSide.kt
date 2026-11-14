package com.sphynx.game

// WHICH LANE IS ON TOP (owner, 2026-11-11): the Gold lane and the EXP lane can be swapped from one match to
// the next, and the Lord and Turtle pits with them: the TURTLE is always by the EXP lane, the LORD by the Gold
// lane (wiki: "the first Turtle always spawns next to the EXP lane"; and the owner's screenshot with the "$"
// mark top-left shows the Turtle's countdown in the bottom-right pit). The minimap shows it at the start of a
// match: a lane mark at its top-left corner - a gold "$" (Gold lane on top) or a lavender "E" (EXP lane on
// top) - and the other at the bottom-right corner.
// Measured on the owner's screenshots (2712x1220; same result at the app's 800x359): the "$" at x 0.048-0.057,
// y 0.006-0.031, almost white with a warm tint (blue - red about -45); the "E" (bottom-right, x 0.173-0.182,
// y 0.285-0.309) cool (blue - red about +13). They fade after the first seconds, so they are read only while
// the match is young, on the normal game view, and only the TOP-LEFT mark decides (the bottom-right corner
// also catches gold text on other screens).
// TWO CONVENTIONS IN THE CODE, kept as they were:
//  - the LANE geometry (corridors, towers, lane checks) is drawn for "EXP lane on top"; geo() swaps it when
//    the Gold lane is on top;
//  - the PIT / JUNGLE tables (SceneryMap, JungleSpots, JungleNav: Lord top-left, Turtle bottom-right) were
//    measured in the owner's matches, which are GOLD-on-top maps; view() mirrors them when the EXP lane is
//    on top.
// SOLO PRACTICE HAS NO LANE MARKS (owner, 2026-11-11 - they show only in 5v5), but its minimap shows the
// Lord's and the Turtle's icons in their pits: the Lord is by the Gold lane, so the Lord's icon in the
// top-left pit = Gold on top, in the bottom-right pit = EXP on top (fromPits). Only decided when BOTH icons
// are seen, in opposite pits (the first seconds show pit countdowns, not icons), and - in a 5v5 - only before
// 8:00 (later the Lord can stand in the Turtle's pit).
// Until it is known, both stay as they are. Pure logic, unit tested.
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

    // the side from the boss icons' pits (minimap fractions of each icon's centre; null = not seen)
    fun fromPits(lord: Pair<Float, Float>?, turtle: Pair<Float, Float>?): Mark {
        if (lord == null || turtle == null) return Mark.NONE
        fun near(p: Pair<Float, Float>, q: Pair<Float, Float>) = kotlin.math.hypot(p.first - q.first, p.second - q.second) < 0.15f
        return when {
            near(lord, TOP_LEFT_PIT) && near(turtle, BOTTOM_RIGHT_PIT) -> Mark.GOLD
            near(lord, BOTTOM_RIGHT_PIT) && near(turtle, TOP_LEFT_PIT) -> Mark.EXP
            else -> Mark.NONE
        }
    }

    @Synchronized fun observe(m: Mark) {
        if (goldTop != null || m == Mark.NONE) return
        val v = m == Mark.GOLD
        if (v == runValue) run++ else { runValue = v; run = 1 }
        if (run >= NEED) {
            goldTop = v
            com.sphynx.core.Diag.info("Map: " + (if (v) "Gold lane on top (the Lord's pit top-left)" else "EXP lane on top (the Turtle's pit top-left)"))
        }
    }

    // the lane whose GEOMETRY (top / bottom route, towers, corridors) a role lane uses: the code's map
    // geometry is drawn for "EXP on top"; with the Gold lane on top the two are swapped
    fun geo(lane: Lane, goldOnTop: Boolean? = goldTop): Lane = if (goldOnTop != true) lane else when (lane) {
        Lane.EXP -> Lane.GOLD
        Lane.GOLD -> Lane.EXP
        else -> lane
    }

    // THE MIRRORED MAP: with the EXP lane on top this match's map is the mirror of the pit / jungle tables' map
    // across the line through both bases (your base stays bottom-left; the top-left corner and the bottom-right
    // corner trade places) - that is what swaps the lanes and the two boss pits. view() turns a position of the
    // tables' map (Gold on top, Lord top-left) into this match's map, and back (it is its own inverse);
    // viewDir() does the same for a direction.
    fun mirrored(goldOnTop: Boolean? = goldTop): Boolean = goldOnTop == false
    fun view(p: Pair<Float, Float>, goldOnTop: Boolean? = goldTop): Pair<Float, Float> =
        if (mirrored(goldOnTop)) (1f - p.second) to (1f - p.first) else p
    fun viewDir(d: Pair<Float, Float>, goldOnTop: Boolean? = goldTop): Pair<Float, Float> =
        if (mirrored(goldOnTop)) (-d.second) to (-d.first) else d

    // the Lord by the Gold lane, the Turtle by the EXP lane
    val TOP_LEFT_PIT = 0.31f to 0.26f
    val BOTTOM_RIGHT_PIT = 0.67f to 0.68f
    fun lordPit(goldOnTop: Boolean? = goldTop) = if (mirrored(goldOnTop)) BOTTOM_RIGHT_PIT else TOP_LEFT_PIT
    fun turtlePit(goldOnTop: Boolean? = goldTop) = if (mirrored(goldOnTop)) TOP_LEFT_PIT else BOTTOM_RIGHT_PIT

    fun text(): String = when (goldTop) { true -> "Gold lane on top"; false -> "EXP lane on top"; null -> "not read yet (5v5: the lane mark; Solo: the Lord / Turtle icons)" }
}
