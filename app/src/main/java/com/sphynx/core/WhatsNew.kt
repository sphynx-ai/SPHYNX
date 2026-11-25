package com.sphynx.core

// WHAT'S NEW: the app's own change list, shown on the "What's new" page and on Home.
// One line per merged change, newest first. Add an entry to the top in the same pull request as
// the change itself (the pull request number goes in `pr`, an issue it fixes in `issue`).
// Only changes that are IN this build belong here - nothing that is still waiting to be merged.
object WhatsNew {

    enum class Kind(val label: String, val color: String) {
        NEW("New", "#6D8BFF"),
        FIXED("Fixed", "#3DD68C"),
        IMPROVED("Improved", "#F4C25B")
    }

    data class Entry(
        val date: String,        // yyyy-mm-dd, when it was merged
        val kind: Kind,
        val title: String,
        val details: String,
        val pr: Int,
        val issue: Int? = null,
        val needsDeviceCheck: Boolean = false,  // merged, but not yet confirmed on a phone
        val version: String? = null             // the app version that brought it (from 37.20, owner 2026-11-14)
    )

    val ENTRIES: List<Entry> = listOf(
        Entry("2026-11-25", Kind.NEW, "Model Lab guide and phone checklist",
            "A short guide to the Model Lab is in docs/PHASE_9_MODEL_LAB_GUIDE.md: adding a model (download, the Input " +
                "folder, a GGUF file), which file goes where, picture parts (mmproj), reading a card, and freeing room. " +
                "The phone checklist for the whole of Phase 9 is in docs/PHASE_9_MODEL_LAB_PLAN.md, section 7.",
            pr = 133, needsDeviceCheck = true, version = "38.42"),
        Entry("2026-11-25", Kind.NEW, "Download a model from Hugging Face",
            "The Model page has DOWNLOAD A MODEL: a short list of models known to work with Sphynx (LiteRT-LM and GGUF, " +
                "with and without pictures). Each one shows its size and its Hugging Face fingerprint (SHA-256) before " +
                "anything downloads, and warns when you are not on Wi-Fi. A GGUF model with a picture part offers its " +
                "mmproj file too, and the two pair by themselves. The file goes into Sphynx-Storage/Input, goes on from " +
                "where it stopped after a break or a pause, and is checked against the fingerprint before the app takes " +
                "it. Nothing downloads by itself; the list is built into the app.",
            pr = 132, needsDeviceCheck = true, version = "38.41"),
        Entry("2026-11-25", Kind.NEW, "Export the model registry for debugging",
            "The Debug data page can save the Model Lab's registry now: every model with its details, its own settings " +
                "and what the app learnt about it, the two jobs and the storage line. It goes into Recordings and is " +
                "uploaded with the other debug data, then deleted from the phone. It names files only, never a folder " +
                "on the phone.",
            pr = 131, needsDeviceCheck = true, version = "38.40"),
        Entry("2026-11-25", Kind.FIXED, "What the app learns about a model lives in its Model Lab record",
            "What the app learnt about each model on this phone (whether it reads pictures, the best thread count from the " +
                "GGUF benchmark, which processor loaded it and how fast it answered, and its fingerprint) now lives in the " +
                "model's record in the Model Lab instead of loose settings. Your earlier results move in by themselves; " +
                "the old settings are still written for this version, so nothing is lost if you go back. Nothing plays " +
                "differently.",
            pr = 130, needsDeviceCheck = true, version = "38.39"),
        Entry("2026-11-25", Kind.NEW, "Model storage in the Model Lab",
            "The Model Lab starts with a STORAGE card: how much room the models take (by format), how much is free, a " +
                "warning when the phone is running out of room for another model, and the files nothing uses any more " +
                "(a copy that broke off, a model the app no longer lists), which you can delete after a question. A copy " +
                "still running is never counted. Both kinds of copy now keep the same 256 MB spare.",
            pr = 129, needsDeviceCheck = true, version = "38.38"),
        Entry("2026-11-25", Kind.NEW, "The Model Lab",
            "The Model page now starts with the Model Lab: one card for every model the app holds (LiteRT-LM, GGUF and " +
                "mmproj files), with its family, quantization, size, whether it reads pictures, where it came from, its " +
                "fingerprint, and the job it does. From the card you can give it the strategic or tactical job (any kept " +
                ".litertlm too), change its own settings, and delete the app's copy. A file in Sphynx-Storage/Input stays " +
                "there and is not copied back unless it changes. The cards in use come first.",
            pr = 128, needsDeviceCheck = true, version = "38.37"),
        Entry("2026-11-25", Kind.IMPROVED, "Settings per model (ready for the Model Lab)",
            "Behind the scenes: each model can now have its own settings: context length, temperature, threads and the " +
                "8-bit cache (GGUF), which processors it may use, and whether its picture part loads. Each value is checked " +
                "against safe limits. None is set yet, so every model runs exactly as before; the Model Lab page will let " +
                "you change them.",
            pr = 127, needsDeviceCheck = true, version = "38.36"),
        Entry("2026-11-25", Kind.IMPROVED, "Every LiteRT-LM file is kept, and every model is checked",
            "Your choice: every .litertlm in Sphynx-Storage/Input is now kept, not only the two in use. The others go to the " +
                "app's LiteRT-LM library, ready for the Model Lab. Before a .litertlm is copied, the app checks that it really is " +
                "one (not a web page, an unfinished download or an old .task zip) and that the phone has room. Every model copy " +
                "now gets its fingerprint (SHA-256), and a GGUF file already added under another name is not added twice.",
            pr = 126, needsDeviceCheck = true, version = "38.35"),
        Entry("2026-11-25", Kind.IMPROVED, "The app keeps a list of its models",
            "Behind the scenes, the first step of the Model Lab: the app now keeps one record for every model file it " +
                "holds (LiteRT-LM and GGUF, with their mmproj files): format, family, quantization, size, whether it reads " +
                "pictures and where it came from. It is updated after every folder scan and GGUF change, and copied to " +
                "Sphynx-Storage/Output so a reinstall finds it again. Nothing changes on the pages yet.",
            pr = 125, needsDeviceCheck = true, version = "38.34"),
        Entry("2026-11-25", Kind.IMPROVED, "Phase 9 starts: the Model Lab",
            "Behind the scenes: the Model Lab plan is approved with your answers. It keeps every LiteRT-LM file, offers " +
                "downloads from Hugging Face (size and fingerprint shown first), deletes only the app's copy, and has every " +
                "per-model setting.",
            pr = 124, needsDeviceCheck = true, version = "38.33"),
        Entry("2026-11-25", Kind.IMPROVED, "Phase 9 plan: the Model Lab",
            "Behind the scenes: the plan for Phase 9 is written for your OK. It covers one list of every model with its " +
                "details and state, checks for every file before it loads, more than two LiteRT-LM files, settings per " +
                "model, storage use, and a short guide. It has four questions for you.",
            pr = 123, needsDeviceCheck = true, version = "38.32"),
        Entry("2026-11-25", Kind.IMPROVED, "Phase 8 results from your phone",
            "Behind the scenes: from your 11-25 upload. The new llama.cpp writes Llama 3.2 1B at 22 tokens a second " +
                "(13.4 before) even with your phone hot; 4 threads is best; the 8-bit cache cost 16% speed, so it stays off; " +
                "loading and unloading 20 times left no memory behind. Two checks remain: the Vulkan test app on the GPU, " +
                "and the tactical minimap pictures after today's fix.",
            pr = 122, needsDeviceCheck = true, version = "38.31"),
        Entry("2026-11-25", Kind.NEW, "A Vulkan test app for GGUF on the GPU",
            "Your choice for the GPU: GitHub now builds a separate Vulkan test app every time the GGUF engine changes. It " +
                "installs next to Sphynx (its version ends in -vulkan-test), so your normal app and its data stay as they are. " +
                "In the test app the GGUF benchmark adds one more run with the model on the GPU, so you can compare it with " +
                "the CPU. Your normal app stays on the CPU.",
            pr = 121, needsDeviceCheck = true, version = "38.30"),
        Entry("2026-11-25", Kind.FIXED, "The small tactical model works again with the minimap switch on",
            "Found in your 11-25 debug data: with Tactical model reads the minimap on, a text-only tactical model (Gemma 3 " +
                "270M) loaded as if it could read pictures, and then every question failed, fight advice too. The app now " +
                "opens one test conversation while loading, so such a model loads text only and keeps answering. A minimap " +
                "picture that fails 3 times in a row is no longer retried every 5 seconds. The speed records now name the " +
                "GGUF model that does a job (they said Gemma 4 E2B for your Llama).",
            pr = 120, needsDeviceCheck = true, version = "38.29"),
        Entry("2026-11-23", Kind.IMPROVED, "Phase 8 is ready for your phone test",
            "Behind the scenes: every Phase 8 step is built except the GPU one, which waits for your choice. The GGUF " +
                "steps are: model details before loading, real engine tests on every change, a safer engine, a benchmark " +
                "with the best thread count, a newer llama.cpp, stability checks, and pictures for the tactical model. The " +
                "checklist for testing it all at once is in the Phase 8 plan.",
            pr = 119, needsDeviceCheck = false, version = "38.28"),
        Entry("2026-11-23", Kind.IMPROVED, "GGUF on the GPU: checked, not added yet",
            "Behind the scenes: the GPU (Vulkan) version of the GGUF engine was built and measured. It needs about 1,300 " +
                "GPU programs compiled while the app is built, which your phone build can't do, and it would make the app " +
                "about 35 MB bigger. GGUF stays on the CPU for now; the options are written down for your choice.",
            pr = 118, needsDeviceCheck = false, version = "38.27"),
        Entry("2026-11-23", Kind.NEW, "GGUF stability checks",
            "The GGUF benchmark card has a new button: Load and unload 20 times. It loads one GGUF model 20 times in a row " +
                "and checks that the app's memory comes back each time. During a 5v5 with a GGUF model doing a job, the app " +
                "also writes the memory, heat and battery temperature once a minute. Both are saved for the Debug data upload.",
            pr = 117, needsDeviceCheck = false, version = "38.26"),
        Entry("2026-11-23", Kind.IMPROVED, "Newer llama.cpp for GGUF models (b12651)",
            "The GGUF engine is updated from llama.cpp b11900 (October) to b12651 (November 23), the newest release. It " +
                "knows 16 more model families. Your phone test decides whether it stays: run the GGUF benchmark on the same " +
                "models as before; if it is slower or answers worse, the app goes back to b11900.",
            pr = 116, needsDeviceCheck = false, version = "38.25"),
        Entry("2026-11-23", Kind.NEW, "A GGUF benchmark, and the best thread count per model",
            "The Model page has a GGUF benchmark. It asks one GGUF model three fixed questions with 4, 2 and 6 CPU threads, " +
                "then with 4 threads and an 8-bit memory cache (about half the context memory), loading each setting fresh. " +
                "It shows the writing speed of each, and keeps the fastest thread count for that model when it is clearly " +
                "faster than 4. Every answer is saved with its speed, memory and heat for the Debug data upload. The " +
                "8-bit cache stays off until your test shows it costs nothing.",
            pr = 115, needsDeviceCheck = false, version = "38.24"),
        Entry("2026-11-23", Kind.NEW, "The tactical model can look at the minimap",
            "A new Model page switch, Tactical model reads the minimap (pictures), off by default. With it on, and a " +
                "tactical model that reads pictures (a .litertlm with vision, or a GGUF with its mmproj), the model looks at " +
                "the minimap every few seconds outside fights and writes one line: where the enemies are, whether they are " +
                "grouped, turtle or lord. That line goes into the next fight questions; fight advice never waits for a " +
                "picture. The job picker now marks models that read text only. Each reading is saved next to what the " +
                "detectors saw, so the Debug data shows whether it helps.",
            pr = 114, needsDeviceCheck = false, version = "38.23"),
        Entry("2026-11-23", Kind.FIXED, "Safer GGUF engine",
            "The GGUF engine (llama.cpp) can no longer crash the app from a stop or unload that comes at the wrong moment: " +
                "it now knows which models it has loaded, refuses a call on one already freed, and waits for a running " +
                "answer to stop before freeing it. If the phone runs out of memory while loading or answering, the Model " +
                "page says so in plain words instead of the app closing.",
            pr = 113, needsDeviceCheck = false, version = "38.22"),
        Entry("2026-11-23", Kind.IMPROVED, "The GGUF engine is now tested for real on every change",
            "Behind the scenes: every change to the app now also builds the real GGUF engine (llama.cpp) on GitHub's " +
                "computer and runs it with a tiny test model: loading, streamed answers, stopping an answer (also from " +
                "another thread), broken files, a picture without its mmproj, and 20 loads in a row. Until now the engine " +
                "was only compiled there. Nothing changes in the app itself.",
            pr = 112, needsDeviceCheck = false, version = "38.21"),
        Entry("2026-11-23", Kind.IMPROVED, "GGUF models are checked before they load",
            "The app now reads a GGUF file's own description before llama.cpp opens it: the model family, the " +
                "quantization (for example Q4_K_M), its size, how much text it was trained for, and whether it has a chat " +
                "format. The job picker shows this under each GGUF model. A broken file, an unfinished download, or a model " +
                "family newer than the app's llama.cpp is refused with the reason, instead of failing inside the engine.",
            pr = 111, needsDeviceCheck = false, version = "38.20"),
        Entry("2026-11-23", Kind.IMPROVED, "Phase 8 starts: how the GGUF engine fits together",
            "Behind the scenes: a document now explains how the GGUF engine (llama.cpp) is built into the app, who owns " +
                "what, its memory, which phones it supports and what each GGUF model measured on your phone. It also lists " +
                "four safety gaps that the next steps close.",
            pr = 110, needsDeviceCheck = false, version = "38.19"),
        Entry("2026-11-23", Kind.IMPROVED, "Phase 8 plan: finishing the GGUF engine",
            "Behind the scenes: the plan for finishing Phase 8 (GGUF models through llama.cpp) is approved, with your addition: both jobs read pictures. " +
                "It covers reading a model's details before loading it, tests that run the real engine, safer native code, " +
                "thread and memory tuning, a repeatable speed test, a newer llama.cpp, an experimental GPU switch, and a " +
                "full-match stability check.",
            pr = 109, needsDeviceCheck = false, version = "38.18"),
        Entry("2026-11-23", Kind.NEW, "Detectors get their own NPU switch",
            "The Model page has a new switch, Detectors on the NPU (experimental), off by default. It moves only the " +
                "minimap and screen detectors to the NPU, so Gemma can stay on the GPU (faster on your phone) at the same " +
                "time. Use the NPU (experimental) now moves only Gemma. The detectors' lines in the FILES card show where " +
                "they run and their time per run. They need the detector files in the Input folder.",
            pr = 108, needsDeviceCheck = true, version = "38.17"),
        Entry("2026-11-23", Kind.IMPROVED, "Phase 7 done: your phone's NPU result",
            "Behind the scenes: Phase 7 is closed after your phone test. Gemma ran on your phone's NPU (compiled on the " +
                "phone, about 12 s the first time, 10 s after that), but the GPU is faster: it writes about 7.3 tokens a " +
                "second against 5.4, and gives the first word in under half a second against about 2.3 s. Keep the NPU " +
                "switch off for play; it stays an experiment.",
            pr = 107, needsDeviceCheck = true, version = "38.16"),
        Entry("2026-11-23", Kind.IMPROVED, "Phase 7 is ready for your phone test",
            "Behind the scenes: every Phase 7 step is built (streamed answers, the memory question, the GPU fallback, " +
                "plain error words, the experimental NPU for Gemma and the detectors, and the best processor per model). " +
                "The checklist for testing it all at once is in the Phase 7 plan.",
            pr = 106, needsDeviceCheck = false, version = "38.15"),
        Entry("2026-11-23", Kind.NEW, "Each model picks its best processor on this phone",
            "The app now remembers, for each model file, how it did on each processor of your phone: whether it loaded, how " +
                "long the load took and how fast it answered. Next time it tries the fastest one first, and a processor that " +
                "failed, or closed the app while loading, is skipped until the app, the model file or LiteRT-LM changes. The " +
                "Model page says which processor goes first and why. Your GPU and NPU switches still decide what may be tried.",
            pr = 105, needsDeviceCheck = false, version = "38.14"),
        Entry("2026-11-23", Kind.NEW, "Experimental: the detectors on the NPU",
            "The same Use the NPU (experimental) switch now also puts the minimap and screen detectors on the phone's NPU. " +
                "Each is tested with one run when it loads; if the NPU can't run it, or fails later, it goes back to the CPU " +
                "until the next start. Their status line shows where they run and the time per run, so you can compare " +
                "with the switch off. The fight-choice network stays on the CPU.",
            pr = 104, needsDeviceCheck = false, version = "38.13"),
        Entry("2026-11-23", Kind.NEW, "Experimental: Gemma on the NPU",
            "A new Model page switch, Use the NPU (experimental), off by default. With it on, the strategic model is tried " +
                "on the phone's NPU first, then the GPU, then the CPU. The phone compiles the model for its NPU while it loads, " +
                "so the first time can take over a minute. A line under the switch says whether this phone can try it. If it " +
                "fails, the GPU is used as before; if the app closes while compiling, it is not tried by itself again.",
            pr = 103, needsDeviceCheck = false, version = "38.12"),
        Entry("2026-11-23", Kind.IMPROVED, "A GPU failure mid-match moves Gemma to the CPU",
            "If Gemma's answer fails on the GPU during play, the app now loads it again on the CPU once (with pictures) " +
                "and the floating panel says so for 2 minutes; the GPU is tried again at the next start. If it fails again " +
                "on the CPU, it is unloaded and play goes on with the rules. Model errors now say what went wrong in plain " +
                "words (not enough memory, a file the app can't read, the GPU stopped working) and what you can do.",
            pr = 102, needsDeviceCheck = false, version = "38.11"),
        Entry("2026-11-23", Kind.IMPROVED, "A question before a model that may not fit",
            "When you load a model by hand (the load button, the small-model switch, or the job picker), the app now first " +
                "compares the model's size with the phone's free memory, keeping about 2.5 GB for the game. If it probably " +
                "won't fit, it says so and asks; Load anyway still loads it. Loads at start only write the warning in the log.",
            pr = 101, needsDeviceCheck = false, version = "38.10"),
        Entry("2026-11-23", Kind.IMPROVED, "Faster fight advice: Gemma's answers now stream",
            "The language models now write their answers word by word inside the app. The small tactical model stops as soon " +
                "as it has named its choice (all in, poke, farm, retreat, group), so fight advice arrives sooner, on LiteRT-LM " +
                "and GGUF alike. The speed recorder now notes the time to the first word on every answer. The answers themselves " +
                "are the same.",
            pr = 100, needsDeviceCheck = false, version = "38.09"),
        Entry("2026-11-23", Kind.IMPROVED, "Phase 7 starts: LiteRT-LM checked, Gemma 4 E2B supported",
            "Phase 7 has started with a check of how the app uses LiteRT-LM. The Model page now names the supported model " +
                "(Gemma 4 E2B, tested on your phone). The app already uses the newest LiteRT-LM release (0.19.1), so there is " +
                "nothing to upgrade.",
            pr = 99, needsDeviceCheck = false, version = "38.08"),
        Entry("2026-11-23", Kind.IMPROVED, "Phase 7 plan: NPU compiled on the phone, best processor per model",
            "Behind the scenes: the Phase 7 plan now has your two additions. A Use the NPU (experimental) switch, off by " +
                "default, lets the phone compile Gemma for its NPU when it loads (the first time can take over a minute), " +
                "falling back to the GPU and then the CPU. And the app will remember, for each model, which processor works " +
                "best on your phone and use that one first. Waiting for your OK. Nothing in the app changes.",
            pr = 98, needsDeviceCheck = false, version = "38.07"),
        Entry("2026-11-23", Kind.IMPROVED, "Phase 6 finished; the plan for Phase 7 (LiteRT-LM)",
            "Your phone test of Phase 6 is recorded: the phase is complete. The Phase 7 plan is written from your answers: " +
                "Gemma's answers will arrive word by word, a warning will come before loading a model that may not fit next " +
                "to the game, a GPU failure in a match will fall back to the CPU once, and a newer LiteRT-LM will be tried in " +
                "its own update. It is waiting for your OK. Nothing in the app changes.",
            pr = 97, needsDeviceCheck = false, version = "38.06"),
        Entry("2026-11-22", Kind.IMPROVED, "Phase 6 is built: one phone test for all of it",
            "Every step of Phase 6 is in this version: every model goes through one common part, you choose which model " +
                "does which job (LiteRT-LM or GGUF, GGUF with pictures through its mmproj file), Step down gently switches to " +
                "a smaller model, and a model that crashed the app is not loaded again by itself. The checklist to test it " +
                "all at once is in the Phase 6 plan. From now on each phase is built completely before the phone test.",
            pr = 96, version = "38.05"),
        Entry("2026-11-22", Kind.NEW, "GGUF models can read pictures (with their mmproj file)",
            "A GGUF model can now read pictures, so it can do the strategic job fully (draft, loading cards, end screen). " +
                "Add the model and its mmproj file (the picture part, e.g. mmproj-gemma-3-4b-it-f16.gguf) to the GGUF library; " +
                "the app pairs them by name and loads the picture part only for the strategic job. Without an mmproj file the " +
                "model reads text only, as before. The app's engine is bigger, so the first build on the phone takes longer.",
            pr = 95, version = "38.04"),
        Entry("2026-11-22", Kind.NEW, "Model page: choose which model does which job",
            "New on the Model page, Which model does which job: tap the strategic or the tactical job to give it its usual " +
                "LiteRT-LM model or one of your GGUF models. The choice is kept and loads by itself at the next start. Only one " +
                "GGUF model runs at a time. A GGUF strategic model reads text only for now, so the picture questions wait for a " +
                "model that reads pictures.",
            pr = 94, version = "38.03"),
        Entry("2026-11-22", Kind.IMPROVED, "A model that crashed the app is not loaded again by itself",
            "If the app stops while a model is loading (for example the GPU driver crashes on a big model), the next start " +
                "does not load that model again by itself, so the app cannot crash in a loop. The Model page and the logs say " +
                "which model it was; loading it by hand (a Load button, or Scan the folder) tries it again.",
            pr = 93, version = "38.02"),
        Entry("2026-11-22", Kind.NEW, "Step down gently now switches to a smaller model",
            "Your idea is in: with Setup > Low memory and heat > Step down gently, when the phone gets warm or low on memory " +
                "a job's model is replaced by the next smaller model you have (LiteRT-LM files in the app or in " +
                "Sphynx-Storage/Input, or your GGUF models) instead of only being unloaded. The strategic job only takes a " +
                "model that also reads pictures. The panel says which model is standing in, and your own model comes back " +
                "after five quiet minutes, never in a fight. Only warn me (the default) never unloads or replaces anything, " +
                "for testing a model at full speed.",
            pr = 92, version = "38.01"),
        Entry("2026-11-22", Kind.IMPROVED, "Foundation: the pages read the models through the same part",
            "Behind the scenes, for Phase 6: the Play, Model and Performance pages now show whether each job's model is " +
                "loaded, where it runs and how fast it answered through the new part that decides which model does which job. " +
                "With your usual models the words are the same as before. A test now keeps the rest of the app from going " +
                "around it.",
            pr = 91, version = "38.00"),
        Entry("2026-11-22", Kind.IMPROVED, "Foundation: every model question goes through one part",
            "Behind the scenes, for Phase 6: loading Gemma and the small model, and every question the app asks them (draft, " +
                "plan, combo, fight advice, the speed test, the readiness test), now goes through the new part that decides " +
                "which model does which job. With your usual models it is the same engine, the same settings and the same " +
                "answers. Please play a 5v5 and run the model speed test to confirm.",
            pr = 90, version = "37.99"),
        Entry("2026-11-22", Kind.IMPROVED, "Foundation: stepping down to a smaller model",
            "Behind the scenes, your idea for Step down gently: when the phone is low on memory or hot, instead of only " +
                "unloading a model the app will switch that job to the next smaller model you have. The strategic job only " +
                "takes a model that also reads pictures, and your own model comes back when the phone has recovered. This " +
                "version adds the choosing rule; it starts working when the low-memory policy goes through the new model " +
                "part (Phase 6, step S5). Nothing in play changes yet.",
            pr = 89, version = "37.98"),
        Entry("2026-11-22", Kind.IMPROVED, "Foundation: one part decides which model does which job",
            "Behind the scenes, for Phase 6: a new part holds which model does the strategic job and which does the " +
                "tactical one, on LiteRT-LM or GGUF. It knows what each model can do (pictures or text only) and refuses a " +
                "question a model cannot answer, saying why. Nothing uses it yet, so play is exactly as before.",
            pr = 88, version = "37.97"),
        Entry("2026-11-22", Kind.IMPROVED, "Your resource snapshot is recorded",
            "The snapshot you uploaded (both models loaded: the app uses 2.7 GB and 3.2 GB of the phone's 11.1 GB stays " +
                "free) is now in the Phase 5 record, with your phone's details (Android 16). Three more would complete " +
                "the set: with nothing loaded, with screen capture on, and with a GGUF model loaded. Nothing in the app changes.",
            pr = 87, version = "37.96"),
        Entry("2026-11-22", Kind.IMPROVED, "Foundation: LiteRT-LM on the shared model contract",
            "Behind the scenes, for Phase 6: Gemma and the small model (LiteRT-LM) can now also run through the same " +
                "common contract the GGUF engine already uses, and one test kit checks both engines the same way. Nothing " +
                "uses the new path yet, so the models load and answer exactly as before.",
            pr = 85, version = "37.95"),
        Entry("2026-11-22", Kind.IMPROVED, "Development: the design decision for Phase 6",
            "Behind the scenes: the written decision for Phase 6. The strategic and the tactical job are each given a model, " +
                "and that model runs on LiteRT-LM or GGUF. One part decides which model does which job, checks what it can " +
                "do (pictures or text only), and does not reload a model that crashed the app. It is waiting for your OK. " +
                "Nothing in the app changes.",
            pr = 84, version = "37.94"),
        Entry("2026-11-22", Kind.IMPROVED, "Phase 5 finished; Phase 6 starts",
            "Your phone test of 37.91 and 37.92 is recorded and Phase 5 (reliability on Android) is closed, as you decided. " +
                "Phase 6 starts with the plan you approved: every model through one common part, any model for either " +
                "job (GGUF models included, with pictures through their mmproj file later), and a model that crashed the " +
                "app while loading is not loaded again by itself. Please upload the four resource snapshots from the Debug " +
                "data page. Nothing in the app changes.",
            pr = 83, version = "37.93"),
        Entry("2026-11-21", Kind.IMPROVED, "Development: the plan for Phase 6 (one way to run every model)",
            "Behind the scenes: a plan for the next phase. Every language model, LiteRT-LM or GGUF, will run through one " +
                "common part, and the strategic and tactical jobs will be assigned to models instead of being built into " +
                "them. Today the strategic job is always Gemma and the tactical job always the small model. It is waiting " +
                "for your approval. Nothing in the app changes.",
            pr = 82, version = "37.92"),
        Entry("2026-11-21", Kind.NEW, "Debug data: save a resource snapshot",
            "Your phone test of 37.87 to 37.90 is recorded. New on the Debug data page: one tap saves a snapshot of the " +
                "phone's memory, the app's memory and CPU, the heat and the battery, and which models are loaded, into " +
                "Recordings (uploaded with the debug data). Tap it four times: with nothing loaded, with screen capture " +
                "on, with the strategic model loaded and with a GGUF model loaded. These four snapshots are the last " +
                "thing Phase 5 needs.",
            pr = 81, version = "37.91"),
        Entry("2026-11-21", Kind.IMPROVED, "Development: lifecycle tests on an emulator",
            "Behind the scenes: every change is now also started on an Android emulator, which checks that each start " +
                "writes its diagnostics lines (device profile, start-up time, last close) and that the app survives its " +
                "screen being rebuilt and going to the background and back. Nothing in the app changes.",
            pr = 80, version = "37.90"),
        Entry("2026-11-21", Kind.NEW, "Setup: what to do when the phone is low on memory or hot",
            "A new choice on the Setup page, Low memory and heat. Only warn me (the default) shows a line on the floating " +
                "panel and in the log. Step down gently also unloads models by itself: the tactical model first, the " +
                "GGUF model when the phone is hot or very low on memory, and the strategic model only when memory is " +
                "critical. They load again after five quiet minutes, never during a fight, and play goes on with the " +
                "rules in the meantime.",
            pr = 79, version = "37.89"),
        Entry("2026-11-21", Kind.IMPROVED, "Privacy: your keys are left out of Android's backup",
            "The app's settings, which hold your Groq, Gemini, Cerebras and Kaggle keys, no longer go into Android's own " +
                "backup (Google Drive) or a transfer to a new phone. After a reinstall the keys are read again from " +
                "Sphynx-Storage/Input and calibration comes back from the Output backups, as before.",
            pr = 78, version = "37.88"),
        Entry("2026-11-21", Kind.IMPROVED, "Your phone test and your Phase 5 decisions are recorded",
            "Your phone test of 37.79 to 37.86 is recorded, and so is your confirmation of the older changes: nothing on " +
                "this page waits for a phone check any more. Your decisions for the next steps are written down too: a " +
                "Settings choice for low memory and heat, the online-model keys left out of Android's backup, start-up " +
                "left as it is (under half a second), and automatic emulator tests. Nothing in the app changes.",
            pr = 77, version = "37.87"),
        Entry("2026-11-21", Kind.IMPROVED, "Logs: how long the app takes to start",
            "Each start now writes one line into the diagnostics history: how long the app took to start, and how much " +
                "of that was spent before the first screen could draw. It marks a start over one second as slow. This " +
                "measures first, before any start-up work is moved. The diagnostics history also checks its own size less " +
                "often. Nothing in play changes.",
            pr = 76, version = "37.86"),
        Entry("2026-11-21", Kind.IMPROVED, "Development: a phone checklist for the app's lifecycle",
            "Behind the scenes: twelve phone checks (force stop, capture stopped by the system, accessibility off, " +
                "rotation, low memory, heat, offline, battery restriction, reinstall and more), each with what the " +
                "diagnostics history should show, so Android reliability can be confirmed step by step. Nothing in the " +
                "app changes.",
            pr = 75, version = "37.85"),
        Entry("2026-11-21", Kind.IMPROVED, "Development: a record of every permission and what leaves the phone",
            "Behind the scenes: a new document lists why the app needs each permission, every place it sends data to " +
                "(the online models, the wiki, the debug upload, Kaggle) and how long data stays on the phone. It found " +
                "that Android's own backup can copy the online-model keys; how to handle that is your decision. Nothing " +
                "in the app changes.",
            pr = 74, version = "37.84"),
        Entry("2026-11-21", Kind.NEW, "Ready check: battery restrictions",
            "The Ready check on Play now also shows when Android restricts the app in the background or optimises its " +
                "battery use; on some phones that stops or delays the app while the game is on screen. A tap opens the " +
                "phone's own battery page for Sphynx. It is optional: play still starts without it.",
            pr = 73, version = "37.83"),
        Entry("2026-11-21", Kind.NEW, "Logs: low memory and heat are written down",
            "When Android asks the app to free memory, or the phone starts or stops throttling because of heat, a line " +
                "now goes into the diagnostics history (and into a running session recording), with the phone's free memory " +
                "and the app's own. Switching to the game is not counted. This only records: the app does not unload " +
                "anything by itself yet. Nothing in play changes.",
            pr = 72, version = "37.82"),
        Entry("2026-11-21", Kind.NEW, "Performance: what this phone supports",
            "The Performance page has a new card at the top: this phone checked against what the app needs (Android " +
                "11, a 64-bit ARM processor, 6 GB of memory minimum, 8 to 12 GB recommended) and whether its processor can " +
                "run the GGUF engine, with the GPU and any NPU drivers it has. The same line is written into the " +
                "diagnostics history at each start and into every session recording, so recordings show which phone " +
                "made them. It only reads; nothing in play changes.",
            pr = 71, version = "37.81"),
        Entry("2026-11-21", Kind.NEW, "Logs: why the app closed last time",
            "When the app stops unexpectedly (a crash in the AI engines' native code, the app not responding, or " +
                "Android closing it for lack of memory) the reason is now written into the diagnostics history at the " +
                "next start, and the Logs page shows how it closed last time. For a \"not responding\" or a native " +
                "crash, Android's report is saved in Sphynx-Storage/Recordings and uploaded with the debug data. " +
                "Nothing in play changes.",
            pr = 70, version = "37.80"),
        Entry("2026-11-21", Kind.IMPROVED, "Phase 5 started: reliability on Android",
            "Your phone test of 37.64 to 37.78 is recorded: those changes are no longer marked as waiting for a phone " +
                "check. Phase 5 has started with a reliability check of the app: what it already does on Android, what " +
                "is missing (for example a record of why the app was closed last time), the phones it is known to work " +
                "on, and the order of the next steps. Nothing in the app changes yet.",
            pr = 69, version = "37.79"),
        Entry("2026-11-21", Kind.IMPROVED, "Development: review and feature plugins, and the rules for the Claude Code plugins",
            "Behind the scenes, for development only: the code-review, pr-review-toolkit, feature-dev, " +
                "commit-commands, security-guidance, pyright-lsp and kotlin-lsp plugins and the serena code tool " +
                "are turned on, and the project rules now say the superpowers plugin only helps the existing way of " +
                "working and never overrides it, and that you decide on the design, approvals and merges. Nothing in " +
                "the app changes.",
            pr = 68, version = "37.78"),
        Entry("2026-11-21", Kind.NEW, "Development: an Android build tool and a plugin for Claude Code",
            "Behind the scenes, for development only: the project now names an Android build helper (Gradle, adb, " +
                "logcat, UI automation) that Claude Code can use on a computer with the Android SDK and a connected " +
                "phone, and turns on the superpowers plugin for Claude Code. Nothing in the app changes.",
            pr = 67, version = "37.77"),
        Entry("2026-11-16", Kind.IMPROVED, "Phase 2 finished: Mobile Legends is the first game of a universal core",
            "The foundation work of Phase 2 is done: the app's core no longer names Mobile Legends; everything about " +
                "the game sits in its own part, checked by tests. Play should be exactly as before; please play a 5v5 " +
                "match and a Solo practice to confirm. Next: Phase 5, reliability on Android.",
            pr = 66, version = "37.76"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: a guide and a test kit for adding other games",
            "Behind the scenes: a guide now says what a second game would need (its names, rules, screen reading and " +
                "questions), how features it cannot provide behave, and how it is versioned and tested. A test kit checks " +
                "any game's part, and Mobile Legends passes it. Only Mobile Legends is supported; nothing in play changes.",
            pr = 65, version = "37.75"),
        Entry("2026-11-16", Kind.IMPROVED, "Safety: a last check on every decision",
            "Behind the scenes: after the trained network or \"Play like me\" has mixed into a decision, a last check " +
                "makes sure a retreat, recall or regen of the rules is never replaced, the joystick direction is a " +
                "valid one, and each button is pressed at most once. The learned parts already did this, so play should " +
                "not change; it is now guaranteed for any model.",
            pr = 64, version = "37.74"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the objectives' names come from the Mobile Legends rules",
            "Behind the scenes, for adding other games later: the last Mobile Legends words in the Player's reasons " +
                "(the Lord, the Turtle, the Purple Buff) now come from the Mobile Legends rules, and the check that keeps " +
                "the Player's decisions free of Mobile Legends names now covers them too. The reasons read exactly as " +
                "before.",
            pr = 63, version = "37.73"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the strategy questions come from the Mobile Legends rules",
            "Behind the scenes, for adding other games later: everything the app asks the strategic model about Mobile " +
                "Legends (the plan, the draft, the loading cards, your hero and battle spell, the build, ranked or " +
                "standard, the live match clock) and the Build page's text now come from the Mobile Legends part; the " +
                "strategy part only schedules the questions and applies the answers. The questions are word for word " +
                "the same, so the answers should not change.",
            pr = 62, version = "37.72"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the screen reading is split into a pipeline and Mobile Legends' readers",
            "Behind the scenes, for adding other games later: taking each captured frame, timing it and keeping the " +
                "latest reading is now one game-independent part; everything that reads Mobile Legends' screen (HP bars, " +
                "minimap, buttons, banners, bushes, the tower circle, the level badge, K/D/A) is Mobile Legends' screen " +
                "reader, plugged in at start. What the AI sees should be exactly the same, at the same speed.",
            pr = 61, version = "37.71"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the fight scores ask the Mobile Legends rules too",
            "Behind the scenes, for adding other games later: the scores the Player decides with (how safe you are, how " +
                "good a fight looks, which option wins) no longer name Mobile Legends heroes, skills, guides, towers or " +
                "buffs themselves; they ask the Mobile Legends rules, like the Player's decisions already do. The Player " +
                "decides exactly as before (all recorded situations unchanged).",
            pr = 60, version = "37.70"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the Player's decisions no longer contain Mobile Legends",
            "Behind the scenes, for adding other games later: the last Mobile Legends map data in the Player's decisions " +
                "(the lane corridors, the outer towers' places, the roam patrol) moved to the Mobile Legends rules, and a " +
                "test now fails if the Player's decisions ever name a Mobile Legends hero, spell, monster or map helper " +
                "again. The Player decides exactly as before (all recorded situations unchanged).",
            pr = 59, version = "37.69"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: jungle, towers and objectives move to the Mobile Legends rules",
            "Behind the scenes, for adding other games later: the jungle path and camps, the enemy towers, the turtle " +
                "and the lord, the learned map scenery, the map's mirroring and the role paths are now asked from the " +
                "Mobile Legends rules instead of being called by the Player's decisions directly. The Player decides " +
                "exactly as before (all recorded situations unchanged).",
            pr = 58, version = "37.68"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: battle spells move to the Mobile Legends rules",
            "Behind the scenes, for adding other games later: when to use Retribution, Flicker, Execute, Flameshot and " +
                "the other battle spells, and the Retribution percentages from your rulebook, are now asked from the " +
                "Mobile Legends rules instead of being written into the Player's decisions. The Player decides exactly " +
                "as before (all recorded situations unchanged).",
            pr = 57, version = "37.67"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the Player's hero special cases move to the Mobile Legends rules",
            "Behind the scenes, for adding other games later: the Player's decisions no longer name Mobile Legends " +
                "heroes themselves. Yu Zhong's dragon flight, Argus's and Thamuz's ultimates, Edith's ranged form, " +
                "skills that must not be interrupted, execute and multi-phase ultimates and dash skills are now asked " +
                "from the Mobile Legends rules. The Player decides exactly as before (all 25 recorded situations are " +
                "unchanged).",
            pr = 56, version = "37.66"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: more recorded fight situations to protect the Player",
            "Behind the scenes: nine more recorded situations that the Player's decisions are checked against on every " +
                "change, covering Mobile Legends' special cases (Retribution on a creep, a low lord after 8:00, Yu " +
                "Zhong's dragon flight, Argus's ultimate, the recall bar, Execute and Flicker kills, a 3-on-3 fight, a low " +
                "enemy near its tower). They guard the coming split of the Player's rules into a game-independent part " +
                "and a Mobile Legends part. Nothing changes in play.",
            pr = 55, version = "37.65"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the game's heroes, lanes and items through one adapter",
            "Behind the scenes, for adding other games later: the game-independent part of the app can now ask the " +
                "game's adapter for its characters, roles, lanes, battle spells, items and objectives, instead of " +
                "knowing Mobile Legends' lists itself. The Mobile Legends adapter reads the same lists the app already " +
                "uses. Nothing should look or behave differently.",
            pr = 54, version = "37.64"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the layering rule now has no exceptions",
            "Behind the scenes, for adding other games later: the code that translates the AI's advice into the shared " +
                "decision format moved next to the AI, so the game-independent part of the app no longer uses any " +
                "Mobile Legends code. A test now checks that this stays true. Nothing should look or behave differently.",
            pr = 53, version = "37.63"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: the model files no longer call the AI directly",
            "Behind the scenes, for adding other games later: finding, copying and loading the model files (Gemma, " +
                "the small model, the tactical networks, the detectors, the online backups) now reaches the parts that " +
                "run them through one plugged-in list instead of calling them itself. Nothing should look or behave " +
                "differently: Scan the folder, loading the models and the automatic loading at start work as before.",
            pr = 52, version = "37.62"),
        Entry("2026-11-16", Kind.IMPROVED, "Foundation: screen capture and picture matching no longer reach into the app",
            "Behind the scenes, for adding other games later: the screen capture service now reports when it starts " +
                "and stops (and the notification's emergency stop) instead of calling the AI and game code itself, and " +
                "the end-of-match and loading-screen readers ask for hero and item pictures the same way. Nothing should " +
                "look or behave differently: learning still starts with screen capture, an open match still closes a " +
                "minute after capture stops, and the emergency stop still stops everything.",
            pr = 51, version = "37.61"),
        Entry("2026-11-16", Kind.IMPROVED, "A marksman pokes a hurt enemy instead of waiting for the wave",
            "Your decision: when you play a marksman and a single enemy hero at 60% HP or less is within poke reach, " +
                "the Player now attacks it and uses its skills from range, keeping its distance, instead of standing at " +
                "the lane waiting for the minion wave. It still does not poke when more enemies are around, when the " +
                "enemy is under its tower, when your HP is low, or when the plan says retreat.",
            pr = 50, version = "37.60"),
        Entry("2026-11-16", Kind.IMPROVED, "The new look is finished; work on the app's foundation resumes",
            "Every step of the redesign (themes, the five sections, Play's Running now and Ready check, Hero and Play " +
                "style, status words, empty and offline states, the floating panel's colours) has been tested on the " +
                "phone, so that phase is complete. Work now goes back to the app's foundation: separating what any MOBA " +
                "needs from what is specific to Mobile Legends, so another game can be added later. Nothing changes on " +
                "screen in this version.",
            pr = 49, version = "37.59"),
        Entry("2026-11-16", Kind.IMPROVED, "Library says when it last learned; enemy slots can be set by tap",
            "Knowledge > Library now shows when the whole database was last learned and where from (the Mobile " +
                "Legends wiki and the online guides), or that it has not been learned as a whole yet. On Hero & match > " +
                "Hero, tapping one of the five enemy slots opens a hero list with a search box: pick the hero, or Clear " +
                "the slot. A hero read later from the loading screen or the scoreboard can still replace it.",
            pr = 48, version = "37.58"),
        Entry("2026-11-16", Kind.IMPROVED, "Setup and the first-run guide share one Permissions card",
            "Settings > Setup now shows the same Permissions card as the first-run guide, instead of its own copy of " +
                "the buttons: Accessibility service, Allow restricted settings, Display over other apps, Notifications " +
                "and All files access (which Setup did not have), each ending with Granted or Missing, and a chip that " +
                "counts how many are done.",
            pr = 47, version = "37.57"),
        Entry("2026-11-16", Kind.IMPROVED, "Clear empty, loading and offline states",
            "Pages now say plainly when there is nothing to show and what fills it: What the AI sees offers Start screen " +
                "capture when there is no capture, Recent actions says nothing has happened yet, and a Library search with " +
                "no result says so. Learning the whole database shows a moving bar while it runs (its button is the Stop). " +
                "Buttons that need the internet (learn the database or a hero's skills, update the meta, upload to GitHub) " +
                "are greyed out while the phone is offline, with a line saying why; everything else keeps working offline.",
            pr = 46, version = "37.56"),
        Entry("2026-11-16", Kind.IMPROVED, "Ready check lists only what is missing; a failed start says why",
            "The Ready check on Play now lists only what is not ready yet, each with its Fix, Start or Open button, the " +
                "needed ones first; when everything is ready it shows one line, Ready to play. While the strategic model " +
                "loads it shows a bar with the model's name. If an engine in Running now cannot start, a red line under it " +
                "says why, with a Try again button, instead of a message that disappears.",
            pr = 45, version = "37.55"),
        Entry("2026-11-16", Kind.FIXED, "Short labels no longer break inside a word; pictures have spoken names",
            "Names under hero and item pictures and the Library tabs used to break a long word across two lines " +
                "(\"Equipmen/t\"). They now take the largest size at which every word fits on its line, or end with " +
                "\"...\" if none does. Every hero and item picture now has its name for TalkBack (the phone's screen " +
                "reader), so it says \"Layla\" instead of \"image\".",
            pr = 44, version = "37.54"),
        Entry("2026-11-15", Kind.IMPROVED, "The floating panel takes the app's theme",
            "The floating panel in the game now uses the colours of the theme chosen on Settings > Setup > Theme: its " +
                "ground, buttons, borders and text, and the AI ON button filled with the theme's colour (teal in Signal, " +
                "cyan in Arcane, amber in Ember). Red STOP and the status colours stay the same in every theme. A panel " +
                "that is already open takes a new theme the next time it opens.",
            pr = 43, version = "37.53"),
        Entry("2026-11-15", Kind.IMPROVED, "Play is shorter: research tests on Practice, What's new under Settings",
            "The research tests (test the small model on the practice moments, clear the arbiter comparison, delete the " +
                "practice moments) moved from Play to AI & models > Practice, under Research tests. The What's new card " +
                "left Play too: the full list is on Settings > What's new. Play now shows what runs, what is missing, " +
                "what the AI sees and its recent actions.",
            pr = 42, version = "37.52"),
        Entry("2026-11-15", Kind.IMPROVED, "Meta: when it was updated and where it came from",
            "On Knowledge > Meta, the patch meta and the hero meta now start with one line: \"Updated <date> (<days> " +
                "ago) from <sources>\", so you can see at once how old the downloaded meta is and where it was read. " +
                "It replaces the Sources line at the bottom of each card.",
            pr = 41, version = "37.51"),
        Entry("2026-11-15", Kind.IMPROVED, "Hero: battle spell in one tap, enemy team as five portraits",
            "On Hero & match > Hero, the battle spell is no longer a button you tap through one spell at a time: all of " +
                "them are shown as buttons (Detect and the twelve spells) and one tap picks yours. The enemy team shows " +
                "five slots, each with the hero's portrait and name once it is read on the loading screen or the " +
                "scoreboard, and a question mark until then.",
            pr = 40, version = "37.50"),
        Entry("2026-11-15", Kind.IMPROVED, "Player is split into Hero and Play style; lane and Team / Solo are one-tap choices",
            "Hero & match now has four pages: Hero, Play style, Draft and Build. Hero holds your hero, player name, battle " +
                "spell, lane, the enemy team and what the app knows about the hero (game knowledge, how to play it, its " +
                "skills). Play style holds how the AI plays it: skill combos, behaviour, jungle, turtle and lord, level, " +
                "items, regen and recall, Coach and Data & resets. The lane is now a choice of six buttons (Auto, EXP, " +
                "Gold, Mid, Roam, Jungle) instead of six switches, and Draft's ranked Team / Solo is a two-button choice.",
            pr = 39, version = "37.49"),
        Entry("2026-11-15", Kind.NEW, "Play: Running now and Ready check",
            "Play now has one place that shows and switches what runs: the Running now card, with a switch for the " +
                "Player, the Drafter, the Builder and Learning, each with its state in words (On, On · Dry run, Off), and " +
                "the Dry run and Autonomous switches. Its badge says All off, Dry run or Live. Below it, the Ready check " +
                "lists the permissions (Granted or Missing, with a Fix button), screen capture (Start) and the strategic " +
                "model (Open), and says Ready or how many things are left to fix. The running switches on Player, Draft " +
                "and Build are replaced by a line saying whether each one runs. The research tests stay on Play under " +
                "Practice research for now.",
            pr = 38, version = "37.48"),
        Entry("2026-11-15", Kind.IMPROVED, "Player: all the reset buttons together under Data & resets",
            "The five buttons on Player that delete or reset something were spread over the page. They are now together " +
                "in one section at its end, Data & resets: Clear manual combo, Forget the remembered level badges, Forget " +
                "what Coach learned, Delete my recorded play and Reset learned aggression. Each still asks first.",
            pr = 37, version = "37.47"),
        Entry("2026-11-15", Kind.NEW, "Badges on features that are experimental or need the internet",
            "A section that is not fully working yet, or needs a connection, now says so with a small word badge at the " +
                "end of its header: Experimental on Autonomous ranked draft (Draft) and GGUF models (AI & models), Needs " +
                "internet on Train in the cloud (Learning) and Upload to GitHub (Debug data). Everything else works " +
                "offline and carries no badge.",
            pr = 36, version = "37.46"),
        Entry("2026-11-15", Kind.IMPROVED, "Setup is shorter: no more typing button positions by hand",
            "The long \"Type positions by hand\" list at the end of Setup (an x,y field for every button, shop item " +
                "and hero-select spot) is gone, with its \"Show markers\" switch. Every one of those positions is set " +
                "in the in-game calibration from the floating panel, which covers all the same groups. Presets and " +
                "\"Reset all positions to defaults\" stay.",
            pr = 35, version = "37.45"),
        Entry("2026-11-15", Kind.IMPROVED, "Every state says it in words, not only in colour",
            "The permission buttons on Setup and in the first-run setup now end with Granted or Missing, and the model " +
                "button with Loaded, Loading or Not loaded, besides turning green. On AI & models, each line of the files " +
                "card starts with a dot and a word in its colour: Ready (full dot), Partial (half dot: loading, or loaded but " +
                "switched off) or Missing (empty dot).",
            pr = 34, version = "37.44"),
        Entry("2026-11-15", Kind.IMPROVED, "Help for a group of switches sits on its header; even combo buttons",
            "The \"?\" that explains a group of switches (Theme, Heading font, Lane, Draft Team / Solo and the other " +
                "one-of-several choices) now sits at the end of the group's header instead of next to its last switch. " +
                "On Player, the six combo buttons (+ S1 ... + Spell) are now two rows of three equal buttons, each label " +
                "on one line.",
            pr = 33, version = "37.43"),
        Entry("2026-11-15", Kind.IMPROVED, "Buttons that delete or reset ask first, and are red",
            "Every button that deletes, clears, forgets, resets or removes something now has red text and a red outline, " +
                "and asks before it acts. Eleven of them used to act at once: Clear manual combo, Forget the level badges, " +
                "Forget what Coach learned, Reset learned aggression, Clear the comparison, Remove a model file, Clear the " +
                "match log, Clear the icon samples, Reset the sync offsets, Clear the map scenery and Reset all positions.",
            pr = 32, version = "37.42"),
        Entry("2026-11-15", Kind.FIXED, "Switches line up and buttons are the same width again",
            "A control with a \"?\" used to give up room for it, so its switch sat further left than the others and its " +
                "button was narrower. Now every switch sits at the right edge, with the \"?\" between its name and the " +
                "switch, and a button keeps its full width with the \"?\" inside its right end. Tapping a switch's name " +
                "still turns it on or off.",
            pr = 31, version = "37.41"),
        Entry("2026-11-15", Kind.NEW, "Three colour themes to choose from, with icons and a banner",
            "You said the grey look felt dead. The app now has three themes, chosen on Settings > Setup > Theme: " +
                "Signal (the brand's teal, the default), Arcane (violet with cyan) and Ember (amber). Each one gives a " +
                "colour to what is selected or switched on, the main buttons and the switches; the logo's diamond takes " +
                "the theme's colour too, in the app and on the floating panel. The bottom bar has a drawn icon for each " +
                "section, and the top card on Play is a banner in the theme's colour. Text sizes and the red STOP are " +
                "the same in every theme. The app icon on your phone's home screen stays teal for now.",
            pr = 30, version = "37.40"),
        Entry("2026-11-15", Kind.IMPROVED, "Five sections in a bar at the bottom instead of 14 tabs",
            "The 14 tabs you had to scroll sideways are now five sections, always visible at the bottom: Play, " +
                "Hero & match, Knowledge, AI & models and Settings. A section with several pages shows them in a row " +
                "under the header: Hero & match has Player, Draft and Build; Knowledge has Library and Meta; AI & models " +
                "has Models, Learning, Practice and Performance; Settings has Setup, Debug data, Logs and What's new. " +
                "Each section opens where you left it, and swiping left or right still walks through the pages. Nothing " +
                "was removed; the pages themselves are unchanged for now.",
            pr = 29, version = "37.39"),
        Entry("2026-11-15", Kind.IMPROVED, "The new look: brand colours, the app's own fonts, bigger text",
            "Phase 4 has started, with the redesign first as you asked. The main screen now uses the brand colours " +
                "(one set, checked for contrast by a test); main buttons are light with dark text and STOP is a deeper " +
                "red, both easy to read; green / amber / red buttons have dark text too. The app brings its own fonts: " +
                "a pixel font for the app name, the tabs and the headings, and Inter for everything else, so it no " +
                "longer depends on your phone's font. Choose between two pixel fonts on Setup (Heading font). Text is " +
                "at least 13, tabs, buttons and switches are at least 48 high. The pages are still the same 14: the " +
                "five sections come next.",
            pr = 28, version = "37.38"),
        Entry("2026-11-15", Kind.IMPROVED, "Development plan: Phase 3 (the new design) is complete",
            "You approved the design for all five sections: Play, Hero & match, Knowledge, AI & models, Settings & help. " +
                "The plan and mockups for the last three are written down (docs/PHASE_3_SCREEN_SPECS.md), with the list " +
                "of what gets built (docs/PHASE_3_DESIGN_BACKLOG.md). Your order: the new look first (colours, sizes, " +
                "fonts), then the bottom bar with five sections, a question before every delete, and one place that " +
                "shows what is running. The app itself does not change in this step: that is Phase 4.",
            pr = 27, version = "37.37"),
        Entry("2026-11-15", Kind.IMPROVED, "Long information texts go behind the \"?\" too",
            "From your screenshots: the long texts the app keeps updating (the practice moments and readiness test on " +
                "Home, the match timeline and jungle facts on Player, and others) were still on the page. Any such text " +
                "longer than 140 letters or 3 lines now sits behind the \"?\" of the row above it; the bubble shows " +
                "what it says at that moment. Short status lines (AI off, fps) stay on the page.",
            pr = 26, version = "37.36"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: first screens of the new design (Play, Hero & match)",
            "Phase 3: how the Play and Hero & match sections will look, card by card (docs/PHASE_3_SCREEN_SPECS.md), " +
                "with mockups of Play, Hero and Play style for your review: one card that shows what is running, a " +
                "ready check that lists only what is missing, the lane as one choice, the help \"?\" next to each " +
                "setting, and all resets in one card that asks first. They are drafts until you approve them. The app " +
                "itself does not change yet (Phase 4).",
            pr = 25, version = "37.35"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: what every screen shows when loading, empty or failing",
            "Phase 3: one way for each situation, used everywhere (docs/PHASE_3_STATE_MATRIX.md) - loading and progress " +
                "(with Stop where it can stop), empty pages with one next step, errors with Try again, missing " +
                "permissions with a Fix button, and a question before every delete or reset. It also lists the 11 " +
                "delete / reset buttons that act today without asking. The app itself does not change yet (Phase 4).",
            pr = 24, version = "37.34"),
        Entry("2026-11-14", Kind.IMPROVED, "Help \"?\" next to each setting, like the game",
            "As in the game's own settings: a small \"?\" now sits at the end of each button or switch that has an " +
                "explanation. Tap it and the explanation opens in a bubble next to it; tap anywhere else to close it. " +
                "The \"?\" in the header (hold to see every description of the page) is gone.",
            pr = 23, version = "37.33"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: the new look (colours, fonts, sizes)",
            "Phase 3: the design rules for the new look (docs/PHASE_3_DESIGN_TOKENS.md): one set of brand colours for the " +
                "app and the floating panel; your choice of fonts - a pixel-style font for headings and numbers and Inter " +
                "for longer text, both inside the app so every phone looks the same; bigger text (descriptions at least " +
                "14); buttons that are easy to read (dark text on light, a darker red STOP); every tap target at least " +
                "48; states always with a word, not only a colour. The app itself does not change yet (Phase 4).",
            pr = 22, version = "37.32"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: the new layout of the app (5 sections)",
            "Phase 3: from your screenshots and your yes, the plan for the app's layout: five sections in a bar at the " +
                "bottom - Play, Hero & match, Knowledge, AI & models, Settings & help - instead of 14 tabs; one place " +
                "that shows what is running; the Player page split in two; every delete / reset asks first " +
                "(docs/PHASE_3_INFORMATION_ARCHITECTURE.md). The audit now includes what your screenshots showed. The " +
                "app itself does not change yet: the new layout is built in Phase 4.",
            pr = 21, version = "37.31"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: Phase 3 (the app's design) has started",
            "Phase 2 is paused for now (what is done and where it continues is written down). Phase 3 is about how the " +
                "app looks and works: its first step is an audit of every page and the floating panel - what is on " +
                "them, what is hard to find, which texts are too small or low in contrast, which actions have no " +
                "confirmation (docs/PHASE_3_UX_AUDIT.md). The new design comes next, for your approval. No change to " +
                "the app itself.",
            pr = 20, version = "37.30"),
        Entry("2026-11-14", Kind.IMPROVED, "Clean-up: the session recorder no longer reaches into the AI",
            "Phase 2, second clean-up step: what a session recording writes about the match (positions, enemies, " +
                "the decision, the minimap...) is now put together by the AI loop and handed to the recorder, which only " +
                "writes it. The recording format is the same. Also through the app: whether a match may be recorded by " +
                "itself (5v5 only), the screen capture check, and the debug export's scenery picture. 6 more of the " +
                "known exceptions gone (14 left). Please record one session and check it uploads as before.",
            pr = 19, version = "37.29"),
        Entry("2026-11-14", Kind.IMPROVED, "Clean-up: the phone services no longer reach into the AI",
            "Phase 2, first clean-up step: the speed pages, the screen-to-touch log, the phone state, the session video " +
                "and the debug upload now get what they show (frame rates, model speeds, where the enemies are) from the " +
                "app when it starts, instead of reaching into the AI and game code themselves. The build checks this " +
                "direction from now on (11 of the 31 known exceptions gone). Nothing should look or work differently: " +
                "please check the Speed page, a session video and the debug upload once.",
            pr = 18, version = "37.28"),
        Entry("2026-11-14", Kind.FIXED, "Turning the AI off and on forgets the old fight",
            "When the AI was paused and started again, the Player's fast rules still remembered parts of what came " +
                "before: the last jungle monster (it could stand still \"holding position\" at a monster that was gone), " +
                "a Turtle / Lord rotation it had given up on (blocked for 60 s), the decision it was committed to, the " +
                "invade and travel timers. Starting again now begins clean. Found by the new MLBB safety net.",
            pr = 17, version = "37.27"),
        Entry("2026-11-14", Kind.IMPROVED, "A safety net for how the Player plays MLBB",
            "Phase 2: 16 fixed MLBB situations (a minion wave, a weak enemy in reach, low HP alone, outnumbered, an " +
                "enemy dashing at you, a jungle camp, the Turtle, an enemy tower, before the ultimate, dead, shop open...) " +
                "now go through the Player's fast rules in a test, and the decisions are compared with the recorded " +
                "ones. The coming clean-ups of the code must keep MLBB play exactly the same; any change shows up here. " +
                "Also: the new app id and the com.sphynx code name are confirmed on your phone. No change to the app itself.",
            pr = 16, version = "37.26"),
        Entry("2026-11-14", Kind.IMPROVED, "The code is now called com.sphynx inside too",
            "The app's code still carried its first name (com.example.mlbbresearch) inside; it is now com.sphynx, " +
                "the same as the app id. You see no difference, with one exception: Android lists the Accessibility " +
                "service under its new inside name, so turn Sphynx on again once in Accessibility. Please check that a " +
                "GGUF model still loads (Model page): its native bridge carries the name. New tests now check the names " +
                "the compiler cannot see (native functions, shrinker rules, manifest).",
            pr = 15, version = "37.25"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: a written contract for the game state",
            "Phase 2, step 3: the game-neutral description of a match moment (what the decision checks and the " +
                "decision log read) now has a version number and written rules (docs/contracts/UNIVERSAL_MATCH_STATE.md): " +
                "an unknown value is empty, never a guess or -1; health and progress are 0..1; MLBB-only details stay in " +
                "their own list. New tests check that the MLBB adapter always keeps these rules. No change to how the " +
                "app plays.",
            pr = 14, version = "37.24"),
        Entry("2026-11-14", Kind.IMPROVED, "New app id: com.sphynx",
            "The app id is now com.sphynx (was com.sphynx.autopilot). Android sees it as a new app, so it installs " +
                "next to the old one. Give the new one its permissions again (Accessibility, display over other apps, " +
                "All files access, screen capture). Your button and minimap calibration, scenery sync, learned data and " +
                "most settings come back by themselves from the backups in Sphynx-Storage/Output once All files access " +
                "is on (restart the app once); models and keys are read from Sphynx-Storage/Input as before. Check " +
                "the switches once. When the new app works, uninstall the old one.",
            pr = 13, version = "37.23"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: the target design for the app's parts",
            "Phase 2, step 2: a written decision (docs/adr/0001-layered-architecture.md) splits the app into four " +
                "layers - phone services, the game-neutral core, one adapter per game (MLBB first) and the app " +
                "screens - and says which may use which. A new check runs with every build and stops any change that " +
                "adds a forbidden link; the 31 links that exist today are listed and will be removed step by step. " +
                "No change to the app itself.",
            pr = 11, version = "37.22"),
        Entry("2026-11-14", Kind.IMPROVED, "Development plan: Phase 2 has started (architecture map)",
            "Phase 2 of the development plan makes the app's core work for any MOBA, with MLBB as its first game. Its " +
                "first step maps the app as it is today: which parts are game-neutral and which assume MLBB, how the " +
                "parts depend on each other, the path from a screen frame to a touch, and how the AI models are kept " +
                "within the safety rules (docs/PHASE_2_ARCHITECTURE_MAP.md). No change to the app itself.",
            pr = 10, version = "37.21"),
        Entry("2026-11-14", Kind.IMPROVED, "Version numbers go up with every change (now 37.20)",
            "The app was still called version 37.19, as on 2026-11-08, although dozens of changes had come since, so " +
                "two builds could not be told apart on the Home page. From now on the version goes up by one step " +
                "with every change merged into the test branch: 37.20 is this one, the next is 37.21. Each What's new " +
                "entry shows its version. Also: git now ignores the native build folder (app/.cxx), so it no longer " +
                "shows as an untracked file on your phone; and Phase 1 of the development plan is complete.",
            pr = 9, version = "37.20"),
        Entry("2026-11-14", Kind.IMPROVED, "Sphynx-Storage has three folders: Input, Output, Recordings",
            "Inside /CodeOnTheGoProjects/Sphynx-Storage: Input is where you put models and keys (.litertlm, .gguf, " +
                ".tflite, groq_key.txt, kaggle.json, github_token.txt); Output holds what the app makes (training " +
                "pictures, CSV exports, picture zips, Kaggle extras such as best.pt, the calibration and online-data " +
                "backups); Recordings holds the debug recordings and logs (uploaded, then deleted). On the next start " +
                "the app moves the files it recognises into their folder; anything it does not recognise stays where " +
                "it is, and nothing is overwritten. A file you put at the top by hand is still found. Also: the " +
                "Phase 1 report of the development plan (docs/PHASE_1_BASELINE.md).",
            pr = 8),
        Entry("2026-11-14", Kind.FIXED, "The Home page no longer shows an empty box for the power symbol",
            "The Home page line about the floating icon used the power symbol as text, and your phone's font has no " +
                "power symbol, so it showed an empty box. It now says \"the power button on the floating icon\".",
            pr = 7),
        Entry("2026-11-14", Kind.IMPROVED, "New Sphynx icon; the floating panel has drawn icons and a new look",
            "The app has its new symbol: two interlocking halves around one point of focus (launcher icon, themed " +
                "icon, the header). On the floating panel the AI button had no icon: the panel used text symbols, and " +
                "your phone's font has no power symbol. Every icon on the panel and the round floating icon is now " +
                "drawn, so it looks the same on every phone. The panel is restyled to match: the symbol and a status " +
                "word in the title, evenly sized buttons, and the AI button filled teal while the AI is on. The round " +
                "icon shows the symbol with ON / DRY / ERR / OFF under it and a ring in the status colour.",
            pr = 6),
        Entry("2026-11-14", Kind.NEW, "GGUF models: add, load and ask them on the Model page",
            "The GGUF engine (llama.cpp) is now a real part of the app instead of a one-off test. On the Model page, " +
                "add a .gguf model (it is copied into the app), load it, and ask the test question: the answer streams " +
                "in as it is written, Stop ends it, and the speed is shown. The model stays loaded until you unload it. " +
                "It runs on the CPU. A phone whose processor is too old is told so instead of the app closing. Your " +
                "2026-11-14 tests: Qwen2.5 0.5B answered at 25.5 tokens per second, Llama 3.2 1B at 13.4. The AI " +
                "does not use GGUF models yet.",
            pr = 5),
        Entry("2026-11-14", Kind.IMPROVED, "The app is called Sphynx; descriptions show only while you hold \"?\"",
            "The app's new name is Sphynx: the launcher, the header, the notifications and the pages. Its folder is now " +
                "/CodeOnTheGoProjects/Sphynx-Storage (Download/Sphynx-Storage without All files access), the debug uploads " +
                "and the training notebooks come from the sphynx-ai/SPHYNX repository, and the test branch is " +
                "sphynx-test-branch. The app id stays the same, so the app updates in place and keeps its calibration and " +
                "permissions. The descriptions under the buttons and switches are now hidden (the names stay): press and " +
                "hold the small \"?\" circle next to STOP to see them, and they hide again when you let go. Lines that " +
                "show a live reading stay visible.",
            pr = 4),
        Entry("2026-11-13", Kind.NEW, "The app reads which buff you hold",
            "While you hold a jungle buff, emblems of its colour circle your hero: orange for the orange buff, purple for " +
                "the purple buff (you can hold both). The app now reads them: the floating panel shows \"Buffs held\", the " +
                "log notes each change, and every recording row carries it. The orange reading is measured on your " +
                "2026-11-13 recording (22 of 22 screenshots right). The purple colour is a first guess - that recording has " +
                "no frame with it - so a screenshot is saved with the recording each time a buff reading changes, to " +
                "confirm it. The jungle route does not use the reading yet.",
            pr = 84),
        Entry("2026-11-13", Kind.FIXED, "Phantom 3% monsters, and camp monsters read as enemy heroes",
            "From your 2026-11-13 recording. Small orange / red things next to dark ground (the emblems that circle your hero " +
                "while you hold the orange buff, damage numbers) were read as monsters with 3-5% HP, and the Player \"finished\" them on empty ground: a " +
                "monster or minion bar at 6% or less now only counts where a fuller bar was seen in the last 2 seconds. And " +
                "the first sighting of a camp monster's bar (right above your own bar) was taken for an enemy hero for a " +
                "moment, so the Player backed off in the middle of a camp: while you stand at a jungle camp or buff spot, a " +
                "bar is an enemy hero only when its level number can be read.",
            pr = 82),
        Entry("2026-11-13", Kind.FIXED, "The AI remembers where it got stuck",
            "When the hero walks into a wall the minimap did not show, that place used to be forgotten at the start of " +
                "every match, so it got stuck at the same walls again. Now each stuck place is counted across matches: " +
                "stuck there twice (in any matches) and it is a wall for the path finder from the first second of every " +
                "later match. A place the hero later stands on is forgotten again. Setup page (scenery section): how many " +
                "places are remembered, and a button to forget them.",
            pr = 81),
        Entry("2026-11-13", Kind.FIXED, "Gemma on the GPU; training circles for the AI's fights and yours",
            "Turning the GPU switch on (Model page or the first-run setup) now reloads the model on it at once: in your " +
                "2026-11-13 match Gemma stayed on the CPU (line-up read 122 s, strategy answers 12-17 s). The GPU switch is " +
                "ON on a new install (a speed setting). Learn page: the tactical engine circle now counts only the AI's " +
                "fights, and a new circle counts your own fights (the first 84% was yours). The detector results show how " +
                "many pictures they were tested on.",
            pr = 80),
        Entry("2026-11-13", Kind.FIXED, "Misreads and log noise from the 2026-11-13 recording",
            "The K / D / A counter at the top is no longer read as a jungle monster at 3-5% HP. Recording screenshots: at " +
                "most one \"monster\" a minute and one \"enemy hero\" every 20 s (30 of 39 were monsters). The learned enemy " +
                "tower range no longer jumps (it was 6% to 16% in one match): misreads outside 4-10% are ignored and it " +
                "moves one point at a time. The scoreboard's spell list starts empty in every match, and one scoreboard " +
                "read no longer contradicts the spell button already recognised. An online answer cut off in the middle " +
                "keeps its complete part (the meta update failed on one). The Coach's \"observed your sequence\" line is " +
                "logged only when it changes.",
            pr = 79),
        Entry("2026-11-13", Kind.IMPROVED, "No learning in Solo practice; learning from you by itself in 5v5",
            "Solo practice vs Computer is for calibration only (skill buttons, scenery): nothing is learned or recorded " +
                "there - no recorded moments, no practice benchmark, no minimap or screen training pictures (the tour-stop " +
                "pictures too), no fight scoring and no automatic recording. In a 5v5 match (vs Computer or real players) " +
                "with the Research player off or only advising, the app learns from you by itself, and the floating panel " +
                "says so (or says why it is not learning). Solo or 5v5 is now read from the loading screen and the " +
                "scoreboard even with the Research player off. In the first-run setup the skill button calibration comes " +
                "before the scenery calibration.",
            pr = 78),
        Entry("2026-11-13", Kind.FIXED, "Recordings cover the whole match, without duplicate files",
            "Your 2026-11-13 recording covered only about half of the match: each part stopped at 25 MB (~70 s) and the next " +
                "one had to be started by hand. Now a full part is closed and the next one starts at once, always (not only " +
                "with the automatic upload); the video uses half the data and a part is 50 MB, about 4 minutes. The settings, " +
                "the log, the scenery picture and the research files are saved once per recording (with its last part), not " +
                "with every part, and the CSV copies are gone. The match's own scorecard is saved before the recording is " +
                "closed. When screen capture stops in the middle of a match, the match is closed as unfinished after a " +
                "minute, so its scorecard and recording are still saved.",
            pr = 77),
        Entry("2026-11-12", Kind.FIXED, "The small tactical model keeps learning between trainings",
            "On Kaggle each training of the small tactical model started again from plain Gemma 3 270M. Now the notebook " +
                "also saves a trainable copy (tactical_llm_trainable.zip, ~500 MB) that the app downloads and sends with the " +
                "next training, which continues from it (use Wi-Fi). The app's own copy of that notebook was also out of date: " +
                "it never wrote the quality report, so the Model page could not tell a weak model and the Learn page had no " +
                "score for it. Fixed.",
            pr = 76),
        Entry("2026-11-12", Kind.NEW, "First-run setup",
            "A new install opens a setup screen with six steps that turn green when done: permissions (accessibility, " +
                "display over apps, notifications, all files access), the app's folder, loading the models, choosing what " +
                "runs (every switch starts off; Dry run stays on), scenery calibration and skill button calibration. " +
                "Finish setup goes to Home; Setup > Open the first-run setup opens it again. Every calibration is now on the " +
                "Setup page: the scenery calibration and minimap synchronization moved there from the Player page.",
            pr = 75),
        Entry("2026-11-12", Kind.NEW, "Training progress circles, and training data in the GitHub upload",
            "The Learn page shows a circle for each training (minimap detector, screen detector, tactical networks, small " +
                "tactical model, tactical engine): how much better (green) or worse (red) the last training is than the one " +
                "before. The detector notebooks now report their score. The Debug data page's upload also sends the training " +
                "results and the minimap / screen pictures taken since the last upload (zips of up to 95 MB). Every file is " +
                "deleted from the phone once it is on GitHub; the picture zips used for training are deleted after a " +
                "successful training (the detector keeps what it learned in its best.pt). New buttons: Delete all minimap " +
                "pictures and Delete all screen pictures (Learn page).",
            pr = 74),
        Entry("2026-11-12", Kind.NEW, "New app icon and logo",
            "A sphynx cat head (big ears, amber eyes) with an arrow for a nose and an AI spark, on the dark " +
                "background - Sphynx. It is the launcher icon (also the one-colour themed icon on Android 13+) and " +
                "the logo at the top of the app.",
            pr = 73),
        Entry("2026-11-12", Kind.IMPROVED, "Every switch starts off on a new install",
            "On a fresh install every feature switch starts OFF, so nothing runs until you turn it on (the first-run setup " +
                "helps). Safety guards keep their safe position: Dry run ON and the Quick buy guards ON. A phone that already " +
                "had the app keeps how it worked: switches you never changed stay on.",
            pr = 72),
        Entry("2026-11-12", Kind.IMPROVED, "Minimap and screen picture collectors tuned",
            "Both collectors skip near-duplicate pictures (nothing moved since the last one). The screen collector also takes " +
                "a clean picture at every calibration tour stop (walls, bushes, pits and towers all over the map), keeps one " +
                "monster from filling the set, and skips moments when your HP bar is covered; its labels follow the new rules " +
                "(real enemy heroes only, monsters named by their spots on both lane layouts). Minimap picture names now tell " +
                "the lane layout and Solo / 5v5 (mm_<time>_gs.png).",
            pr = 66),
        Entry("2026-11-12", Kind.NEW, "Recordings continue in a new file at the size limit",
            "Your last recording stopped by itself every ~68 s (the 25 MB video limit) and about 2 minutes of the match were " +
                "lost between files. With the automatic GitHub upload on (and a token), a full recording is now saved and " +
                "uploaded while the next part starts by itself, so the whole match is covered. Without the upload it stops and " +
                "saves at the limit, as before.",
            pr = 65),
        Entry("2026-11-12", Kind.NEW, "Debug data page: every export in one place, uploaded to GitHub by a button",
            "A new Debug data page holds everything needed for debugging: the session recorder (moved from Logs), the speed / " +
                "scorecard exports (moved from Performance and Practice), a full-log export, and \"Upload the debug data to GitHub " +
                "now\" - it commits what is in the recordings folder to the branch recordings-for-debugging (all non-video files " +
                "in one commit, each video in its own), and by itself after each recording if you leave that switch on. Put a " +
                "fine-grained GitHub token (this repository only, Contents: read and write) in github_token.txt in the app's " +
                "folder. The training data for the notebooks is not part of it.",
            pr = 64),
        Entry("2026-11-12", Kind.NEW, "Recordings now carry the debug data",
            "Every recording also saves: clean screenshots at key moments (the gate's readings, the lane side decided, an enemy " +
                "hero or monster first seen, your HP read as 0%, the pictures Gemma was given), everything the app saw each frame " +
                "(allies, minions, towers, the minimap reading, the gate, the lane side, Solo / 5v5), every touch sent and how long " +
                "it waited, Gemma's prompts and replies, the calibration tour's log, your settings (no keys or tokens), the full " +
                "log with crashes, and a picture of the saved scenery map.",
            pr = 63),
        Entry("2026-11-12", Kind.NEW, "Recording starts by itself at the start of every match",
            "The session recorder now starts on its own when a match starts (the loading screen, or your hero first seen) and " +
                "stops and saves at the match end - switch it off on the Logs page if you don't need it (a recording you start " +
                "yourself is never stopped by the match end). The video's time / decision banner moved to the BOTTOM (at the top " +
                "it covered the minimap's lane mark), and every recording's start row names the build's git commit.",
            pr = 62),
        Entry("2026-11-12", Kind.IMPROVED, "Gemma freed: no practice check by Gemma, line-up read once",
            "Your recording: the Gemma line-up read took 50 s on the loading screen and 70 s on the scoreboard (Gemma could do " +
                "nothing else meanwhile), and its practice check misread the Computer cards. Now the practice gate uses only its " +
                "pixel check, and Gemma reads the line-up once per match on the loading screen - never on the scoreboard (the " +
                "enemy spells are read there from the icons) and never in Solo practice.",
            pr = 61),
        Entry("2026-11-12", Kind.IMPROVED, "Faster touches: joystick slices 120 ms instead of 320 ms",
            "Your recording measured about 280 ms from screen to touch, ~240 ms of it waiting in the touch queue: every joystick " +
                "slice was held 320 ms and the next decision waited for it to end. A joystick that is already held now continues in " +
                "120 ms slices (the first press still holds 320 ms so the game registers it), and skills / taps are no longer " +
                "stretched to the joystick's 320 ms. Check the Performance page: the touch queue should drop.",
            pr = 60),
        Entry("2026-11-12", Kind.FIXED, "Paths keep out of enemy tower range; no regen while being hit",
            "Your recording had 198 frames of \"inside the enemy tower range with no minions: getting out\" - the jungle and gank " +
                "paths cut through enemy towers' range - and at 2:29 the Player stood still to regenerate while taking hits. Now the " +
                "paths go around enemy towers' range when there is a way (a goal inside it can still be reached), and Regen is " +
                "neither started nor continued while your HP is dropping (being hit).",
            pr = 59),
        Entry("2026-11-12", Kind.FIXED, "A sudden 0% HP reading is ignored",
            "Your recording had 94 frames where your HP read 0% while the bar was full (effects and the buff's bars covered it), " +
                "which made the Player stand \"waiting\". Now a 0% reading right after a healthy one is ignored (the last good reading " +
                "is kept) unless the death screen shows or the 0% lasts 1.5 s.",
            pr = 58),
        Entry("2026-11-12", Kind.FIXED, "Commits to the Turtle / Lord instead of flip-flopping",
            "In your recording \"turtle is up: rotating there\" ran from 2:07 to 7:18 and the Player never reached the pit. Now, once " +
                "it starts rotating to the Turtle or Lord, that goes first (before the jungle route, ganks, waves) until it " +
                "arrives - unless enemies are near or its HP is under 40%. Not there after 45 s: it gives up on the objective " +
                "for 60 s. The panel shows \"(committed: N s)\". The Lord still needs 3 or more allies.",
            pr = 57),
        Entry("2026-11-12", Kind.FIXED, "Research experiment (R3) is safety-first",
            "In your recording the research experiment changed the fight choice 82 times, always to \"keep farming\" because the " +
                "strategy's generic plan said farm - 19 times over a retreat and 30 times over an all-in. Now it never overrides a " +
                "retreat, and the strategic plan's generic \"farm\" alone cannot turn an all-in into farming.",
            pr = 56),
        Entry("2026-11-12", Kind.FIXED, "Buffs and camps are finished, not left alive",
            "In your recording (0:36-0:46) the Molten Fiend's bar was read as enemy minions (\"clearing the wave\", \"last hit: minion\") " +
                "in a pit with no minions, so the Player walked off to \"a jungle camp\" while the buff was still alive. Now, while " +
                "you stand at a known jungle camp and no enemy minion wave is near you on the minimap, those bars are the camp's " +
                "monster: the Player keeps hitting it until it dies (and uses Retribution on the buff). Low-HP enemy heroes are now " +
                "real ones only (the ghost \"enemies at 3%\" were fixed in the previous update), so real kills are taken.",
            pr = 55),
        Entry("2026-11-12", Kind.FIXED, "The Lord only with 3 or more allies",
            "The Research player kept hitting the Lord with no ally near and was killed by it. Now it starts, finishes or rotates " +
                "to the Lord only with 3 or more allied heroes near it (on the screen, or on the minimap around you); with fewer it " +
                "walks away from the Lord. The Turtle is unchanged.",
            pr = 54),
        Entry("2026-11-12", Kind.FIXED, "Jungle monsters no longer taken for low-HP enemy heroes",
            "Your recording (0:36) showed the Molten Fiend's bar read as two enemy heroes at 3% and 25% - the Player left the buff to " +
                "\"chase a kill\" and spent Retribution on ghosts. A red bar now counts as an enemy hero only with its own level badge " +
                "(your own badge next to it no longer counts) AND an enemy hero icon near you on the minimap; while a camp is being " +
                "fought, a bar where the monster was just seen stays the monster unless its level number can be read.",
            pr = 53),
        Entry("2026-11-11", Kind.IMPROVED, "Without All files access, files go to Download/Sphynx-Storage",
            "Recordings (\u25cf REC), CSV exports, training pictures and zips, cloud-training results and backups are saved in " +
                "Download/Sphynx-Storage when the app has no All files access. The old Download/MLBB-Research folder is " +
                "ignored completely - not written and not read (backups there no longer restore); delete it yourself once you " +
                "have moved anything you want to keep. With All files access nothing changes: " +
                "/CodeOnTheGoProjects/Sphynx-Storage.",
            pr = 52),
        Entry("2026-11-11", Kind.FIXED, "Solo calibration no longer waits for lane marks",
            "Solo practice never shows the \"$\" / \"E\" lane marks (only 5v5 does), so the calibration tour waited forever. In " +
                "Solo, which lane is on top is now read from the Lord and Turtle icons on the minimap: the Lord's pit is on the Gold " +
                "lane's side (Lord top-left = Gold on top). Both icons must show in their pits. Also: the floating panel's title is " +
                "now SPHYNX AI.",
            pr = 51),
        Entry("2026-11-11", Kind.FIXED, "Your lane corrections for 10 heroes",
            "Alpha: Jungle, then EXP · Chou: EXP, then Roam · Lapu-Lapu: EXP only · Lunox: Mid, then Gold · Masha: EXP, then Roam · Minsitthar: EXP, " +
                "then Roam · Paquito: EXP, then Jungle · Phoveus: EXP only · Valentina: Mid, then EXP · Yi Sun-shin: Jungle, then " +
                "Gold. These come before the online guide and the wiki (your own per-hero setting still comes first).",
            pr = 50),
        Entry("2026-11-11", Kind.NEW, "Recommended lanes per hero: primary, secondary and flex pick",
            "Every hero now has a recommended primary lane, a secondary lane, and whether it is a flex pick (playable in almost " +
                "any lane) - from its online guide (now asked for both lanes and flex) and every wiki laning list it is in. Shown " +
                "on the Library's hero list and hero page. The automatic lane uses it: the primary lane; without Retribution never " +
                "the Jungle (the secondary instead); a flex pick takes its secondary when an ally's primary is the same lane. Your own " +
                "setting per hero comes first (Player page, under the selected hero: e.g. \"exp, jungle\" or \"exp, jungle, flex\"), " +
                "then lanes you confirmed (Alice: EXP, then Jungle - not Roam or Mid), then the online guide, then the wiki.",
            pr = 49),
        Entry("2026-11-11", Kind.NEW, "Gold and EXP lanes swapped between matches: read from the minimap's lane marks",
            "Some matches put the Gold lane on top, others the EXP lane, with the Lord and Turtle pits swapped too: the Turtle is " +
                "always by the EXP lane, the Lord by the Gold lane. At the start of each match the minimap's top-left lane mark (gold " +
                "\"$\" or lavender \"E\") is read and shown on the panel. Lane walking and tower targets follow the lanes; the Lord / " +
                "Turtle pits and the jungle camps (measured in your Gold-on-top matches, Lord top-left) are mirrored when the EXP lane " +
                "is on top, where scenery is not learned and the calibration tour waits for a Gold-on-top match. Also: only one " +
                "Lithowanderer is up at a time (either side of the river, back 120 s after its kill), the enemy buffs' spots are " +
                "measured from your screenshots. Solo practice or 5v5 is told from the loading screen / scoreboard (shown on the " +
                "panel): in solo practice (calibration) both the Lord and the Turtle are up and named by their pit; in 5v5 the " +
                "wiki's timers apply (Turtle at 2:00, no Turtle from 9:00, the Lord then in its pit). The scenery calibration tour " +
                "runs only in Solo mode vs computer (god mode is only there), on either lane layout once the lane mark is read: " +
                "what it sees on a mirrored map is saved mirrored back. In Solo every jungle monster is up from 0:00.",
            pr = 48),
        Entry("2026-11-11", Kind.IMPROVED, "Every jungle monster named; the Stone Roamer is not counted as an ally",
            "From your 20 jungle screenshots: each monster is named from where you stand on the minimap and its bar's size - " +
                "Thunder Fenrir, Molten Fiend, Lava Golem, Fire Beetle, Horned Lizard, Scavenger Crab (both river ends) and the " +
                "Lithowanderer (in the river by the mid crossing), on both sides of the map. After a Lithowanderer kill, the Stone " +
                "Roamer (blue bar, no level badge) is a friendly patrol for 45 s: not counted as an ally hero or minion. The Walkie " +
                "Grass is never a target. The jungle route uses these camp positions too (the Rockursa is replaced by the Lava Golem; the Lizard, " +
                "Fire Beetle and Lithowanderer were in the wrong places), and the scenery calibration tour visits the real pits.",
            pr = 47),
        Entry("2026-11-11", Kind.IMPROVED, "Scenery calibration tour reaches the enemy base; never into the enemy fountain",
            "The calibration tour now also visits the enemy inner and base towers of all three lanes and the enemy base itself " +
                "(placed from the towers on your minimap), then walks back to your base. Meant for 1v1 vs Computer with god mode on. " +
                "In every mode the Research player never enters the enemy fountain: inside it walks out, at its edge no step or skill leads in.",
            pr = 46),
        Entry("2026-11-11", Kind.NEW, "Scenery calibration ends with a walk back to base",
            "After the last stop of the calibration tour the Research player walks back to its base (base gate, then the " +
                "fountain), still without fighting. The scenery keeps being read on the way and is re-synced and saved at both " +
                "stops, so the walk back revalidates what the tour learned. The panel shows \"returning to base 1/2\".",
            pr = 45),
        Entry("2026-11-11", Kind.IMPROVED, "Performance page redesigned",
            "Laid out like a phone performance screen: the phone's name, then one card per topic with a big headline number " +
                "and label / value rows - memory (ring of RAM in use, available and total, 5-minute line), storage, CPU (one tile " +
                "per core with its clock and maximum), graphics processor, NPU drivers, temperature and battery, local models. " +
                "The repeated phone figures are gone; the AI's speed and the speed recorder follow in the same style.",
            pr = 44),
        Entry("2026-11-11", Kind.IMPROVED, "Research player; shorter floating panel; calibration without fighting; Performance page",
            "The Player switch is now \"Research player\" and the floating panel says so (no more \"Player + Research + Learning " +
                "from you\"; learning is named only while coach mode is on). The panel shows less: what runs, the practice check and " +
                "its last reading, what the Player does, the match in one line. Scenery calibration starts and stops from the panel " +
                "(CALIBRATE / STOP CALIB) and the Player does not fight while it runs. The Speed page is now the Performance page: " +
                "the phone's memory, storage, CPU and cores, graphics processor, NPU drivers, temperature and battery, and the local models.",
            pr = 43),
        Entry("2026-11-10", Kind.FIXED, "Practice check reads \"Computer\" at the app's capture size",
            "The app reads the screen at 800x359, where \"Computer\" is only about 49x9 pixels: the practice check read it as " +
                "unclear (0.66) and never unlocked the Player. It now matches the word at that size (0.92 on the scoreboard, " +
                "0.81-0.89 on loading cards; real players' names 0.50 at most), and finds the scoreboard rows with a player there too.",
            pr = 42),
        Entry("2026-11-10", Kind.NEW, "Practice check: 5v5 loading screen read without Gemma",
            "A 5v5 practice match vs Computer is verified on its loading screen from the picture alone, like solo practice: " +
                "the 9 other cards must read \"Computer\" (measured 0.90-0.94 on your screenshot; real players' names 0.30 at most).",
            pr = 41),
        Entry("2026-11-10", Kind.FIXED, "Practice check: solo loading card read without Gemma; last reading on the panel; vibration",
            "Solo practice is verified from the enemy card's \"Computer\" on the loading screen, from the picture alone (Gemma " +
                "not needed). The floating panel shows the practice check's last reading (what it saw and its scores), and the " +
                "phone vibrates once when the Player is unlocked and three times when a real player locks the match.",
            pr = 40),
        Entry("2026-11-10", Kind.FIXED, "Solo practice is identified; scenery calibration waits for the unlocked Player",
            "Solo practice (you vs one Computer) was never identified as a match - its loading screen was not recognised and a " +
                "quick restart was not seen as a new match - so the practice check never unlocked the Player. Both are fixed. " +
                "The scenery calibration tour now starts only once the practice check has unlocked the Player.",
            pr = 39),
        Entry("2026-11-10", Kind.FIXED, "Practice check from the loading screen; overlay hidden on loading and scoreboard; one Player switch",
            "The floating window and the boxes are hidden on the loading screen and the scoreboard (they covered the names, so a " +
                "practice match was locked). A practice match is now verified on the loading screen when the 9 others are " +
                "\"Computer\", and a real player must be seen twice in a row to lock it. The Player and research mode are one switch. " +
                "Solo practice (you and one Computer enemy) is verified too: empty scoreboard rows are skipped. " +
                "A Blessed Retribution icon now counts as Retribution, so the hero goes to the jungle.",
            pr = 38),
        Entry("2026-11-10", Kind.NEW, "Speed recorder: both language models and screen to touch",
            "Speed page: every answer of the strategist and the tactical model is recorded (time, engine, GPU or CPU, with or " +
                "without a picture, the phone's temperature and free memory), and while the Player plays, the screen-to-touch " +
                "time every 5 s. Optional detailed figures (tokens per second, time to the first token), a speed test with the " +
                "same questions every time, and a CSV export to send. It only measures.",
            pr = 35),
        Entry("2026-11-10", Kind.NEW, "Practice benchmark: a scorecard for every practice match",
            "New Practice page. Every practice match vs Computer that the Player plays gets a scorecard: result, K/D/A, level at " +
                "5 and 10 minutes, how long the AI stood idle or got stuck, and every death with what the AI was doing just before " +
                "(fighting, farming, escaping; outnumbered or not). The page shows the trend (last 5 matches vs the 5 before), " +
                "per hero, and the most common death causes; export as CSV. It only measures - it never touches the game.",
            pr = 31),
        Entry("2026-11-10", Kind.IMPROVED, "Player mode is for practice vs Computer only",
            "Your decision: the Player plays the hero only in a match verified as practice against Computer (open the scoreboard " +
                "once). In Ranked, Classic or any other match it becomes your companion: the strategist and the tactical advisor " +
                "keep working and show their advice on the floating panel, including which skill to level up next, but nothing is " +
                "touched (skills are leveled for you only in practice). Drafter and Builder stay automatic in every match, and the " +
                "Builder now also presses item actives (Frozen, Wind Chant, Conceal) when the Player is not playing.",
            pr = 30),
        Entry("2026-11-09", Kind.IMPROVED, "Small tactical model is no longer experimental",
            "Your decision: Gemma 3 270M is a full engine now. Its fight advice can be used like the other engines' - in the decision " +
                "arbiter too - but it still has to pass the app's Kotlin checks and the validator (no all-in at low HP, etc.).",
            pr = 29),
        Entry("2026-11-08", Kind.FIXED, "Skills released where they were aimed; no more chasing the Lithowanderer",
            "From your 13 recordings: a skill was re-aimed at release with the AI's newest decision - a third of skills went 90 degrees " +
                "off (\"aimed right, cast elsewhere\"). Now it releases at the target it was aimed at. On-screen \"enemies\" the minimap " +
                "never shows (the Lithowanderer's thin bar, companions) are dropped, the in-match scoreboard is recognised reliably " +
                "(the AI no longer plays on it), and the floating window's buttons stay one line and the same size.",
            pr = 28, issue = 2),
        Entry("2026-11-08", Kind.NEW, "Session recorder: data file + video for bug reports",
            "Logs page > Start recording. It saves what the AI saw and decided (about 10 times a second, every aimed skill " +
                "as planned and as released, the log) and a video of the AI's view with its targets, joystick and aim drawn on " +
                "top, both stamped with the clock and match timer. Files go to Sphynx-Storage/recordings. " +
                "It stops and saves by itself when the video reaches 25 MB (a GitHub website upload) or 95 MB (git). " +
                "Also on the floating window: ● REC starts, ■ REC stops and saves; the round icon gets a red ring while recording. " +
                "Research mode is recorded too (verified practice, the arbiter / normal group, each arbiter choice and scored fight), " +
                "and saving also copies the R5 comparison, the practice moments and the readiness report next to the recording.",
            pr = 27),
        Entry("2026-11-08", Kind.FIXED, "Jungle fights: no more leaving mid-fight, getting stuck or phantom enemies",
            "From your device test: the hero no longer walks to the next camp while the monster still has HP (hidden HP bars " +
                "no longer count as a kill), stands still and attacks when a big monster's body stops it, and a jungle monster's " +
                "red HP bar is no longer taken for an enemy hero on the map.", pr = 26, issue = 2),
        Entry("2026-11-08", Kind.NEW, "Sphynx is its own app now",
            "New app name everywhere (launcher, header, Accessibility list, notification) and a new app id, " +
                "com.sphynx.autopilot: it installs next to the old MLBB Research app. Give it its permissions again; " +
                "with All files access on it also finds the old app's exports, results and backups in Download/MLBB-Research.",
            pr = 25),
        Entry("2026-11-08", Kind.IMPROVED, "One app folder: /CodeOnTheGoProjects/Sphynx-Storage (v37.19)",
            "Models, detectors, networks and keys are read from it, and CSV exports, training pictures and zips, Kaggle results " +
                "and backups are saved in it. Turn on \"All files access\" once on the Model page. Until then everything works " +
                "as before (the folder you choose, and Download/MLBB-Research for the app's own files).",
            pr = 24),
        Entry("2026-11-08", Kind.NEW, "Arbiter vs normal comparison in practice (research R5)",
            "Each verified practice match is randomly put in an arbiter or a normal group; fight scores, deaths, K/D/A and results " +
                "are compared under Research mode, with \"not enough data\" until each group has 5 matches.",
            pr = 21),
        Entry("2026-11-08", Kind.FIXED, "Enemies under your green minimap ring; monsters out of range",
            "Enemy icons half covered by your own green ring no longer turn into phantom or duplicate enemies, and a covered " +
                "enemy is kept for a moment instead of vanishing (minimap and screen are combined into one target list). " +
                "Against jungle monsters the hero now walks into its own attack range and never steps out of the monster's aggro zone.",
            pr = 22, issue = 2),
        Entry("2026-11-08", Kind.NEW, "Small model readiness test (research R4)",
            "In verified practice, fight moments are recorded (the situation, what the app played, which options were dangerous). " +
                "A button under Research mode tests the small tactical model on them against the approved criteria. " +
                "A pass does not switch the model on.", pr = 20),
        Entry("2026-11-08", Kind.NEW, "Arbiter picks the fight option in practice (research R3)",
            "Only in a verified practice match against Computer, with Research and Player mode on, the decision arbiter chooses " +
                "the fight option. Retreat wins any fight-or-retreat conflict, and every pick must pass the app's own checks.",
            pr = 19),
        Entry("2026-11-08", Kind.NEW, "Research mode switch with live practice check (research R2)",
            "A Research mode switch next to Player, Drafter and Builder. It reads the in-match scoreboard and shows " +
                "\"verified practice\" only when all 9 other players are Computer; any human locks the match.",
            pr = 18),
        Entry("2026-11-08", Kind.NEW, "Map of the 5v5 battlefield",
            "Bases, turrets, buff and monster camps, and the Lord and Turtle pits as a small data file, with map regions " +
                "(lanes, river, jungle). Not used by the AI yet.", pr = 17),
        Entry("2026-11-07", Kind.NEW, "Practice-only safety gate (research R1)",
            "Recognises the word \"Computer\" on the scoreboard. Real ranked boards read \"human present\"; " +
                "the gate can never be switched on by hand.", pr = 16),
        Entry("2026-11-07", Kind.NEW, "Decision logging",
            "Every engine's advice is logged next to what was actually played, so the engines can be compared. " +
                "It never changes what is played.", pr = 15),
        Entry("2026-11-07", Kind.FIXED, "Battle spell from the loading card is only a hint",
            "Gemma's reading of the loading card no longer counts as your verified battle spell.", pr = 14),
        Entry("2026-11-07", Kind.NEW, "Shared decision format and safety arbiter",
            "All engines state their advice the same way; a validator rejects advice that contradicts what is seen " +
                "(for example, all in at very low health) and an arbiter settles conflicts, safety first.", pr = 13),
        Entry("2026-11-07", Kind.NEW, "Universal MOBA foundation",
            "A game-neutral match model and a list of what the app can and cannot read, with Mobile Legends as the first game.",
            pr = 12),
        Entry("2026-11-07", Kind.IMPROVED, "Reliable builds",
            "The on-device model library is pinned to one version, and every change is built and unit tested automatically.",
            pr = 8),
        Entry("2026-11-07", Kind.NEW, "Upgraded Retribution and Blessed icons",
            "Reads \"Retribution Upgraded to ...\" and the Blessed icons on your scoreboard row.", pr = 6),
        Entry("2026-11-07", Kind.IMPROVED, "Repository clean-up (v37.18)",
            "Build outputs are no longer stored with the source; first automatic tests.", pr = 3)
    )

    fun latest(n: Int): List<Entry> = ENTRIES.take(n)

    // entries grouped by date, newest date first (the list is already newest first)
    fun byDate(): List<Pair<String, List<Entry>>> = ENTRIES.groupBy { it.date }.toList()

    fun counts(): Map<Kind, Int> = ENTRIES.groupingBy { it.kind }.eachCount()

    fun ref(e: Entry): String = (e.version?.let { "v$it · " } ?: "") + "#${e.pr}" + (e.issue?.let { " · issue #$it" } ?: "")
}
