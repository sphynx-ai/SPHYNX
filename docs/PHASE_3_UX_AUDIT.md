# Phase 3 — UX audit of the current app (submilestone 1)

| | |
|---|---|
| **Phase** | 3 — UI/UX Audit and Product Design Blueprint. Primary role: Principal Product Designer and UX/IA specialist |
| **Scope** | The whole app as it is at version 37.29 (`ab25ef1`): the main screen with its 14 pages plus the first-run page, and the floating panel |
| **Method** | Code inspection of `app/MainActivity.kt` (pages, components, theme), `controller/OverlayManager.kt` (floating panel), `app/HelpHold.kt` and `docs/brand/`. Element counts come from a script over the page functions. Contrast ratios are computed with the WCAG 2.x formula |
| **Not done here** | No usability test with people and no screenshots on a phone yet. Findings marked *(phone check)* need a look on the device. This is an audit, not a design: the information architecture, design system and screen specs are the next submilestones and need owner approval |

## 1. What the app is made of today

**Main screen:** a header (the mark, "Sphynx", the AI status, the hold-"?" help and a big red STOP), the page content (a vertical list of cards), and a sideways-scrolling row of **14 page tabs at the bottom**.

| Tab (order) | Page | Lines | Buttons | Switches | Fields | Text lines | Sections |
|---|---|---:|---:|---:|---:|---:|---|
| 1 Home | `homePage` | 151 | 5 | 7 | 0 | 10 | Vision tuning, Run, What the AI sees, Recent actions |
| 2 What's new | `newsPage` | 15 | 0 | 0 | 0 | 2 | What's new list |
| 3 Player | `playerPage` | **393** | **17** | 6 | 4 | **40** | **12 sections**: Your hero, Battle spell, Lane, Enemy team, Skill combos, Jungle/Turtle/Lord, Hero level, Turtle & Lord, Behaviour, Skills & items, Kills/towers/gold, Regen & recall |
| 4 Draft | `draftPage` | 66 | 1 | 6 | 0 | 6 | Match type, Standard match, Ranked, Ban & pick, Enemy damage, Autonomous ranked draft |
| 5 Build | `buildPage` | 129 | 1 | 5 | 0 | 12 | Experimental builds, Counter items, Recommended picks, Item actives, Auto quick buy, Builds per role |
| 6 Meta | `metaPage` | 157 | 3 | 3 | 1 | 11 | Patch meta, Hero meta |
| 7 Library | `libraryPage` | 343 | 2 | 0 | 0 | 3 | What the app knows (heroes, items…) |
| 8 Model | `modelPage` + GGUF | 183 | 13 | 4 | 0 | 15 | Files, Where to get them, Settings, Fight advisers, GGUF models, Test question |
| 9 Learn | `learnPage` | 236 | 9 | 5 | 0 | 17 | 9 sections: training progress, cloud and Kaggle training, smart fights, lessons, live matches, per-hero results, detector pictures |
| 10 Practice | `practicePage` | 21 | 1 | 0 | 0 | 4 | Practice benchmark, Scorecards |
| 11 Performance | `speedPage` | 296 | 2 | 1 | 0 | 7 | AI speed (charts), Speed recorder |
| 12 Logs | `logsPage` | 38 | 2 | 0 | 0 | 2 | Diagnostics, History |
| 13 Debug data | `debugPage` | 82 | 5 | 2 | 0 | 5 | Session recorder, Exports, Upload to GitHub |
| 14 Setup | `setupPage` | 191 | **23** | 2 | 1 | 14 | Permissions, Screen, Calibrate buttons, Minimap icons, Scenery & minimap sync, Presets, Positions by hand |
| (first run) | `welcomePage` | 141 | 1 | 1 | 0 | 10 | 6 numbered steps: permissions, folder, models, what runs, button calibration, scenery calibration |

**Floating panel** (over the game, `OverlayManager`, 903 lines):
- **Header:** the Sphynx mark, the word SPHYNX, a status chip (ON / DRY / ERR / OFF), minimize and close.
- **Action row:** stop, record, test combo, calibrate and power, all with drawn icons (`PanelIcons`).
- **Below:** a live info line and a resize handle.
- When minimized, it collapses to the mark.

**Shared components** (all built in code, inside `MainActivity`): `card`, `cardHeader` (with a status chip), `cardText`, `cardNote`, `btn` (primary or secondary), `switch`, `field`, `chip`, `title`, `radioSwitches`, `exclusivePair`, `stageSwitch`. Charts are in `SpeedViews` and the progress ring is in `ProgressRing`.

**Theme in use** (`MainActivity.T`):

| Token | Value |
|---|---|
| BG | `#0B0E14` |
| SURF | `#151A24` |
| SURF2 | `#1E2533` |
| STROKE | `#283247` |
| TEXT | `#E9ECF2` |
| MUTED | `#8D96AA` |
| ACCENT | `#6D8BFF` |
| GOLD | `#F4C25B` |
| GREEN | `#3DD68C` |
| RED | `#FF5A6A` |

The floating panel uses the **brand palette** in `PanelIcons` (OBSIDIAN, GRAPHITE, ICE, TITANIUM, MIST, SLATE…), which is a **second, different palette**. There is also an old-colour mapping (`themed()`) for hex values left in pages.

## 2. Findings

Severity: **H** = blocks or confuses a core task, **M** = slows down or risks mistakes, **L** = polish.

### Information architecture and navigation
1. **H — 14 flat tabs of equal weight, in a sideways scroll.** Only 3 or 4 tabs fit on a phone screen, so most pages can't be seen or found without scrolling the tab row. There's no grouping. Daily use (Home, Player) sits next to rare or technical pages (Logs, Debug data, Performance, Model) and next to one-off setup.
2. **H — The Player page carries 12 sections, 17 buttons and about 40 text lines on one scroll.** It mixes hero choice, lane, combos, behaviour tuning, learned data and destructive resets. This is the densest page and the core one.
3. **M — Related things are split across tabs.**
   - Meta, Build, Draft and Library are all "game knowledge" or "pre-match".
   - Learn, Practice and Performance are all "how the AI is doing".
   - Logs and Debug data are both troubleshooting.
4. **M — The order doesn't follow the user's journey.** Setup comes last, although it's needed first. What's new is tab 2.
5. **M — The first-run page and the Setup page overlap** (permissions, calibration). The relationship is "first time" versus "later", and the UI doesn't explain it.

### Status, trust and AI transparency (roadmap Section 7)
6. **H — Capability status isn't shown in the main app.** The roadmap requires Operational, Partially Operational, Experimental, Planned and Unsupported to be clearly distinguished. `MlbbAdapter.capabilities` has these statuses, but the pages don't show them; only some section titles say "Experimental".
7. **M — Model and runtime status is spread out.** Which model is loaded, on CPU or GPU, and online versus offline (Groq) are shown on Model, Home and Performance separately. There's no single place that says "this is what is running now".
8. **M — Data freshness and provenance are uneven.** The Meta page shows patch meta, and the Library shows what the app knows, but "when updated / from where" isn't shown in one consistent way.

### States (loading, empty, error, permission, confirmation)
9. **M — Few loading and progress states.** One progress component exists (`ProgressRing`). Long actions show a toast or a changing text line: model loading, scans, online updates, training, upload.
10. **M — Destructive actions are inconsistent.** There are 26 Delete, Forget, Clear, Reset and Remove buttons. About 15 ask for confirmation and the rest act at once. They also sit among everyday controls, for example on the Player page.
11. **M — Permission states.** Permissions are on Setup and the first-run page. *(phone check)* Whether other pages say clearly when a needed permission is missing (Accessibility, capture, overlay, All files access), and offer a button to fix it.
12. **L — Empty states.** Pages with nothing yet (no recordings, no scorecards, no learned data) mostly show a sentence. A consistent empty-state pattern would help.

### Accessibility and readability
13. **H — White text on the accent colour has a contrast of 3.1:1.** This applies to primary buttons and the selected tab. WCAG AA asks for 4.5:1 for normal text. 3.1:1 only passes for large or bold text from 18.7 px bold, and the tab labels are 13sp.
14. **M — Page tabs are 38dp tall,** below the 48dp touch-target guideline.
15. **M — Small text.**
    - Most description and note text is 12–13sp.
    - The panel's status chip is 9.5sp.
    - About 36 text lines are set to 13sp and 10 to 12sp.

    *(phone check)* How readable these are in a bright room.
16. **L — Other contrast pairs pass AA:**
    - TEXT on BG 16.3:1, TEXT on SURF 14.7:1;
    - MUTED on SURF 5.9:1, MUTED on SURF2 5.2:1;
    - ACCENT on SURF 5.6:1, RED 5.7:1, GREEN 9.3:1, GOLD 10.6:1.
17. **M — Screen-reader labels.** There is 1 `contentDescription` in the main screen and 5 in the panel. Drawn icons need labels for TalkBack.
18. **L — Help descriptions were hidden behind one hold-"?" button in the header.** That kept pages short, but a new user might not find it. **Resolved 2026-11-14 (owner):** a "?" at each control, tap for a bubble, like the game's settings.

### Visual system
19. **M — Two palettes.** The main app uses `T` (blue accent) and the panel uses the brand palette (ice, titanium, obsidian). The brand in `docs/brand/` should become the one source, with semantic tokens (surface, text, accent, success, warning, danger, status levels).
20. **L — The components are local helpers inside one 3,000-line `MainActivity`.** That's fine for now. Phase 4 will need them as reusable components (the design-system implementation).

## 2a. Phone evidence (owner's screenshots, 2026-11-14)

The owner sent screenshots of every page and of the floating panel, opened and minimized. The phone was on v37.25. They confirm findings 1, 2, 6, 13 and 14. They also settle the *(phone check)* points and add these:

21. **M — The font.** All text uses a pixel-style monospace font. Headings and values read well. Long muted descriptions read poorly at 12–13sp, for example on What's new, Meta and Setup. *Resolves finding 15:* the type scale must raise body and description text, or use a more readable face for running text (design system decision).
22. **H — Primary buttons are confirmed hard to read:** white on light blue. Examples are "Start screen capture", "Update the patch meta", "Make experimental builds" and "Learn the whole database online" (finding 13).
23. **M — Labels are cut or wrapped mid-word:**
    - Library chips: "Equipmen / t".
    - Build item names: "Immortalit / y", "Brute Force Bre…".

    Chips and item labels need ellipsis rules or wider cells.
24. **M — Permission state is shown by colour only.** On Setup, granted permissions turn green and missing ones stay grey, with no "granted" / "missing" text or icon. That doesn't work for colour-blind users or in a bright room.
25. **M — Each feature page has its own on/off switch at the top:** "Research player running", "Drafter running", "Builder running". It's the same pattern as the Home RUN switches, with no single place that shows what is running. *(Supports finding 7: one "Running now" view.)*
26. **L — Team / Solo are two switches that act as one choice.** They should be a segmented choice (Draft page).
27. **L — Home repeats What's new** (a "What's new" card on Home and a tab). The navigation decision removes the duplication.
28. **L — Practice is almost empty** when nothing has been recorded. It's better as a section of "AI & models" than as a tab.
29. **Keep:**
    - the Performance page (device name, the memory ring, the storage bar);
    - the Model page's status list (dot + text);
    - the Logs "No active errors" line;
    - the floating panel (5 drawn icon buttons, status lines, and a minimized bubble with close and power).

The "⊠" box seen in one older Home screenshot is already fixed in the current code. It now reads "the power button on the floating icon".

**Owner decision (2026-11-14):** the 14 tabs are grouped into 5 sections: Play / Hero & match / Knowledge / AI & models / Settings & help. The details are in `docs/PHASE_3_INFORMATION_ARCHITECTURE.md`.

## 3. What already works well (keep)
- **Card layout:** a consistent dark card layout with section headers and status chips.
- **Help on demand:** the hold-"?" pattern for descriptions, an owner rule that keeps pages uncluttered.
- **Floating panel:** drawn icons, a clear status chip, and stop/power always one tap away.
- **First-run guide:** a numbered guide exists.
- **Safety defaults:** every feature switch is OFF by default and Dry run is ON.
- **Charts:** the Performance page shows its numbers as charts.
- **What's new:** the list shows the version of every change.

## 4. Recommended next submilestones (for owner approval)
1. **Information architecture proposal.** Group the 14 tabs into about 4–5 destinations. One possible shape, to be validated:
   - **Play** (Home with live status, the panel);
   - **Hero & match** (Player, Draft, Build);
   - **Knowledge** (Library, Meta);
   - **AI & models** (Model, Learn, Practice, Performance);
   - **Settings & help** (Setup, Debug data, Logs, What's new).

   It includes a navigation map, and a split of the Player page into sub-sections.
2. **Design tokens from `docs/brand/`.** One palette with semantic roles, the type scale (minimum sizes), spacing, radii, touch targets of at least 48dp, and accent-on-dark contrast fixed to at least 4.5:1.
3. **State and interaction matrix.** Per page: loading, empty, error, permission-missing, confirmation (all destructive actions), progress. Plus the capability-status badge.
4. **Screen specs** for Play and Hero & match first, then the others. These are illustrative mockups for owner review, **not** approved designs until the owner says so.
5. **Phone checks** for the items marked *(phone check)*: owner screenshots of each page would make the audit evidence-based.
