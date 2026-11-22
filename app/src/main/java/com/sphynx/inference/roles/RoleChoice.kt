package com.sphynx.inference.roles

import com.sphynx.inference.ModelRole

// WHICH MODEL THE OWNER CHOSE FOR EACH JOB (Phase 6 step S7, owner decision 2026-11-22: a GGUF model may serve either
// role). Stored as one setting per role: "litertlm" (the LiteRT-LM model file of that job, the default, as before) or
// "gguf:<file name>" (a model from the GGUF library). Pure, unit-tested.
object RoleChoice {

    const val LITERT = "litertlm"
    private const val GGUF = "gguf:"

    fun settingKey(role: ModelRole) = "role_model_" + role.name.lowercase()

    /** The GGUF file chosen for a role, or null when the role uses its LiteRT-LM file (also for unknown values). */
    fun ggufFile(value: String?): String? = value?.takeIf { it.startsWith(GGUF) }?.removePrefix(GGUF)?.trim()?.takeIf { it.isNotEmpty() }

    fun gguf(fileName: String) = GGUF + fileName

    /**
     * Why choosing [value] for [role] is not allowed, or null. Phase 6 runs one GGUF model at a time, so the two jobs
     * cannot both use one.
     */
    fun problem(role: ModelRole, value: String, otherRoleValue: String?): String? {
        if (ggufFile(value) != null && ggufFile(otherRoleValue) != null)
            return "only one GGUF model can run at a time: the ${other(role).name.lowercase()} job already uses ${ggufFile(otherRoleValue)}"
        return null
    }

    fun other(role: ModelRole) = if (role == ModelRole.STRATEGIC) ModelRole.TACTICAL else ModelRole.STRATEGIC

    /** The words on the Model page for a role's choice. */
    fun label(role: ModelRole, value: String?, liteName: String, withPictures: Boolean = false): String =
        ggufFile(value)?.let { "$it (GGUF, CPU" + (if (role == ModelRole.STRATEGIC) (if (withPictures) ", reads pictures" else ", text only") else "") + ")" }
            ?: "$liteName (LiteRT-LM)"
}
