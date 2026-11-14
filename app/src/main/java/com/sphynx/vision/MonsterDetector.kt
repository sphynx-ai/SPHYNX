package com.sphynx.vision

import com.sphynx.core.Cfg

// Neutral monsters (jungle creeps, turtle, lord) have RED HP bars like enemy heroes; they are
// told apart by the missing level badge (see FrameProcessor.monstersFromRedBars).
//
// Older versions LEARNED a "monster bar colour" from bar-shaped marks next to your hero. That
// went wrong in play: it learned the teal of grass, leaves or your own hero, and then every bush,
// wall and your hero's body showed up as monsters (pink boxes), so the AI attacked them and
// stood at the "camp" forever. The learned colour is no longer used and is wiped on start.
object MonsterDetector {

    @Volatile private var wiped = false

    val learnedText: String get() = "red bars without a level badge"

    // per pixel: is this a monster HP bar colour? (no separate colour: monsters are red bars)
    fun matches(r: Int, g: Int, b: Int): Boolean {
        if (!wiped) {
            wiped = true
            try { if (Cfg.raw("monster_rgb") != null) Cfg.setRaw("monster_rgb", null) } catch (_: Throwable) {}
        }
        return false
    }

    fun forget() = Cfg.setRaw("monster_rgb", null)
}
