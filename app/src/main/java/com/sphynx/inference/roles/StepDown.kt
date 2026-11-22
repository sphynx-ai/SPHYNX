package com.sphynx.inference.roles

import com.sphynx.inference.ModelConfiguration
import com.sphynx.inference.ModelRole

// STEPPING DOWN TO A SMALLER MODEL (owner decision 2026-11-22, Phase 6 step S5). When "Step down gently" (Phase 5,
// core/PressurePolicy) frees a role's model under low memory or heat, the role is given the next smaller model that is
// available instead of nothing:
//   - any compatible model on either runtime (LiteRT-LM file in Sphynx-Storage/Input, or a model in the GGUF library);
//   - strictly smaller than the one loaded (file size), the largest of those first (the smallest step down);
//   - for the STRATEGIC role, only models that also read pictures (draft, loading cards, end screen);
//   - when the pressure is over, the owner's original model comes back (PressurePolicy's cool-down rule).
// Pure: the router (S5) supplies the candidates and the compatibility check.
object StepDown {

    /**
     * The model to step down to from [current] for [role], or null when there is none (then the role is only unloaded,
     * as before). [compatible] says why a candidate cannot run here (null = it can): the runtime's own check, the GGUF
     * one-role rule and the phone's support. [visionCapable] says whether a candidate reads pictures.
     */
    fun choose(
        role: ModelRole,
        current: ModelConfiguration,
        candidates: List<ModelConfiguration>,
        visionCapable: (ModelConfiguration) -> Boolean,
        compatible: (ModelConfiguration) -> String?
    ): ModelConfiguration? {
        if (current.sizeBytes <= 0) return null                     // the loaded model's size is unknown: nothing is "smaller"
        return candidates.asSequence()
            .filter { it.id != current.id && it.sizeBytes in 1 until current.sizeBytes }
            .filter { it.role == null || it.role == role }
            .filter { role != ModelRole.STRATEGIC || visionCapable(it) }
            .filter { compatible(it) == null }
            .sortedByDescending { it.sizeBytes }
            .firstOrNull()
    }

    /** The configuration to load for the stepped-down model: images on only when it can read them and the role wants them. */
    fun configFor(role: ModelRole, chosen: ModelConfiguration, visionCapable: Boolean): ModelConfiguration =
        chosen.copy(role = role, wantsVision = role == ModelRole.STRATEGIC && visionCapable)

    /** The floating panel's line, e.g. "Phone under pressure: strategic job on Gemma 3n E2B (smaller) for now". */
    fun notice(role: ModelRole, chosen: ModelConfiguration?): String =
        if (chosen == null) "" else "Phone under pressure: ${role.name.lowercase()} job on ${chosen.displayName} (smaller) for now"
}
