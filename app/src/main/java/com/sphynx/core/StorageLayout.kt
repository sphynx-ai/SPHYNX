package com.sphynx.core

// THE FOLDERS INSIDE Sphynx-Storage (owner, 2026-11-14): three folders instead of one pile, for less
// confusion.
//   Input/       what the app READS: models (.litertlm .gguf .tflite), keys (groq_key.txt, kaggle.json,
//                github_token.txt ...), detector labels, trained tactical networks
//   Output/      what the app MAKES: training pictures (dataset/, screen_dataset/), CSV exports, picture
//                zips, Kaggle extras (best.pt, reports), the online-data and calibration backups
//   Recordings/  debugging: session recordings, diagnostics logs, exports for debugging - uploaded to
//                GitHub and then deleted from the phone (owner's rule, 2026-11-12)
// The app still READS the top of Sphynx-Storage too, so a file put there by hand is found; when the app
// may write there (All files access) it moves the files it recognises into their folder.
// Pure Kotlin, no Android: unit tested.
object StorageLayout {

    const val INPUT = "Input"
    const val OUTPUT = "Output"
    const val RECORDINGS = "Recordings"

    /** Folders the training-picture collectors write into, moved under Output/. */
    val OUTPUT_SUBFOLDERS = listOf("dataset", "screen_dataset")

    /**
     * Which folder a file belongs in, by its name (not case sensitive); null = not recognised, so it
     * is left where it is.
     */
    fun areaFor(name: String): String? {
        val n = name.trim().lowercase()
        if (n.isEmpty() || n.endsWith(".part") || n.startsWith(".")) return null
        return when {
            // debugging
            n.startsWith("sphynx-session-") || n.startsWith("diagnostics-") || n.startsWith("training-results-") ||
                n.endsWith(".mp4") -> RECORDINGS
            // models the app loads
            n.endsWith(".litertlm") || n.endsWith(".task") || n.endsWith(".gguf") || n.endsWith(".tflite") -> INPUT
            // keys and settings files
            n == "kaggle.json" || n == "github_token.txt" -> INPUT
            n.endsWith(".txt") && (n.startsWith("groq") || n.startsWith("gemini") || n.startsWith("cerebras") || n.startsWith("roboflow")) -> INPUT
            // what goes with a model
            n.endsWith("labels.txt") -> INPUT
            n.endsWith(".json") && (n.startsWith("policy_info") || n == "tactical_llm_info.json") -> INPUT
            n.endsWith(".zip") && (n.contains("polic") || n.contains("tactical")) && !n.startsWith("tactical_llm_trainable") -> INPUT
            // what the app and its trainings make
            n.endsWith(".csv") || n.endsWith(".pt") || n.endsWith(".pth") || n.endsWith(".onnx") -> OUTPUT
            n.endsWith("_report.json") || n.startsWith("tactical_llm_trainable") -> OUTPUT
            n.startsWith("mlbb-") && (n.endsWith(".json") || n.endsWith(".zip")) -> OUTPUT
            else -> null
        }
    }

    /**
     * Sorts the top of [root] into Input / Output / Recordings. Moves only what [areaFor] recognises,
     * never overwrites a file already in place, and leaves everything else where it is. Returns what
     * it moved, for the log. Safe to run on every start.
     */
    fun organizeIn(root: java.io.File): List<String> {
        val moved = ArrayList<String>()
        // the old "recordings" folder becomes "Recordings" (phone storage ignores letter case, so it is
        // renamed through a temporary name)
        root.listFiles()?.firstOrNull { it.isDirectory && it.name == "recordings" }?.let { old ->
            val tmp = java.io.File(root, "recordings-moving")
            if (old.renameTo(tmp)) {
                val target = java.io.File(root, RECORDINGS)
                if (!target.exists() && tmp.renameTo(target)) moved.add("recordings/ -> ${RECORDINGS}/")
                else {
                    target.mkdirs()
                    tmp.listFiles()?.forEach { f -> val t = java.io.File(target, f.name); if (!t.exists() && f.renameTo(t)) moved.add("recordings/${f.name}") }
                    if (tmp.list().isNullOrEmpty()) tmp.delete() else tmp.renameTo(old)
                }
            }
        }
        for (a in listOf(INPUT, OUTPUT, RECORDINGS)) java.io.File(root, a).mkdirs()
        // the training-picture folders move under Output
        for (sub in OUTPUT_SUBFOLDERS) {
            val old = java.io.File(root, sub)
            val target = java.io.File(root, OUTPUT + "/" + sub)
            if (old.isDirectory && !target.exists() && old.renameTo(target)) moved.add("$sub/ -> ${OUTPUT}/$sub/")
        }
        // the files at the top
        root.listFiles()?.filter { it.isFile }?.forEach { f ->
            val area = areaFor(f.name) ?: return@forEach
            val t = java.io.File(root, area + "/" + f.name)
            if (!t.exists() && f.renameTo(t)) moved.add("${f.name} -> $area/")
        }
        return moved
    }

    /** What each folder is for, shown on the Model page. */
    fun describe(area: String): String = when (area) {
        INPUT -> "put models and keys here"
        OUTPUT -> "training pictures, exports and training results the app makes"
        RECORDINGS -> "debug recordings and logs (uploaded, then deleted)"
        else -> ""
    }
}
