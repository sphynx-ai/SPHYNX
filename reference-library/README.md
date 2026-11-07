# Sphynx-Auto-Pilot — Shared Visual Reference Library

## 1. Purpose of the Reference Library
This `reference-library/` directory is the centralized, version-controlled visual knowledge repository for **Sphynx-Auto-Pilot** as it evolves into a **Universal MOBA Companion** (supporting *Mobile Legends: Bang Bang*, *League of Legends: Wild Rift*, *Honor of Kings*, *Arena of Valor*, *Pokémon UNITE*, and *Onmyoji Arena*).

It provides a single source of truth for owner-provided screenshots, HUD layouts, minimap states, hero transformations, skill duration badges, and technical diagrams shared across all three project agents:
- **Claude Code** — Primary Developer
- **Gemini CLI** — Secondary Developer and Asset Manager
- **ChatGPT** — Project Manager and Technical Secretary
- **Human Owner (`georgenaval1-blip`)** — Final Authority

> **Important Note**: Assets stored in this library are **shared development reference materials**. Presence in `reference-library/` does not by itself imply that a feature has been integrated into production code on `main`.

## 2. Repository & Branch Policy
- **Repository**: `https://github.com/georgenaval1-blip/Sphynx-Auto-Pilot`
- **Dedicated Branch**: `reference-assets`
- **Branch Isolation Rules**:
  1. Never upload raw reference image collections directly to `main`.
  2. Never modify production application source code (`app/src/main/...`) when committing asset updates on `reference-assets`.
  3. Never merge `reference-assets` into `main` without explicit owner authorization.
  4. Preserve original image bytes without recompression or format conversion so pixel-level RGB/HSV calibration measurements remain 100% reproducible.

## 3. Directory Structure
```text
reference-library/
├── README.md
├── asset-index.md
├── manifests/
│   └── asset-manifest.json
├── moba-games/
│   ├── mobile-legends/
│   │   ├── maps/
│   │   ├── heroes/
│   │   ├── items/
│   │   ├── abilities/
│   │   └── ui-hud/
│   └── other-games/
├── gameplay-references/
├── technical-diagrams/
├── ai-model-references/
└── miscellaneous/
```

## 4. Asset Categorization Rules
- **`moba-games/mobile-legends/maps/`**: Minimap screenshots, turret plating states, jungle camp icons, Lord/Turtle pit icons, and 3D-to-minimap synchronization references.
- **`moba-games/mobile-legends/heroes/`**: Hero model appearances, multi-form transformations (e.g., Yu Zhong Black Dragon, Thamuz Cauterant Inferno), and hero HP/resource bar structures.
- **`moba-games/mobile-legends/abilities/`**: Active skill duration badges, skill button countdown arcs (e.g., Cici `s1`, Leomord `ult`, Argus `ult`), and skill aiming indicators.
- **`moba-games/mobile-legends/ui-hud/`**: In-game HUD button states (3-stage Basic Attack glow/dim, golden `+` upgrade badges, Recall blue progress bar, Battle Spell icons, announcement banners).
- **`moba-games/other-games/`**: Reference screenshots for *Wild Rift*, *Honor of Kings*, *Arena of Valor*, *Pokémon UNITE*, and *Onmyoji Arena*.
- **`gameplay-references/`**: Multi-frame combat sequences, rotation paths, and kiting/spacing examples.
- **`technical-diagrams/` & `ai-model-references/`**: Architecture diagrams, YOLO bounding-box annotations, and TFLite/LiteRT model evaluation plots.

## 5. How Agents Should Locate and Use Assets
1. **Consult the Catalog First**: Read [`asset-index.md`](./asset-index.md) for human-readable descriptions or [`manifests/asset-manifest.json`](./manifests/asset-manifest.json) for machine-readable metadata (`asset_id`, `repository_path`, `dimensions`, `sha256`, `verified_information`, `ai_generated_interpretation`) before loading binary image files into context.
2. **Verify Checksums**: Use the recorded `sha256` hash in `asset-manifest.json` to confirm file integrity and prevent duplicate uploads.
3. **Distinguish Facts from Interpretations**: Every asset entry separates **Verified Information** (directly observable HUD text, timestamps, pixel coordinates, RGB values) from **AI-Generated Interpretation** (how the vision/tactical engine uses those observations).

## 6. How New Assets Should Be Added & Maintained
1. Compute the SHA-256 hash of each new owner-provided image and verify it is not already present in `manifests/asset-manifest.json`.
2. Exclude non-reference images (chat screenshots, terminal credentials, unrelated personal photos).
3. Copy the original file without recompression into the matching `reference-library/` subdirectory using a descriptive kebab-case filename while recording `original_filename`.
4. Append the complete metadata entry to `manifests/asset-manifest.json` and `asset-index.md`, commit on `reference-assets`, and push to `origin/reference-assets`.
