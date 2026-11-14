# Phase 3 — Information architecture and navigation map

| | |
|---|---|
| **Phase** | 3 — UI/UX Audit and Product Design Blueprint (information architecture deliverable) |
| **Based on** | `docs/PHASE_3_UX_AUDIT.md` (findings 1–5, 7, 10, 25–28) and the owner's screenshots |
| **Owner decision** | 2026-11-14: **yes** to grouping the 14 tabs into 5 sections: Play / Hero & match / Knowledge / AI & models / Settings & help |
| **Status** | The grouping is **approved**. The details below (sub-pages, what moves where, the global controls) are **proposed** for owner review. Implementation is Phase 4 |

## 1. Principles
1. **Order by the user's journey:** set up once, prepare a match, play, then look back and improve. Rarely used and technical pages go to the end.
2. **Five destinations, always visible.** A bottom bar with 5 items, each with a drawn icon and a label, and touch targets of at least 48dp. No sideways scrolling.
3. **One level of sub-pages.** Inside a destination, a short segmented row at the top (2–4 items). Never deeper.
4. **One place for "what is running".** The Play page shows every engine's state and its one switch. Feature pages no longer each carry their own "… running" switch (finding 25). They show a status line that links back to Play.
5. **Destructive actions are grouped and confirmed.** Each destination has at most one "Data & resets" card at the bottom of the relevant sub-page, and every Delete / Forget / Clear / Reset asks first (finding 10).
6. **No duplicates.** What's new lives in one place, and the first-run guide and Setup share their content.

## 2. Global elements (on every page)
| Element | Where | Behaviour |
|---|---|---|
| Sphynx mark, name and the AI status line ("AI off" / "Player on – Dry run" / …) | Header, left | Tapping the status opens Play |
| Help "?" (owner rule, 2026-11-14) | At the end of each control that has a description | Tap: the description in a bubble next to it (like the game's settings); tap elsewhere: closed |
| **STOP** | Header, right, always | Unchanged: emergency stop of every engine |
| Bottom bar: **Play · Hero & match · Knowledge · AI & models · Settings** | Bottom | 5 items, icon + label; the selected one is marked by colour **and** shape, not colour alone |
| Floating panel | Over the game | Unchanged in this step (see Section 5) |

## 3. The five destinations

### 3.1 Play (start page)
Replaces **Home**. Its job: *is everything ready, what is running, what does the AI see right now.*

| Card | Content | From |
|---|---|---|
| **Running now** | One row per engine (Research player, Drafter, Builder, Learning, Research mode): its switch and state. Plus the Dry run switch and the match type | Home "Run", plus the switches at the top of Player, Draft and Build |
| **Ready check** | Screen capture (start button), the models in use (strategic, tactical, detectors, with a dot **and** a word each), the permissions that are missing, each with a "Fix" button | Home status card, the Model page status list, Setup permissions |
| What the AI sees | Live preview, hero card, recent actions | Home |
| Vision tuning | The two "show boxes / touches" switches | Home |
| Show the floating icon | The button | Home |

The What's new card is removed from Play; What's new moves to Settings & help (finding 27).

### 3.2 Hero & match
Its job: *prepare and tune how the Player plays this hero and match.* Sub-pages: **Hero · Play style · Draft · Build**.

| Sub-page | Content | From |
|---|---|---|
| **Hero** | Your hero, player name, battle spell, lane (segmented choice), enemy team, "learn this hero" | Player: Your hero, Battle spell, Lane, Enemy team |
| **Play style** | Skill combos, behaviour, hero level, jungle / Turtle / Lord, kills / towers / gold, regen and recall, skills and items; at the bottom one **Data & resets** card (forget badges, Coach data, learned aggression, recorded play, all confirmed) | Player: the remaining 8 sections and the reset buttons |
| **Draft** | Match type, standard match, ranked (Team / Solo as **one segmented choice**, finding 26), ban & pick, enemy damage, autonomous ranked draft | Draft |
| **Build** | Your hero build, experimental builds, counter items, recommended picks, item actives, auto quick buy, builds per role | Build |

This splits the Player page (12 sections, 17 buttons) over two sub-pages (finding 2).

### 3.3 Knowledge
Its job: *what the app knows about the game, and how fresh it is.* Sub-pages: **Library · Meta**.

| Sub-page | Content | From |
|---|---|---|
| **Library** | Heroes / spells / equipment / terms, search, "what the app knows" counts, learn the database, load icons | Library |
| **Meta** | Patch meta, hero meta, online sources and their keys, plus a "last updated / from where" line on every card (finding 8) | Meta |

### 3.4 AI & models
Its job: *the AI's models, how it learns and how it performs.* Sub-pages: **Models · Learning · Practice · Performance**.

| Sub-page | Content | From |
|---|---|---|
| **Models** | Files and folder, where to get them, settings, fight advisers, GGUF models and test question; a capability-status badge per model (Operational / Partially operational / Experimental / Planned / Unsupported, finding 6) | Model |
| **Learning** | Training progress, cloud and Kaggle training, smart fights, lessons, live matches, per-hero results, detector pictures; one Data & resets card | Learn |
| **Practice** | Practice benchmark and scorecards (merged here: the page is nearly empty on its own, finding 28) | Practice |
| **Performance** | Device, memory, storage, AI speed charts, speed recorder | Performance |

### 3.5 Settings & help
Its job: *set up once, and fix problems.* Sub-pages: **Setup · Help & data · What's new**.

| Sub-page | Content | From |
|---|---|---|
| **Setup** | Permissions (each with a state word: granted / missing, finding 24), screen, all calibration (buttons, minimap icons, scenery & minimap sync, presets, positions by hand: owner rule, calibration stays on Setup), "open the first-run guide" | Setup |
| **Help & data** | Debug data (session recorder, exports, GitHub upload) and Logs (diagnostics, history) | Debug data, Logs |
| **What's new** | The full list; the app version | What's new |

The **first-run guide** stays a separate, step-by-step flow shown on a new install (owner rule). It reuses the Setup cards rather than duplicating them (finding 5).

## 4. Map: today's 14 tabs → 5 destinations
| Today | Goes to |
|---|---|
| Home | Play |
| What's new | Settings & help › What's new |
| Player | Hero & match › Hero + Play style ("running" switch → Play) |
| Draft | Hero & match › Draft ("running" switch → Play) |
| Build | Hero & match › Build ("running" switch → Play) |
| Meta | Knowledge › Meta |
| Library | Knowledge › Library |
| Model | AI & models › Models (status list also summarised on Play) |
| Learn | AI & models › Learning |
| Practice | AI & models › Practice |
| Performance | AI & models › Performance |
| Logs | Settings & help › Help & data |
| Debug data | Settings & help › Help & data |
| Setup | Settings & help › Setup (missing permissions also shown on Play) |

Nothing is removed: every control on today's pages has a place above. Only the duplicated What's new card on Home goes.

## 5. Not decided here (next deliverables)
- **Floating panel:** this IA doesn't change it. Its content will be reviewed with the screen specs (it already follows the brand).
- **Visuals:** design tokens (one palette from `docs/brand/`, type scale and font for running text, contrast of at least 4.5:1, 48dp targets) are the next deliverable.
- **States:** the state and interaction matrix per sub-page (loading, empty, error, permission missing, confirmation, progress, capability badge).
- **Screen specs and mockups:** Play and Hero & match first, then the others, for owner review.
- **Phase 4 order (suggestion):**
  1. the bottom bar and the 5 destinations, moving the existing cards without redesigning them;
  2. confirmations on all destructive actions;
  3. the new visual tokens;
  4. screen by screen.

  Each step is its own PR and phone test.
