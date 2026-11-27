# Phase 10 — Evaluation sets and rubric (MLBB, version 1)

**Status:** version 1, written by Claude on 2026-11-27 from MLBB knowledge and the app's own fight rules. **Not yet reviewed by the owner** (`"reviewed": false` in each file). By the owner's decision (2026-11-27), the scores count once the owner has reviewed the sets. A corrected set becomes version 2, and results of the two versions are never compared (protocol, section 4).

## 1. The files

The sets are in `app/src/main/assets/eval/`. `tools/eval-sets/build.py` regenerates them; edit the lists there, then run `python3 tools/eval-sets/build.py app/src/main/assets/eval`.

| File | Set | Job | Items | Kind |
|---|---|---|---:|---|
| `mlbb_tactical.json` | `mlbb-tactical` v1 | tactical | 40 | CHOICE: fight moments |
| `mlbb_strategic.json` | `mlbb-strategic` v1 | strategic | 30 | 15 PLAN (in-match plans), 15 RUBRIC (coaching questions) |
| `mlbb_review.json` | `mlbb-review` v1 | strategic | 10 | RUBRIC: post-match reviews |

The form (`universal/eval/EvalSet`) is game-free; the questions and answers belong to the MLBB adapter. `MlbbEvalSetsTest` checks that:

- each set parses;
- the tactical prompts are exactly what `TacticalAdvisor.prompt` sends in a match;
- every hero and role matches `HeroDb`;
- the plan prompts have the form of `MlbbStrategy.strategyPrompt`, and `DecisionParser` accepts every expected plan;
- every hero a rubric names exists.

## 2. Tactical fight moments (CHOICE)

Each item is a fight summary in the same lines the tactical model gets in a match:

- the hero, role, range, lane and time;
- HP, level, ready skills and the battle spell;
- the enemies close, their HP and whether the nearest is in reach;
- the allies, minions, enemy tower and missing enemies;
- sometimes the fight scores.

The model answers with one of `ALL_IN`, `POKE`, `FARM`, `RETREAT`, `GROUP` or `RULES`.

| List | Meaning | Score |
|---|---|---:|
| `best` | the expected option | 1.0 |
| `ok` | also a reasonable choice | 0.5 |
| `unsafe` | could get the hero killed (for example `ALL_IN` at low HP against several healthy enemies, or under their tower) | 0, counted as **unsafe** |
| anything else | wrong for the moment | 0 |
| no option named | no answer | 0 |

The rules the answers follow, in order:

1. **Low HP with healthy enemies near, no allies → back off.** At 30% HP or less against healthy enemies, or under an enemy tower with no minions, `RETREAT` is best and `GROUP` is acceptable. `ALL_IN` is unsafe.
2. **More enemies than allies, or enemies missing from the map → don't commit.**
3. **A low enemy alone in reach, enough HP and the key skills ready → take the kill** (`ALL_IN`).
4. **A tank or initiator with ultimate (and Flicker) and allies close, against grouped enemies → start the fight** (`ALL_IN`).
5. **A marksman or mage with allies in front → hit from range** (`POKE`); **a support → stay with the team** (`GROUP`).
6. **Early, even, nobody in reach → farm.**

Each item's `why` gives its own reason.

## 3. Strategic plans (PLAN)

Each item is asked in the form `MlbbStrategy.strategyPrompt` uses in a match: the hero, role and lane, then the state line, ending with "reply JSON only: {"plan": …}". The answer's `plan` field is scored like a CHOICE: `farm`, `push`, `fight`, `retreat`, `group` or `defend`. If there is no JSON, or no valid plan, the item counts as no answer.

## 4. Coaching questions and match reviews (RUBRIC)

Free answers of at most three or four sentences.

- **`must`** is a list of points. Each point is a list of alternative words or phrases, and the answer names the point when it contains any one of them (case does not matter).
- The score is the share of points named:

| Points named | Verdict |
|---|---|
| all | **best** |
| at least half | **ok** |
| fewer | **wrong** |

- **`mustNot`** lists claims that make the answer **unsafe** (score 0). For example, a physical-defence item against three magic heroes, or "dive the tower" when the jungler is missing. A claim right after a negation ("don't dive") does not count.
- Facts the rubric relies on, as the app knows them: the turtle first appears at 2:00 and the lord at 8:00 (`LaneLogic.objectiveName`).

**Limits of a keyword rubric:** it rewards naming the right points, not how well an answer explains them. A wrong answer that names the right words can still score. That is why scores are reported with their spread, the owner reviews the sets, and no single number ranks models.

## 5. Owner review

To review, open the three files, or `tools/eval-sets/build.py`, which is easier to read. For each item, check the expected answer and its reason. Corrections go into version 2 of a set. When a set is accepted, its `reviewed` becomes `true`, and from then on the report counts its scores.
