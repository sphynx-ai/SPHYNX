package com.example.mlbbresearch.vision

// WHICH BUFF YOU HOLD, READ FROM THE EMBLEMS AROUND YOUR HERO (owner, 2026-11-13: "that spinning orange emblem
// is the indication that you have the orange buff, and a purple emblem if you have the purple buff; you can have
// both"). While you hold a jungle buff, small emblems of its colour circle your hero's feet.
//
// Measured on the clean screenshots of the 2026-11-13 recording (800 x 359 frames), in a ring around the feet:
//   orange buff held: 38-130 orange pixels in every one of 22 screenshots; not held: 0.
//   A fight at a red / orange monster or an explosion gives far more (136-394): that is not the emblems, so a
//   count above the ceiling says nothing and the last reading is kept.
// PURPLE IS NOT VERIFIED YET: that recording has no frame with the purple emblems, so its colour range is a first
// guess (violet). A screenshot is saved with the recording whenever a buff reading starts or ends ("buff-...") so
// the next recording shows whether it is right.
// A buff counts as held once the emblems are seen in 6 of the last 9 looks (about 3 s) and as gone once they
// are missing in 8 of the last 9. Pure logic, unit tested.
object BuffEmblems {

    enum class Buff(val label: String) { ORANGE("orange buff"), PURPLE("purple buff") }

    // ---- colours (r, g, b 0..255) ----
    fun isOrange(r: Int, g: Int, b: Int): Boolean = r >= 190 && g in 90..170 && b <= 110 && r - b >= 110
    fun isPurple(r: Int, g: Int, b: Int): Boolean = b >= 200 && r in 120..230 && g <= 150 && b - g >= 70

    // ---- the ring around the feet (fractions of the frame; measured at 800 x 359) ----
    const val FEET_BELOW = 0.075f      // the feet are this far (frame heights) under the hero's body point
    const val RING_RX = 0.0525f        // frame widths
    const val RING_RY = 0.0836f        // frame heights
    const val RING_IN = 0.45f          // squared ellipse radius: inner and outer edge of the ring
    const val RING_OUT = 1.6f
    // pixel counts at 800 px width (scaled by the frame's area)
    const val MIN_AT_800 = 25
    const val MAX_AT_800 = 135         // above it the colour is a monster / an effect, not the emblems

    fun scale(w: Int): Float = (w / 800f) * (w / 800f)

    // how many ring pixels have each buff's colour: [orange, purple]
    fun count(px: IntArray, w: Int, h: Int, selfX: Float, selfY: Float): IntArray {
        val cx = selfX * w; val cy = (selfY + FEET_BELOW) * h
        val rx = RING_RX * w; val ry = RING_RY * h
        val out = IntArray(2)
        val edge = Math.sqrt(RING_OUT.toDouble()).toFloat()
        val y0 = (cy - ry * edge).toInt().coerceAtLeast(0); val y1 = (cy + ry * edge).toInt().coerceAtMost(h - 1)
        val x0 = (cx - rx * edge).toInt().coerceAtLeast(0); val x1 = (cx + rx * edge).toInt().coerceAtMost(w - 1)
        for (y in y0..y1) for (x in x0..x1) {
            val dx = (x - cx) / rx; val dy = (y - cy) / ry
            val d = dx * dx + dy * dy
            if (d < RING_IN || d > RING_OUT) continue
            val p = px[y * w + x]
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            if (isOrange(r, g, b)) out[0]++ else if (isPurple(r, g, b)) out[1]++
        }
        return out
    }

    // one look: true = the emblems are there, false = they are not, null = too much of the colour to tell
    fun look(count: Int, w: Int): Boolean? {
        val s = scale(w)
        return when {
            count > MAX_AT_800 * s -> null
            else -> count >= MIN_AT_800 * s
        }
    }

    // the last looks of one buff -> held or not (with hysteresis)
    class Tracker(private val window: Int = 9, private val onAt: Int = 6, private val offAt: Int = 1) {
        private val looks = ArrayDeque<Boolean>()
        var held = false; private set
        fun add(look: Boolean?): Boolean {
            if (look == null) return held                     // nothing learned from this frame
            looks.addLast(look); while (looks.size > window) looks.removeFirst()
            val yes = looks.count { it }
            if (!held && yes >= onAt) held = true
            else if (held && looks.size >= window && yes <= offAt) held = false
            return held
        }
        fun reset() { looks.clear(); held = false }
    }

    // ---- the live reading ----
    private val trackers = mapOf(Buff.ORANGE to Tracker(), Buff.PURPLE to Tracker())
    @Volatile var held: Set<Buff> = emptySet(); private set
    @Volatile var lastCounts = IntArray(2); private set

    // called a few times a second with the frame and your hero's body point; returns true when the reading changed
    @Synchronized fun observe(px: IntArray, w: Int, h: Int, selfX: Float, selfY: Float): Boolean {
        val c = count(px, w, h, selfX, selfY)
        lastCounts = c
        trackers.getValue(Buff.ORANGE).add(look(c[0], w))
        trackers.getValue(Buff.PURPLE).add(look(c[1], w))
        val now = trackers.filter { it.value.held }.keys
        if (now == held) return false
        held = now
        return true
    }

    @Synchronized fun reset() { trackers.values.forEach { it.reset() }; held = emptySet() }

    fun text(): String = if (held.isEmpty()) "none" else held.joinToString(" + ") { it.label }
}
