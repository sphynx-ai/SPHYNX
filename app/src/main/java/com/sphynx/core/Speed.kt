package com.sphynx.core

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
    // every raw value also goes to the screen-to-touch recorder (TouchSpeed), which keeps exact samples
    fun vision(ms: Long) { vision = avg(vision, ms); TouchSpeed.log.reading(ms) }
    fun touch(ms: Long) { touch = avg(touch, ms); TouchSpeed.log.touchQueue(ms) }
    fun decided(waitMs: Long, decideMs: Long) {
        wait = avg(wait, waitMs); decide = avg(decide, decideMs)
        TouchSpeed.log.decided(waitMs, decideMs)
        val now = System.currentTimeMillis()
        if (now - loggedAt > 15_000) { loggedAt = now; Diag.info("Speed: " + text()) }
    }
    fun total() = vision + wait + decide + touch
    fun text() = "frame to touch about %.0f ms (vision %.0f, waiting %.0f, deciding %.0f, touch queue %.0f)".format(
        java.util.Locale.US, total(), vision, wait, decide, touch)
}

// The last 5 minutes of every engine's and model's speed, one sample a second (Speed page, v36.86: was 2 min).
object SpeedLog {
    // budget: the value that is still fine (green under 60%, amber up to it, red above);
    // higherIsBetter for frame rates
    class Series(val name: String, val unit: String, val budget: Float, val color: String, val group: String,
                 val higherIsBetter: Boolean = false, val read: () -> Float) {
        val values = FloatArray(300)
        var count = 0; private set
        private var head = 0
        fun add(v: Float) { values[head] = v; head = (head + 1) % values.size; if (count < values.size) count++ }
        fun last(): Float = if (count == 0) 0f else values[(head - 1 + values.size) % values.size]
        fun history(): FloatArray = FloatArray(count) { values[(head - count + it + values.size) % values.size] }
        fun avg(): Float = if (count == 0) 0f else history().average().toFloat()
        fun max(): Float = history().maxOrNull() ?: 0f
        // 0 good, 1 so-so, 2 slow
        fun grade(v: Float = last()): Int = if (higherIsBetter) (if (v >= budget) 0 else if (v >= budget * 0.6f) 1 else 2)
            else (if (v <= budget * 0.6f) 0 else if (v <= budget) 1 else 2)
    }

    val series: List<Series> = listOf(
        Series("Frame to touch", "ms", 150f, "#6D8BFF", "Play loop") { Speed.total() },
        Series("Reading the screen", "ms", 60f, "#3DD68C", "Play loop") { Speed.vision },
        Series("Waiting for the AI", "ms", 50f, "#F4C25B", "Play loop") { Speed.wait },
        Series("Deciding", "ms", 30f, "#9B7CFF", "Play loop") { Speed.decide },
        Series("Touch queue", "ms", 60f, "#FF8A5C", "Play loop") { Speed.touch },
        Series("Screen capture", "fps", 15f, "#2FB3C9", "Frames", higherIsBetter = true) { Probes.captureFps() },
        Series("Frames read", "fps", 12f, "#3DD68C", "Frames", higherIsBetter = true) { Probes.framesReadFps() },
        Series("Tactical network", "ms", 5f, "#6D8BFF", "Trained models") { Probes.tacticalNetworkMs() },
        Series("Minimap detector", "ms", 150f, "#2FB3C9", "Trained models") { Probes.minimapDetectorMs().toFloat() },
        Series("Screen detector", "ms", 200f, "#F4C25B", "Trained models") { Probes.screenDetectorMs().toFloat() },
        Series("Small tactical model", "ms", 350f, "#9B7CFF", "Language models") { Probes.tacticalModelMs().toFloat() },
        Series("Strategic model (Gemma)", "s", 10f, "#FF8A5C", "Language models") { Probes.strategicModelMs() / 1000f },
        Series("Online meta (Groq)", "s", 30f, "#FF5A6A", "Language models") { Probes.onlineMetaMs() / 1000f }
    )

    private var lastTick = 0L

    // called often (the play loop, the Speed page); samples once a second
    @Synchronized
    fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastTick < 1000) return
        lastTick = now
        for (s in series) s.add(try { s.read() } catch (e: Throwable) { 0f })
    }
}
