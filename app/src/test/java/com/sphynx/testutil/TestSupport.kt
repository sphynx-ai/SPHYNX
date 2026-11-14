package com.sphynx.testutil

import android.content.SharedPreferences
import com.sphynx.core.Cfg

// In-memory SharedPreferences so code that reads settings through Cfg runs in plain JVM tests
// (no device, no Robolectric). Installed into Cfg's private `prefs` field by reflection, so the
// production code needs no test hook.
class FakePrefs : SharedPreferences {
    val store = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(store)
    override fun getString(key: String?, defValue: String?): String? = store[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = store[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = store[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = store[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = store[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = store[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = store.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class Editor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { store[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { store[key!!] = values }
        override fun putInt(key: String?, value: Int) = apply { store[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { store[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { store[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { store[key!!] = value }
        override fun remove(key: String?) = apply { store.remove(key) }
        override fun clear() = apply { store.clear() }
        override fun commit(): Boolean = true
        override fun apply() {}
    }
}

object TestSupport {
    // a fresh, empty settings store for each test
    fun freshCfg(): FakePrefs {
        val prefs = FakePrefs()
        val f = Cfg::class.java.getDeclaredField("prefs")
        f.isAccessible = true
        f.set(Cfg, prefs)
        return prefs
    }
}

// A synthetic screen: w x h ARGB pixels, filled with one colour, with helpers to paint rectangles
// given in screen fractions (the same coordinates the vision code uses).
class SynthFrame(val w: Int = 2000, val h: Int = 900, fill: Int = rgb(30, 40, 50)) {
    val px = IntArray(w * h) { fill }

    fun rect(x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        for (y in (y0 * h).toInt() until (y1 * h).toInt().coerceAtMost(h))
            for (x in (x0 * w).toInt() until (x1 * w).toInt().coerceAtMost(w)) px[y * w + x] = color
    }

    companion object {
        fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
