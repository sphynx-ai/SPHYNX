package com.example.mlbbresearch.controller

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.game.CastMode

object CastTiming {
    // Fast Skill, Basic Attack, and Joystick execution hold timings (v37.16):
    // - Basic attack: snappy hold (140 ms standard, 220 ms for continuous basic-attack reliant heroes), re-pressed immediately when button dims
    // - Joystick control: continuous slices (willContinue = true) so the thumb never lifts while moving, yet new skills/stops react within ~35 ms
    // - Tap (target-lock skill): press -> hold 0.15s (150 ms) -> release
    // - Aim (short-range skillshot / dash): press -> hold 0.115s (115 ms) -> live re-aim direction while holding 0.135s (135 ms) -> release (250 ms total)
    // - Aim (long-range skillshot): press -> hold 0.115s (115 ms) -> live re-aim direction while holding 0.21s (210 ms) -> release (325 ms total)
    const val ATTACK_HOLD_MS = 140L
    const val CONTINUOUS_ATTACK_HOLD_MS = 220L
    const val JOY_HOLD_MS = 320L
    // A HELD joystick continues in short slices (owner, 2026-11-12 - the 2026-11-11 recording: screen to touch ~280 ms,
    // of which ~240 ms was the touch queue: every joystick slice was held 320 ms and a new decision waited for it
    // to end). The first press keeps JOY_HOLD_MS so the game registers the drag; after that 120 ms slices.
    const val JOY_SLICE_MS = 120L

    // how long the joystick stroke of one gesture lasts: held = continuing a stroke already down;
    // otherMs = the longest other action in the same gesture (a skill hold, a tap)
    fun joyDuration(held: Boolean, joyMs: Long, otherMs: Long): Long =
        if (held) maxOf(otherMs, JOY_SLICE_MS) else maxOf(joyMs, otherMs, JOY_HOLD_MS)
    const val TAP_HOLD_MS = 150L
    const val AIM_PRESS_HOLD_MS = 115L
    const val AIM_SHORT_DIR_HOLD_MS = 135L
    const val AIM_LONG_DIR_HOLD_MS = 210L

    private fun key(hero: String?, slot: String) = "castgap_v4_" + (hero ?: "?").lowercase().filter { it.isLetterOrDigit() } + "_" + slot
    fun gap(hero: String?, slot: String, mode: CastMode, longRange: Boolean = false): Long {
        val minGap = when {
            mode == CastMode.AIM && longRange -> AIM_PRESS_HOLD_MS + AIM_LONG_DIR_HOLD_MS + 20L
            mode == CastMode.AIM -> AIM_PRESS_HOLD_MS + AIM_SHORT_DIR_HOLD_MS + 20L
            else -> TAP_HOLD_MS + 20L
        }
        val saved = Cfg.raw(key(hero, slot))?.toLongOrNull()
        return if (saved != null) saved.coerceIn(minGap, minGap + 120L) else minGap
    }
    fun adjust(hero: String?, slot: String, mode: CastMode, delta: Long) {
        val cur = gap(hero, slot, mode)
        Cfg.setRaw(key(hero, slot), (cur + delta).coerceIn(170L, 620L).toString())
    }
}
