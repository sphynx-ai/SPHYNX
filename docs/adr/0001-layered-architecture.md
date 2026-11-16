# ADR 0001 — Layered architecture: platform, universal core, game adapters, app

| | |
|---|---|
| **Status** | **Accepted** by the owner on 2026-11-16 (proposed 2026-11-14) |
| **Phase** | 2 — Universal Architecture Audit and Foundation, submilestone 2 ("define the game-agnostic core") |
| **Based on** | `docs/PHASE_2_ARCHITECTURE_MAP.md` (current-state map, submilestone 1) |
| **Enforced by** | `app/src/test/.../architecture/ArchitectureRulesTest.kt` |

## Context

The current-state map found:
- **The code is organised by technical role** (`vision`, `ai`, `game`), not by "universal vs game-specific".
- **MLBB knowledge is concentrated** in `game/` and `ai/`.
- **A universal layer exists** (`universal/`, `adapters/mlbb/`, `engines/`), but the live AI path doesn't use it.
- **There are cycles:** `ai ↔ game ↔ vision`, and `core` depends on the layers above it.

Sphynx must stay MLBB-first while becoming a Universal MOBA Companion. A big rewrite is ruled out by the roadmap ("no speculative large rewrite"), so the target has to be reachable in small steps, each of which keeps MLBB working.

## Decision

### Four layers

| Layer | Holds | Today's packages (draft placement, map Section 7) |
|---|---|---|
| **L0 Platform** | Android and device services with no MOBA knowledge: storage, logs, settings store, device info, screen capture, model files, inference runtimes, touch gestures | `core` (platform part), `capture`, `inference`, `models`, `controller/AccessibilityController` |
| **L1 Universal core** | MOBA concepts and decision support for any game: the universal match state, capabilities, decisions, validator, arbiter, readiness, research gates, learning plumbing, game-neutral AI runtime pieces | `universal`, `engines`, the neutral parts of `research`, `learning`, `ai` |
| **L2 Game adapters** | Everything specific to one game: its state reading, screen and HUD perception, knowledge (heroes, items, map, objectives), rules and tuning. One adapter per game; MLBB is the first | `adapters/mlbb`, `game`, the MLBB parts of `vision` and `ai`, `core/Layout` |
| **L3 App** | Composition and presentation: the screens, the floating panel, the AI loop that wires the layers together, and services that need several layers (session recorder, speed recorders, debug upload) | `app`, `ai/AiRuntime`, `controller/OverlayManager`, `core/SessionRecorder`, `core/Speed`, `core/TouchSpeed`, `core/VideoRecorder`, `core/GitHubUpload`, `core/PhoneState` |

### The dependency rule

A layer may depend only on the layers **below** it: **L3 → L2 → L1 → L0**.
- **L1 never depends on a game.** The universal core reaches a game only through interfaces it owns: `GameAdapter`, `CapabilitySet`, `UniversalMatchState`.
- **L0 never depends on anything above it.**
- **Nothing but L3 depends on the UI.**
- **Within L2, adapters don't depend on each other.**

### How the cycles are cut, without behaviour change

| Cycle or upward link | Cut by |
|---|---|
| `core` services that use `ai`, `game`, `vision`, `learning` (`SessionRecorder`, `Speed`, `TouchSpeed`, `VideoRecorder`, `GitHubUpload`, `PhoneState`) | ~~Reclassify as L3 and move to an app-services package~~ (corrected 2026-11-14: the lower layers call these services, so a move would only turn the arrows round). The service stays in L0 and reads what it needs from the layers above through `core/Probes`, which the app layer fills at start (`app/ProbesWiring`). **Done for `Speed`, `TouchSpeed`, `PhoneState`, `VideoRecorder`, `GitHubUpload` (step B1, v37.28)** and **`SessionRecorder` (step B2, v37.29: its MLBB frame row is built by `ai/SessionRows`)** |
| `core/Settings` storing MLBB `Lane` | Store the adapter's lane id as a string; the MLBB adapter converts it. **Done (B1): `Lane.live()` in `game`** |
| `game/GameStateTracker` reading `AiRuntime.playerOn`, and the `TacticalDecision` / `Intent` types | The tracker receives the flags it needs as input from L3. The decision types move to L1 (`universal/Decision` already exists) |
| `vision` → `app/IconCache` (portraits) | Move the MLBB portrait cache into the MLBB adapter |
| `models` → `ai`, `learning`, `vision` (`AutoFolder` applies what it finds) | `AutoFolder` lists and copies (L0) and reports each file to registered handlers. The handlers live with the code that loads the file |
| `capture` → `ai`, `game` | Status callbacks registered by L3 |
| `engines` → `ai`, `learning`, `game`, `adapters` (decision bridge and logging) | Map at the L3 edge: the AI loop converts to universal decisions before calling `engines` |

### Enforcement

`ArchitectureRulesTest` reads the source and fails the build when a package uses one it may not. Today's rules cover the foundation:
- `universal` and `inference` use nothing else;
- `core` uses nothing else;
- `capture` uses `core`;
- `models` uses `core` and `inference`;
- `engines` uses `universal`, `core` and `inference`;
- nothing but `app` uses `app`.

The **31 existing violations** (20 after step B1, 14 after B2) are listed in the test as a baseline that may only shrink:
- **a new violation fails the test;**
- **a fixed violation that is still listed also fails,** so the list stays exact.

Each migration step removes its entries. Rules for `game`, `vision`, `ai` and `learning` are added once those packages are split (L1 vs L2).

## Consequences

- **Good:** the direction is fixed and checked on every PR, so new code can't deepen the tangle. Each cut above is a small, separate PR with no behaviour change.
- **Cost:** some moves change package names, which touches imports across files. They are mechanical, but each needs the full CI build.
- **Not decided here** (later submilestones):
  - the versioned game-state schema (submilestone 3);
  - the exact adapter surface for knowledge, rules and perception (submilestones 4–7);
  - the final deterministic decision check (submilestone 8).
- **Not a rewrite:** no file is split or rewritten by this ADR.

## Alternatives considered

1. **Split into Gradle modules now** (`:platform`, `:universal`, `:adapter-mlbb`, `:app`). The compiler would enforce the layers. Rejected for now: the cycles make the split impossible without first cutting them, and the phone's Code on the Go build is sensitive to project-structure changes. Revisit once the baseline is empty.
2. **Leave packages as they are and document only.** Rejected: without a check, the tangle grows back.
3. **Rewrite `TacticalEngine` and `GameStateTracker` on the universal state first.** Rejected: they're the most MLBB-dense and the least tested code (map Section 9). They need a regression net (submilestone 9) before they move.
