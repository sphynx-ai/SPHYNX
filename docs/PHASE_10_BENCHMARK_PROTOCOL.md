# Phase 10 — Benchmark protocol (schema 1)

This protocol says how Sphynx measures a model, so that two results can be compared fairly. The code that follows it is in `universal/eval/` (record, statistics, scoring), the MLBB evaluation sets are in the MLBB adapter's assets, and the harness is on the phone.

## 1. What a run is

A **run** asks one model one evaluation set (tactical, strategic or post-match review), in one job, on one phone.

- Each item is asked **N times** (default 3). The first answer after the model loads is marked **cold**.
- One warm-up answer before the set is not counted.
- Answers are **greedy** (temperature 0), in the same order every time.
- The model's own settings (Phase 9 M4) are recorded with the run.

A **sustained run** (E8) repeats the tactical set at the in-match pace (one question every 330 ms, as `TacticalAdvisor`) for a fixed time, 10 minutes by default (owner, 2026-11-27, on battery).

## 2. What each run records (`EvalRecord.RunInfo`)

| Field | Why |
|---|---|
| set id and version | answers are only comparable on the same questions |
| job (strategic / tactical) | the same model is judged per job |
| model fingerprint (SHA-256), file, format, quantization | the exact file |
| backend, processor | LiteRT-LM or llama.cpp; NPU / GPU / CPU |
| phone, Android version | results are per phone |
| app version, runtime version | regressions (E7) |
| the model's own settings | context, threads, cache, processors, pictures |
| repeats | the sample count |
| conditions at the start | see section 3 |

Each answer (`EvalRecord.Sample`) records:

- the item and its repeat, and whether it was cold;
- the time to the first word, the time to a **usable answer** (tactical: the moment the option is named), and the whole answer's time;
- the answer's length;
- the app's memory, the heat status, and the battery temperature and level;
- the answer, its score and verdict (E3), or the error.

## 3. Conditions

A run starts under these conditions:

- no match is running;
- screen capture is off;
- the battery is at least 30% or charging;
- the heat status is no worse than *light*.

A run outside them is **kept but marked**. It is never ranked, and the report says why.

## 4. Comparing runs

Two runs are **comparable** only when all of these match: kind, set and version, job, model file, backend, processor, phone, Android version, runtime version and the model's own settings. This is the comparability key (`EvalRecord.comparabilityKey`).

- The app version is deliberately left out: comparing app versions on otherwise equal runs is the regression check.
- Runs that are not comparable are shown side by side but never ranked, and the reason is named (`EvalRecord.whyNotComparable`).

## 5. Statistics (`EvalStats`)

- **Speed:** the median and the 90th percentile of each time, plus the mean with a 95% bootstrap interval (2,000 resamples, fixed seed, so the same samples always give the same interval). The cold answers are reported apart.
- **Quality:** the share of best, acceptable, wrong and unsafe answers (tactical), or the mean rubric score (strategic, review). Each comes with a 95% interval: Wilson for shares, bootstrap for means.
- Quality and speed are **never combined into one number**.

## 6. Regression thresholds (E7)

A model's first comparable run on this phone under good conditions is its **baseline**. A later comparable run is flagged when either:

- its median time to a usable answer is more than **15%** slower than the baseline's; or
- its quality interval lies wholly below the baseline's.

A flag only reports: nothing is changed by itself.

## 7. Files

| File | What |
|---|---|
| `files/research/eval_runs.jsonl` | one `{"run":…}` line per run, then one `{"runId":…,"sample":…}` line per answer |
| `Recordings/diagnostics-eval-<time>.jsonl` | the export for debugging, uploaded and then deleted from the phone |

The format is `EvalRecord` schema 1. A newer schema is not read by an older app.
