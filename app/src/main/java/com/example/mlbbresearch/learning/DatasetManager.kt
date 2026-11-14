package com.example.mlbbresearch.learning

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.example.mlbbresearch.core.Diag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Export or delete the learning data. Exports go to Download/Sphynx-Storage as CSV,
// ready for training a policy model later.
object DatasetManager {

    fun export(ctx: Context, done: (String) -> Unit) = exportTable(
        ctx, "experiences", "mlbb-experiences",
        "id,ts,hero,intent,actions,self_hp,allies,enemies,minions,target_id,target_hp,plan,aggression,reward", done
    )

    // your recorded play (Coach): features = the 17 numbers of Features.of, features2 = the 32 of
    // Features2.of (empty in rows recorded before v25), casts a bit mask. Train the tactical
    // network on it with notebooks/train_tactical_policy.ipynb.
    fun exportDemos(ctx: Context, done: (String) -> Unit) = exportTable(
        ctx, "demos", "mlbb-my-play", "id,ts,hero,features,move_x,move_y,casts,features2,died", done, heroType = true
    )

    // live matches you watched: the same columns as mlbb-my-play (the notebook reads both), plus
    // source = live, the watched player's role and the match time (-1 = clock not read)
    fun exportLive(ctx: Context, done: (String) -> Unit) = exportTable(
        ctx, "live_demos", "mlbb-live", "id,ts,hero,features,move_x,move_y,casts,features2,source,lane,match_sec,died", done, heroType = true
    )

    // fight moments for the small tactical model (notebooks/train_tactical_llm.ipynb): the summary
    // it is shown, the option played, who chose it (1 = you) and how it went (the Brain's score)
    fun exportFights(ctx: Context, done: (String) -> Unit) = exportTable(
        ctx, "fights", "mlbb-fights", "id,ts,hero,lane,summary,option,by_you,reward,died", done
    )

    // heroType: adds "hero_type" - the hero's 11 type numbers (role, ranged, starts fights, protects,
    // late / early game; PolicyNet.HeroType), so the notebook's all-heroes network knows what hero it is
    private fun exportTable(ctx: Context, table: String, prefix: String, cols: String, done: (String) -> Unit, heroType: Boolean = false) {
        ExperienceRecorder.onDb({ db ->
            val sb = StringBuilder(cols).append(if (heroType) ",hero_type" else "").append('\n')
            val heroCol = cols.split(",").indexOf("hero")
            val typeCache = HashMap<String, String>()
            var rows = 0
            db.rawQuery("SELECT $cols FROM $table ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    for (i in 0 until c.columnCount) {
                        if (i > 0) sb.append(',')
                        val v = c.getString(i) ?: ""
                        // standard CSV quoting (summaries hold commas, line breaks)
                        sb.append(if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"" + v.replace("\"", "\"\"") + "\"" else v)
                    }
                    if (heroType) {
                        val h = if (heroCol >= 0) c.getString(heroCol) ?: "" else ""
                        sb.append(",\"").append(typeCache.getOrPut(h) { HeroType.of(h).joinToString(",") { "%.0f".format(Locale.US, it) } }).append('"')
                    }
                    sb.append('\n')
                    rows++
                }
            }
            val name = "$prefix-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv"
            // v37.19: straight into the app's folder when all files access is on
            val out = com.example.mlbbresearch.core.StorageLayout.OUTPUT
            com.example.mlbbresearch.core.Storage.dir(out)?.let { d ->
                java.io.File(d, name).writeText(sb.toString())
                return@onDb "Exported $rows rows to ${com.example.mlbbresearch.core.Storage.writeLabel}/$out/$name"
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, com.example.mlbbresearch.core.Storage.downloadsRel(out))
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("could not create the file")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
            "Exported $rows rows to ${com.example.mlbbresearch.core.Storage.FALLBACK_REL}/$out/$name"
        }) { msg ->
            if (msg != null) Diag.info(msg)
            done(msg ?: "Export failed (see Logs)")
        }
    }

    fun clear(done: () -> Unit) {
        ExperienceRecorder.onDb({ db -> db.delete("experiences", null, null) }) {
            ExperienceRecorder.refreshCount()
            PerformanceEvaluator.reset()
            done()
        }
    }
}
