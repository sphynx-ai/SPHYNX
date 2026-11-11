package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import kotlin.math.abs
import kotlin.math.hypot

// Settings the app works out by itself from your matches (nothing to type):
//  - the enemy tower range on the minimap: the moment you step into a tower's circle on screen
//    (it turns red / yellow and you are inside), your distance to that tower on the minimap is
//    the range
//  - seconds from base to your lane: from (re)spawning until your icon reaches your lane
object AutoTune {

    @Volatile var status = "-"
        private set
    private var wasInside = false
    private var laneTimedSpawn = 0L

    // BUTTON POSITIONS: the first time a match (or hero select) is seen, the buttons are found on
    // the screen by themselves (the same detection as Setup's calibration) - only buttons you never
    // placed yourself, so a position you set by hand is never moved
    private val calibrated = HashSet<String>()
    @Volatile var calStatus = "-"
        private set

    fun autoCalibrate(phase: Phase) {
        val kind = when (phase) { Phase.IN_GAME -> "game"; Phase.DRAFT, Phase.HERO_LOCKED -> "select"; else -> return }
        if (kind in calibrated || Cfg.activePreset.isNotEmpty()) return
        calibrated.add(kind)
        val snap = com.example.mlbbresearch.capture.FrameHub.snapshot(-1L, null) ?: run { calibrated.remove(kind); return }
        Thread {
            try {
                val hint = com.example.mlbbresearch.vision.ScreenReader.classify(snap.pixels, snap.width, snap.height)
                val r = com.example.mlbbresearch.vision.ButtonDetector.detect(snap.pixels, snap.width, snap.height, hint)
                var n = 0
                for ((k, p) in r.found) if (Cfg.raw("cal_$k") == null) { com.example.mlbbresearch.core.Layout.setPoint(k, p.first, p.second); n++ }
                calStatus = "buttons found by themselves on the $kind screen: $n placed" + (if (r.found.size > n) " (${r.found.size - n} you placed are kept)" else "")
                Diag.info("Auto calibration ($kind): " + r.report.replace("\n", " | "))
            } catch (e: Exception) { Diag.error("Auto calibration", e) }
        }.start()
    }

    fun observe(s: GameState) {
        if (s.phase != Phase.IN_GAME) return
        towerRange(s)
        laneTravel(s)
    }

    private fun towerRange(s: GameState) {
        val ring = s.towerRing
        val inside = ring.inside && (ring.zone == com.example.mlbbresearch.vision.TowerRing.Zone.RED ||
            ring.zone == com.example.mlbbresearch.vision.TowerRing.Zone.YELLOW || ring.zone == com.example.mlbbresearch.vision.TowerRing.Zone.GREEN)
        val me = s.minimapSelf
        if (inside && !wasInside && me != null) {
            val d = s.towers.minOfOrNull { hypot(it.x - me.first, it.y - me.second) }
            if (d != null && d in 0.03f..0.2f) {
                val pct = d * 100f
                val old = Cfg.towerRangePct.toFloat()
                val n = (old * 0.7f + pct * 0.3f + 0.5f).toInt().coerceIn(3, 20)
                if (n != Cfg.towerRangePct) { Cfg.towerRangePct = n; Diag.info("Enemy tower range learned: $n% of the minimap") }
                status = "tower range ${Cfg.towerRangePct}% (learned)"
            }
        }
        wasInside = inside
    }

    private fun laneTravel(s: GameState) {
        val me = s.minimapSelf ?: return
        val since = s.sinceSpawnMs
        if (since > 90_000L || since < 2000L) return
        val spawn = s.ts - since
        if (abs(spawn - laneTimedSpawn) < 5000) return            // this spawn is already timed
        val onLane = when (MapSide.geo(s.lane)) {        // the top / bottom lane (MapSide: which is EXP / Gold)
            Lane.EXP -> me.first < 0.14f && me.second < 0.6f || me.second < 0.14f
            Lane.GOLD -> me.second > 0.86f && me.first > 0.4f || me.first > 0.86f
            Lane.MID -> abs(me.first + me.second - 1f) < 0.1f && me.first > 0.3f
            else -> return
        }
        if (!onLane) return
        laneTimedSpawn = spawn
        val sec = (since / 1000L).toInt()
        val n = ((Cfg.laneTravelSec * 0.6f + sec * 0.4f) + 0.5f).toInt().coerceIn(3, 60)
        Cfg.laneTravelSec = n
        status = "base to lane ${n}s (learned)"
    }
}
