# Phase 3 — Screen specs: Play and Hero & match

| | |
|---|---|
| **Phase** | 3 — UI/UX Audit and Product Design Blueprint (screen specs, first two destinations) |
| **Based on** | `PHASE_3_INFORMATION_ARCHITECTURE.md`, `PHASE_3_DESIGN_TOKENS.md`, `PHASE_3_STATE_MATRIX.md` |
| **Mockups** | Play, Hero and Play style: the owner's private review page https://claude.ai/artifact/6QiW5kE1Kdy2etugUsSPwi, with sources in `docs/mockups/phase3/`. Illustrative only, **not approved designs** until the owner says so (roadmap Section 7) |
| **Status** | Proposed for owner review. Knowledge, AI & models and Settings & help follow the same patterns once these two are approved |

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

## 3. What the owner reviews
1. The layout of Play: Running now first, then Ready check that shows only problems.
2. The Hero / Play style split of today's Player page.
3. The segmented controls (lane, Team / Solo, combo mode).
4. The look of the mockups: brand palette, pixel headings, Inter text, light primary buttons, deeper red STOP.

After approval, Phase 4 builds these two destinations first (IA Section 5 order), then the other three with the same patterns.
