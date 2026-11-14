package com.sphynx.learning

import org.json.JSONObject

// A trained fight adviser (the fight-choice network or the small tactical model) is only used
// when its notebook report shows it learned something (v36.53): right more often than "always the
// most common answer" would be, and, when the report has it, better than chance across the
// answers (balanced accuracy over 30%; chance with 5 answers = 20%). A weak one stays loaded but
// gives no advice, even with its switch on - the Model page says "too weak, not used".
object ModelQuality {
    const val MIN_GAIN = 0.01        // must beat "always the most common answer" by more than this
    const val MIN_BALANCED = 0.30

    // null = no report (an older file): used as before
    fun weakReason(info: String?): String? {
        val o = try { JSONObject(info ?: return null) } catch (e: Exception) { return null }
        if (!o.has("acc") || !o.has("base")) return null
        val acc = o.optDouble("acc"); val base = o.optDouble("base"); val bal = o.optDouble("balanced", -1.0)
        if (acc <= base + MIN_GAIN) return "right %d%%, no better than always answering the most common choice (%d%%)".format((acc * 100).toInt(), (base * 100).toInt())
        if (bal >= 0 && bal < MIN_BALANCED) return "balanced %d%% (chance is 20%%)".format((bal * 100).toInt())
        return null
    }
}
