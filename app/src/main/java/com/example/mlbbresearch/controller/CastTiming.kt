package com.example.mlbbresearch.controller

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.game.CastMode

object CastTiming {
    // Skill, basic attack, and joystick execution hold timings (v37.09):
    // - Basic attack: firm hold (220 ms standard, 340 ms for continuous basic-attack reliant heroes), re-pressed immediately when button dims
    // - Joystick control: 85 ms continuous slices (willContinue = true) so the thumb never lifts while moving, yet new skills/stops react within ~40 ms
    // - Tap (target-lock skill): press -> hold 0.5s (500 ms) -> release
    // - Aim (short-range skillshot / dash): press -> hold 0.3s (300 ms) -> live re-aim direction while holding 0.3s (300 ms) -> release (600 ms total)
    // - Aim (long-range skillshot): press -> hold 0.3s (300 ms) -> live re-aim direction while holding 0.5s (500 ms) -> release (800 ms total)
    const val ATTACK_HOLD_MS = 220L
    const val CONTINUOUS_ATTACK_HOLD_MS = 340L
    const val JOY_HOLD_MS = 85L
    const val TAP_HOLD_MS = 500L
    const val AIM_PRESS_HOLD_MS = 300L
    const val AIM_SHORT_DIR_HOLD_MS = 300L
    const val AIM_LONG_DIR_HOLD_MS = 500L

    private fun key(hero: String?, slot: String) = "castgap_v3_" + (hero ?: "?").lowercase().filter { it.isLetterOrDigit() } + "_" + slot
    fun gap(hero: String?, slot: String, mode: CastMode, longRange: Boolean = false): Long {
        val minGap = when {
            mode == CastMode.AIM && longRange -> AIM_PRESS_HOLD_MS + AIM_LONG_DIR_HOLD_MS + 35L
            mode == CastMode.AIM -> AIM_PRESS_HOLD_MS + AIM_SHORT_DIR_HOLD_MS + 35L
            else -> TAP_HOLD_MS + 35L
        }
        val saved = Cfg.raw(key(hero, slot))?.toLongOrNull()
        return if (saved != null) saved.coerceIn(minGap, minGap + 160L) else minGap
    }
    fun adjust(hero: String?, slot: String, mode: CastMode, delta: Long) {
        val cur = gap(hero, slot, mode)
        Cfg.setRaw(key(hero, slot), (cur + delta).coerceIn(530L, 960L).toString())
    }
}
