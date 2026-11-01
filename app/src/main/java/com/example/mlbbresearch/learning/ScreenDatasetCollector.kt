package com.example.mlbbresearch.learning

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.vision.BushDetector
import com.example.mlbbresearch.vision.MinimapInfo
import com.example.mlbbresearch.vision.ScreenDetector
import com.example.mlbbresearch.vision.Seen
import com.example.mlbbresearch.vision.Team
import com.example.mlbbresearch.vision.TowerRing
import com.example.mlbbresearch.vision.VisionResult
import java.util.concurrent.Executors

// Training pictures for the WHOLE-SCREEN detector: every 10 s in a match (200 a day at most) the
// whole picture is saved with boxes ALREADY DRAWN from what the app's colour vision found:
//   heroes (yours / ally / enemy) and minions (ally / enemy)  - from their HP bars
//   monsters BY NAME (JungleDb)                                - monster bars: lord / turtle by the
//     pit you stand by, the orange / purple buff (molten_fiend / thunder_fenrir) by body colour,
//     horned_lizard by colour; other small ones as jungle_creep (rename them in Roboflow)
//   enemy towers (tower_enemy_plated before 5:00)             - the range circle around them
//   bushes and walls                                           - the bush / wall finder's cells
// in Download/MLBB-Research/screen_dataset (images/*.jpg, labels/*.txt, classes.txt), YOLO format.
// These boxes are the app's guesses: checking / fixing them in Roboflow is what makes the trained
// detector better than the colour vision (it then also learns units WITHOUT HP bars, e.g. a
// monster nobody has hit yet, or a hero standing in a bush).
object ScreenDatasetCollector {

    class Box(val cls: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "screen-dataset") }
    private var ctx: Context? = null
    private var lastAt = 0L
    private var quiet = 0
    @Volatile var status = "-"
        private set

    fun init(c: Context) { ctx = c.applicationContext }

    private fun cls(name: String) = ScreenDetector.CLASSES.indexOf(name)

    // monsters / ring: what the COLOUR vision found (never the screen detector's own finds: it
    // would learn its own mistakes back)
    fun boxes(v: VisionResult, mini: MinimapInfo, atBuff: Boolean, matchSec: Int,
              monsters: List<Seen> = v.monsters, ring: TowerRing.Ring = v.towerRing): List<Box> {
        val out = ArrayList<Box>()
        fun add(name: String, x0: Float, y0: Float, x1: Float, y1: Float) {
            val c = cls(name); if (c < 0) return
            val a = x0.coerceIn(0f, 1f); val b = y0.coerceIn(0f, 1f); val cc = x1.coerceIn(0f, 1f); val d = y1.coerceIn(0f, 1f)
            if (cc - a > 0.005f && d - b > 0.01f) out.add(Box(c, a, b, cc, d))
        }
        // a unit's body hangs under its HP bar
        fun unit(name: String, s: Seen, widen: Float, below: Float) {
            val cx = (s.barLeft + s.barRight) / 2
            val bw = ((s.barRight - s.barLeft) * widen).coerceAtLeast(0.03f)
            add(name, cx - bw / 2, s.barTop - 0.01f, cx + bw / 2, s.barBottom + below)
        }
        v.self?.let { unit("hero_self", it, 1.0f, 0.2f) }
        for (a in v.allies) unit("hero_ally", a, 1.0f, 0.2f)
        for (e in v.all.filter { it.team == Team.ENEMY && it.hero }) unit("hero_enemy", e, 1.0f, 0.2f)
        for (m in v.allyMinions) unit("minion_ally", m, 1.4f, 0.09f)
        for (m in v.enemyMinions) unit("minion_enemy", m, 1.4f, 0.09f)
        for (m in monsters) {
            // an enemy tower's own red bar is not a monster
            if (ring.zone != TowerRing.Zone.NONE && kotlin.math.abs((m.barLeft + m.barRight) / 2 - ring.cx) < 0.08f && m.barTop < ring.cy - 0.15f) continue
            val sp = com.example.mlbbresearch.game.JungleDb.byId(m.species) ?: if (m.hero) com.example.mlbbresearch.game.JungleDb.boss(mini, matchSec)
                else com.example.mlbbresearch.game.JungleDb.CREEP
            when (sp.kind) {
                com.example.mlbbresearch.game.MonsterKind.BOSS -> unit(sp.id, m, 1.4f, 0.38f)
                com.example.mlbbresearch.game.MonsterKind.BUFF -> unit(sp.id, m, 1.6f, 0.32f)
                else -> unit(sp.id, m, 1.7f, 0.22f)
            }
        }
        // an enemy tower: its base is the centre of the range circle, the tower stands above it
        // (outer turrets keep a gold energy shield until 5:00)
        val towerName = if (matchSec in 0 until com.example.mlbbresearch.game.JungleDb.OUTER_SHIELD_ENDS_SEC) "tower_enemy_plated" else "tower_enemy"
        if (ring.zone != TowerRing.Zone.NONE && ring.cx in 0f..1f) add(towerName, ring.cx - 0.05f, ring.cy - 0.32f, ring.cx + 0.05f, ring.cy + 0.04f)
        // bushes and walls: groups of the finder's cells
        groups(BushDetector.lastCells, 3).forEach { add("bush", it[0], it[1], it[2], it[3]) }
        groups(BushDetector.lastWalls, 4).forEach { add("wall", it[0], it[1], it[2], it[3]) }
        return out
    }

    // connected groups of grid cells -> boxes (fractions of the screen)
    private fun groups(cells: BooleanArray, minCells: Int): List<FloatArray> {
        val gx = 40; val gy = 20
        if (cells.size != gx * gy) return emptyList()
        val seen = BooleanArray(cells.size)
        val out = ArrayList<FloatArray>()
        for (i0 in cells.indices) {
            if (!cells[i0] || seen[i0]) continue
            val st = ArrayDeque<Int>(); st.add(i0); seen[i0] = true
            var n = 0; var x0 = gx; var x1 = 0; var y0 = gy; var y1 = 0
            while (st.isNotEmpty()) {
                val i = st.removeLast(); n++
                val x = i % gx; val y = i / gx
                x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until gx || ny !in 0 until gy) continue
                    val j = ny * gx + nx
                    if (cells[j] && !seen[j]) { seen[j] = true; st.add(j) }
                }
            }
            if (n >= minCells) out.add(floatArrayOf(x0.toFloat() / gx, y0.toFloat() / gy, (x1 + 1f) / gx, (y1 + 1f) / gy))
        }
        return out
    }

    fun maybeSave(px: IntArray, w: Int, h: Int, v: VisionResult, mini: MinimapInfo, inMatch: Boolean, atBuff: Boolean, matchSec: Int,
                  colourMonsters: List<Seen>, colourRing: TowerRing.Ring) {
        if (!Cfg.collectDataset || !inMatch) return
        val c = ctx ?: return
        val now = System.currentTimeMillis()
        if (now - lastAt < 10_000) return
        val day = (now / 86_400_000L).toString()
        if (Cfg.raw("sds_day") != day) { Cfg.setRaw("sds_day", day); Cfg.setRaw("sds_count", "0") }
        var saved = Cfg.raw("sds_count")?.toIntOrNull() ?: 0
        if (saved >= 200) { status = "200 screen pictures saved today (limit)"; return }
        lastAt = now
        val boxes = boxes(v, mini, atBuff, matchSec, colourMonsters, colourRing)
        // mostly pictures with units / monsters / towers in them; now and then a quiet one
        val busy = boxes.any { ScreenDetector.CLASSES[it.cls] !in setOf("bush", "wall", "hero_self") }
        if (!busy && ++quiet % 4 != 0) return
        val copy = IntArray(w * h) { px[it] or (0xff shl 24) }
        io.execute {
            try {
                val name = "sc_$now"
                val bmp = Bitmap.createBitmap(copy, w, h, Bitmap.Config.ARGB_8888)
                write(c, "images", "$name.jpg", "image/jpeg") { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                val txt = boxes.joinToString("\n") { b ->
                    "%d %.5f %.5f %.5f %.5f".format(java.util.Locale.US, b.cls, (b.x0 + b.x1) / 2, (b.y0 + b.y1) / 2, b.x1 - b.x0, b.y1 - b.y0)
                }
                write(c, "labels", "$name.txt", "text/plain") { it.write(txt.toByteArray()) }
                if (Cfg.raw("sds_classes") != ScreenDetector.CLASSES.joinToString(",")) {
                    write(c, "", "classes.txt", "text/plain") { it.write(ScreenDetector.CLASSES.joinToString("\n").toByteArray()) }
                    Cfg.setRaw("sds_classes", ScreenDetector.CLASSES.joinToString(","))
                }
                saved++
                Cfg.setRaw("sds_count", saved.toString())
                status = "$saved screen pictures saved today (last: ${boxes.size} boxes)"
            } catch (t: Throwable) {
                Diag.error("Screen dataset picture", t)
                status = "saving failed (see Logs)"
            }
        }
    }

    private fun write(c: Context, sub: String, name: String, mime: String, body: (java.io.OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MLBB-Research/screen_dataset" + if (sub.isEmpty()) "" else "/$sub")
        }
        val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("could not create $name")
        c.contentResolver.openOutputStream(uri)?.use(body)
    }
}
