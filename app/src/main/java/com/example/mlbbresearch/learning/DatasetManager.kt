package com.example.mlbbresearch.learning

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.example.mlbbresearch.core.Diag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Export or delete the learning data. Exports go to Downloads/MLBB-Research as CSV,
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
        ctx, "demos", "mlbb-my-play", "id,ts,hero,features,move_x,move_y,casts,features2", done
    )

    private fun exportTable(ctx: Context, table: String, prefix: String, cols: String, done: (String) -> Unit) {
        ExperienceRecorder.onDb({ db ->
            val sb = StringBuilder(cols).append('\n')
            var rows = 0
            db.rawQuery("SELECT $cols FROM $table ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    for (i in 0 until c.columnCount) {
                        if (i > 0) sb.append(',')
                        val v = c.getString(i) ?: ""
                        sb.append(if (v.contains(',')) "\"$v\"" else v)
                    }
                    sb.append('\n')
                    rows++
                }
            }
            val name = "$prefix-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MLBB-Research")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("could not create the file")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
            "Exported $rows rows to Downloads/MLBB-Research/$name"
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
