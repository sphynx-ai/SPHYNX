package com.sphynx.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// "Updated <date> from <source>" under downloaded knowledge (audit finding 8, backlog item 9): how fresh it is and
// where it came from, so an old or unsourced answer is visible. Pure: unit-tested.
object Freshness {
    private const val DAY = 24L * 3600 * 1000

    fun ago(updatedAt: Long, now: Long): String {
        val days = ((now - updatedAt).coerceAtLeast(0) / DAY).toInt()
        return when (days) { 0 -> "today"; 1 -> "yesterday"; else -> "$days days ago" }
    }

    fun line(updatedAt: Long, sources: List<String>, now: Long = System.currentTimeMillis(),
             zone: TimeZone = TimeZone.getDefault()): String {
        if (updatedAt <= 0) return "Not downloaded yet"
        val fmt = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).apply { timeZone = zone }
        val from = sources.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val src = when {
            from.isEmpty() -> "source not recorded"
            from.size <= 3 -> from.joinToString(", ")
            else -> from.take(3).joinToString(", ") + " and ${from.size - 3} more"
        }
        return "Updated ${fmt.format(Date(updatedAt))} (${ago(updatedAt, now)}) from $src"
    }
}
