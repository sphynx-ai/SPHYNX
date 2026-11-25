package com.sphynx.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

// Sphynx-Storage is sorted into Input / Output / Recordings (owner, 2026-11-14).
class StorageLayoutTest {

    // ---- which folder a file belongs in ----

    @Test fun modelsAndKeysAreInput() {
        for (n in listOf("gemma-4-E2B-it.litertlm", "gemma3-270m-mlbb-tactical.litertlm", "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                "mlbb_detector.tflite", "mlbb_screen_detector.tflite", "labels.txt", "screen_labels.txt", "mlbb_policies.zip",
                "policy_info.json", "tactical_llm_info.json", "groq_key.txt", "groq_model.txt", "gemini_key.txt",
                "cerebras_key.txt", "roboflow_key.txt", "kaggle.json", "github_token.txt", "GROQ_KEY.TXT"))
            assertEquals(n, StorageLayout.INPUT, StorageLayout.areaFor(n))
    }

    @Test fun whatTheAppMakesIsOutput() {
        for (n in listOf("mlbb-my-play-20261114-101010.csv", "mlbb-fights-20261114.csv", "mlbb-minimap-dataset-20261114-1010.zip",
                "mlbb-screen-dataset-20261114-1010.zip", "minimap_detector_best.pt", "screen_detector_best.pt",
                "minimap_detector_report.json", "tactical_llm_trainable.zip", "mlbb-online-meta.json",
                "mlbb-online-skills.json", "mlbb-scenery-sync.json", "mlbb-learned.json", "sphynx-model-registry.json"))
            assertEquals(n, StorageLayout.OUTPUT, StorageLayout.areaFor(n))
    }

    @Test fun debuggingIsRecordings() {
        for (n in listOf("sphynx-session-20261113-164428.mp4", "sphynx-session-20261113-164428-diagnostics.log",
                "diagnostics-20261114-175031.log", "training-results-20261114-172756.json"))
            assertEquals(n, StorageLayout.RECORDINGS, StorageLayout.areaFor(n))
    }

    @Test fun unknownAndUnfinishedFilesStayWhereTheyAre() {
        for (n in listOf("notes.txt", "photo.jpg", "random.zip", "model.gguf.part", ".nomedia", ""))
            assertNull(n, StorageLayout.areaFor(n))
    }

    // ---- moving the files of an existing folder ----

    private val root: File = Files.createTempDirectory("sphynx-storage").toFile()
    @After fun cleanUp() { root.deleteRecursively() }
    private fun file(path: String, text: String = "x") = File(root, path).apply { parentFile?.mkdirs(); writeText(text) }

    @Test fun anOldFolderIsSorted() {
        file("gemma-4-E2B-it.litertlm"); file("groq_key.txt"); file("mlbb-scenery-sync.json"); file("minimap_detector_best.pt")
        file("notes.txt")
        file("dataset/images/mm_1.png"); file("screen_dataset/classes.txt")
        file("recordings/sphynx-session-1.mp4")

        val moved = StorageLayout.organizeIn(root)

        assertTrue(File(root, "Input/gemma-4-E2B-it.litertlm").isFile)
        assertTrue(File(root, "Input/groq_key.txt").isFile)
        assertTrue(File(root, "Output/mlbb-scenery-sync.json").isFile)
        assertTrue(File(root, "Output/minimap_detector_best.pt").isFile)
        assertTrue(File(root, "Output/dataset/images/mm_1.png").isFile)
        assertTrue(File(root, "Output/screen_dataset/classes.txt").isFile)
        assertTrue(File(root, "Recordings/sphynx-session-1.mp4").isFile)
        // not recognised: left at the top
        assertTrue(File(root, "notes.txt").isFile)
        assertFalse(File(root, "groq_key.txt").exists())
        assertFalse(File(root, "dataset").exists())
        assertTrue(moved.isNotEmpty())
    }

    @Test fun aFileAlreadyInPlaceIsNeverOverwritten() {
        file("Input/groq_key.txt", "new key")
        file("groq_key.txt", "old key")
        StorageLayout.organizeIn(root)
        assertEquals("new key", File(root, "Input/groq_key.txt").readText())
        assertEquals("old key", File(root, "groq_key.txt").readText())   // left for the owner to decide
    }

    @Test fun sortingTwiceChangesNothing() {
        file("kaggle.json"); file("mlbb-learned.json")
        StorageLayout.organizeIn(root)
        assertEquals(emptyList<String>(), StorageLayout.organizeIn(root))
        assertTrue(File(root, "Input/kaggle.json").isFile)
    }

    @Test fun theThreeFoldersAlwaysExist() {
        StorageLayout.organizeIn(root)
        for (a in listOf("Input", "Output", "Recordings")) assertTrue(a, File(root, a).isDirectory)
    }
}
