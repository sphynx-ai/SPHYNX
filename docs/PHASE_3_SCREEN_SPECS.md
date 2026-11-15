# Phase 3 — Screen specs: all five destinations

| | |
|---|---|
| **Phase** | 3 — UI/UX Audit and Product Design Blueprint (screen specs) |
| **Based on** | `PHASE_3_INFORMATION_ARCHITECTURE.md`, `PHASE_3_DESIGN_TOKENS.md`, `PHASE_3_STATE_MATRIX.md` |
| **Mockups** | The owner's private review page https://claude.ai/artifact/6QiW5kE1Kdy2etugUsSPwi: Play, Hero, Play style, then Library, Models and Setup. Sources in `docs/mockups/phase3/` |
| **Status** | **Approved by the owner on 2026-11-15: all five destinations** (Play and Hero & match first, then Knowledge, AI & models and Settings & help). Built in Phase 4 in the order of `PHASE_3_DESIGN_BACKLOG.md` |

Every screen below shares the frame (top to bottom):
1. **Header, 64dp:** the mark, "Sphynx" and the AI status line. On the right, STOP (`stop` red, white text).
2. **Sub-page row:** segmented, 48dp, only where the destination has sub-pages.
3. **Content:** cards on `bg`, with a 16dp margin and 12dp gaps.
4. **Bottom bar, 64dp:** Play · Hero & match · Knowledge · AI & models · Settings. The selected item is an Ice pill with Obsidian text and its icon filled.

## 1. Play

Purpose: *is everything ready, what is running, what does the AI see.* There are no sub-pages.

| # | Card | Contents (top to bottom) | States (state matrix) |
|---|---|---|---|
| 1 | **Running now** | Title row: "Running now" + a badge ("All off" muted / "Dry run" warning / "Live" success). One row per engine (**Player**, **Drafter**, **Builder**, **Learning**), each with name, a one-word state and a switch. Then the **Dry run** switch. Then "Match: Standard (detected)" in caption | Engine fails to start: danger row + Try again. No Accessibility: the switch opens Fix |
| 2 | **Ready check** | Only what's **not** ready, one row each: icon, what ("Accessibility service"), state word ("Missing"), **Fix**. When all is ready: a single line, "Ready to play" with a success dot. Below: **Start screen capture** (primary) or "Screen capture on · 30 fps" | Model loading: a bar and the model name |
| 3 | **What the AI sees** | Preview (16:9). Hero row: portrait, name, lane, spell. Then phase, enemies, target and plan in 2 columns | No capture: empty state with Start screen capture |
| 4 | **Recent actions** | The last 5 actions, newest first, in mono caption | Empty: "Nothing yet" |
| 5 | **On screen** | Switches: "Show what the AI sees", "Show where the AI touches", "Floating icon" | — |

The What's new card leaves Play (it moves to Settings & help). The research test buttons (small model test, arbiter comparison, practice moments) move to AI & models › Practice. Their destructive buttons are confirmed.

## 2. Hero & match
Sub-pages: **Hero · Play style · Draft · Build**. The first visit opens Hero; after that, the last one used.

### 2.1 Hero
| # | Card | Contents |
|---|---|---|
| 1 | **Your hero** | Portrait (or "?") + name + role and lane. Field "Hero" with a **Detect** button and a **Set** button (Set replaces today's "Enter"). Field "Player name" + **Save**. A one-line status: "Using: Karina (fighter, melee)" |
| 2 | **Battle spell** | A choice chip row: Detect, Flicker, Retribution, … (scrolls). The current one is selected |
| 3 | **Lane** | **Segmented control:** EXP · Gold · Mid · Roam · Jungle, plus the "Save recommended lanes" link |
| 4 | **Enemy team** | 5 slots (portrait or "?"), filled by detection or by tap |
| 5 | **Learn this hero** | A primary button "Learn skills + guide" (needs internet), with progress |

### 2.2 Play style
| # | Card | Contents |
|---|---|---|
| 1 | **Skill combos** | "Built-in" or "Manual" segmented; the combo editor; **Test combo** |
| 2 | **Behaviour** | Aggression and the behaviour switches from today's Behaviour section |
| 3 | **Objectives** | Jungle, Turtle and Lord rules (today's two sections merged) |
| 4 | **Level & items** | Hero level, skills & items, kills / towers / gold |
| 5 | **Regen & recall** | Today's section |
| 6 | **Data & resets** | Destructive list, each with a confirmation: clear manual combo, forget level badges, forget Coach data, reset learned aggression, delete my recorded play |

### 2.3 Draft
| # | Card | Contents |
|---|---|---|
| 1 | **Match type** | "Detected: Standard" + the current screen |
| 2 | **Standard match** | Switches: tap my hero, change spell, change emblem; **Test the standard pick** |
| 3 | **Ranked** | **Segmented Team / Solo** (today it's two switches), ban & pick lists |
| 4 | **Enemy damage** | The bar, or empty "Shown once the enemy heroes are known" |
| 5 | **Autonomous ranked draft** | Today's switches, with the capability badge (Experimental) |

### 2.4 Build
| # | Card | Contents |
|---|---|---|
| 1 | **Your hero build** | 6 item icons in a 3 × 2 grid. Labels on up to 2 lines, ending with "…", never broken mid-word |
| 2 | **Experimental builds** | Experimental badge; primary **Make experimental builds**; empty: "Choose your hero first" with a link to Hero |
| 3 | **Counter items** | List or empty |
| 4 | **Recommended picks / bans** | List or empty with the link to Meta |
| 5 | **Item actives & quick buy** | Switches, with the "needs Accessibility" reason when it's off |
| 6 | **Builds per role** | Today's section |

## 3. Owner review of Sections 1–2: approved (2026-11-15)
What was approved:
1. The layout of Play: Running now first, then Ready check that shows only problems.
2. The Hero / Play style split of today's Player page.
3. The segmented controls (lane, Team / Solo, combo mode).
4. The look of the mockups: brand palette, pixel headings, Inter text, light primary buttons, deeper red STOP.

## 4. Knowledge
Sub-pages: **Library · Meta**.

### 4.1 Library
| # | Card | Contents |
|---|---|---|
| 1 | **What the app knows** | Three counters in pixel numbers: Skills 0/133, Guides 0/133, Equipment 0/98. "Last run: <date>" or "Never". **Learn the whole database** (primary, needs internet, progress + Stop); **Load all icons** (secondary) |
| 2 | **Browse** | Segmented: Heroes · Spells · Items · Terms. Labels fit or end with "…" (today "Equipmen/t", finding 23). Search field |
| 3 | **List** | Rows of 72dp: portrait 48dp, name, then "assassin · Jungle", then the state as words with a dot ("Skills learned", "Guide: not yet"). Tap opens the hero's detail sheet |
| — | Empty search | "No hero named '…'" |

### 4.2 Meta
| # | Card | Contents |
|---|---|---|
| 1 | **Online sources** | One row per online model (Groq, Gemini ×3, Cerebras): name, state word ("Ready" / "Limit until 14:05" / "No key"). Keys are never shown, only "key found" (owner rule). A capability badge on the card: **needs internet** |
| 2 | **Update** | Switches: Use the online meta, Web search per hero, Automatic updates (each with "?"). **Update the patch meta** (primary). Hero field + **Update this hero** |
| 3 | **Patch meta** | Its content, or empty "Not downloaded yet", with a line "Updated <date> from <source>" (finding 8) |
| 4 | **Hero meta** | Same pattern per hero |
| 5 | **Data & resets** | Delete all downloaded meta (confirmed) |

## 5. AI & models
Sub-pages: **Models · Learning · Practice · Performance**.

### 5.1 Models
| # | Card | Contents |
|---|---|---|
| 1 | **Models in use** | One row per model: strategic (Gemma 4 E2B), small tactical, minimap detector, screen detector, tactical network, GGUF. Each row: a dot **and** a word (Ready on GPU / Off / Not loaded), its size, and the capability badge (Operational / Experimental …). Tap a row for details and Load / Unload |
| 2 | **Folder** | "Sphynx-Storage/Input". **Scan the folder now**, **Change the folder**, "All files access: Granted / Missing + Fix" |
| 3 | **Get models** | Links: Gemma 4 E2B (2.6 GB), Gemma 3 270M, notebooks, Groq key, each with its size and "needs internet" |
| 4 | **GGUF (llama.cpp)** | Experimental badge. Add a .gguf, the list with Remove (confirmed), test question + Stop |
| 5 | **Settings** | GPU switch (on by default), fight advisers on / off + their comparison (Clear: confirmed) |

### 5.2 Learning
| # | Card | Contents |
|---|---|---|
| 1 | **Training progress** | The progress rings (today's), one per model, with the result or "No result yet: train it" |
| 2 | **Train in the cloud** | Kaggle: key state ("key set" / missing + where to put it), the training buttons with progress |
| 3 | **Learning from play** | Smart fights, recent lessons, live matches, per-hero results |
| 4 | **Training pictures** | Counts per detector; Delete (confirmed) |
| 5 | **Data & resets** | The forget / delete actions of today's Learn page (all confirmed) |

### 5.3 Practice
| # | Card | Contents |
|---|---|---|
| 1 | **Scorecards** | The list, or empty: "No scorecards yet. A match counts when it is a practice match vs Computer and the Player plays at least a minute" |
| 2 | **Research tests** | Moved from Home: Test the small model on the practice moments, practice moments and readiness status (in the card's "?"), the arbiter vs normal comparison |
| 3 | **Data & resets** | Clear the scorecards, Clear the comparison, Delete the practice moments (all confirmed) |

### 5.4 Performance
The approved look of today's page stays: the device name, the memory ring and storage bar, then the AI speed charts and the speed recorder (Export, Clear: confirmed).

## 6. Settings & help
Sub-pages: **Setup · Help & data · What's new**.

### 6.1 Setup
| # | Card | Contents |
|---|---|---|
| 1 | **Permissions** | One row each: Accessibility service, Display over other apps, Notifications, All files access, Screen capture. Each with **Granted** (success, tick icon) or **Missing** (danger, Fix button). Never colour only (finding 24). "Switch greyed out? Allow restricted settings" becomes the "?" of the Accessibility row |
| 2 | **Calibrate buttons** | State per layout: "2-skill: calibrated" / "not calibrated" (warning). One button per layout + shop |
| 3 | **Minimap icons** | Samples per icon type, Clear (confirmed) |
| 4 | **Scenery & minimap sync** | Sync now (primary), Reset offsets / Clear scenery / Forget stuck places (confirmed) |
| 5 | **Presets & positions** | Save preset, Reset all positions (confirmed). Positions by hand removed in v37.45 (owner, 2026-11-15) |
| 6 | **Screen** | Today's screen-size facts, behind the card's "?" |
| 7 | **First-run guide** | "Open the first-run guide" |

### 6.2 Help & data
| # | Card | Contents |
|---|---|---|
| 1 | **Session recorder** | State ("Off · last file: –"), Start recording (data + video), auto-record switch, file size choice |
| 2 | **Upload to GitHub** | "Token found" (never shown) · files waiting · last upload; Upload now (primary); auto-upload switch |
| 3 | **Exports** | Export speed data and scorecards, Save the full log |
| 4 | **Diagnostics** | "No active errors" (success) or the errors; History (mono), Copy, Delete (confirmed) |

### 6.3 What's new
The list, newest first. Each entry: kind chip (New / Fixed / Improved), title, text, "v37.36 · #26 · not yet checked on a phone". The app version at the top.

## 7. Owner review of Sections 4–6: approved (2026-11-15)
What was approved:
1. Knowledge: counters on Library; online sources and freshness on Meta.
2. AI & models: one "Models in use" list with dot + word + badge; research tests moved from Home to Practice.
3. Settings & help: permission rows with Granted / Missing words; Debug data and Logs together.

Phase 4 follows `docs/PHASE_3_DESIGN_BACKLOG.md`. By owner decision (2026-11-15) the new look comes first (colours, sizes, fonts, status words), then the bottom bar and the five destinations, the confirmations, and Play's Running now and Ready check.
