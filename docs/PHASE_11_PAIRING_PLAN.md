# Phase 11 — Model Pairing and Device-Aware Recommendations: audit and execution plan

**Status:** **APPROVED by the owner 2026-11-28**, together with Phases 12–16 ("Proceed to phase 11, 12, 13, 14, 15, 16 and all of the phase. I will test everything on phone later on."). IN PROGRESS: the steps are built back to back, and the owner phone-tests later.
**Based on:** `sphynx-test-branch` at v38.52, read from the code. No phone was used for this plan.

## 1. Role for this phase (roadmap 3.1)

- **Primary role:** AI Systems Optimization Scientist and Model Selection Specialist.
- **Supporting roles:** Benchmarking Scientist; On-Device ML Engineer; Android Device-Capability Engineer; Product/UX Designer.
- **Mission:** recommend model pairs (strategic + tactical) and their set-up from **measured** phone capability and Phase 10 results, showing the trade-offs and how sure each recommendation is.
- **Rules (roadmap):**
  - base recommendations on comparable Phase 10 measurements and declared compatibility;
  - show the trade-offs and the confidence;
  - keep the owner in control;
  - never imply one best pair for everyone;
  - never change a job by itself.
- **Out of scope:** judging decisions inside matches (Phase 12); new models (the Phase 9 list).

## 2. What exists (verified by reading the code)

| Part | State |
|---|---|
| Phone description | `core/DeviceProfile`: phone, chip, Android, ABIs, total RAM, cores, clock, GPU, NPU libraries, whether GGUF can run |
| Memory check | `core/MemoryFit`: a model needs about its file plus 200 MB, and 2.5 GB stays for the game. It warns before a load |
| Models | The Model Lab registry (`ModelRecord`: size, format, pictures) and the download list (`ModelCatalog`: size, pictures, job) |
| Measurements | Phase 10: `EvalReport` (quality interval, usable-answer median and 90th percentile, conditions) and `EvalSustained` (slowdown, battery) per run, in `files/research/eval_runs.jsonl` |
| Job rules | `RoleChoice.problem` (one GGUF at a time) and the Phase 7 memory question |
| Gaps | No workload profiles. No phone class. No way to put candidate pairs side by side. No confidence for a recommendation. No recommendation shown to the owner |

## 3. Plan (one PR each, version +1 each)

| Step | Work | Changes play? |
|---:|---|---|
| P1 | **Method, workloads and phone class:** `docs/PHASE_11_PAIRING_METHOD.md`. Two workload profiles. **Strategic:** infrequent, long answers; its budget is the median whole answer under 20 s; pictures preferred. **Tactical:** frequent, short and latency-bound; the 90th percentile of usable answers must be under 1 s, because a fight answer is only used for 1 s (`TacticalAdvisor.VALID_MS`), and it is asked at most every 330 ms. A phone class (entry, midrange, flagship) is worked out from total RAM. Pure, tested | No |
| P2 | **Recommendation engine (pure, tested):** candidates per job, with their measured evidence (quality interval, usable-time percentiles, heat slowdown) or only declared facts (size, pictures). Pairs that can't run together are removed (job rules, and memory next to the game). A **Pareto front** keeps quality, speed and memory as separate axes. Each recommendation carries its evidence, its trade-offs ("best measured quality", "fastest", "lightest"), what is missing, and a **confidence**: high = measured on this phone under good conditions on a reviewed set; medium = measured, but the set is not reviewed or the sample is small; low = declared only | No |
| P3 | **Recommendations on the Model page:** a RECOMMENDED PAIRS card built from the registry, the download list, this phone's profile and its Phase 10 runs. Each pair shows its evidence and confidence; *Use this pair* asks first, then gives the two jobs through the usual rules and memory question. Nothing changes by itself | Only when the owner taps *Use this pair* |
| P4 | **Evidence-backed configuration catalog:** the download list gets a declared phone class per model (from its size and the memory rule). An export (Debug data → Recordings) holds the recommendations with their evidence, the phone class and the inputs, so a recommendation can be checked and repeated | No |
| P5 | **Guide, phone checklist and closing record** | No |

## 4. Acceptance criteria (roadmap deliverables)

- [ ] Pairing method and workload/phone profiles (P1). **In code (v38.53):** `docs/PHASE_11_PAIRING_METHOD.md`, `universal/pairing/Workloads` (the two workloads with their budgets, phone class from RAM, declared class from size; tested).
- [ ] A recommendation engine with explicit trade-offs and confidence (P2). **In code (v38.54):** `universal/pairing/PairRecommender` (job rules and memory filter, Pareto front on quality, speed and memory without weights, labels, reasons, budget misses, confidence = the lower of the two models; an unmeasured model is never pushed off by quality alone; tested in `PairRecommenderTest`).
- [ ] A recommendation view that explains itself and keeps the owner in control (P3). **In code (v38.55):** `ai/PairingAdvisor` (candidates from the Model Lab for each job they can take, and from the download list as declared-only; evidence = the newest rankable Phase 10 run of that file in that job, plus the heat run; room = free memory plus what the jobs hold, minus the game's 2.5 GB; the rule one GGUF at a time), the Model page's RECOMMENDED PAIRS card (5 pairs, confidence colour, *Use this pair…* asks first and reloads both jobs).
- [ ] Evaluation tests for the engine (P2, P4).
- [ ] An evidence-backed configuration catalog (P4).

## 5. Risks

- **Little evidence at first:** until the owner runs Phase 10 tests, most recommendations are low confidence (declared only), and the card says so.
- **One phone:** recommendations are for this phone; the phone class only guides models that have not been measured.
- **Budgets are judgement:** the 1 s and 20 s budgets come from how the app uses the answers (`TacticalAdvisor`, the strategic plan). They are constants in the method document and can be changed.
