package com.example.mlbbresearch.core

import android.content.Context
import android.view.WindowManager

// The REAL screen: the whole panel including the camera cut-out area (MLBB draws there too),
// e.g. 2712 x 1220 on a POCO X6 Pro. Screen capture, touches and the overlay all use this
// size, so a fraction of the screen means the same pixel everywhere. (Android's "display
// metrics" can leave the cut-out out, which shifted the overlay and the taps sideways.)
object ScreenGeom {
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set

    fun update(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val b = wm?.maximumWindowMetrics?.bounds
        if (b != null && b.width() > 0 && b.height() > 0) {
            width = b.width(); height = b.height()
        } else {
            val m = ctx.resources.displayMetrics
            width = m.widthPixels; height = m.heightPixels
        }
        return width to height
    }

    fun size(ctx: Context): Pair<Int, Int> = update(ctx)     // cheap; follows rotation

    fun describe(ctx: Context): String {
        val (w, h) = update(ctx)
        val m = ctx.resources.displayMetrics
        return "${w} x $h pixels (whole screen incl. camera cut-out); Android's usable area ${m.widthPixels} x ${m.heightPixels}"
    }
}
