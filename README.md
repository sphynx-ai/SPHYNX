# Sphynx Auto Pilot — Universal MOBA Companion

An Android-based AI companion for MOBA players. Sphynx Auto Pilot is being developed around a **Universal MOBA Companion** vision: reusable game understanding, strategic reasoning, tactical decision support, learning, and player assistance that can expand beyond a single title.

**Mobile Legends: Bang Bang (MLBB) is the current supported implementation and development target.** The wider multi-MOBA vision is the product direction, not a claim that every MOBA is already supported.

## AI architecture

Sphynx uses a layered, hybrid architecture so that fast gameplay decisions do not depend on a large language model:

- **Strategic engine — Gemma 4 E2B (LiteRT-LM):** the primary on-device language model for higher-level strategy, draft and build reasoning.
- **Small tactical model — Gemma 3 270M (experimental):** an optional lightweight model being explored for fight-level guidance. It is not yet considered action-ready; fast Kotlin rules remain the dependable tactical decision layer.
- **Deterministic tactical engine — Kotlin:** frequent gameplay decisions such as lane clear, engagement, retreat, skill use and objectives.
- **Game perception — Android screen capture and vision:** reads game state, HUD elements, minimap information and screen phases.
- **Learning systems:** use recorded play and match outcomes to improve decision support over time.

The strategic and small tactical models have distinct responsibilities. The Gemma 3 270M model must not be described as a finished or autonomous action engine.

## Current MLBB capabilities

- Home dashboard, Player, Draft, Build, Model, Learn, Logs and Setup pages.
- Screen-phase detection and game-state tracking.
- Hero, battle-spell, lane, minimap and objective awareness.
- Role-aware tactical rules, target selection, engagement and retreat scoring.
- Draft, build and strategic guidance through the strategic model.
- Optional trained detectors and learning models.
- Optional online meta lookup; core gameplay assistance is designed to work offline.

Capabilities and readiness vary by subsystem. Experimental models and optional integrations should not be treated as production-ready features.

## Project direction

The long-term goal is a **Universal MOBA Companion** with a game-agnostic core and title-specific adapters for game rules, heroes, items, maps, roles and interfaces. MLBB is the first implementation; support for other MOBAs will require their own data, perception mappings, mechanics and validation.

## Development and setup

See [SETUP.md](SETUP.md) for Android setup and model placement, and [build-gradle-additions.txt](build-gradle-additions.txt) for the Gradle dependencies.

Main source: `app/src/main` (including `ai`, `vision`, `game`, `controller`, `capture`, `learning`, `models`, `core` and `app`).

For research and development use. Automating gameplay may violate a game's terms of service; use responsibly and check the relevant game's rules.
