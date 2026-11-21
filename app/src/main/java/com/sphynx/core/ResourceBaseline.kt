package com.sphynx.core

import org.json.JSONObject

// RESOURCE BASELINES ON THE OWNER'S PHONE (Phase 5 acceptance criterion: "resource baselines measured on the owner's
// phone: idle, capture on, Gemma loaded, GGUF loaded"). One tap on the Debug data page saves a snapshot of the phone's
// memory, this app's memory and CPU, the heat and the battery, and what is loaded at that moment, into the Recordings
// folder (owner's debug-data rule: uploaded, then deleted). The state name follows from what is loaded, so four taps in
// the four states give the four baselines. Pure, unit-tested; the Debug data page fills it in.
object ResourceBaseline {

    data class Reading(
        val atMs: Long,
        val captureOn: Boolean, val strategicLoaded: Boolean, val tacticalLoaded: Boolean, val ggufLoaded: Boolean,
        val ramTotalMb: Long, val ramAvailMb: Long, val lowMemory: Boolean,
        val appRamMb: Long, val appJavaMb: Long, val appNativeMb: Long, val appCpuPct: Float,
        val thermal: String, val thermalHeadroom: Float?, val batteryC: Float?, val batteryPct: Int?, val charging: Boolean?
    )

    /** The state the snapshot was taken in, from what was on: "idle", "capture on", "capture on + Gemma"... */
    fun state(captureOn: Boolean, strategic: Boolean, tactical: Boolean, gguf: Boolean): String {
        val parts = ArrayList<String>()
        if (captureOn) parts.add("capture on")
        if (strategic) parts.add("strategic model")
        if (tactical) parts.add("tactical model")
        if (gguf) parts.add("GGUF model")
        return if (parts.isEmpty()) "idle" else parts.joinToString(" + ")
    }

    /** The file name: in Recordings (StorageLayout routes "diagnostics-*" there). */
    fun fileName(stamp: String): String = "diagnostics-baseline-$stamp.json"

    fun toJson(r: Reading, device: String?, app: String): JSONObject = JSONObject()
        .put("kind", "resource-baseline").put("t", r.atMs).put("app", app).put("device", device ?: "not read yet")
        .put("state", state(r.captureOn, r.strategicLoaded, r.tacticalLoaded, r.ggufLoaded))
        .put("loaded", JSONObject().put("capture", r.captureOn).put("strategic", r.strategicLoaded)
            .put("tactical", r.tacticalLoaded).put("gguf", r.ggufLoaded))
        .put("phoneRamTotalMb", r.ramTotalMb).put("phoneRamAvailMb", r.ramAvailMb).put("lowMemory", r.lowMemory)
        .put("appRamMb", r.appRamMb).put("appJavaMb", r.appJavaMb).put("appNativeMb", r.appNativeMb)
        .put("appCpuPct", r.appCpuPct.toDouble())
        .put("thermal", r.thermal).put("thermalHeadroom", r.thermalHeadroom?.toDouble() ?: JSONObject.NULL)
        .put("batteryC", r.batteryC?.toDouble() ?: JSONObject.NULL).put("batteryPct", r.batteryPct ?: JSONObject.NULL)
        .put("charging", r.charging ?: JSONObject.NULL)

    /** The toast after saving. */
    fun summary(r: Reading): String =
        "Snapshot (" + state(r.captureOn, r.strategicLoaded, r.tacticalLoaded, r.ggufLoaded) + "): phone ${r.ramAvailMb} MB free of " +
            "${r.ramTotalMb} MB, app ${r.appRamMb} MB, CPU ${"%.0f".format(java.util.Locale.US, r.appCpuPct)}%, ${r.thermal}"
}
