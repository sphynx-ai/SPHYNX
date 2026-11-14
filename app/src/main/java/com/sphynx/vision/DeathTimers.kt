package com.sphynx.vision

// HOW MANY HEROES ARE DEAD (v36.86, your screenshots): next to the score at the top, a dead hero
// shows its portrait with a bright red countdown (its respawn seconds) - enemies on the right of the
// score, allies on the left. In your own matches an enemy's portrait shows only while it is dead; in
// a live match all 10 are shown and the dead ones carry the red number. So each red number = one
// dead hero. Counted on your 9 screenshots: right on all (enemies 0 / 2 / 5 / 2 / 1 / 2 / 1 / 0 / 0,
// allies 1 where an ally showed "3").
object DeathTimers {

    @Volatile var enemiesDead = 0; private set
    @Volatile var alliesDead = 0; private set
    @Volatile var readAt = 0L; private set
    private var candE = -1; private var candA = -1; private var n = 0

    // red number blobs across x0..x1 (fractions of the width) in the top strip
    fun count(px: IntArray, w: Int, h: Int, x0: Float, x1: Float): Int {
        val y0 = (0.02f * h).toInt(); val y1 = (0.068f * h).toInt()
        val xa = (x0 * w).toInt(); val xb = (x1 * w).toInt()
        val gapMax = (0.009f * w).toInt()
        var blobs = 0; var start = -1; var last = -1
        for (x in xa..xb + gapMax + 1) {
            var red = 0
            if (x <= xb) {
                var y = y0
                while (y < y1) {
                    val p = px[y * w + x]
                    val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                    if (r > 200 && g < 50 && b < 50) red++
                    y++
                }
            }
            if (red >= 3) { if (start < 0) start = x; last = x }
            else if (start >= 0 && x - last > gapMax) { if (last - start >= 3) blobs++; start = -1 }
        }
        return blobs
    }

    // every few frames in a match or a live match; a count is used after 2 agreeing readings
    fun observe(px: IntArray, w: Int, h: Int) {
        val e = count(px, w, h, 0.545f, 0.715f).coerceAtMost(5)
        val a = count(px, w, h, 0.28f, 0.455f).coerceAtMost(5)
        if (e == candE && a == candA) n++ else { candE = e; candA = a; n = 1 }
        if (n >= 2) { enemiesDead = e; alliesDead = a; readAt = System.currentTimeMillis() }
    }

    // a reading older than 5 s is not trusted (shop, map, another screen)
    fun fresh() = System.currentTimeMillis() - readAt < 5000
    fun enemiesDeadNow() = if (fresh()) enemiesDead else 0
    fun alliesDeadNow() = if (fresh()) alliesDead else 0
    fun text() = if (!fresh()) "not read" else "enemies dead $enemiesDead / alive ${5 - enemiesDead} · allies dead $alliesDead"
    fun reset() { enemiesDead = 0; alliesDead = 0; readAt = 0L; candE = -1; candA = -1; n = 0 }
}
