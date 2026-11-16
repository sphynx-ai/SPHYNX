# Adding a game to Sphynx — the adapter guide

| | |
|---|---|
| **Roadmap** | `docs/MASTER_DEVELOPMENT_PLAN.md` Section 4, Phase 2 submilestones 6 (what an adapter supplies), 7 (capabilities, unsupported features, versioning, validation) and 10 (onboarding) |
| **Rules** | ADR 0001 (`docs/adr/0001-layered-architecture.md`), the game-state contract (`docs/contracts/UNIVERSAL_MATCH_STATE.md`) |
| **First adapter** | Mobile Legends: Bang Bang (`adapters/mlbb/`) |
| **Written** | 2026-11-16 (v37.75) |

**No game other than MLBB is supported.** This guide says what a second game would need; it does not claim that any other game works. Phase 14 adds other games, and only when the game has an adapter that passes the checks below and has been tested on a phone.

## 1. What the core does and what the adapter does

The core is game-independent. It holds the frame pipeline, the tactical engine, the strategy scheduling, the decision checks, the learning loop and the UI.

The adapter holds everything that belongs to one game:

- the names in that game;
- the numbers in that game;
- what the game's screen looks like;
- the questions the strategic model is asked about that game.

The core asks the adapter. It never names a game's heroes, spells, items, monsters or map. `ArchitectureRulesTest.theTacticalEngineNamesNoMlbbKnowledge` enforces this. It checks `TacticalEngine`, `Utility`, `Deliberation`, `StrategicAdvisor`, `FrameProcessor` and `DecisionGuard`. Those files may not use MLBB data objects, MLBB names in strings, any `Mlbb…` type, or anything in `com.sphynx.adapters`.

## 2. What an adapter supplies

| Part | Interface (core) | MLBB's | What it holds |
|---|---|---|---|
| Identity, capabilities, mapping | `universal/GameAdapter` | `adapters/mlbb/MlbbAdapter` | `gameId` (lowercase id), `displayName`, `adapterVersion`, `capabilities`, `toUniversal(state)` (the game's readings → `UniversalMatchState`) |
| Knowledge | `universal/GameKnowledge` | `adapters/mlbb/MlbbKnowledge` | characters (with name matching), roles, ranged / melee, lanes or positions, spells, items, objectives and when they appear |
| Tactical rules | `ai/TacticalRules` | `adapters/mlbb/MlbbTacticalRules` | character special cases (channelling, transforms, recasts, dashes, execute thresholds), battle-spell use, jungle route, towers, scenery, objective names and rules, lane corridors, roles' job order, skills and playbooks, early / late game |
| Screen reading | `vision/FrameReader` (in `vision/FrameProcessor.kt`) | `vision/MlbbScreenReading` | reading one frame: phase, HUD, health bars, minimap and the game's own screens |
| Strategy questions | `ai/StrategyGame` | `adapters/mlbb/MlbbStrategy` | every question the strategic model is asked about the game (plan, draft, line-up, build...), how its answers are read, and the Build page text |

The app plugs the adapter in at start-up, in `app/ProbesWiring`:

- `TacticalEngine.rules = MlbbTacticalRules`
- `FrameProcessor.reader = MlbbScreenReading`
- `StrategicAdvisor.game = MlbbStrategy`

Each interface has a `NONE` that knows no game. With `NONE` the core does nothing game-specific: it has no special cases, no objectives and no questions, and it reads no frame. It does not guess.

The remaining coupling is known and documented. The game state that `FrameReader` produces and the tactical engine reads is still the MLBB-shaped `game/GameState` (with `Lane`, `Phase`, `HeroProfile`, `Role`). A second game must either fill in that type or be the reason it is generalised. That decision is made in Phase 14, with the owner.

### Game modes, rules and events

Game modes, rules and events are not interfaces of their own yet. They live in the same adapter parts:

- **Game modes** are phases and adapter rules. For example, MLBB's Solo practice vs Computer turns learning off (`learning/LearnGate`), and ranked or standard comes from the strategy questions.
- **Match events** (deaths, objectives taken, tower falls) come through the screen reading.

A game that needs more adds it to its adapter first. It moves into a core interface only when a second game needs the same thing.

## 3. Capabilities and unsupported features

Every adapter declares a status for each `universal/Capability`: Operational, Partially Operational, Experimental, Planned or Unsupported. A capability that is not listed is **Unsupported**, because support is never assumed (`CapabilitySet`).

How the core behaves:

- **Usable** means Operational or Partially Operational. Other parts may rely on the capability, with their own checks.
- **Experimental:** a decision from an engine whose capability is Experimental is **advisory only**. `DecisionValidator` returns `ADVISORY_ONLY`. The decision may be shown or logged but is never acted on.
- **Planned / Unsupported:** the feature is off for that game. A value the adapter cannot read is `null` in the universal state, never a guessed or default value (`StateContract`). Decisions that need it are rejected or not made, and the UI says the feature is not available for this game.
- Capabilities are never raised above what has been verified. MLBB's are at most Partially Operational until they are checked on a device.

## 4. Safety: models cannot bypass the rules

Whatever an adapter or a model supplies, every decision goes through the same deterministic checks, in this order:

1. The rules engine (`TacticalEngine` + the adapter's `TacticalRules`) makes the decision.
2. The learned blends may adjust it: the tactical network (`PolicyNet`) and "Play like me" (`ImitationPolicy`).
3. `TacticalEngine.filterCasts` applies the cast rules.
4. **`ai/DecisionGuard.check`** (v37.74) makes sure that:
   - a RETREAT / RECALL / REGEN from the rules is never replaced;
   - the joystick direction is finite and within -1..1;
   - the target position is finite;
   - each button is pressed at most once.
5. `FountainGuard` keeps the hero out of the enemy fountain. `DecisionGuard.sane` runs again on its result.
6. `ActionExecutor` sends the touches, with Dry run and the other safety switches.

Advice from the language models (the universal `Decision`) goes through `DecisionValidator` and `DecisionArbiter`.

## 5. Versioning

- **`UniversalMatchState.SCHEMA_VERSION`** (now 1) is the version of the game-state contract. It is raised only when a field's meaning changes, as described in `docs/contracts/UNIVERSAL_MATCH_STATE.md`.
- **`GameAdapter.adapterVersion`** (MLBB: 1) is the version of one adapter. Raise it when its knowledge, rules or mapping change meaning: a character's role, a rule's threshold, or what a field maps to. Recorded data and trained models can then tell which adapter version they came from.
- **The app version** (`sphynxVersion`) goes up with every PR, as usual.

## 6. Validation: the adapter test kit

Every adapter must pass these before it is called supported:

1. **`AdapterContractKit.check(adapter, samples)`** (`app/src/test/java/com/sphynx/testing/AdapterContractKit.kt`). Give it sample readings of the game, including a full one and a "nothing read" one for every phase. It checks:
   - the identity;
   - the knowledge: no empty or duplicate names, every character matches itself, made-up names match nothing, and lane and objective keys are ids;
   - that every sample keeps the game-state contract, under the adapter's own id, schema and capabilities.

   MLBB runs it in `MlbbAdapterContractTest.theAdapterPassesTheAdapterTestKit`. The kit's own test (`AdapterContractKitTest`) checks it on a small made-up game.
2. **`StateContract`** on real readings (the kit does this on the samples).
3. **A golden regression file** for the game's tactical rules, like `TacticalEngineGoldenTest` / `golden/tactical_engine_v1.txt` for MLBB. It records what the engine decides in fixed situations. A deliberate change updates the file in the same PR and says why.
4. **`ArchitectureRulesTest`** stays green.
5. **A phone test by the owner** before anything is marked working.

## 7. Onboarding a new game, step by step

1. Write down the game's capabilities honestly (most start as Planned).
2. Create `adapters/<game>/` with a `GameAdapter` and its `GameKnowledge`, and a contract test that runs the kit.
3. Add the `TacticalRules`, `FrameReader` and `StrategyGame` for the game, each with tests. Start from `NONE` and add what is verified.
4. Record golden situations for the game's rules.
5. Let the user choose the game (the app plugs one adapter in, in `app/ProbesWiring`), test it on a phone, and only then raise capabilities and announce support.

Nothing in the core should need to change for these steps. If something does, it is MLBB knowledge still in the core: move it into MLBB's adapter first, in its own PR, with the golden test unchanged.
