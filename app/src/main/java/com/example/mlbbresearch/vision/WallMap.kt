package com.example.mlbbresearch.vision

import com.example.mlbbresearch.core.Layout
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Walls, from the minimap: MLBB draws walls / rocks as dark grey shapes. The minimap is
// cut into a 40 x 40 grid; a cell seen dark on most looks is a wall. Improvements:
//  - icons passing over a cell are ignored (they would hide a wall)
//  - lone dark specks (icon outlines) are not walls
//  - spots where the hero got stuck while walking are added as walls for this match
//  - paths keep a little distance from walls, so the hero does not scrape along them
// Paths are planned around the walls (A*), so the hero walks around rocks instead of into them.
//
// Terrain (v36.14): the minimap ground has 5 shades, measured on a real minimap:
//   lightest grey (about 125-140 brightness, a little green)  = BUSH   (hide, ambush, recall)
//   light grey (about 96-121)                                  = LANE   (lane / tower paths)
//   grey (about 62-95)                                         = JUNGLE
//   dark grey (under 62)                                       = WALL
//   aquamarine blue (green and blue well above red)            = RIVER
// Every cell keeps the class it showed most often; icons passing over it are ignored.
// This works alongside the screen detection (bushes / walls seen on screen).
enum class Terrain { UNKNOWN, WALL, BUSH, LANE, JUNGLE, RIVER }

object WallMap {

    const val G = 40
    private val dark = IntArray(G * G)
    private val seen = IntArray(G * G)
    private val blocked = IntArray(G * G)      // times the hero got stuck walking into this cell
    @Volatile private var wall = BooleanArray(G * G)
    private val bushN = IntArray(G * G)
    private val laneN = IntArray(G * G)
    private val riverN = IntArray(G * G)
    @Volatile private var terrain = Array(G * G) { Terrain.UNKNOWN }
    @Volatile var samples = 0
        private set

    fun reset() {
        dark.fill(0); seen.fill(0); blocked.fill(0); wall = BooleanArray(G * G); samples = 0
        bushN.fill(0); laneN.fill(0); riverN.fill(0); terrain = Array(G * G) { Terrain.UNKNOWN }
    }

    // one minimap pixel -> its terrain shade (null = an icon / text covers the ground there)
    fun classify(p: Int): Terrain? {
        val rr = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val bb = p and 0xff
        val mx = max(rr, max(g, bb)); val mn = min(rr, min(g, bb))
        val lum = (rr * 3 + g * 6 + bb) / 10
        val sat = if (mx < 20) 0f else (mx - mn).toFloat() / mx
        if (g in 80..150 && rr * 100 < g * 62 && bb * 100 in g * 85..g * 120) return Terrain.RIVER
        if (sat > 0.45f && lum > 80) return null
        if (lum < 62 && sat < 0.6f || MinimapColors.matches(MinimapColors.Kind.WALL, p)) return Terrain.WALL
        if (lum >= 122) return if (sat < 0.45f && lum <= 175) Terrain.BUSH else null   // brighter = white text / icons
        if (lum >= 96) return Terrain.LANE
        return Terrain.JUNGLE
    }

    // called now and then with a frame where the minimap is the normal (small) one
    @Synchronized
    fun update(px: IntArray, w: Int, h: Int) {
        val (l, t) = Layout.get("mapTL")
        val (r, b) = Layout.get("mapBR")
        if (r - l < 0.03f || b - t < 0.05f) return
        val next = BooleanArray(G * G)
        for (gy in 0 until G) for (gx in 0 until G) {
            var d = 0; var icon = 0; var bu = 0; var la = 0; var ri = 0
            for (sy in 0 until 2) for (sx in 0 until 2) {
                val x = ((l + (r - l) * (gx + (sx + 0.5f) / 2f) / G) * w).toInt().coerceIn(0, w - 1)
                val y = ((t + (b - t) * (gy + (sy + 0.5f) / 2f) / G) * h).toInt().coerceIn(0, h - 1)
                when (classify(px[y * w + x])) {
                    null -> icon++                                   // an icon covers this spot
                    Terrain.WALL -> d++
                    Terrain.BUSH -> bu++
                    Terrain.LANE -> la++
                    Terrain.RIVER -> ri++
                    else -> {}
                }
            }
            val i = gy * G + gx
            if (icon >= 2) continue                                  // cannot see the ground here now
            seen[i]++
            if (d >= 2) dark[i]++
            if (bu >= 2) bushN[i]++
            if (la >= 2) laneN[i]++
            if (ri >= 2) riverN[i]++
        }
        for (gy in 0 until G) for (gx in 0 until G) {
            val i = gy * G + gx
            next[i] = blocked[i] >= 2 || seen[i] >= 3 && dark[i] * 10 >= seen[i] * 6
        }
        // lone specks are icon outlines, not walls
        for (gy in 0 until G) for (gx in 0 until G) {
            val i = gy * G + gx
            if (!next[i] || blocked[i] >= 2) continue
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val x = gx + dx; val y = gy + dy
                if (x in 0 until G && y in 0 until G && next[y * G + x]) n++
            }
            if (n == 0) next[i] = false
        }
        samples++
        wall = next
        terrain = Array(G * G) { i ->
            val n = seen[i]
            when {
                next[i] -> Terrain.WALL
                n < 3 -> Terrain.UNKNOWN
                bushN[i] * 2 >= n -> Terrain.BUSH
                riverN[i] * 2 >= n -> Terrain.RIVER
                laneN[i] * 2 >= n -> Terrain.LANE
                else -> Terrain.JUNGLE
            }
        }
    }

    fun terrainAt(x: Float, y: Float): Terrain {
        val gx = (x * G).toInt(); val gy = (y * G).toInt()
        if (gx !in 0 until G || gy !in 0 until G) return Terrain.UNKNOWN
        return terrain[gy * G + gx]
    }

    // standing in a bush, by the minimap (the screen check may miss it)
    fun inBush(p: Pair<Float, Float>?): Boolean = p != null && ready && terrainAt(p.first, p.second) == Terrain.BUSH

    fun count(k: Terrain): Int = terrain.count { it == k }

    // The nearest minimap bush (cell centre) within `maxDist` of `from`; with `dirX/dirY` only bushes
    // in that general direction (e.g. the way out when retreating).
    fun nearestBush(from: Pair<Float, Float>, maxDist: Float, dirX: Float = 0f, dirY: Float = 0f): Pair<Float, Float>? {
        if (!ready) return null
        val t = terrain
        var best: Pair<Float, Float>? = null
        var bd = maxDist
        val anyDir = dirX == 0f && dirY == 0f
        for (i in 0 until G * G) {
            if (t[i] != Terrain.BUSH) continue
            val cx = (i % G + 0.5f) / G; val cy = (i / G + 0.5f) / G
            val dx = cx - from.first; val dy = cy - from.second
            val d = hypot(dx, dy)
            if (d >= bd) continue
            if (!anyDir && d > 0.5f / G && (dx * dirX + dy * dirY) / d < 0.3f) continue
            bd = d; best = cx to cy
        }
        return best
    }

    val ready: Boolean get() = samples >= 3

    // The hero got stuck walking from `at` in direction (dx, dy) (minimap units): the cell just
    // ahead is a wall the minimap did not show. Twice = remembered for this match.
    @Synchronized
    fun markBlocked(at: Pair<Float, Float>, dx: Float, dy: Float) {
        val x = ((at.first + dx * 1.5f / G) * G).toInt()
        val y = ((at.second + dy * 1.5f / G) * G).toInt()
        if (x !in 0 until G || y !in 0 until G) return
        val i = y * G + x
        blocked[i]++
        if (blocked[i] >= 2) wall = wall.copyOf().also { it[i] = true }
    }

    fun isWall(x: Float, y: Float): Boolean {
        val gx = (x * G).toInt(); val gy = (y * G).toInt()
        if (gx !in 0 until G || gy !in 0 until G) return true
        return wall[gy * G + gx]
    }

    // Walking distance (minimap units) around the walls; straight distance when unknown
    fun distance(from: Pair<Float, Float>, to: Pair<Float, Float>): Float {
        val straight = hypot(to.first - from.first, to.second - from.second)
        if (!ready) return straight
        val c = search(from, to) ?: return straight
        return c.second / G
    }

    // Direction (in minimap units) to walk from `from` towards `to` around the walls,
    // or null when there is no wall map yet / no path (then walk straight).
    fun direction(from: Pair<Float, Float>, to: Pair<Float, Float>): Pair<Float, Float>? {
        if (!ready) return null
        val (path, _) = search(from, to) ?: return null
        if (path.isEmpty()) return null
        val ahead = path[max(0, path.size - 3)]
        val ax = (ahead % G + 0.5f) / G - from.first
        val ay = (ahead / G + 0.5f) / G - from.second
        val l = hypot(ax, ay)
        return if (l < 1e-4f) null else (ax / l) to (ay / l)
    }

    // A*: the path (goal first) and its length in cells, or null
    private fun search(from: Pair<Float, Float>, to: Pair<Float, Float>): Pair<List<Int>, Float>? {
        val w = wall
        val ter = terrain
        fun cell(p: Pair<Float, Float>) = (p.first * G).toInt().coerceIn(0, G - 1) to (p.second * G).toInt().coerceIn(0, G - 1)
        val (sx, sy) = cell(from)
        val (tx, ty) = free(w, cell(to)) ?: return null
        val start = sy * G + sx
        val goal = ty * G + tx
        if (start == goal) return emptyList<Int>() to 0f
        val cost = FloatArray(G * G) { Float.MAX_VALUE }
        val prev = IntArray(G * G) { -1 }
        val open = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        cost[start] = 0f
        open.add(0f to start)
        while (open.isNotEmpty()) {
            val (_, c) = open.poll()!!
            if (c == goal) break
            val cx = c % G; val cy = c / G
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx; val ny = cy + dy
                if (nx !in 0 until G || ny !in 0 until G) continue
                val n = ny * G + nx
                if (w[n] && n != goal) continue
                // no cutting corners between two walls
                if (dx != 0 && dy != 0 && (w[cy * G + nx] || w[ny * G + cx])) continue
                val step = (if (dx != 0 && dy != 0) 1.414f else 1f) * (if (ter[n] == Terrain.LANE) 0.9f else 1f)
                // keep a little distance from walls (no scraping along rocks)
                var near = 0f
                for (ky in -1..1) for (kx in -1..1) {
                    val qx = nx + kx; val qy = ny + ky
                    if (qx in 0 until G && qy in 0 until G && w[qy * G + qx]) near = 0.6f
                }
                val nc = cost[c] + step + near
                if (nc < cost[n]) {
                    cost[n] = nc
                    prev[n] = c
                    open.add(nc + 0.9f * hypot((tx - nx).toFloat(), (ty - ny).toFloat()) to n)
                }
            }
        }
        if (prev[goal] == -1) return null
        val path = ArrayList<Int>()
        var c = goal
        while (c != start && c != -1) { path.add(c); c = prev[c] }
        return path to cost[goal]
    }

    // Is there a THIN wall (at most 3 cells, about 7% of the map) straight ahead towards `to`,
    // with open ground right behind it? Then a dash, a mobility skill or Flicker can jump it
    // instead of walking all the way around.
    fun thinWallAhead(from: Pair<Float, Float>, dirX: Float, dirY: Float, maxDist: Float = 0.15f): Boolean {
        if (!ready) return false
        val w = wall
        var inWall = 0
        var hit = false
        var d = 0.5f / G
        while (d <= maxDist) {
            val x = ((from.first + dirX * d) * G).toInt(); val y = ((from.second + dirY * d) * G).toInt()
            if (x !in 0 until G || y !in 0 until G) return false
            if (w[y * G + x]) { hit = true; inWall++; if (inWall > 6) return false }   // 6 half-cell steps = 3 cells
            else if (hit) return true            // open ground behind a thin wall
            d += 0.5f / G
        }
        return false
    }

    // nearest free cell to a target that sits on a wall (e.g. a camp drawn on rocks)
    private fun free(w: BooleanArray, c: Pair<Int, Int>): Pair<Int, Int>? {
        for (r in 0..3) for (dy in -r..r) for (dx in -r..r) {
            if (max(abs(dx), abs(dy)) != r) continue
            val x = c.first + dx; val y = c.second + dy
            if (x in 0 until G && y in 0 until G && !w[y * G + x]) return x to y
        }
        return null
    }
}
