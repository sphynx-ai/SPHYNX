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

// Training pictures for the WHOLE-SCREEN detector: ONLY about 1 s after a skill or a basic attack
// (v36.53), when the effects have faded, with the app's own drawings hidden - clean pictures of the
// creeps / heroes being fought. A monster shows its HP bar only once it is hit, which is exactly
// when its box can be drawn (zipped when full, see DatasetZipper) the
// whole picture is saved with boxes ALREADY DRAWN from what the app's colour vision found:
//   heroes (yours / ally / enemy) and minions (ally / enemy)  - from their HP bars
//   monsters BY NAME (JungleDb)                                - monster bars: lord / turtle by the
//     pit you stand by, the orange / purple buff (molten_fiend / thunder_fenrir) by body colour,
//     horned_lizard by colour; other small ones as jungle_creep (rename them in Roboflow)
//   enemy towers (tower_enemy_plated before 5:00)             - the range circle around them
//   bushes and walls                                           - the bush / wall finder's cells
// in Download/Sphynx-Auto-Pilot-Storage/screen_dataset (images/*.jpg, labels/*.txt, classes.txt), YOLO format.
// These boxes are the app's guesses: checking / fixing them in Roboflow is what makes the trained
// detector better than the colour vision (it then also learns units WITHOUT HP bars, e.g. a
// monster nobody has hit yet, or a hero standing in a bush).
object ScreenDatasetCollector {

    class Box(val cls: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "screen-dataset") }
    private var ctx: Context? = null
    private var lastAt = 0L
    private var shotAt = 0L
    private var lastSkillAt = 0L
    private var lastHitAt = 0L
    private var lastTargetHp = -1f
    private var lastTargetN = 0
    private val btnBase = HashMap<String, Float>()
    private val btnPrev = HashMap<String, Float>()
    private val speciesAt = HashMap<String, Long>()
    @Volatile var attackShots = 0
        private set
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
        if (!Cfg.collectScreenDataset || !inMatch) return
        val c = ctx ?: return
        val now = System.currentTimeMillis()
        // ---- WHEN (v36.53): only ~1 s after a skill or a basic attack, once its effects have faded ----
        // A skill (yours or the AI's: its button goes dark) is followed by a picture when its effects
        // are gone: 1.0 s after a skill / the spell, 1.5 s after the ultimate; a newer skill restarts
        // the wait (no picture full of effects). Hitting something (a target's HP bar dropping, or the
        // AI's basic attack) gives a picture 1 s later. Nothing else is pictured.
        for ((k, br) in v.skillBrightness) {
            if (k !in setOf("s1", "s2", "ult", "spell")) continue
            val base = maxOf(btnBase[k] ?: br, br).let { if (br < it) it * 0.995f + br * 0.005f else it }
            btnBase[k] = base
            val prev = btnPrev[k] ?: br; btnPrev[k] = br
            val aiCast = com.example.mlbbresearch.controller.ActionExecutor.aiCastAgo(k) < 600
            if (v.self != null && base > 40f && (aiCast || br < base * 0.72f && prev >= base * 0.82f)) {
                if (now - lastSkillAt > 300) {
                    lastSkillAt = now
                    // (only when a picture may be taken by then: no hiding the app's drawings for nothing)
                    if (now - lastAt >= 1_500L) {
                        shotAt = now + if (k == "ult") 1500L else 1000L      // effects first fade
                        com.example.mlbbresearch.controller.OverlayManager.requestClean(shotAt - 400, shotAt + 600)
                    } else shotAt = 0L
                }
            }
        }
        val targets = colourMonsters + v.all.filter { it.team == Team.ENEMY }
        val hpNow = targets.sumOf { it.hp.toDouble() }.toFloat()
        val hit = targets.isNotEmpty() && targets.size == lastTargetN && lastTargetHp >= 0f && hpNow < lastTargetHp - 0.005f
        lastTargetHp = if (targets.isEmpty()) -1f else hpNow; lastTargetN = targets.size
        val aiAttacking = com.example.mlbbresearch.controller.ActionExecutor.aiCastAgo("attack") < 300
        if ((hit || aiAttacking && targets.isNotEmpty()) && shotAt == 0L && now - lastSkillAt > 1000 && now - lastAt >= 1_500L) {
            shotAt = now + 1000L
            com.example.mlbbresearch.controller.OverlayManager.requestClean(shotAt - 400, shotAt + 600)
        }
        val attacking = hit || aiAttacking
        if (attacking) lastHitAt = now
        if (shotAt == 0L || now < shotAt) return
        if (now - shotAt > 700) { shotAt = 0L; return }                   // the moment passed
        // clean: the app's drawings hidden, no new skill since, no kill banner, the camera not panning
        if (!com.example.mlbbresearch.controller.OverlayManager.cleanNow() || now - lastSkillAt < 900) return
        if (kotlin.math.abs(v.hud.motionX) + kotlin.math.abs(v.hud.motionY) > 0.35f) return
        if (com.example.mlbbresearch.vision.ScreenReader.announcement(px, w, h)) return
        shotAt = 0L
        val kinds = colourMonsters.map { it.species.ifEmpty { if (it.hero) "boss" else "creep" } }.toSet()
        val newKind = kinds.any { now - (speciesAt[it] ?: 0L) > 8_000 }
        if (now - lastAt < (if (newKind) 2_500L else 5_000L)) return
        val day = (now / 86_400_000L).toString()
        if (Cfg.raw("sds_day") != day) { Cfg.setRaw("sds_day", day); Cfg.setRaw("sds_count", "0") }
        var saved = Cfg.raw("sds_count")?.toIntOrNull() ?: 0
        lastAt = now
        val boxes = boxes(v, mini, atBuff, matchSec, colourMonsters, colourRing)
        // only pictures with units / monsters / towers in them
        if (boxes.none { ScreenDetector.CLASSES[it.cls] !in setOf("bush", "wall", "hero_self") }) return
        val recentHit = now - lastHitAt < 2500
        if (recentHit) { attackShots++; for (k in kinds) speciesAt[k] = now }
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
                DatasetZipper.saved(c, DatasetZipper.Kind.SCREEN)
                status = "${DatasetZipper.count(DatasetZipper.Kind.SCREEN)} of ${DatasetZipper.Kind.SCREEN.capacity} pictures " +
                    "(zipped at ${DatasetZipper.Kind.SCREEN.capacity}; last: ${boxes.size} boxes" +
                    (if (recentHit) ", 1 s after hitting ${kinds.joinToString().ifEmpty { "an enemy" }}" else ", after a skill") + "; $attackShots while attacking this session)"
            } catch (t: Throwable) {
                Diag.error("Screen dataset picture", t)
                status = "saving failed (see Logs)"
            }
        }
    }

    private fun write(c: Context, sub: String, name: String, mime: String, body: (java.io.OutputStream) -> Unit) {
        // v37.19: straight into the app's folder when all files access is on
        com.example.mlbbresearch.core.Storage.dir("screen_dataset" + if (sub.isEmpty()) "" else "/$sub")?.let { d ->
            java.io.File(d, name).outputStream().use(body); return
        }
        val rel = com.example.mlbbresearch.core.Storage.downloadsRel("screen_dataset") + if (sub.isEmpty()) "" else "/$sub"
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
