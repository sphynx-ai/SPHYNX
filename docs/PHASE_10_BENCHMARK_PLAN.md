# Phase 10 — Model Benchmarking and Evaluation: audit and execution plan

**Status:** **APPROVED by the owner 2026-11-27** with the answers in section 6. **All steps E1–E9 built in code (v38.44–v38.52, PRs #135–#143); the owner's phone test of the whole phase and the owner's review of the evaluation sets are pending (section 7).** Phase 9 is built (v38.34–v38.42, PRs #125–#133), and its phone test runs alongside this plan.
**Based on:** `sphynx-test-branch` at v38.42, read from the code. No phone was used for this plan.

## 1. Role for this phase (roadmap 3.1)

- **Primary role:** AI Evaluation Scientist and Mobile Performance Benchmarking Engineer.
- **Supporting roles:** ML Evaluation Researcher; Android Performance Engineer; Data Analyst/Statistician; QA Engineer.
- **Mission:** repeatable, fair evaluations of Strategic and Tactical model and runtime set-ups on the owner's phone, with quality and speed reported apart.
- **Standards (roadmap):**
  - versioned evaluation sets and controlled conditions;
  - every run records the model, quantization, backend, processor, phone, Android version, settings, cold or warm, the number of samples and the spread;
  - no ranking from runs that cannot be compared.
- **Out of scope:**
  - which pair of models to recommend on which phone (Phase 11);
  - judging decisions inside real matches (Phase 12);
  - training or changing models.

## 2. What exists (verified by reading the code)

| Part | State |
|---|---|
| GGUF benchmark (Phase 8 G5/G6) | `models/GgufBenchmark`: 3 fixed questions on one GGUF model, greedy, each setting loaded fresh; rows in `research/gguf_bench.jsonl` with tokens/s, time to the first word, memory and heat. **GGUF only, speed only, no quality score, no spread** |
| Speed records | `ai/ModelSpeed` / `ModelSpeedLog`: every load and every answer in real use (`research/model_speed.jsonl`). Real use, not controlled conditions |
| LiteRT-LM figures | `GemmaEngine` can read LiteRT-LM's own benchmark numbers (prefill / decode speed, time to the first word) |
| Resources | `core/DeviceStats` (memory, CPU), `core/DeviceInfo.power` (heat, battery), `core/ResourceBaseline` (one snapshot per tap) |
| Tactical answer | `ai/TacticalAdvisor`: a fixed prompt from a fight summary, answered with ONE of ALL_IN, POKE, FARM, RETREAT, GROUP, RULES (`parse`), so it can be scored exactly |
| Strategic answers | `ai/StrategyGame` (MLBB: `adapters/mlbb/MlbbStrategy`): plan, draft, build and lineup prompts with their own parsers |
| Practice scorecards | `research/PracticeBenchmark`: per-match scorecards vs Computer. Judges whole matches, not models |
| Gaps | No evaluation set with expected answers. No quality score for any model. No common harness across both backends. No repeats, spread or confidence. No controlled-conditions check. No sustained heat and battery run. No regression thresholds. No reproducibility guide |

## 3. Plan (one PR each, version +1 each; built back to back after the owner's OK, one phone test at the end)

| Step | Work | Changes play? |
|---:|---|---|
| E1 | **Protocol and result schema:** the benchmark protocol (`docs/PHASE_10_BENCHMARK_PROTOCOL.md`). One run record holds the model's fingerprint, file, format, quantization, backend, processor, phone model, Android version, app version, runtime version, the model's own settings, the set and its version, cold or warm, the conditions at the start, and each sample. A **comparability key** says which runs may be compared: same set version, same phone, same settings and same runtime. Pure statistics: mean, median, 90th percentile, spread, 95% confidence interval (bootstrap). All pure and unit-tested | No |
| E2 | **Evaluation sets (versioned, in the MLBB adapter's assets):** **Tactical:** about 40 fight moments written in the exact `TacticalAdvisor` summary format, each with its best option, the options that are also acceptable, and the unsafe ones (for example ALL_IN at 10% HP under an enemy tower). **Strategic:** about 30 questions on the draft, objectives (turtle, lord), rotations and builds, using the app's own prompts, each with a rubric. **Post-match review:** about 10 short match summaries with the points a good review must name. A pure checker refuses a broken set. The universal schema stays in the core; the MLBB content stays in the adapter | No |
| E3 | **Scoring (pure, tested):** **Tactical:** the answer's format (one option?), best / acceptable / wrong / **unsafe**. **Strategic and review:** does the app's parser accept the answer, which required points it names, which forbidden claims it makes (for example a hero or item that doesn't exist in MLBB, checked against `MlbbKnowledge`). Deterministic: no second model judges, so a score never depends on the network or another model. Quality is never mixed with speed | No |
| E4 | **Harness (both backends):** runs a set on one model from the Model Lab, LiteRT-LM or GGUF, in the job the set is for. The model's job is restored afterwards. Greedy (temperature 0) and the same order every time. One warm-up answer is not counted. Each question is asked **N times** (default 3). Measures the time to the first word, the time to a usable answer (tactical: until the option is named, `TacticalAdvisor.enough`), the whole answer's time, reading and writing tokens/s, the app's and the phone's memory, heat status and battery temperature. A **conditions check** at the start: not in a match, screen capture off, battery at least 30% or charging, heat no worse than "light". A run outside these conditions is kept but marked and never ranked. It can be stopped. It never runs by itself | No (only when the owner starts it) |
| E5 | **Results:** raw rows in `research/eval_runs.jsonl` (one per answer) and one summary per run. Exported to Recordings and uploaded by the debug-data rule (big runs one per file). They stay in the app until uploaded | No |
| E6 | **Report in the app:** an EVALUATION card on the Model page. Per model and job it shows quality (scores with their interval) and speed (median and 90th-percentile times, tokens/s) **side by side, never as one number**. Two runs are compared only when their comparability key matches; otherwise the card says why they can't be compared. The "?" help bubbles explain each figure | No |
| E7 | **Regression thresholds:** the first good run of a model on this phone becomes its baseline. A later run (a new app version, runtime or setting) is flagged when its median time to a usable answer is more than 15% slower, or its quality interval lies wholly below the baseline's. Thresholds are in the protocol and unit-tested. They only report: nothing is changed by itself | No |
| E8 | **Sustained heat and battery run:** a longer, optional run (default 10 minutes) that repeats the tactical set at the in-match pace (3 questions a second, as `TacticalAdvisor`). It records how speed falls as the phone heats up (throttling), the heat status over time, and the battery used per minute. It stops early at the "severe" heat status (Phase 5 heat policy) | No |
| E9 | **Reproducibility guide and closing record:** how to repeat a run (conditions, settings, set version), how to read the report, the phone checklist and the closing record, with the owner's first measured results | No |

## 4. Acceptance criteria (roadmap deliverables)

- [ ] A benchmark protocol and a versioned result schema with a comparability rule (E1). **In code (v38.44):** `docs/PHASE_10_BENCHMARK_PROTOCOL.md`, `universal/eval/EvalRecord` (run, sample, conditions, comparability key, JSON lines; tested), `universal/eval/EvalStats` (median, 90th percentile, spread, fixed-seed bootstrap and Wilson intervals; tested).
- [ ] Versioned Strategic, Tactical and post-match evaluation sets, with a documented rubric (E2). **In code (v38.45):** `assets/eval/mlbb_tactical.json` (40), `mlbb_strategic.json` (15 plans, 15 coaching questions), `mlbb_review.json` (10), all version 1, not yet reviewed by the owner; written by `tools/eval-sets/build.py`; form `universal/eval/EvalSet` (checked: tested); `MlbbEvalSetsTest` (exact match prompts, real heroes and roles, the app's plan reader); rubric `docs/PHASE_10_EVAL_SETS.md`.
- [ ] Deterministic scoring that keeps quality and speed apart (E3). **In code (v38.46):** `universal/eval/EvalScoring` (best / ok / wrong / unsafe / no answer; first option named, first JSON object, rubric share with negation-aware forbidden claims; a model's thinking part is ignored; tested), and `MlbbEvalSetsTest` checks that every set's own expected answers score best and its unsafe ones unsafe. Instead of a hero-name check on free answers, forbidden claims are listed per item (`mustNot`), which is deterministic.
- [ ] A harness for both backends that records model, quantization, backend, processor, phone, settings, cold or warm, sample count and spread (E4). **In code (v38.47):** `ai/EvalHarness` (the model doing the set's job now, either backend; warm-up kept apart as `_warmup`; N repeats in a fixed order; greedy; conditions recorded; stops at the severe heat status; results in `files/research/eval_runs.jsonl`), `Roles.askTimed` (first word, first usable answer, whole answer; the tactical answer stops at its option as in a match), `Roles.processor` (and the existing `Roles.backend`), the Model page's EVALUATION card (Run a test, Stop the test). To compare processors, set the model's own processor order (Model Lab → Settings) and run again; the record holds the processor.
- [ ] Raw results kept and exported for debugging (E5). **In code (v38.48):** every run is appended to `files/research/eval_runs.jsonl` (kept for the report) and saved at once as `Recordings/diagnostics-eval-<run>.jsonl` (uploaded by the automatic upload, then deleted from the phone); the Debug data page's *Export the model test results now* saves every kept run in one file (`EvalHarness.exportAll`).
- [ ] A report with confidence intervals that never ranks runs that can't be compared (E6). **In code (v38.49):** `universal/eval/EvalReport` (run summary: best / acceptable / unsafe / no-answer shares with Wilson intervals, mean score with a bootstrap interval, usable / first-word / whole-answer times as median and 90th percentile, the warm-up as the cold start, the conditions; `compare` only for comparable runs under good conditions, quality by non-overlapping intervals, speed by the median ratio; tested), the EVALUATION card's newest runs and *Compare two test runs*. A set not yet reviewed by the owner is shown with "these scores don't count yet".
- [ ] Regression thresholds (E7). **In code (v38.50):** `universal/eval/EvalRegression` (baseline = the first rankable run of each comparable group; flagged when the median usable answer is more than 15% slower or the score interval lies wholly below the baseline's; tested), the REGRESSION card on the Model page.
- [ ] Heat and battery under sustained load (E8). **In code (v38.51):** `EvalHarness.startSustained` (the tactical set at the in-match pace, at most one question every 330 ms, 10 minutes by the owner's decision, stopped at the severe heat status, kind `sustained` so it is never compared with set runs), `universal/eval/EvalSustained` (per-minute median usable answer, first-to-last-minute slowdown, battery % per minute only when not charging, battery temperature rise, highest heat status; tested), the EVALUATION card's *Run the 10-minute heat and battery test*.
- [ ] A reproducibility guide; tests for the schema, statistics, sets and scoring (E9). **In code (v38.52):** `docs/PHASE_10_BENCHMARK_GUIDE.md`; tests `EvalRecordTest`, `EvalStatsTest`, `EvalSetTest`, `EvalScoringTest`, `EvalReportTest`, `EvalRegressionTest`, `EvalSustainedTest`, `MlbbEvalSetsTest`.

## 5. Risks

- **Expected answers are judgement:** the right fight option or draft answer is MOBA knowledge, not a fact. I write the first version from MLBB knowledge and the app's own rules (`MlbbTacticalRules`), and mark each item's reason. The owner reviews the sets before they count, and a later set version can correct them.
- **One phone:** results are from the owner's phone only. Each record holds the phone, so a second phone can be added later without mixing results.
- **Heat and battery:** a sustained run warms the phone. It stops at "severe" and is never started by itself.
- **Small samples:** 3 repeats of 40 questions gives wide intervals. The report shows the interval instead of hiding it. More repeats are a setting.
- **Greedy is not identical every time:** GPU answers can differ slightly between runs. That is why repeats are made and the spread is shown.

## 6. Owner decisions (2026-11-27)

1. **Expected answers:** I write the first evaluation sets (with each item's reason); the owner reviews them before their scores count.
2. **First runs:** Gemma 4 E2B on the GPU and on the CPU (strategic job). The tactical set can still run on any model, but these are the runs that make the first results.
3. **Sustained run:** 10 minutes, on battery, so battery use is measured. It stops at the "severe" heat status.
4. **Repeats:** 3 per question (the default; not changed by the owner).

## 7. Phone checklist (one test for the whole phase)

Update the app (`git pull` on `sphynx-test-branch`, rebuild; version 38.52), then follow `docs/PHASE_10_BENCHMARK_GUIDE.md`:

1. **Review the sets (E2):** read `docs/PHASE_10_EVAL_SETS.md` and the three files (or `tools/eval-sets/build.py`). Tell me which expected answers to change. Until then, the report marks the scores as not counting yet.
2. **Conditions (E4):** start a test while screen capture is on, or with the battery below 30%: the question says it will be marked. Cancel.
3. **Strategy test, GPU (E4, E6):** Gemma 4 E2B with processors `GPU,CPU` → *Run a test…* → *Strategy plans and coaching* → Start. A new card shows QUALITY and SPEED lines and "Conditions: as the protocol asks".
4. **Strategy test, CPU (E4, E6):** processors `CPU` → reload → the same test. *Compare two test runs* on the two: it says "can't be compared: different processor", which is right, since the processor is part of the key. Then *Compare* two runs of the same processor; if there is only one, run the GPU test twice. It names which scores higher, or "no clear difference", and the speed difference.
5. **Match reviews (E4):** *Match reviews* on the GPU.
6. **Fight test (E4):** give a tactical model (Gemma 3 270M) the tactical job → *Fight moments*. The usable answer should be much shorter than the whole answer.
7. **Stop (E4):** start any test, then *Stop the test*: a card shows the answers so far.
8. **Heat test (E8):** unplug the phone → *Run the 10-minute heat and battery test…* → wait 10 minutes. The card shows the slowdown, battery % per minute, the temperature rise and the highest heat status.
9. **Regression (E7):** after the next app update, run the GPU strategy test again. If it is more than 15% slower, a REGRESSION card appears; if not, no card.
10. **Export (E5):** Debug data → upload. The `diagnostics-eval-*.jsonl` files arrive on `recordings-for-debugging` and are gone from the phone. *Export the model test results now* saves all runs in one file.

The measured figures from checks 3–8 become the first results in section 8.

## 8. Closing record

| Step | Version | PR | State |
|---|---|---|---|
| Plan | 38.43 | #134 | Merged (approved 2026-11-27) |
| E1 protocol, record, statistics | 38.44 | #135 | Merged |
| E2 evaluation sets and rubric | 38.45 | #136 | In code |
| E3 scoring | 38.46 | #137 | In code |
| E4 harness | 38.47 | #138 | In code |
| E5 results export | 38.48 | #139 | In code |
| E6 report | 38.49 | #140 | In code |
| E7 regression thresholds | 38.50 | #141 | In code |
| E8 heat and battery test | 38.51 | #142 | In code |
| E9 guide, checklist, closing record | 38.52 | #143 | In code |

So far the phase has only been verified by CI: unit tests, build and native tests for each PR. No test has run on a phone, so there are **no measured results yet**. Every What's new entry has `needsDeviceCheck = true` until the owner's test. The evaluation sets are version 1, not yet reviewed by the owner.
