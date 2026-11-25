package com.sphynx.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * PHASE 9, step M1: one model file as the Model Lab knows it, for both formats. Pure (org.json only), unit-tested.
 *
 * The id is the file's SHA-256 once known (M2 computes it during the copy), else "name:size" until then. Everything
 * the app learns about a model on this phone lives here instead of in loose settings keys (M6 moves them in).
 */
data class ModelRecord(
    val id: String,
    val fileName: String,
    val format: Format,
    val sizeBytes: Long,
    val sha256: String? = null,
    val family: String? = null,
    val quantization: String? = null,
    val sizeLabel: String? = null,
    val contextLength: Long? = null,
    /** Reads pictures: true / false, null = not known yet (learnt at the first load). */
    val pictures: Boolean? = null,
    /** For a projector (mmproj): the model file it belongs to. */
    val projectorFor: String? = null,
    val source: Source = Source.UNKNOWN,
    /** The name the file had where it came from (a picked file or a download can be renamed). */
    val originalName: String? = null,
    val addedAt: Long = 0L,
    /** Per-model settings (M4); empty = today's defaults. */
    val settings: Map<String, String> = emptyMap(),
) {
    enum class Format(val label: String) { LITERT_LM("LiteRT-LM"), GGUF("GGUF"), MMPROJ("GGUF picture projector") }
    enum class Source(val label: String) { INPUT_FOLDER("Input folder"), PICKED("picked file"), DOWNLOAD("download"), UNKNOWN("unknown") }

    /** One line for the Lab: "Llama-3.2-1B…gguf · GGUF · llama · Q4_K_M · 1B · 770 MB · pictures: no". */
    fun summary(): String = listOfNotNull(
        fileName, format.label, family, quantization, sizeLabel, "${sizeBytes / 1_048_576} MB",
        when (pictures) { true -> "reads pictures"; false -> "text only"; null -> null },
    ).joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().put("id", id).put("fileName", fileName).put("format", format.name)
        .put("sizeBytes", sizeBytes).putOpt("sha256", sha256).putOpt("family", family).putOpt("quantization", quantization)
        .putOpt("sizeLabel", sizeLabel).putOpt("contextLength", contextLength).putOpt("pictures", pictures)
        .putOpt("projectorFor", projectorFor).put("source", source.name).putOpt("originalName", originalName)
        .put("addedAt", addedAt).put("settings", JSONObject(settings as Map<*, *>))

    companion object {
        const val SCHEMA = 1

        fun idFor(fileName: String, sizeBytes: Long, sha256: String?) = sha256 ?: "$fileName:$sizeBytes"

        fun formatOf(fileName: String, isProjector: Boolean): Format? {
            val n = fileName.lowercase()
            return when {
                n.endsWith(".litertlm") -> Format.LITERT_LM
                n.endsWith(".gguf") -> if (isProjector || n.startsWith("mmproj")) Format.MMPROJ else Format.GGUF
                else -> null
            }
        }

        fun fromJson(o: JSONObject): ModelRecord? = try {
            val st = o.optJSONObject("settings")
            ModelRecord(
                id = o.getString("id"), fileName = o.getString("fileName"), format = Format.valueOf(o.getString("format")),
                sizeBytes = o.getLong("sizeBytes"), sha256 = o.optString("sha256").ifEmpty { null },
                family = o.optString("family").ifEmpty { null }, quantization = o.optString("quantization").ifEmpty { null },
                sizeLabel = o.optString("sizeLabel").ifEmpty { null },
                contextLength = if (o.has("contextLength")) o.getLong("contextLength") else null,
                pictures = if (o.has("pictures")) o.getBoolean("pictures") else null,
                projectorFor = o.optString("projectorFor").ifEmpty { null },
                source = try { Source.valueOf(o.optString("source", "UNKNOWN")) } catch (e: Exception) { Source.UNKNOWN },
                originalName = o.optString("originalName").ifEmpty { null }, addedAt = o.optLong("addedAt"),
                settings = st?.keys()?.asSequence()?.associateWith { st.getString(it) } ?: emptyMap(),
            )
        } catch (e: Exception) { null }

        /** The registry file's text: schema version and the records. */
        fun encodeAll(records: List<ModelRecord>): String =
            JSONObject().put("schema", SCHEMA).put("models", JSONArray(records.map { it.toJson() })).toString(1)

        /** Records from the file's text; broken entries are skipped, a broken or unknown file gives an empty list. */
        fun decodeAll(text: String?): List<ModelRecord> {
            if (text.isNullOrBlank()) return emptyList()
            return try {
                val o = JSONObject(text)
                if (o.optInt("schema", 0) > SCHEMA) return emptyList()       // a newer app wrote it: rebuild instead
                val a = o.optJSONArray("models") ?: return emptyList()
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }
            } catch (e: Exception) { emptyList() }
        }

        /**
         * The registry after a scan: files found now keep their known record (matched by id, then by name and size);
         * new files get a fresh one; records whose file is gone are dropped. Pure.
         */
        fun merge(known: List<ModelRecord>, found: List<ModelRecord>): List<ModelRecord> = found.map { f ->
            val old = known.firstOrNull { it.id == f.id }
                ?: known.firstOrNull { it.fileName == f.fileName && it.sizeBytes == f.sizeBytes }
            if (old == null) f else f.copy(
                sha256 = f.sha256 ?: old.sha256, id = f.sha256 ?: old.sha256 ?: f.id,
                pictures = f.pictures ?: old.pictures, source = if (f.source == Source.UNKNOWN) old.source else f.source,
                originalName = f.originalName ?: old.originalName, addedAt = if (old.addedAt > 0) old.addedAt else f.addedAt,
                settings = old.settings + f.settings)
        }
    }
}
