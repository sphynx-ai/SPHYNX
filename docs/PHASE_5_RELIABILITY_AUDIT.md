# Phase 5 — Reliability audit and execution plan

**Phase:** 5 — Android Reliability and Application Foundation (roadmap Section 3).
**Started:** 2026-11-16 (owner decision). **This audit:** 2026-11-21, Claude Code, on `sphynx-test-branch` @ `5aa1d9e` (v37.78), by reading the code. No phone or emulator was used for this audit, and there is no Android SDK in the cloud container: CI ("Android build") is the build check and the owner's phone is the device check.

**Entry condition met:** the owner phone-tested v37.64–v37.78 (Phase 2 split and the development-only PRs) on 2026-11-21: "All test works fine".

## 1. Role for this phase (roadmap 3.1)

- **Primary role:** Principal Android Platform, Reliability, and Performance Engineer.
- **Supporting roles:** Android Systems Engineer; Mobile Security Engineer; Performance Engineer; QA/Device-Lab Engineer.
- **Mission:** dependable Android foundations and device-capability awareness for the companion, inference, perception and offline workflows.
- **Out of scope:** new inference contracts (Phase 6), new model features (Phases 7–11), perception quality (Phases 12–15), any widening of autonomous play.

## 2. What the app already does (verified by reading the code)

| Area | Current behaviour | Where |
|---|---|---|
| Platform | minSdk 30 (Android 11), targetSdk / compileSdk 36, arm64-v8a only, `largeHeap` | `app/build.gradle.kts`, manifest |
| Screen capture | Foreground service of type `mediaProjection`, started before the projection (Android 14 rule); stops itself when the system stops the projection; `START_NOT_STICKY`; resizes on rotation; pads short rows; releases display, reader, projection and thread in `onDestroy` | `capture/ScreenCaptureService` |
| Emergency stop | Notification "STOP AI" button; accessibility service unbind also stops the AI | `ScreenCaptureService`, `controller/AccessibilityController.onUnbind` |
| Permissions | Ready check lists what is missing (overlay, notifications, accessibility, capture, all files access) and requests each one | `app/MainActivity` (Permissions card), `app/PlayStatus` |
| Storage | All files access → `/CodeOnTheGoProjects/Sphynx-Storage`; otherwise `Download/Sphynx-Storage` via MediaStore; one place decides the folder per file type | `core/Storage`, `core/StorageLayout` |
| Crashes | A default uncaught-exception handler writes the Java crash into the diagnostics log, then lets Android stop the app | `core/Diag` (`CrashLog`) |
| Diagnostics | Persistent diagnostics log (200 KB cap), Logs page, Debug data exports, GitHub upload of recordings | `core/Diag`, `core/SessionRecorder`, `core/GitHubUpload` |
| Device facts | Phone name, chip, cores and max clocks, GPU renderer, NPU driver files, thermal status and headroom, battery; live CPU / GPU / RAM / storage every 2 s | `core/DeviceInfo`, `core/DeviceStats`, `core/PhoneState` |
| Native engine guard | GGUF library not loaded on a CPU without dot product + fp16 (would be an illegal instruction) | `inference/LlamaCpu` |
| Rotation | Activity handles configuration changes itself (no restart) | manifest `configChanges` |

## 3. Findings (gaps and risks)

Severity: **H** can lose the user's session or data, or hide why the app failed; **M** degrades reliability or makes failures hard to diagnose; **L** polish or later.

| # | Finding | Sev. | Evidence |
|---:|---|:-:|---|
| F1 | **Why the app was closed last time is never recorded.** Native crashes (llama.cpp, LiteRT-LM GPU), ANRs and low-memory kills bypass the Java crash handler, so they leave nothing in the diagnostics log. Android 11+ keeps this history (`ApplicationExitInfo`) and minSdk is 30, so it is always available. | H | No call to `getHistoricalProcessExitReasons` anywhere |
| F2 | **No reaction to memory pressure.** No `onTrimMemory` / `onLowMemory`; a loaded Gemma (GPU) plus a GGUF model plus detectors can push the phone into killing the app or the game. At minimum the event must be logged so a kill can be explained. | H | No `onTrimMemory` in the app or services |
| F3 | **No device capability profile.** Device facts are shown on the Performance page but not turned into one record (memory class, cores, ABI, API level, GGUF CPU support, GPU, accelerators) that the model platform (Phases 6, 9, 11) can read. | M | `DeviceInfo` only formats for display |
| F4 | **Thermal state is polled, never reacted to.** Thermal status is read for display and speed rows; there is no listener and no log entry when the phone starts throttling. | M | `DeviceInfo.power`, `PhoneState.read` |
| F5 | **Background restrictions not checked.** Battery optimisation / restricted background state is not shown in the Ready check; on some phones it stops the capture service or delays the overlay. | M | No `isIgnoringBatteryOptimizations` / `isBackgroundRestricted` |
| F6 | **Start-up does a lot on the main thread** (14 one-off settings migrations, initialisers, folder scan) and starts five raw `Thread`s for model loading. A slow storage card can make the first frame late (ANR risk). | M | `app/MainApplication.onCreate` |
| F7 | **No compatibility matrix.** Only the owner's phone (POCO X6 Pro, MediaTek Dimensity 8300-Ultra, Mali-G615) has device evidence. | M | `docs/PHASE_1_BASELINE.md` risk 10 |
| F8 | **Broad permissions** (accessibility, overlay, capture, all files access, internet). Each is used, but no written justification / data-flow record exists. | M | Manifest; Phase 1 risk 8 |
| F9 | **No instrumented tests**; lifecycle paths are checked only by hand on the phone. | M | Phase 1 risk 6 |
| F10 | Diagnostics log trims by rewriting the whole file under a lock on the caller's thread. | L | `Diag.log` |
| F11 | `allowBackup="true"` backs up settings, which may include keys read into preferences. To review with F8. | L | Manifest |

## 4. Compatibility matrix (start)

| Device | Android | SoC / GPU | ABI | Capture | Overlay | LiteRT-LM GPU | GGUF CPU | Evidence |
|---|---|---|---|---|---|---|---|---|
| POCO X6 Pro (owner) | UNKNOWN (to be read by the device profile) | MediaTek Dimensity 8300-Ultra / Mali-G615 | arm64-v8a | Works | Works | Works (Gemma 4 E2B) | Works (Qwen 0.5B 25.5 tok/s, Llama 1B 13.4 tok/s) | Owner phone tests; roadmap 9.1 |
| Android 10 or older | — | — | — | Not supported (minSdk 30) | — | — | — | `app/build.gradle.kts` |
| 32-bit ARM / x86 phones | — | — | armeabi-v7a, x86 | Not supported (arm64-v8a only) | — | — | Refused with a reason | `abiFilters`, `LlamaCpu` |
| ARMv8.0 cores (Cortex-A53/A73) | ≥ 11 | — | arm64-v8a | Expected | Expected | Unknown | Refused with a reason (no dot product / fp16) | `LlamaCpu` |
| Any other phone | — | — | — | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | None yet |

Each row moves to "Works" only with a phone test or a recording from that phone. The device profile (P3 below) will be written into every recording so rows can be filled from uploaded recordings.

## 5. Execution plan (ordered; one PR each, version +1 each)

| Step | Work | Finding | Behaviour change in play? | Status |
|---:|---|---|---|---|
| P1 | This audit, the compatibility matrix and the plan; Phase 2 phone test recorded | — | No | **Done (v37.79)** |
| P2 | **Last exit reasons:** at start, read Android's record of how the app last ended (crash, native crash, ANR, low memory, killed by the user / system) and write each new one into the diagnostics log; show the last one on the Logs page | F1 | No (diagnostics only) | **Done in code (v37.80)**: `core/ExitHistory`, `ExitHistoryTest`; phone check pending |
| P3 | **Device profile:** one record of the phone's capabilities (RAM, cores, ABI, API level, GGUF CPU support, GPU, accelerators), checked against the owner-approved requirements (`UNIVERSAL_COMPATIBILITY.md` A.9.4); shown on the Performance page, written into the diagnostics log at start and into every recording's start row. It picks **no** model profile: A.9.3 chooses profiles from measurements, in Phase 11 | F3, F7 | No | **Done in code (v37.81)**: `core/DeviceProfile`, `DeviceProfileTest`; phone check pending |
| P4 | **Memory pressure and thermal events logged:** `onTrimMemory` and a thermal-status listener write to the diagnostics log and the session recording (no automatic unloading yet) | F2, F4 | No | **Done in code (v37.82)**: `core/PressureLog`, `PressureLogTest`; phone check pending |
| P5 | **Memory / thermal policy:** what to unload or slow down at which level (e.g. drop the tactical model before the strategic one; pause GGUF at severe throttling). | F2, F4 | **Yes — needs owner approval of the policy** | Planned, owner decision |
| P6 | **Background restrictions in the Ready check:** battery optimisation and background restriction shown with a button to the system page | F5 | Ready check only | Planned |
| P7 | **Start-up off the main thread:** migrations and initialisers that touch storage run on a background start-up task; one-shot model loads go through one executor | F6 | No (same work, other thread) | Planned |
| P8 | **Permissions and data-flow record:** why each permission is needed, what leaves the phone (Groq, GitHub upload, Kaggle, wiki), retention; `allowBackup` review | F8, F11 | No (document; any change is owner decision) | Planned |
| P9 | **Lifecycle test plan:** phone checklist for process death, capture stop by the system, accessibility off, rotation, low memory, airplane mode; instrumented tests where CI can run an emulator | F9 | No | Planned |
| P10 | Diagnostics log trimming off the caller's thread | F10 | No | Planned |

## 6. Acceptance criteria for Phase 5 (from the roadmap mission and deliverables)

- [x] Reliability audit (this file).
- [ ] Compatibility matrix maintained with dated device evidence (started in Section 4).
- [ ] Every way the app can end (Java crash, native crash, ANR, low-memory kill) leaves a readable record (P2; needs a phone check).
- [ ] Device capability profile available to the model platform and recorded with diagnostics (P3).
- [ ] Memory pressure and thermal changes are recorded (P4) and handled by an owner-approved policy (P5).
- [ ] Permissions and background restrictions are explained and checked (P6, P8).
- [ ] Start-up does no storage work on the main thread (P7).
- [ ] Lifecycle transitions and recovery paths tested on the phone (P9).
- [ ] Resource baselines measured on the owner's phone (idle, capture on, Gemma loaded, GGUF loaded).

## 7. Owner decisions needed

1. **P5 policy** — what the app may unload or slow down by itself under memory pressure or heat.
2. **P8** — whether `allowBackup` stays on, after the data-flow record.
3. Which other phones (if any) can be used to fill the compatibility matrix.
