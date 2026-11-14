package com.sphynx.capture

import android.graphics.Bitmap

// The latest screen frame, shared by vision, Gemma and the preview.
// Pixels are kept in one reused array, so there is almost no memory churn.
object FrameHub {
    // Frames are scaled to this width (height follows the screen's shape)
    const val CAPTURE_WIDTH = 800

    @Volatile var source: String = "none"      // "screen capture", "accessibility (1 fps)" or "none"
    @Volatile var fps: Float = 0f
    @Volatile var frameId: Long = 0L
    @Volatile var lastFrameAt: Long = 0L

    private val lock = Any()
    private var pixels = IntArray(0)
    private var width = 0
    private var height = 0
    private var fpsCount = 0
    private var fpsStart = 0L

    // w/h can be smaller than the bitmap (capture buffers have padding on the right)
    fun submit(bmp: Bitmap, w: Int, h: Int, src: String) {
        synchronized(lock) {
            if (pixels.size != w * h) pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            width = w
            height = h
            frameId++
        }
        source = src
        val now = System.currentTimeMillis()
        lastFrameAt = now
        if (fpsStart == 0L) fpsStart = now
        fpsCount++
        if (now - fpsStart >= 1000) {
            fps = fpsCount * 1000f / (now - fpsStart)
            fpsCount = 0
            fpsStart = now
        }
    }

    class Snapshot(val pixels: IntArray, val width: Int, val height: Int, val id: Long)

    // Copies the newest frame into `reuse` (resized if needed). Returns null if nothing new.
    fun snapshot(lastId: Long, reuse: IntArray?): Snapshot? {
        synchronized(lock) {
            if (frameId == lastId || width == 0) return null
            val out = if (reuse != null && reuse.size == pixels.size) reuse else IntArray(pixels.size)
            System.arraycopy(pixels, 0, out, 0, pixels.size)
            return Snapshot(out, width, height, frameId)
        }
    }

    // A fresh Bitmap of the newest frame (for Gemma and the preview)
    fun bitmap(): Bitmap? {
        synchronized(lock) {
            if (width == 0) return null
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }
    }

    fun isLive(): Boolean = System.currentTimeMillis() - lastFrameAt < 2500

    fun reset() {
        fps = 0f
        source = "none"
    }
}
