package com.sphynx.inference.roles

import com.sphynx.inference.BackendType
import com.sphynx.inference.InferenceBackend
import com.sphynx.inference.InferenceError
import com.sphynx.inference.InferenceEvent
import com.sphynx.inference.InferenceRequest
import com.sphynx.inference.ModelConfiguration
import com.sphynx.inference.ModelRole
import com.sphynx.inference.RuntimeState
import com.sphynx.inference.RuntimeStatus

// PHASE 6, step S3 (ADR 0002, accepted 2026-11-22): the ONE place that knows which model serves which job.
// A role (STRATEGIC, TACTICAL) is served by an assignment: a model configuration on a runtime. Callers ask a role; they
// never name a slot, a model or a runtime. The router loads and unloads a role's model, reports its status, says what
// it can do (images? streaming?) and refuses a request the role cannot serve, with a reason. L0 platform: pure Kotlin,
// no game knowledge and no Android; the app plugs in the runtimes. Nothing calls it yet: the callers move in S4.

/** What a role's loaded model can do. */
data class RoleCapabilities(val loaded: Boolean, val images: Boolean, val streaming: Boolean, val maxOutputTokens: Int) {
    companion object { val NONE = RoleCapabilities(loaded = false, images = false, streaming = false, maxOutputTokens = 0) }
}

/** Which model serves a role. */
data class Assignment(val role: ModelRole, val model: ModelConfiguration) {
    val backend: BackendType get() = model.backend
}

class RoleRouter(
    /** The runtime instance that runs [type] for [role] (each role has its own; null = not in this build). */
    private val runtimeFor: (ModelRole, BackendType) -> InferenceBackend?
) {
    private val assignments = HashMap<ModelRole, Assignment>()
    /** The runtime each role uses now (only one runtime per role; a role switching runtime unloads the old one). */
    private val active = HashMap<ModelRole, InferenceBackend>()

    /** Why [a] cannot be used, or null. Phase 6: one GGUF model at a time, so GGUF serves at most one role. */
    @Synchronized
    fun problemWith(a: Assignment): String? {
        if (a.model.role != null && a.model.role != a.role) return "${a.model.displayName} is set up for the ${a.model.role.name.lowercase()} role"
        val rt = runtimeFor(a.role, a.backend) ?: return "${a.backend.label} is not available for the ${a.role.name.lowercase()} role"
        rt.check(a.model)?.let { return it.message }
        val other = ModelRole.values().filter { it != a.role }.mapNotNull { assignments[it] }
        if (a.backend == BackendType.LLAMA_CPP && other.any { it.backend == BackendType.LLAMA_CPP })
            return "only one GGUF model can be loaded at a time: it already serves the ${other.first { it.backend == BackendType.LLAMA_CPP }.role.name.lowercase()} role"
        return null
    }

    /** Sets which model serves [a].role (it is not loaded here). Returns why not, or null when set. */
    @Synchronized
    fun assign(a: Assignment): String? {
        problemWith(a)?.let { return it }
        val old = assignments[a.role]
        if (old != null && old.model.id != a.model.id) unload(a.role)
        assignments[a.role] = a
        return null
    }

    @Synchronized fun assignment(role: ModelRole): Assignment? = assignments[role]

    /** Loads the role's assigned model on its runtime; the final status (READY or FAILED). */
    fun load(role: ModelRole): RuntimeStatus {
        val a = synchronized(this) { assignments[role] }
            ?: return failed(role, InferenceError.LoadFailed("no model is assigned to the ${role.name.lowercase()} role"))
        val rt = runtimeFor(role, a.backend) ?: return failed(role, InferenceError.BackendUnavailable(a.backend))
        synchronized(this) {
            val prev = active[role]
            if (prev != null && prev !== rt) { try { prev.unload() } catch (t: Throwable) { } }
            active[role] = rt
        }
        return rt.load(a.model)
    }

    fun unload(role: ModelRole) {
        val rt = synchronized(this) { active.remove(role) } ?: return
        try { rt.unload() } catch (t: Throwable) { }
    }

    /** The role's status: its runtime's, or UNLOADED with no runtime yet. */
    fun status(role: ModelRole): RuntimeStatus {
        val rt = synchronized(this) { active[role] }
        val a = synchronized(this) { assignments[role] }
        return rt?.status() ?: RuntimeStatus(a?.backend ?: BackendType.LITERT_LM)
    }

    fun capabilities(role: ModelRole): RoleCapabilities = capabilitiesOf(status(role))

    /**
     * Asks the role. A request the role cannot serve (not loaded, an image to a text-only model, too many output
     * tokens) ends at once with Failed(InvalidRequest) and the reason: it is never silently degraded.
     */
    fun ask(role: ModelRole, request: InferenceRequest, onEvent: (InferenceEvent) -> Unit) {
        val rt = synchronized(this) { active[role] }
        val st = rt?.status()
        val why = refusal(role, st, request)
        if (rt == null || why != null) {
            onEvent(InferenceEvent.Started(request.id))
            onEvent(InferenceEvent.Failed(request.id, InferenceError.InvalidRequest(listOf(why ?: "the ${role.name.lowercase()} role has no model loaded"))))
            return
        }
        rt.generate(request, onEvent)
    }

    fun cancel(role: ModelRole, requestId: String) { synchronized(this) { active[role] }?.cancel(requestId) }

    private fun failed(role: ModelRole, e: InferenceError): RuntimeStatus =
        RuntimeStatus(assignment(role)?.backend ?: BackendType.LITERT_LM).moveTo(RuntimeState.LOADING).moveTo(RuntimeState.FAILED, error = e)

    companion object {
        fun capabilitiesOf(st: RuntimeStatus?): RoleCapabilities {
            if (st == null || !(st.state == RuntimeState.READY || st.state == RuntimeState.BUSY)) return RoleCapabilities.NONE
            return RoleCapabilities(loaded = true, images = st.visionOk,
                streaming = st.backend == BackendType.LLAMA_CPP,          // LiteRT-LM answers in one piece here (S2)
                maxOutputTokens = st.config?.maxOutputTokens ?: 0)
        }

        /** Why [role] cannot serve [req] now, or null. */
        fun refusal(role: ModelRole, st: RuntimeStatus?, req: InferenceRequest): String? {
            val r = role.name.lowercase()
            if (st == null || st.state == RuntimeState.UNLOADED) return "the $r role has no model loaded"
            if (st.state == RuntimeState.FAILED) return "the $r model failed to load: ${st.error?.message ?: "unknown reason"}"
            if (st.state == RuntimeState.LOADING) return "the $r model is still loading"
            if (req.image != null && !st.visionOk) return "the $r model reads text only, so the picture cannot be sent"
            val max = st.config?.maxOutputTokens ?: Int.MAX_VALUE
            if (req.maxOutputTokens > max) return "the $r model answers at most $max tokens"
            return null
        }
    }
}
