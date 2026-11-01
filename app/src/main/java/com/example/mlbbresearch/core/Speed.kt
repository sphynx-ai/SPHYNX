package com.example.mlbbresearch.core

// Think-to-touch timing, averaged, shown on the Player page and written to the Logs every 15 s:
//   vision  = reading one frame (bars, minimap, HUD...)
//   wait    = from the frame being read to the AI deciding on it
//   decide  = the AI working out what to do (rules, combo, network)
//   touch   = a touch waiting for the previous one to finish on screen
object Speed {
    @Volatile var vision = 0f; private set
    @Volatile var wait = 0f; private set
    @Volatile var decide = 0f; private set
    @Volatile var touch = 0f; private set
    private var loggedAt = 0L

    private fun avg(old: Float, v: Long) = if (old == 0f) v.toFloat() else old * 0.9f + v * 0.1f
    fun vision(ms: Long) { vision = avg(vision, ms) }
    fun touch(ms: Long) { touch = avg(touch, ms) }
    fun decided(waitMs: Long, decideMs: Long) {
        wait = avg(wait, waitMs); decide = avg(decide, decideMs)
        val now = System.currentTimeMillis()
        if (now - loggedAt > 15_000) { loggedAt = now; Diag.info("Speed: " + text()) }
    }
    fun total() = vision + wait + decide + touch
    fun text() = "frame to touch about %.0f ms (vision %.0f, waiting %.0f, deciding %.0f, touch queue %.0f)".format(
        java.util.Locale.US, total(), vision, wait, decide, touch)
}
