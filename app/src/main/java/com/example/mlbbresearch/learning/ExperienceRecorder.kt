package com.example.mlbbresearch.learning

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.mlbbresearch.ai.TacticalDecision
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.game.GameState
import java.util.concurrent.Executors

// Saves what the AI saw and did (one row about twice a second) into a local SQLite
// database. PerformanceEvaluator later scores each row: did it help or hurt?
class ExperienceDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "experiences.db", null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE experiences (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, hero TEXT, intent TEXT, actions TEXT, " +
                "self_hp REAL, allies INTEGER, enemies INTEGER, minions INTEGER, target_id INTEGER, target_hp REAL, " +
                "plan TEXT, aggression REAL, reward REAL, evaluated INTEGER DEFAULT 0)"
        )
        createDemos(db)
        createLive(db)
    }

    // v4: moments from live matches you watched (another player's hero)
    private fun createLive(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS live_demos (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, hero TEXT, features TEXT, " +
                "move_x REAL, move_y REAL, casts INTEGER, features2 TEXT, source TEXT, lane TEXT, match_sec INTEGER, died INTEGER DEFAULT 0)"
        )
    }

    // v2: "Learn from me" demonstrations (what you saw and what you did)
    private fun createDemos(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS demos (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, hero TEXT, features TEXT, " +
                "move_x REAL, move_y REAL, casts INTEGER, features2 TEXT, died INTEGER DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDemos(db)
        // v3: the richer situation (32 numbers, Features2) for the trained tactical network
        else if (oldVersion < 3) db.execSQL("ALTER TABLE demos ADD COLUMN features2 TEXT")
        if (oldVersion < 4) createLive(db)
        // v5: died = 1 on the moments of the 6 s before the hero died
        if (oldVersion in 2..4) try { db.execSQL("ALTER TABLE demos ADD COLUMN died INTEGER DEFAULT 0") } catch (e: Exception) { }
        if (oldVersion == 4) try { db.execSQL("ALTER TABLE live_demos ADD COLUMN died INTEGER DEFAULT 0") } catch (e: Exception) { }
    }
}

object ExperienceRecorder {
    private var db: ExperienceDb? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "learning") }
    private var lastRecord = 0L

    @Volatile var count = 0L
        private set

    fun init(ctx: Context) {
        if (db != null) return
        db = ExperienceDb(ctx)
        io.execute { count = countRows() }
    }

    fun record(s: GameState, d: TacticalDecision, plan: String, aggression: Float) {
        val now = System.currentTimeMillis()
        if (now - lastRecord < 500) return
        lastRecord = now
        val h = db ?: return
        val values = ContentValues().apply {
            put("ts", now)
            put("hero", s.ownHero ?: "unknown")
            put("intent", d.intent.name)
            put("actions", d.casts.joinToString(","))
            put("self_hp", s.selfHp)
            put("allies", s.allies.size)
            put("enemies", s.enemies.size)
            put("minions", s.enemyMinions.size)
            put("target_id", d.targetId)
            put("target_hp", d.targetHp)
            put("plan", plan)
            put("aggression", aggression)
        }
        io.execute {
            try {
                val id = h.writableDatabase.insert("experiences", null, values)
                if (id > 0) {
                    count++
                    PerformanceEvaluator.pending(id, now, s, d)
                }
            } catch (e: Exception) {
                Diag.error("Learning: could not save", e)
            }
        }
    }

    fun setReward(id: Long, reward: Float) {
        val h = db ?: return
        io.execute {
            try {
                val v = ContentValues().apply { put("reward", reward); put("evaluated", 1) }
                h.writableDatabase.update("experiences", v, "id = ?", arrayOf(id.toString()))
            } catch (e: Exception) {
                Diag.error("Learning: could not score", e)
            }
        }
    }

    private fun countRows(): Long = try {
        db?.readableDatabase?.rawQuery("SELECT COUNT(*) FROM experiences", null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        } ?: 0L
    } catch (e: Exception) {
        0L
    }

    // runs on the learning thread
    fun <T> onDb(block: (SQLiteDatabase) -> T, done: (T?) -> Unit) {
        val h = db
        io.execute {
            val r = try { if (h == null) null else block(h.writableDatabase) } catch (e: Exception) {
                Diag.error("Learning database", e); null
            }
            done(r)
        }
    }

    fun refreshCount() = io.execute { count = countRows() }
}
