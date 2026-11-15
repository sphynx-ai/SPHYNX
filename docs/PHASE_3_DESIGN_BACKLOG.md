# Phase 3 — Use cases and prioritized design backlog (input to Phase 4)

| | |
|---|---|
| **Phase** | 3 — UI/UX Audit and Product Design Blueprint (use cases and backlog deliverables) |
| **Based on** | The audit's 29 findings, the approved information architecture, design tokens, state matrix and screen specs |
| **Status** | The design it implements was **approved by the owner on 2026-11-15** (all five sections). **Order decided by the owner (2026-11-15): the redesign (the new look) first, then the new structure** |

## 1. Use cases (from evidence, not invented personas)

The only user evidence is the owner's own use: the phone screenshots and the device tests. So there are no personas here, only the tasks the app is observed to serve. Personas would need more users (roadmap: "where evidence supports them").

| # | Task | Where today | Where in the approved design |
|---|---|---|---|
| 1 | Set the app up once: permissions, folder, models, calibration | First-run page, Setup, Model | First-run guide; Settings & help › Setup; AI & models › Models |
| 2 | Before a match: choose hero, spell, lane; check that everything is ready | Player, Home | Hero & match › Hero; Play › Ready check |
| 3 | Start, watch and stop the AI (Dry run first) | Home switches, floating panel, STOP | Play › Running now; floating panel; STOP |
| 4 | Tune how the Player plays a hero | Player (12 sections) | Hero & match › Play style |
| 5 | Draft and build help | Draft, Build | Hero & match › Draft, Build |
| 6 | Look up heroes, items, the patch meta | Library, Meta | Knowledge |
| 7 | Train and check the AI: models, training, practice results, speed | Model, Learn, Practice, Performance | AI & models |
| 8 | Report a problem: record a session, upload debug data, read the log | Debug data, Logs | Settings & help › Help & data |
| 9 | Test a new build: read What's new, run the phone checklist | What's new | Settings & help › What's new |

## 2. Prioritized backlog for Phase 4

Each item is its own PR, with a version step and a phone test.

**Build order (owner, 2026-11-15: "redesign first"):** the new look comes first: items **5, 6, 7** (colours and sizes, fonts, status words and badges). Then the structure and safety items **1, 2, 3, 4**. Then **8–12**. The item numbers and the P labels below are the original grouping (**P1** = structure and safety, **P2** = the new look, **P3** = polish), kept so the references from other documents stay valid.

| # | Pri | Item | Findings | Done when |
|---|---|---|---|---|
| 1 | P1 | **Bottom bar and five destinations**: move today's cards into Play / Hero & match / Knowledge / AI & models / Settings & help, with the sub-page rows. No redesign of the cards yet | 1, 3, 4, 27, 28 | All 14 tabs' content reachable in the 5 sections; no sideways tab scroll; nothing lost |
| 2 | P1 | **Confirm every destructive action** (the 11 listed in the state matrix) and group them in "Data & resets" cards | 10 | All 26 ask first |
| 3 | P1 | **Play: Running now + Ready check** (one switch per engine; missing permissions with Fix; the per-page "running" switches removed) | 7, 11, 25 | One place shows what runs; starting without a permission leads to Fix, not a toast |
| 4 | P1 | **Split Player** into Hero and Play style; lane and Team / Solo as segmented controls | 2, 26 | Player content on two sub-pages |
| 5 | P2 | **Design tokens in code**: one palette (app + panel), primary and STOP colours, 48dp targets, text sizes | 13, 14, 15, 19, 22 | Contrast and size rules of the tokens doc hold on every page |
| 6 | P2 | **Fonts**: bundle Inter and the pixel font; phone sample of Pixelify Sans vs Silkscreen for the owner's pick | 21 | Same look on every phone; owner picked the pixel font |
| 7 | P2 | **Status words and badges**: permission rows (Granted / Missing), model rows (dot + word), capability badges | 6, 24 | No state by colour alone |
| 8 | P2 | **Standard states**: loading / progress with Stop, empty states, errors with Try again, "needs internet" | 9, 12 | Patterns of the state matrix on each sub-page |
| 9 | P3 | **Freshness lines** on Knowledge ("Updated <date> from <source>") | 8 | Every Meta / Library card shows it |
| 10 | P3 | **Labels never break mid-word**; item grid; TalkBack labels on all drawn icons | 17, 23 | No broken labels; icons announced |
| 11 | P3 | **First-run guide reuses the Setup cards**; research tests moved to Practice | 5 | No duplicated setup content |
| 12 | P3 | **Floating panel on the shared tokens** (status chip at least 11sp) | 15, 19 | Panel uses the same palette and type |

Already done ahead of Phase 4 (owner requests during Phase 3): the help "?" at each control (v37.33) and long information lines behind it (v37.36), finding 18.

## 3. Open decisions carried into Phase 4
- The pixel font: Pixelify Sans or Silkscreen, picked by the owner from a phone sample (backlog item 6).
- The hero detail sheet in Library and the Draft / Build / Meta / Learning / Practice / Help & data / What's new screens have written specs but no mockup; they follow the approved patterns. A mockup is made first if the owner wants one.
