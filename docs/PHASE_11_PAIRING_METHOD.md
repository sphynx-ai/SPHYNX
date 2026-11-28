# Phase 11 — Pairing method

This document says how Sphynx recommends a **pair** of models: one for the strategic job and one for the tactical job, on this phone. The code is in `universal/pairing/`; the measurements come from Phase 10 (`docs/PHASE_10_BENCHMARK_PROTOCOL.md`).

## 1. Workloads (`Workloads`)

| Job | How it is used | Budget |
|---|---|---|
| **Tactical** | Asked about 3 times a second in a fight (at most every 330 ms); an answer older than 1 s is not used (`TacticalAdvisor.VALID_MS`) | **90% of usable answers under 1 s** |
| **Strategic** | Asked when the situation changes (draft, build, plan); long answers are fine; pictures help (the draft, the screen) | **median whole answer under 20 s** |

A model that misses its job's budget is still listed, but marked as "misses the budget".

## 2. Phone class (`Workloads.phoneClass`)

From the phone's total RAM:

| Class | Total RAM |
|---|---|
| entry-level | under about 6 GB |
| midrange | about 6–10 GB |
| flagship | 10 GB or more |

A model's **declared** class is the smallest class whose usual free memory after the game holds it. It uses the `core.MemoryFit` rule: the file plus 200 MB, with 2.5 GB kept for the game.

| Class | Room for models |
|---|---|
| entry-level | 1.5 GB |
| midrange | 3.5 GB |
| flagship | 7 GB |

A declared class is a guide for models that have not been measured; it is not a measurement.

## 3. Evidence and confidence

For each candidate model and job:

- **Measured:**
  - the newest rankable Phase 10 run on this phone, for the job's set: quality interval, usable 90th percentile, whole-answer median;
  - and, when there is one, the heat run's slowdown.
- **Declared:** file size, format, pictures (from the Model Lab or the download list), and the declared phone class.

A recommendation's **confidence** is the lower of its two models:

| Confidence | When |
|---|---|
| **high** | measured under the protocol's conditions, on a set the owner reviewed, with at least 30 answers |
| **medium** | measured, but the set is not yet reviewed, or there are fewer than 30 answers |
| **low** | declared only |

## 4. Pairs

1. **Remove what can't work:**
   - pairs the job rules refuse (one GGUF model at a time: `RoleChoice.problem`);
   - pairs whose two models don't fit next to the game in this phone's free memory.
2. **Keep the Pareto front:** a pair stays unless another pair is at least as good on all three axes and better on one. The axes are quality (measured mean score), speed (the tactical model's usable 90th percentile, then the strategic median) and memory (both models together). No weights combine the axes into one number.
3. **Label the trade-off:** *best measured quality*, *fastest*, *lightest*, or *balanced*.
4. **Explain:** each pair lists its evidence, which budgets it meets or misses, what is unknown, and its confidence.

The engine never chooses for the owner and never changes a job. *Use this pair* (P3) asks first, and then the usual job rules and memory question apply.
