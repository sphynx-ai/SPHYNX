# Phase 2 — Separating MLBB from the universal core (submilestones 4–7)

| | |
|---|---|
| **Roadmap** | `docs/MASTER_DEVELOPMENT_PLAN.md` Section 4, submilestones 4 (title-specific data and mechanics into adapters), 5 (the MLBB adapter, MLBB behaviour preserved), 6 (what a future adapter supplies), 7 (capabilities, unsupported features, versioning, validation) |
| **Rules** | ADR 0001 (accepted 2026-11-16). Every step is its own PR, keeps `TacticalEngineGoldenTest` and `ArchitectureRulesTest` green, and changes no MLBB behaviour unless the owner decides it |
| **Started** | 2026-11-16 (v37.64) |

## Where things stand

- The ruled layers (`universal`, `inference`, `core`, `capture`, `models`, `engines`) use only what ADR 0001 allows (migration B1–B5, baseline 0).
- `game`, `vision`, `ai` and `learning` have **no rule yet**: they mix game-independent logic with MLBB knowledge (heroes, items, Retribution, turtle / lord, the MLBB screen layout). The architecture map (`docs/PHASE_2_ARCHITECTURE_MAP.md`, section 6) lists where.
- The universal layer has the match state (`UniversalMatchState`, versioned), capabilities, decisions, and the `GameAdapter` contract. MLBB's adapter is `adapters/mlbb/MlbbAdapter`.

## The steps

| # | Step | What it gives | Status |
|---|---|---|---|
| 1 | **Game knowledge surface:** `universal/GameKnowledge` (characters, roles, lanes, spells, items, objectives) on `GameAdapter`; MLBB's is `adapters/mlbb/MlbbKnowledge`, a read-only view of `HeroDb`, `SpellDb`, `ItemDb`, `Lane` | Universal code can ask "what are this game's characters / lanes / objectives" without naming MLBB | **Done in v37.64** (`MlbbKnowledgeTest`) |
| 2 | **Universal code reads knowledge through the adapter:** the game-independent parts of `ai` and `learning` (the architecture map's "universal core" list) stop importing `HeroDb` / `SpellDb` / `ItemDb` directly | The first universal pieces with no MLBB names | Planned |
| 3 | **Grow the regression net** (submilestone 9) before touching the big MLBB-dense files: more recorded situations for `TacticalEngine`, and `GameStateTracker` | Proof that the next moves keep MLBB behaviour | Planned |
| 4 | **Split `TacticalEngine`:** the generic fight core (targets, spacing, retreat, survival) vs the MLBB rules (Retribution, turtle / lord, hero special cases, Lithowanderer...) behind an adapter-supplied rule set | The tactical core becomes reusable | Planned |
| 5 | **Split `vision/FrameProcessor` and `StrategicAdvisor`:** the pipeline and prompt plumbing vs MLBB readers and MLBB text | Perception and strategy plumbing become reusable | Planned |
| 6 | **Package rules for the split parts:** the universal halves join `ArchitectureRulesTest` (may not use the MLBB halves) | The separation is enforced | Planned |
| 7 | **Adapter guide and capability rules** (submilestones 6, 7, 10): what a new adapter must supply, how unsupported capabilities behave, versioning, the adapter test kit | A second game could be started without touching the core | Planned |

Each step updates this table with its version and evidence.
