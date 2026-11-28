package com.sphynx.universal.coach

import com.sphynx.universal.DecisionKind
import com.sphynx.universal.EngineSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * PHASE 12, step C1: one structured, versioned coaching recommendation (roadmap 6.1.2 C). The record is built from the
 * engines' Decisions by the Coordinator; a model's free text is never the record itself (it can only be quoted in
 * [evidence]). Game-free, pure, unit-tested. Advice only: nothing here sends input to a game.
 */
data class Recommendation(
    val horizon: Horizon,
    val kind: DecisionKind,
    val urgency: Urgency,
    val confidence: Float?,            // 0..1, null = the engine gave none
    val source: EngineSource,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val target: String? = null,
    val evidence: List<String> = emptyList(),
    /** The engine's capability is not usable for this game: shown as experimental, never acted on. */
    val experimental: Boolean = false,
    val schema: Int = SCHEMA,
) {
    enum class Horizon(val label: String) { STRATEGIC("strategy"), TACTICAL("now") }
    enum class Urgency(val label: String) { LOW("low"), MEDIUM("medium"), HIGH("high"), CRITICAL("critical") }

    fun expired(nowMs: Long) = nowMs >= expiresAtMs

    /** The panel line: "NOW · back off (high, 80%)". */
    fun line(): String = "${horizon.label.uppercase()} · ${kind.label}" + (target?.let { " → $it" } ?: "") +
        " (${urgency.label}" + (confidence?.let { ", ${(it * 100).toInt()}%" } ?: "") + (if (experimental) ", experimental" else "") + ")"

    fun toJson(): JSONObject = JSONObject().put("schema", schema).put("horizon", horizon.name).put("kind", kind.name)
        .put("urgency", urgency.name).putOpt("confidence", confidence?.toDouble()).put("source", source.name)
        .put("createdAtMs", createdAtMs).put("expiresAtMs", expiresAtMs).putOpt("target", target)
        .put("evidence", JSONArray(evidence)).put("experimental", experimental)

    companion object {
        const val SCHEMA = 1

        fun fromJson(o: JSONObject): Recommendation? = try {
            if (o.optInt("schema", 0) > SCHEMA) null else Recommendation(Horizon.valueOf(o.getString("horizon")),
                DecisionKind.valueOf(o.getString("kind")), Urgency.valueOf(o.getString("urgency")),
                if (o.has("confidence") && !o.isNull("confidence")) o.getDouble("confidence").toFloat() else null,
                EngineSource.valueOf(o.getString("source")), o.getLong("createdAtMs"), o.getLong("expiresAtMs"),
                o.optString("target").ifEmpty { null },
                o.optJSONArray("evidence")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                o.optBoolean("experimental"), o.optInt("schema", SCHEMA))
        } catch (e: Exception) { null }
    }
}
