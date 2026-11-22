package com.sphynx.inference.roles

import com.sphynx.inference.InferenceError
import com.sphynx.inference.ModelRole

// THE LOAD SENTINEL (Phase 6 step S6; owner decision 2026-11-22: "don't reload; ask me"). Just before a role's model
// loads, its name is written down; when the load returns, it is cleared. If the app stops in between (a native crash in
// the GPU driver or llama.cpp, a low-memory kill), the note is still there at the next start: that model is then NOT
// loaded by itself again, and the owner is told why. Loading it by hand (Try again) is always allowed. Pure; the app
// supplies a store that writes at once (a crash must not lose the note).
class LoadSentinel(private val store: Store) {

    interface Store {
        fun get(key: String): String?
        /** Written to disk before it returns. */
        fun setNow(key: String, value: String?)
    }

    private val suspected = HashMap<ModelRole, String>()

    private fun key(role: ModelRole) = "loading_" + role.name.lowercase()

    /** At start: what was loading when the app stopped becomes suspected. Returns the roles found. */
    @Synchronized
    fun checkAtStart(): List<Pair<ModelRole, String>> {
        val found = ArrayList<Pair<ModelRole, String>>()
        for (r in ModelRole.values()) store.get(key(r))?.let { name ->
            suspected[r] = name; found.add(r to name); store.setNow(key(r), null)
        }
        return found
    }

    /** Why [model] may not be loaded for [role] by itself now, or null (a load by hand passes [byOwner] = true). */
    @Synchronized
    fun refusal(role: ModelRole, model: String, byOwner: Boolean): InferenceError? {
        if (byOwner) { suspected.remove(role); return null }
        return if (suspected[role] == model) InferenceError.CrashSuspected(model) else null
    }

    @Synchronized fun suspected(role: ModelRole): String? = suspected[role]
    @Synchronized fun forgive(role: ModelRole) { suspected.remove(role) }
    @Synchronized fun forgiveAll() { suspected.clear() }

    fun loading(role: ModelRole, model: String) = store.setNow(key(role), model)
    fun loaded(role: ModelRole) = store.setNow(key(role), null)
}
