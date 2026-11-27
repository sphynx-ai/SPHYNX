# Testing a model: reproducibility guide (Phase 10)

This guide explains how to run a model test on the phone so that it can be repeated, and how to read the result. The rules behind it are in `docs/PHASE_10_BENCHMARK_PROTOCOL.md`; the questions and their expected answers are in `docs/PHASE_10_EVAL_SETS.md`.

## 1. Before a test

1. **Choose the model and its job.** Model page → MODEL LAB → the model's card → *Give it a job…*. A test asks the model doing the set's job *now*.
   - The fight test uses the tactical job.
   - The strategy and match-review tests use the strategic job.
2. **Choose the processor.** On the model's card → *Settings…* → *Processors in order*:
   - `GPU,CPU` for the GPU;
   - `CPU` for the CPU only;
   - empty for the app's own choice.

   Reload the model. The test records the processor the model really landed on.
3. **Conditions** (the test checks them and says when they are not met):
   - no match is running and screen capture is off;
   - the battery is at least 30%, or the phone is charging (for the heat test, unplug it);
   - the phone is cool: heat status *none* or *light*. Let it rest a few minutes after a match or a long test.

   A test outside these conditions is kept, but it is never ranked or compared.
4. Keep the app open on the Model page while the test runs. Leave other heavy apps closed.

## 2. Running a test

- Model page → EVALUATION → *Run a test…* → choose:
  - **Fight moments:** tactical job, 40 questions;
  - **Strategy plans and coaching:** strategic job, 30 questions;
  - **Match reviews:** strategic job, 10 questions.
- Each question is asked **3 times**, greedy, after one **warm-up** answer. On Gemma 4 E2B, this takes several minutes per test.
- *Stop the test* keeps the answers so far.
- **Heat and battery:** *Run the 10-minute heat and battery test…* uses the tactical model at the speed of a fight. Run it unplugged, so the battery use is measured.

**First runs (owner, 2026-11-27):**
- Gemma 4 E2B with `GPU,CPU`: strategy test and match-review test.
- Gemma 4 E2B with `CPU`: the same two tests again.
- *Compare two test runs* on the two strategy tests then shows GPU against CPU on the same questions.

## 3. Reading the report

Each test run gets a card with these lines:

- **The first line names the test:** the set and its version, the model file, the backend and processor, and the app version.
- **QUALITY:**
  - the share of **best**, **acceptable** and **unsafe** answers, and of no answer;
  - a mean score from 0 to 1;
  - the numbers in brackets are the 95% interval: the true value is most likely in that range, and a wide interval means more repeats are needed.
  - While the set is not yet reviewed by you, the card says the scores don't count yet.
- **SPEED:**
  - **usable answer** is when the answer could be used: for a fight question, the moment the option is named, which is what matters in a match;
  - **first word** and **whole answer**;
  - each as the median and the time 90% of answers stay under.
  - The warm-up (**cold**) time is shown apart.
- **Conditions:** whether the test ran as the protocol asks.

Quality and speed are never added together. A fast model that answers badly, and a good model that answers slowly, both show as what they are.

**Compare two test runs** works only for two tests of the same set, model file, processor, phone and settings, under the right conditions. Otherwise it says why not.

- For quality, it says which is higher only when their intervals don't overlap.
- For speed, it compares the median usable answer.

**REGRESSION:** the first good test of a model becomes its baseline. A later comparable test (for example after an app update) is flagged when its median usable answer is more than 15% slower, or its quality interval lies wholly below the baseline's. Nothing is changed by itself.

**The heat test's card** shows:

- the usable answer in the first minute against the last (throttling);
- battery % per minute;
- how much the battery temperature rose;
- the highest heat status.

## 4. Repeating a test exactly

Two tests are comparable when these match:

- the set and its version;
- the model file (its fingerprint);
- the backend and processor;
- the phone and Android version;
- the runtime (LiteRT-LM or llama.cpp version);
- the model's own settings.

The app version may differ: that is how regressions are found. Every test is saved with all of these, in:

- `files/research/eval_runs.jsonl` on the phone;
- `Recordings/diagnostics-eval-<run>.jsonl`, which is uploaded with the debug data.

## 5. Changing the questions

The sets are rebuilt with `python3 tools/eval-sets/build.py app/src/main/assets/eval`. A change to a question or answer raises the set's version, so old and new results are never compared. When you accept a set, its `reviewed` becomes `true`.
