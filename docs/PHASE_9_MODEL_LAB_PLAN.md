# Phase 9 — Model Lab and Model Management: audit and execution plan

**Status:** PROPOSED (2026-11-25), waiting for the owner's OK. Phase 8 is phone-tested (closing record `docs/PHASE_8_GGUF_PLAN.md` §8). Its two re-tests (the Vulkan test APK, tactical pictures after #120) run alongside this phase.
**Based on:** `sphynx-test-branch` at v38.31, read from the code. No phone was used for this plan.

## 1. Role for this phase (roadmap 3.1)

- **Primary role:** AI Developer Tools and Model Lifecycle Product Engineer.
- **Supporting roles:** AI Inference Architect; Android Storage Engineer; Product Designer; Security/Privacy Engineer.
- **Mission:** a trustworthy Model Lab for discovering, importing, validating, configuring, assigning, loading and managing local models.
- **Out of scope:**
  - benchmarks across models and their ranking (Phase 10);
  - which models to pair on which phone (Phase 11);
  - downloads inside the app (see decision 2).

## 2. What exists (verified by reading the code)

| Part | State |
|---|---|
| LiteRT-LM files | `models/AutoFolder` copies `.litertlm` files from `Sphynx-Storage/Input` into the app (`gemma-strategic.litertlm`, `gemma-tactical.litertlm`). The slot is guessed from the name and size (`ModelManager.isTactical`). **One file per slot**: a third `.litertlm` replaces one of them. No checksum, no description read from the file |
| GGUF files | `models/GgufLibrary` adds a picked file to `files/gguf` after checking its header and description (Phase 8 G2), pairs mmproj files by name, and can remove one. Shown on the Model page |
| Choosing | The job picker (Phase 6 S7) gives each job the LiteRT-LM file or a GGUF model, with a description, a picture state (G2, G11) and the memory warning (Phase 7 L3) |
| Per-model results | Processor memory (Phase 7 L9), the best thread count (Phase 8 G5), picture ability (`vision_<file>`), speed records (`ModelSpeed`) |
| UI | The Model page has separate cards for FILES, the job picker, the GGUF library, the test question and the benchmark. There is **no single list of every model** with its details, settings and state |
| Gaps | No model registry (one record per file). No integrity check (hash) or provenance (where it came from). LiteRT-LM files are not described before loading. No per-model settings (context length, temperature). Deleting and checking storage happen only for GGUF. No user guide |

## 3. Plan (one PR each, version +1 each; built back to back after the owner's OK, one phone test at the end)

| Step | Work | Changes play? |
|---:|---|---|
| M1 | **Model registry:** one record per model file, for both formats. It holds id, file, format, family, quantization, size, SHA-256, where it came from (the Input folder, a picked file, the name it had), when it was added, picture ability (mmproj or vision), its last load result per processor (from L9) and its measured speed (from G6 and ModelSpeed). Stored in `Output` and kept across updates. Pure schema and rules, unit-tested; built from today's files on first start | No |
| M2 | **Import and validation for both formats:** a LiteRT-LM file gets a header check and a size check, like GGUF does. The SHA-256 is computed once during the copy. A duplicate (same hash) is not copied twice. A broken or half-copied file is refused with the reason. **More than one `.litertlm`** can be kept: today a third file replaces one of the two | No |
| M3 | **Model Lab page:** one list of every model, with its details (format, family, quantization, size, pictures, hash), its state (loaded, for which job, on which processor) and its numbers (load time, tokens/s). Actions: give it a job, load, unload, run the test question, benchmark (GGUF), and delete with a confirmation. It follows the brand rules and the "?" help bubbles | Only the page |
| M4 | **Per-model settings (profiles):** context length, temperature, threads (GGUF), 8-bit cache (GGUF), processor order, and picture use, each with safe limits. The defaults are today's values, so nothing changes unless the owner changes a setting. Settings travel with the model record. Pure validation, tested | Only if the owner changes a setting |
| M5 | **Storage:** how much each model takes, the space left, a warning before a copy that would fill the phone, and files no record points to (leftovers) offered for deletion | No |
| M6 | **Lifecycle integration:** the job picker, step-down (Phase 6 S5), the load sentinel (S6) and processor memory (L9) read and write the registry instead of loose settings keys. The old keys are migrated once and kept as a fallback for one version | No (same behaviour) |
| M7 | **Export for debugging:** the registry (without paths that name the owner's account) is exported to Recordings and uploaded (debug-data rule) | No |
| M8 | **User guide and closing record:** a short guide (adding a model, which file goes where, mmproj files, reading the Lab), the phone checklist and the closing record | No |

## 4. Acceptance criteria (roadmap deliverables)

- [ ] Model metadata schema and registry for both formats, with provenance, format, quantization, requirements and limitations (M1).
- [ ] Integrity and compatibility are checked before loading; broken, half-copied and duplicate files are handled (M2).
- [ ] A Model Lab page shows every model with its state and offers the lifecycle actions (M3).
- [ ] Per-model configuration profiles with safe limits; the defaults keep today's behaviour (M4).
- [ ] Storage use is explicit and leftover files can be removed (M5).
- [ ] The existing choosers and memories use the registry; old settings are migrated without loss (M6).
- [ ] Tests for the schema, validation, migration and profiles; a user guide (M1–M8).

## 5. Risks

- **Migration:** moving loose settings keys into the registry must not lose the owner's choices. M6 keeps the old keys for one version, and a unit test covers the migration.
- **SHA-256 of a 2.6 GB file** takes several seconds on the phone. It is computed once, during the copy, in the background.
- **More than one `.litertlm`:** today's slot names (`gemma-strategic`, `gemma-tactical`) are referenced in many places. M2 keeps those names for the two files in use and stores the others under their own names.

## 6. Owner decisions needed

1. **Several LiteRT-LM files:** keep every `.litertlm` from the Input folder (more storage), or keep only the two in use, as today?
2. **Downloads:** add model downloads inside the app (from Hugging Face, with the hash shown), or keep today's way (put the file in the Input folder, or pick it)? The roadmap says to avoid opaque downloads.
3. **Deleting:** may the Lab also delete the original in `Sphynx-Storage/Input`, or only the app's copy?
4. **Per-model settings (M4):** which ones should the owner see: all of the list above, or only context length and temperature?
