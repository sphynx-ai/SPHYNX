package com.sphynx.models

import org.json.JSONObject

/**
 * PHASE 9, step M7: the model registry as a debugging file for the Recordings folder (uploaded with the debug data,
 * then deleted from the phone). It names files, never folders: anything that looks like a path on the phone (it could
 * name the owner's account or storage) is cut down to its file name. Pure, unit-tested.
 */
object ModelRegistryExport {

    fun fileName(stamp: String) = "diagnostics-model-registry-$stamp.json"

    private val PATH = Regex("""(?:/(?:storage|sdcard|data|mnt)|content://)[^\s"',;|]*/([^/\s"',;|]+)""")

    /** [s] with every phone path cut down to its last part ("/storage/emulated/0/x/y.gguf" -> "y.gguf"). */
    fun redact(s: String): String = PATH.replace(s) { it.groupValues[1] }

    // every text of a record cut down before it becomes JSON (Android's JSON writes "/" as "\/", so not afterwards)
    private fun clean(r: ModelRecord) = r.copy(fileName = redact(r.fileName), originalName = r.originalName?.let(::redact),
        projectorFor = r.projectorFor?.let(::redact), settings = r.settings.mapValues { redact(it.value) },
        facts = r.facts.mapValues { redact(it.value) })

    /** The file's text: the app version, the jobs, the storage line and every record. */
    fun text(records: List<ModelRecord>, appVersion: String, jobs: Map<String, String>, storage: String, now: Long): String {
        val o = JSONObject().put("kind", "sphynx-model-registry").put("appVersion", appVersion).put("savedAt", now)
            .put("schema", ModelRecord.SCHEMA).put("storage", redact(storage)).put("jobs", JSONObject(jobs.mapValues { redact(it.value) } as Map<*, *>))
            .put("models", org.json.JSONArray(records.map { clean(it).toJson() }))
        return o.toString(1)
    }
}
