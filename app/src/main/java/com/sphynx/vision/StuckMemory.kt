package com.sphynx.vision

// WHERE THE HERO GOT STUCK, REMEMBERED (owner, 2026-11-13: "it is frequently stuck on walls; it should remember
// where it got stuck"). The wall map used to forget these places at every match start, so the hero ran into the
// same unseen wall in every match. Now each place (a cell of the 40 x 40 minimap grid) keeps its count across
// matches: stuck there twice (in any matches) = a wall from the first second of every later match. A place the
// hero later stands on is forgotten (it was not a wall). Saved as "cell=count;cell=count".
// Pure logic, unit tested; WallMap saves it in the settings.
object StuckMemory {

    const val WALL_AT = 2          // times stuck before the cell counts as a wall
    const val MAX_COUNT = 9
    const val MAX_CELLS = 400      // the most-hit cells are kept

    fun parse(s: String?): MutableMap<Int, Int> {
        val m = LinkedHashMap<Int, Int>()
        for (e in (s ?: "").split(';')) {
            val p = e.split('=')
            val c = p.getOrNull(0)?.toIntOrNull() ?: continue
            val n = p.getOrNull(1)?.toIntOrNull() ?: continue
            if (c >= 0 && n > 0) m[c] = n.coerceAtMost(MAX_COUNT)
        }
        return m
    }

    fun format(m: Map<Int, Int>): String =
        m.entries.sortedByDescending { it.value }.take(MAX_CELLS).joinToString(";") { "${it.key}=${it.value}" }

    fun hit(m: MutableMap<Int, Int>, cell: Int): Int {
        val n = ((m[cell] ?: 0) + 1).coerceAtMost(MAX_COUNT)
        m[cell] = n
        return n
    }

    fun walls(m: Map<Int, Int>): List<Int> = m.filter { it.value >= WALL_AT }.keys.toList()
}
