package com.example.mlbbresearch.vision

import com.example.mlbbresearch.core.Cfg
import kotlin.math.abs

// Minimap icon calibration: colours you picked by tapping icons on the game (Setup page).
// Each kind keeps up to 4 samples; a minimap pixel close to one of them counts as that
// kind, on top of the built-in colour rules.
object MinimapColors {

    enum class Kind(val label: String) {
        SELF("My icon (green ring)"),
        ENEMY_TOWER("Enemy tower (red)"),
        BONUS_TOWER("Tower with bonus gold (yellow)"),
        ALLY_TOWER("My team's tower (blue)"),
        BUFF("Buff camp (purple)"),
        CREEP("Small camp (green dot)"),
        WALL("Wall (dark)")
    }

    private val cache = HashMap<Kind, List<IntArray>>()

    fun forgetCache() = cache.clear()

    private fun key(k: Kind) = "mmcol_" + k.name.lowercase()

    @Synchronized
    fun samples(k: Kind): List<IntArray> = cache.getOrPut(k) {
        (Cfg.raw(key(k)) ?: "").split(";").mapNotNull { s ->
            s.split(",").mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 3 }?.toIntArray()
        }
    }

    @Synchronized
    fun add(k: Kind, rgb: IntArray) {
        val list = (samples(k) + listOf(rgb)).takeLast(4)
        cache[k] = list
        Cfg.setRaw(key(k), list.joinToString(";") { "${it[0]},${it[1]},${it[2]}" })
    }

    @Synchronized
    fun clear(k: Kind) {
        cache[k] = emptyList()
        Cfg.setRaw(key(k), null)
    }

    // per pixel; cheap when nothing was calibrated
    fun matches(k: Kind, p: Int): Boolean {
        val list = cache[k] ?: samples(k)
        if (list.isEmpty()) return false
        val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
        for (c in list) if (abs(r - c[0]) + abs(g - c[1]) + abs(b - c[2]) <= (if (k == Kind.WALL) 30 else 55)) return true
        return false
    }
}
