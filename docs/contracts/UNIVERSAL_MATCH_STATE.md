# Universal match state — contract, schema version 1

| | |
|---|---|
| **Phase** | 2 — Universal Architecture, submilestone 3 ("normalized game-state representation and documented contract") |
| **Code** | `universal/UniversalMatchState.kt` (`UniversalMatchState.SCHEMA_VERSION = 1`) |
| **Checked by** | `universal/StateContract.kt` (`StateContract.violations(state)`) |
| **Tests** | `StateContractTest` (rules, pinned field list), `MlbbAdapterContractTest` (MLBB output keeps the contract) |
| **Layer** | L1 universal core (ADR 0001): no game names or terms in the fields |

## Purpose

`UniversalMatchState` is how a game adapter describes one moment of a match to the game-neutral parts of Sphynx: the decision validator, the arbiter, the decision log, tactical readiness and research. Every adapter produces it; nothing in L1 reads a game's own state type.

## General rules

1. **Unknown is `null`.** A value the adapter could not read is `null`. It is never a guess, a default, or a sentinel such as `-1`. An empty list means "none seen", not "could not look".
2. **Confidence is not assumed.** `confidence = null` means the source gives none. It does not mean 1.0.
3. **Fractions are 0..1.** This covers `health`, `confidence` and `levelProgress`. A value outside that range breaks the contract.
4. **Positions are finite fractions.**
   - `screenPos` is a fraction of the screen frame.
   - `mapPos` is a fraction of the minimap or map.
   - `velocity` is in screen fractions per second.

   A position may lie slightly outside 0..1 when a tracked unit is predicted off screen. NaN and infinity are not allowed.
5. **Ids are lowercase.** `gameId` and `assignedZone` are lowercase ids (`[a-z][a-z0-9_]*`), in the adapter's own terms (MLBB: `mlbb`, `gold_lane`).
6. **Game-only details go in `gameSpecific`.** Never add a universal field for one game. Keys are lowercase snake_case; values are strings.
7. **The player is `self`, never a member of `units`.** `self` has side `SELF` and kind `CHARACTER`. `units` holds everything else that was seen.
8. **The contract is read-only.** `StateContract` reports problems and never repairs a state. A wrong value is fixed in the adapter.

## Fields (version 1)

### `UniversalMatchState`

| Field | Type | Meaning | Unknown |
|---|---|---|---|
| `gameId` | String | adapter id, e.g. `mlbb` | never unknown |
| `timestampMs` | Long | when the moment was read (device clock, ms) | never unknown; ≥ 0 |
| `phase` | `MatchPhase` | `UNKNOWN`, `OUT_OF_MATCH`, `CHARACTER_SELECT`, `LOADING`, `IN_MATCH`, `PLAYER_DEAD`, `POST_MATCH`, `SPECTATING` | `UNKNOWN` |
| `matchTimeSec` | Int? | seconds since the match started | `null`; ≥ 0 |
| `self` | `UnitState?` | the player's own character | `null` |
| `units` | List<`UnitState`> | allies, enemies, minions, neutrals, structures seen | empty |
| `resources` | `ResourceState` | the player's gold, items, level | fields `null` |
| `assignedZone` | String? | the player's lane, zone or role id | `null` |
| `allyCharacters` / `enemyCharacters` | List<String> | character ids picked by each team | empty; no blank entries |
| `gameSpecific` | Map<String, String> | adapter-only details | empty |
| `capabilities` | `CapabilitySet` | what the adapter can provide (statuses) | `CapabilitySet.NONE` |
| `schemaVersion` | Int | the contract version the state was made under | defaults to `SCHEMA_VERSION` |

### `UnitState`

| Field | Type | Meaning | Unknown |
|---|---|---|---|
| `side` | `Side` | `SELF`, `ALLY`, `ENEMY`, `NEUTRAL` | required |
| `kind` | `UnitKind` | `CHARACTER`, `MINION`, `NEUTRAL_MONSTER`, `MAJOR_OBJECTIVE`, `STRUCTURE` | required |
| `characterId` | String? | the adapter's id for the character or monster | `null` (never blank) |
| `health` | Float? | 0..1 | `null` |
| `screenPos`, `mapPos`, `velocity` | `Vec2?` | see rule 4 | `null` |
| `confidence` | Float? | 0..1 | `null` (rule 2) |
| `gameSpecific` | Map<String, String> | adapter-only details | empty |

### `ResourceState`

| Field | Type | Meaning | Unknown |
|---|---|---|---|
| `gold` | Int? | ≥ 0 | `null` |
| `itemCount` | Int? | ≥ 0 | `null` |
| `level` | Int? | ≥ 1 | `null` |
| `levelProgress` | Float? | 0..1 towards the next level | `null` |

## MLBB mapping (adapter `mlbb`)

`adapters/mlbb/MlbbAdapter.toUniversal`:
- **Unknown values:** turns the MLBB readers' `-1` unknowns into `null`.
- **Phases:** maps the 14 MLBB screen phases onto `MatchPhase` (shop, scoreboard and map are `IN_MATCH`).
- **Lanes:** become zones (`exp_lane`, `gold_lane`, `mid_lane`, `roam`, `jungle`).
- **`gameSpecific` keys used:**
  - on the state: `battle_spell`, `in_bush`, `boss_hp`, `minimap_enemies`;
  - on units: `species`, `track_id`, `source`.

## Versioning

- **Raise `SCHEMA_VERSION`** when a field is removed or renamed, or when a field or enum value changes meaning. Update this document and the pinned lists in `StateContractTest` in the same PR.
- **Adding** an optional field (default `null` or empty) or a new enum value is still a contract change. Update the document and the test. Raise the version when stored data (logs, recordings) would be read differently.
- **Recording the version:** every `DecisionLog.Entry` records the `schemaVersion` of the state it judged, so logs from different versions can be told apart.
- **Pinned fields:** `StateContractTest.theFieldsAreThoseOfVersion1` pins the fields and enum values, so an undocumented change fails the build.

## For a new adapter

A new game's adapter must:
1. implement `GameAdapter<S>`, with its own `gameId`;
2. declare its `capabilities` honestly, since unlisted means `UNSUPPORTED`;
3. have a contract test like `MlbbAdapterContractTest`. That test feeds every phase, with both full and empty readings, and asserts `StateContract.violations(...)` is empty.

The full adapter onboarding guide is Phase 2 submilestone 10.
