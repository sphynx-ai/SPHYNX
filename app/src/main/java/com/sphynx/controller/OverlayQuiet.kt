package com.sphynx.controller

import com.sphynx.vision.ScreenHint

// THE OVERLAY STEPS ASIDE (owner, 2026-11-10): on the LOADING screen and the in-match SCOREBOARD the
// floating window, the "what the AI sees" boxes and the touch marks are all hidden, and come back on
// any other screen. Screen capture records the overlay too: on the scoreboard its boxes and HP labels
// covered the "Computer" names and the practice gate read a real player (device test 2026-11-10).
// Pure logic, unit tested. Hidden at once; shown again only after `showAfterMs` off those screens, so
// one misclassified frame does not make it flicker.
class OverlayQuiet(private val showAfterMs: Long = 600L, private val staleMs: Long = 1500L) {

    companion object {
        fun quietScreen(hint: ScreenHint?): Boolean = hint == ScreenHint.LOADING || hint == ScreenHint.SCOREBOARD
    }

    @Volatile var hidden = false; private set
    @Volatile var hiddenSince = 0L; private set
    private var notQuietSince = 0L

    // hint / frameAt: the latest analysed frame (null = none). Returns true when `hidden` changed.
    @Synchronized fun update(hint: ScreenHint?, frameAt: Long, nowMs: Long): Boolean {
        val quiet = hint != null && nowMs - frameAt <= staleMs && quietScreen(hint)
        if (quiet) {
            notQuietSince = 0L
            if (!hidden) { hidden = true; hiddenSince = nowMs; return true }
            return false
        }
        if (!hidden) return false
        if (notQuietSince == 0L) notQuietSince = nowMs
        if (nowMs - notQuietSince >= showAfterMs) { hidden = false; notQuietSince = 0L; return true }
        return false
    }

    // how long the overlay has been hidden (-1 = it is showing)
    fun hiddenFor(nowMs: Long): Long = if (hidden) nowMs - hiddenSince else -1L
}
