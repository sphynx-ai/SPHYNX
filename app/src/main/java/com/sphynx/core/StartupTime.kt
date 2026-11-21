package com.sphynx.core

// HOW LONG THE APP TAKES TO START (Phase 5, P7; audit finding F6). Measured first, on the phone, before any start-up
// work is moved off the main thread: Android reports "not responding" after about 5 s on the main thread, and a late
// first frame is felt long before that. One line per start in the diagnostics log. Pure, unit-tested.
object StartupTime {

    const val SLOW_MS = 1_000L

    fun line(fromProcessMs: Long, onCreateMs: Long): String =
        "Start-up: ${fromProcessMs} ms from the process start, ${onCreateMs} ms in Application.onCreate" +
            if (onCreateMs >= SLOW_MS) " (slow: over ${SLOW_MS} ms on the main thread)" else ""
}
