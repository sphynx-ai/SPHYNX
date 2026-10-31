package com.example.mlbbresearch.vision

import kotlin.math.abs
import kotlin.math.hypot

// Merges the whole-screen detector's finds into what the colour vision saw (only fills gaps,
// never removes anything):
//  - creep / buff / turtle / lord boxes with no HP bar near them = monsters nobody has hit yet:
//    added at full HP, so the AI walks up and starts the fight instead of waiting for a bar
//  - bush boxes = extra bushes (for hiding / ambush); wall boxes = wall cells (to steer around)
//  - an enemy tower box when no range circle is on screen = a circle of the usual size around its
//    base: RED if you stand in it, GREEN if your minions are there, else YELLOW (keep out)
object ScreenIntel {

    class Out(val monsters: List<Seen>, val bushes: List<Bush>, val walls: BooleanArray, val ring: TowerRing.Ring)

    @Volatile var added = "-"
        private set

    fun merge(now: Long, self: Seen?, monsters: List<Seen>, bushes: List<Bush>, walls: BooleanArray,
              ring: TowerRing.Ring, allyMinions: List<Seen>): Out {
        if (!ScreenDetector.ready || now - ScreenDetector.latestAt > 700 || self == null) return Out(monsters, bushes, walls, ring)
        val det = ScreenDetector.latest
        val mons = ArrayList(monsters)
        val bs = ArrayList(bushes)
        val ws = walls.copyOf()
        var r = ring
        var nm = 0; var nb = 0; var nw = 0
        for (d in det) {
            when (d.label) {
                "creep", "buff", "turtle", "lord" -> {
                    // the body's middle; a monster bar of the colour vision sits above it
                    if (mons.any { abs(it.x - d.cx) < (d.right - d.left) * 0.7f && abs(it.y - d.cy) < (d.bottom - d.top) * 0.8f }) continue
                    val boss = d.label == "turtle" || d.label == "lord"
                    val top = d.top
                    mons.add(Seen(Team.NEUTRAL, boss, d.cx, d.cy, 1f, d.left, top, d.right, top + 0.01f)); nm++
                }
                "bush" -> if (bs.none { hypot(it.x - d.cx, it.y - d.cy) < 0.06f }) { bs.add(Bush(d.cx, d.cy, 4)); nb++ }
                "wall" -> {
                    if (ws.size != 40 * 20) continue
                    for (gy in (d.top * 20).toInt().coerceAtLeast(0)..(d.bottom * 20).toInt().coerceAtMost(19))
                        for (gx in (d.left * 40).toInt().coerceAtLeast(0)..(d.right * 40).toInt().coerceAtMost(39)) {
                            if (!ws[gy * 40 + gx]) { ws[gy * 40 + gx] = true; nw++ }
                        }
                }
                "tower_enemy" -> if (r.zone == TowerRing.Zone.NONE) {
                    // its base: the bottom of the box; the range circle about as big as measured on screen
                    val cx = d.cx; val cy = d.bottom - 0.03f
                    val rx = 0.22f; val ry = 0.34f
                    val inside = ((self.x - cx) / rx).let { it * it } + ((self.y - cy) / ry).let { it * it } < 1f
                    val minionsIn = allyMinions.any { ((it.x - cx) / rx).let { a -> a * a } + ((it.y - cy) / ry).let { b -> b * b } < 1f }
                    val zone = when { minionsIn -> TowerRing.Zone.GREEN; inside -> TowerRing.Zone.RED; else -> TowerRing.Zone.YELLOW }
                    r = TowerRing.Ring(zone, cx, cy, rx, ry, inside)
                }
            }
        }
        added = "screen detector added: $nm monsters, $nb bushes, $nw wall cells" + if (r !== ring) ", an enemy tower (${r.zone.name.lowercase()})" else ""
        return Out(mons, bs, ws, r)
    }
}
