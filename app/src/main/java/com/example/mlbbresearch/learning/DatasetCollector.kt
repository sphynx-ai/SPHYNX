package com.example.mlbbresearch.learning

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.core.Layout
import com.example.mlbbresearch.vision.MinimapAnalyzer
import com.example.mlbbresearch.vision.MinimapInfo
import com.example.mlbbresearch.vision.ObjectDetector
import java.util.concurrent.Executors
import kotlin.math.hypot

// Builds a TRAINING SET for the minimap detector while you play: every few seconds it saves a
// picture of the minimap plus a label file with boxes ALREADY DRAWN from what the app finds by
// colour (your icon, red enemy portraits, blue ally portraits, towers) - and from the detector
// itself when one is loaded. YOLO format, same class names as the detector (labels.txt), so it
// can be mixed with the Roboflow dataset:
//   Download/Sphynx-Auto-Pilot-Storage/dataset/images/mm_<time>.png
//   Download/Sphynx-Auto-Pilot-Storage/dataset/labels/mm_<time>.txt      (class  x  y  w  h, 0..1)
//   Download/Sphynx-Auto-Pilot-Storage/dataset/classes.txt
// Upload the folder to Roboflow to check / fix the boxes (much faster than drawing them), or
// zip it and give it straight to notebooks/train_mlbb_detector.ipynb.
// At most one picture every 6 s (zipped at 1500, see DatasetZipper), only in a match with your hero on screen.
object DatasetCollector {

    class Box(val cls: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dataset") }
    private var ctx: Context? = null
    private var lastAt = 0L
    @Volatile var savedToday = 0
        private set
    @Volatile var status = "-"
        private set

    fun init(c: Context) { ctx = c.applicationContext }

    // the detector's classes (labels.txt) plus lord and turtle when it does not have them yet:
    // the pictures then teach a new detector those two as well
    fun classes(): List<String> {
        val l = Cfg.detectorLabels.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (l.none { it.lowercase().contains("lord") }) l.add("lord_icon")
        if (l.none { it.lowercase().contains("turtle") }) l.add("turtle_icon")
        return l
    }

    private fun classIndex(labels: List<String>, kind: String): Int = labels.indexOfFirst { l ->
        val n = l.lowercase()
        when (kind) {
            "hero" -> n.contains("hero") && !n.contains("turr")
            "turret" -> n.contains("turr") && !n.contains("hero")
            else -> n.contains(kind)
        }
    }

    // boxes inside the crop (MinimapAnalyzer.rect), 0..1 of the crop
    // Hero portraits on the minimap are round pictures with a coloured RING: red = enemy,
    // blue = ally, green = you. Found by testing ring positions of the portrait's size: most of the
    // circle must be the ring colour while the middle is not. Returns screen x, y and the team.
    fun rings(px: IntArray, w: Int, h: Int, radiusFrac: Float): List<Triple<Float, Float, Int>> {
        val r = MinimapAnalyzer.rect
        val x0 = (r[0] * w).toInt(); val y0 = (r[1] * h).toInt(); val x1 = (r[2] * w).toInt(); val y1 = (r[3] * h).toInt()
        fun team(p: Int): Int {
            val rr = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            return when {
                rr >= 170 && rr - g >= 80 && rr - b >= 25 -> 1                      // red / pink ring: enemy
                b >= 170 && b - rr >= 70 && g >= 90 -> 2                            // blue ring: ally
                g >= 170 && g - rr >= 50 && g - b >= 40 -> 3                         // green ring: you
                else -> 0
            }
        }
        val found = ArrayList<FloatArray>()      // x, y, team, score
        val n = 24
        for (rad in floatArrayOf(radiusFrac * 0.9f, radiusFrac, radiusFrac * 1.12f)) {
            val rp = rad * w
            var y = y0 + rp.toInt()
            while (y < y1 - rp) {
                var x = x0 + rp.toInt()
                while (x < x1 - rp) {
                    val cnt = IntArray(4)
                    for (k in 0 until n) {
                        val a = k * Math.PI * 2 / n
                        val sx = (x + Math.cos(a) * rp).toInt(); val sy = (y + Math.sin(a) * rp).toInt()
                        cnt[team(px[sy * w + sx])]++
                    }
                    val t = (1..3).maxByOrNull { cnt[it] }!!
                    val sc = cnt[t].toFloat() / n
                    if (sc >= 0.5f && team(px[y * w + x]) != t) {
                        // a RING: just outside it the colour is gone again (a glowing lane or a big
                        // blue area is the colour everywhere)
                        var outside = 0; var m = 0
                        for (k in 0 until 16) {
                            val a = k * Math.PI * 2 / 16
                            val sx = (x + Math.cos(a) * rp * 1.6).toInt(); val sy = (y + Math.sin(a) * rp * 1.6).toInt()
                            if (sx !in 0 until w || sy !in 0 until h) continue
                            m++; if (team(px[sy * w + sx]) == t) outside++
                        }
                        if (m > 0 && outside <= m / 4) found.add(floatArrayOf(x.toFloat() / w, y.toFloat() / h, t.toFloat(), sc))
                    }
                    x += 1
                }
                y += 1
            }
        }
        // keep the best ring in each spot
        val out = ArrayList<FloatArray>()
        for (f in found.sortedByDescending { it[3] }) {
            if (out.none { hypot((it[0] - f[0]) * w, (it[1] - f[1]) * h) < radiusFrac * w * 1.3f }) out.add(f)
        }
        return out.map { Triple(it[0], it[1], it[2].toInt()) }
    }

    fun boxes(w: Int, h: Int, mini: MinimapInfo, labels: List<String>, px: IntArray? = null): List<Box> {
        val r = MinimapAnalyzer.rect
        val rw = r[2] - r[0]; val rh = r[3] - r[1]
        val (ml, mt) = Layout.get("mapTL")
        val (mr, mb) = Layout.get("mapBR")
        val heroCls = classIndex(labels, "hero")
        val turretCls = classIndex(labels, "turret")
        // a hero portrait is about as wide as your own icon's ring
        val iconW = (mini.selfIcon?.get(2)?.times(2f) ?: 0.0155f).coerceIn(0.012f, 0.03f)    // fraction of screen width (about 12 px at 800)
        val out = ArrayList<Box>()
        fun add(cls: Int, sx: Float, sy: Float, bw: Float) {
            if (cls < 0) return
            val x = (sx - r[0]) / rw; val y = (sy - r[1]) / rh
            if (x !in 0.02f..0.98f || y !in 0.02f..0.98f) return
            val bwRel = bw / rw; val bhRel = bw * w / h / rh
            if (out.any { it.cls == cls && hypot(it.x - x, it.y - y) < bwRel * 0.6f }) return
            out.add(Box(cls, x, y, bwRel, bhRel))
        }
        // the trained detector's own boxes first (when loaded and fresh)
        if (ObjectDetector.ready && System.currentTimeMillis() - ObjectDetector.latestAt < 800) {
            for (d in ObjectDetector.latest) {
                val c = labels.indexOf(d.label)
                if (c >= 0) {
                    val x = (d.cx - r[0]) / rw; val y = (d.cy - r[1]) / rh
                    out.add(Box(c, x, y, (d.right - d.left) / rw, (d.bottom - d.top) / rh))
                }
            }
        }
        // hero portraits (enemy / ally / you) by their coloured ring
        val heroRings = if (px != null) rings(px, w, h, iconW / 2f) else emptyList()
        for ((sx, sy, _) in heroRings) {
            // only on the map itself (not the buttons around it)
            if (sx < ml - 0.005f || sx > mr + 0.005f || sy < mt - 0.01f || sy > mb + 0.01f) continue
            add(heroCls, sx, sy, iconW * 1.1f)
        }
        mini.selfIcon?.let { add(heroCls, it[0], it[1] * w / h, iconW * 1.1f) }
        val allyTowersScreen = mini.allyTowers.map { (ml + it.first * (mr - ml)) to (mt + it.second * (mb - mt)) }
        // the lord and the turtle in their pits (when alive)
        val lordCls = classIndex(labels, "lord"); val turtleCls = classIndex(labels, "turtle")
        for ((bx, cls) in listOf(mini.lordIcon to lordCls, mini.turtleIcon to turtleCls)) {
            if (bx == null || cls < 0) continue
            val x = ((bx[0] + bx[2]) / 2 - r[0]) / rw; val y = ((bx[1] + bx[3]) / 2 - r[1]) / rh
            if (out.none { it.cls == cls }) out.add(Box(cls, x, y, (bx[2] - bx[0]) / rw, (bx[3] - bx[1]) / rh))
        }
        // towers (both sides): a little smaller than a portrait
        for (t in mini.towers) add(turretCls, ml + t.x * (mr - ml), mt + t.y * (mb - mt), iconW * 0.7f)
        for (t in allyTowersScreen) add(turretCls, t.first, t.second, iconW * 0.7f)
        return out
    }

    // called on every frame; does the work only every 6 s
    fun maybeSave(px: IntArray, w: Int, h: Int, mini: MinimapInfo, inMatch: Boolean) {
        if (!Cfg.collectDataset || !inMatch) return
        val c = ctx ?: return
        val now = System.currentTimeMillis()
        if (now - lastAt < 6000) return
        val day = (now / 86_400_000L).toString()
        if (Cfg.raw("ds_day") != day) { Cfg.setRaw("ds_day", day); Cfg.setRaw("ds_count", "0") }
        savedToday = Cfg.raw("ds_count")?.toIntOrNull() ?: 0
        lastAt = now
        val labels = classes()
        val boxes = boxes(w, h, mini, labels, px)
        if (boxes.isEmpty()) return
        // copy the minimap pixels now (the frame buffer is reused)
        val r = MinimapAnalyzer.rect
        val x0 = (r[0] * w).toInt(); val y0 = (r[1] * h).toInt()
        val cw = ((r[2] - r[0]) * w).toInt().coerceAtMost(w - x0); val ch = ((r[3] - r[1]) * h).toInt().coerceAtMost(h - y0)
        val crop = IntArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) crop[y * cw + x] = px[(y0 + y) * w + x0 + x] or (0xff shl 24)
        io.execute {
            try {
                val name = "mm_$now"
                val bmp = Bitmap.createBitmap(crop, cw, ch, Bitmap.Config.ARGB_8888)
                write(c, "images", "$name.png", "image/png") { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val txt = boxes.joinToString("\n") { b -> "%d %.5f %.5f %.5f %.5f".format(java.util.Locale.US, b.cls, b.x, b.y, b.w, b.h) }
                write(c, "labels", "$name.txt", "text/plain") { it.write(txt.toByteArray()) }
                if (Cfg.raw("ds_classes") != labels.joinToString(",")) {
                    write(c, "", "classes.txt", "text/plain") { it.write(labels.joinToString("\n").toByteArray()) }
                    Cfg.setRaw("ds_classes", labels.joinToString(","))
                }
                savedToday++
                Cfg.setRaw("ds_count", savedToday.toString())
                DatasetZipper.saved(c, DatasetZipper.Kind.MINIMAP)
                status = "${DatasetZipper.count(DatasetZipper.Kind.MINIMAP)} of ${DatasetZipper.Kind.MINIMAP.capacity} pictures " +
                    "(zipped at ${DatasetZipper.Kind.MINIMAP.capacity}; last: ${boxes.size} boxes)"
            } catch (t: Throwable) {
                Diag.error("Dataset picture", t)
                status = "saving failed (see Logs)"
            }
        }
    }

    private fun write(c: Context, sub: String, name: String, mime: String, body: (java.io.OutputStream) -> Unit) {
        // v37.19: straight into the app's folder when all files access is on
        com.example.mlbbresearch.core.Storage.dir("dataset" + if (sub.isEmpty()) "" else "/$sub")?.let { d ->
            java.io.File(d, name).outputStream().use(body); return
        }
        val rel = com.example.mlbbresearch.core.Storage.downloadsRel("dataset") + if (sub.isEmpty()) "" else "/$sub"
        // a file of that name may already be there (classes.txt from an earlier run, a re-install):
        // overwrite it instead of failing on the duplicate name
        val existing = try {
            c.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                arrayOf(name, "$rel%"), null)?.use { cur ->
                if (cur.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(0)) else null
            }
        } catch (e: Exception) { null }
        if (existing != null) {
            try { c.contentResolver.openOutputStream(existing, "wt")?.use(body); return } catch (e: Exception) { }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, rel)
        }
        val uri = try { c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) } catch (e: android.database.sqlite.SQLiteConstraintException) { null }
            ?: return    // the name is taken by a file this app cannot see (e.g. left from a re-install): skip it
        c.contentResolver.openOutputStream(uri)?.use(body)
    }
}
