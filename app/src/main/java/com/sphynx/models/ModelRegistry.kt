package com.sphynx.models

import android.content.Context
import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import java.io.File

// THE MODEL REGISTRY (Phase 9, step M1). One record (ModelRecord) per model file the app holds: the LiteRT-LM files in
// the app's storage and the GGUF library (models and mmproj files). It is rebuilt from the files on every scan and
// merged with what was known before, so what the app learnt (pictures, where a file came from, its hash, its settings)
// stays. Kept in the app's storage (model_registry.json) with a copy in Sphynx-Storage/Output
// (sphynx-model-registry.json), so a reinstall finds it again.
object ModelRegistry {

    const val FILE = "model_registry.json"
    const val BACKUP = "sphynx-model-registry.json"

    @Volatile var records: List<ModelRecord> = emptyList(); private set

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    /** The model files in the app now (LiteRT-LM in files/, GGUF in files/gguf), described as far as the files allow. */
    fun scanFiles(ctx: Context): List<ModelRecord> {
        val out = ArrayList<ModelRecord>()
        val app = ctx.applicationContext
        app.filesDir.listFiles()?.filter { it.isFile && it.name.endsWith(".litertlm", true) }?.forEach { f ->
            val pics = Cfg.raw("vision_" + f.name)?.let { it == "1" }
            out.add(ModelRecord(id = ModelRecord.idFor(f.name, f.length(), null), fileName = f.name,
                format = ModelRecord.Format.LITERT_LM, sizeBytes = f.length(), family = "gemma".takeIf { f.name.contains("gemma", true) },
                pictures = pics, originalName = originalLiteName(f.name), addedAt = f.lastModified()))
        }
        val gg = try { GgufLibrary.dir(app) } catch (e: Exception) { null }
        gg?.listFiles()?.filter { it.isFile && it.name.endsWith(".gguf", true) }?.forEach { f ->
            val info = com.sphynx.inference.GgufInfo.read(f).getOrNull()
            val proj = info?.isProjector == true
            val fmt = ModelRecord.formatOf(f.name, proj) ?: return@forEach
            out.add(ModelRecord(id = ModelRecord.idFor(f.name, f.length(), null), fileName = f.name, format = fmt,
                sizeBytes = f.length(), family = info?.architecture, quantization = info?.quantization?.takeIf { info.fileType != null },
                sizeLabel = info?.sizeLabel, contextLength = info?.contextLength,
                pictures = if (fmt == ModelRecord.Format.GGUF) GgufLibrary.projectorFor(f) != null else null,
                projectorFor = if (proj) GgufLibrary.models(app).firstOrNull { GgufLibrary.projectorFor(it) == f }?.name else null,
                source = ModelRecord.Source.PICKED, addedAt = f.lastModified()))
        }
        return out
    }

    // the name the slot file had in the Input folder (AutoFolder remembers it as "name:size:modified")
    private fun originalLiteName(appName: String): String? {
        val key = when (appName) { "gemma-strategic.litertlm" -> "auto_sig_gemma"; "gemma-tactical.litertlm" -> "auto_sig_gemma_tactical"; else -> return null }
        return Cfg.raw(key)?.substringBefore(':')
    }

    /** Rebuilds the registry from the files, keeps what was known, and saves it. Safe to call often. */
    @Synchronized
    fun refresh(ctx: Context): List<ModelRecord> {
        val known = ModelRecord.decodeAll(try { file(ctx).takeIf { it.isFile }?.readText() } catch (e: Exception) { null })
            .ifEmpty { ModelRecord.decodeAll(readBackup()) }
        val merged = ModelRecord.merge(known, scanFiles(ctx).map { r ->
            if (r.format == ModelRecord.Format.LITERT_LM && r.originalName != null) r.copy(source = ModelRecord.Source.INPUT_FOLDER) else r
        })
        records = merged
        val text = ModelRecord.encodeAll(merged)
        try { file(ctx).writeText(text) } catch (e: Exception) { Diag.error("Model registry not saved", e) }
        writeBackup(text)
        return merged
    }

    private fun readBackup(): String? = try {
        com.sphynx.core.Storage.dir(com.sphynx.core.StorageLayout.OUTPUT)?.let { File(it, BACKUP) }?.takeIf { it.isFile }?.readText()
    } catch (e: Exception) { null }

    private fun writeBackup(text: String) {
        try { com.sphynx.core.Storage.dir(com.sphynx.core.StorageLayout.OUTPUT)?.let { File(it, BACKUP).writeText(text) } } catch (e: Exception) { }
    }
}
