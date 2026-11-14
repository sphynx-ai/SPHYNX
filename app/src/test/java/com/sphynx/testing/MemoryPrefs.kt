package com.sphynx.testing

import android.content.SharedPreferences

// SharedPreferences in memory, for unit tests of code that reads Cfg (Cfg.useForTests). Starts empty: every
// setting has its new-install default (all feature switches off, Dry run on).
class MemoryPrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()
    private val listeners = ArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(k: String?, d: String?): String? = map[k] as? String ?: d
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(k: String?, d: MutableSet<String>?): MutableSet<String>? = (map[k] as? Set<String>)?.toMutableSet() ?: d
    override fun getInt(k: String?, d: Int): Int = map[k] as? Int ?: d
    override fun getLong(k: String?, d: Long): Long = map[k] as? Long ?: d
    override fun getFloat(k: String?, d: Float): Float = map[k] as? Float ?: d
    override fun getBoolean(k: String?, d: Boolean): Boolean = map[k] as? Boolean ?: d
    override fun contains(k: String?): Boolean = map.containsKey(k)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(l) }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(l) }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val put = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false
        override fun putString(k: String, v: String?) = also { put[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = also { put[k] = v?.toSet() }
        override fun putInt(k: String, v: Int) = also { put[k] = v }
        override fun putLong(k: String, v: Long) = also { put[k] = v }
        override fun putFloat(k: String, v: Float) = also { put[k] = v }
        override fun putBoolean(k: String, v: Boolean) = also { put[k] = v }
        override fun remove(k: String) = also { removed.add(k) }
        override fun clear() = also { clear = true }
        override fun commit(): Boolean {
            if (clear) map.clear()
            removed.forEach { map.remove(it) }
            for ((k, v) in put) if (v == null) map.remove(k) else map[k] = v
            val changed = removed + put.keys
            for (k in changed) for (l in listeners.toList()) l.onSharedPreferenceChanged(this@MemoryPrefs, k)
            return true
        }
        override fun apply() { commit() }
    }
}
