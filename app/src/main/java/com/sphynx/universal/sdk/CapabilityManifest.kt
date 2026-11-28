package com.sphynx.universal.sdk

import com.sphynx.universal.Capability
import com.sphynx.universal.CapabilitySet
import com.sphynx.universal.GameAdapter
import com.sphynx.universal.SupportStatus
import com.sphynx.universal.UniversalMatchState
import org.json.JSONArray
import org.json.JSONObject

/**
 * PHASE 14, step K1: a game adapter's capability manifest. It says what the adapter claims for each capability **and
 * the evidence behind each claim** (a unit test, a phone check, or none), the features it does not support, and the
 * source and permission notes for the game. A game counts as supported only with a passing conformance report and a
 * phone check (roadmap: "do not claim a game is supported until conformance and user-facing workflows are validated").
 * Game-free, pure, unit-tested.
 */
data class CapabilityManifest(
    val gameId: String, val displayName: String, val adapterVersion: Int, val contractVersion: Int,
    val capabilities: Map<Capability, Claim>, val unsupported: List<String>, val sourceNotes: List<String>,
    val schema: Int = SCHEMA,
) {
    enum class Evidence(val label: String) { NONE("no evidence"), UNIT_TEST("unit tests"), PHONE("checked on a phone") }
    data class Claim(val status: SupportStatus, val evidence: Evidence, val note: String = "")

    /** A capability claimed usable (operational / partial) without any evidence: a claim the manifest can't back. */
    fun unbackedClaims(): List<Capability> = capabilities.filter { (_, c) -> c.status.usable && c.evidence == Evidence.NONE }.keys.toList()

    /** The support-matrix line: "Mobile Legends: Bang Bang — supported (conformance passed, checked on a phone)". */
    fun matrixLine(conformancePassed: Boolean): String {
        val phone = capabilities.values.any { it.evidence == Evidence.PHONE }
        val verdict = when { conformancePassed && phone -> "supported"; conformancePassed -> "conformance passed, not yet checked on a phone"
            else -> "not supported" }
        return "$displayName — $verdict (adapter v$adapterVersion, contract v$contractVersion)"
    }

    fun toJson(): JSONObject = JSONObject().put("schema", schema).put("gameId", gameId).put("displayName", displayName)
        .put("adapterVersion", adapterVersion).put("contractVersion", contractVersion)
        .put("capabilities", JSONObject().apply { capabilities.forEach { (k, c) ->
            put(k.name, JSONObject().put("status", c.status.name).put("evidence", c.evidence.name).put("note", c.note)) } })
        .put("unsupported", JSONArray(unsupported)).put("sourceNotes", JSONArray(sourceNotes))

    companion object {
        const val SCHEMA = 1

        fun fromJson(o: JSONObject): CapabilityManifest? = try {
            if (o.optInt("schema", 0) > SCHEMA) null else {
                val c = o.getJSONObject("capabilities")
                CapabilityManifest(o.getString("gameId"), o.getString("displayName"), o.getInt("adapterVersion"), o.getInt("contractVersion"),
                    c.keys().asSequence().mapNotNull { k -> runCatching { Capability.valueOf(k) }.getOrNull()?.let { cap ->
                        val j = c.getJSONObject(k)
                        cap to Claim(SupportStatus.valueOf(j.getString("status")), Evidence.valueOf(j.getString("evidence")), j.optString("note")) } }.toMap(),
                    o.getJSONArray("unsupported").let { a -> (0 until a.length()).map { a.getString(it) } },
                    o.getJSONArray("sourceNotes").let { a -> (0 until a.length()).map { a.getString(it) } })
            }
        } catch (e: Exception) { null }

        /** The manifest of [adapter], with the evidence each capability has ([evidence]; missing = none). */
        fun of(adapter: GameAdapter<*>, evidence: Map<Capability, Evidence>, unsupported: List<String>, sourceNotes: List<String>,
               notes: Map<Capability, String> = emptyMap()) = CapabilityManifest(adapter.gameId, adapter.displayName, adapter.adapterVersion,
            UniversalMatchState.SCHEMA_VERSION, Capability.values().associateWith { cap ->
                Claim(adapter.capabilities.status(cap), evidence[cap] ?: Evidence.NONE, notes[cap] ?: "") }, unsupported, sourceNotes)
    }
}

/** The capability set a manifest claims (to hand to an adapter-free part of the app). */
fun CapabilityManifest.capabilitySet(): CapabilitySet = CapabilitySet(capabilities.mapValues { it.value.status })
