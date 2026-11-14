package com.sphynx.learning

import kotlin.math.abs

// WHAT IS WORTH A TRAINING PICTURE (owner, 2026-11-12: the minimap and screen picture collectors, tuned to the
// current app). Pure logic, unit tested.
//  - no near-duplicates: a picture whose boxes are the same as the last saved one (same classes, every centre
//    within a small distance) teaches nothing new;
//  - balance: one kind of monster must not fill the set (the Thunder Fenrir used to be most of it): a picture
//    is refused once every monster kind in it already has its share of this session's pictures;
//  - the file name carries which lane is on top (g = Gold, e = EXP, u = unknown) and the kind of match
//    (s = Solo, 5 = 5v5, u = unknown), so both map layouts can be checked and balanced: mm_<time>_gs.png.
object CollectorRules {

    fun sameScene(last: List<Triple<Int, Float, Float>>?, now: List<Triple<Int, Float, Float>>, tol: Float): Boolean {
        if (last == null || last.size != now.size) return false
        val left = last.toMutableList()
        for (q in now) {
            val i = left.indexOfFirst { it.first == q.first && abs(it.second - q.second) <= tol && abs(it.third - q.third) <= tol }
            if (i < 0) return false
            left.removeAt(i)
        }
        return true
    }

    fun tag(goldTop: Boolean?, matchKind: String): String =
        "_" + (when (goldTop) { true -> "g"; false -> "e"; null -> "u" }) +
            (when (matchKind) { "SOLO" -> "s"; "FIVE_V_FIVE" -> "5"; else -> "u" })

    class Balance(private val minEach: Int = 15, private val share: Float = 0.3f) {
        private val counts = HashMap<String, Int>()
        private var total = 0
        // may a picture with these monster kinds be saved? (no monsters: always)
        fun allow(kinds: Set<String>): Boolean {
            val cap = maxOf(minEach, (total * share).toInt())
            return kinds.isEmpty() || kinds.any { (counts[it] ?: 0) < cap }
        }
        fun saved(kinds: Set<String>) { total++; for (k in kinds) counts[k] = (counts[k] ?: 0) + 1 }
    }
}
