# Phase 3 — State and interaction matrix

| | |
|---|---|
| **Phase** | 3 — UI/UX Audit and Product Design Blueprint (state / interaction deliverable) |
| **Based on** | `docs/PHASE_3_INFORMATION_ARCHITECTURE.md` (5 destinations), `docs/PHASE_3_DESIGN_TOKENS.md` (colours, badges), audit findings 6–12, 24 |
| **Status** | Proposed for owner review. Implemented in Phase 4 |

## 1. Standard states (one pattern each, used everywhere)

| State | Pattern | Rules |
|---|---|---|
| **Loading** (unknown length) | An indeterminate bar under the card title, plus a word ("Loading Gemma…") | Shown after 300ms, never for quick actions. The rest of the page stays usable |
| **Progress** (known length) | A determinate bar or ring plus "12 of 98" or "45%". A **Stop** button where the work can be cancelled | Used for scans, downloads, training, upload and learning the database |
| **Empty** | The section's icon, one sentence saying why it's empty, and **one** next-step button | Example: "No scorecards yet. A practice match vs Computer of 1+ minute makes one." Never a bare "-" |
| **Error** | A `danger` badge with a word, one sentence on what failed, and **Try again** and/or **See log** | The detail goes to Logs. The page never shows a stack trace |
| **Offline / needs network** | An `info` badge "Needs internet" on the card; its buttons are disabled with the reason | Offline-first: everything that works offline stays enabled |
| **Permission missing** | A row: what it's for + `danger` word "Missing" + **Fix** (opens the right Android screen) | Shown where the feature is used **and** on Play › Ready check. Never colour alone (finding 24) |
| **Confirmation** | A dialog: title "Delete …?", one sentence on what is lost, **Cancel** (default) and a red **Delete** | **Every** delete, forget, clear, reset and remove action. 11 of today's 26 have none (Section 4) |
| **Success** | A short toast or snackbar ("Saved", "Uploaded 2 files") | Only for actions with no other visible result |
| **Capability status** | The capability badge (tokens doc, Section 1) | On every feature card that isn't Operational |
| **AI running** | The header status line plus Play › Running now. The engine's card shows "On" or "Dry run" | STOP is always one tap away |

## 2. States per destination

### Play
| Card | Loading | Empty | Error | Permission | Other |
|---|---|---|---|---|---|
| Running now | — | "Nothing is running." Each switch is off | An engine failed to start: `danger` + reason + Try again | Starting an engine without Accessibility goes to Fix (today: a toast) | Dry run shown as a badge on each engine row |
| Ready check | Models loading: bar + model name | — | Model failed to load: `danger` + See log | Missing Accessibility, overlay, All files access, notifications or screen capture: one row each with Fix | A capability badge per model |
| What the AI sees | — | "No screen capture yet": Start screen capture button | Capture stopped by the system: `warning` + Start again | Screen capture permission: asked on Start | — |
| Recent actions | — | "Nothing yet: actions show here while the AI plays" | — | — | — |

### Hero & match
| Sub-page | Loading | Empty | Error | Network | Confirmation |
|---|---|---|---|---|---|
| Hero | "Learning this hero…" with progress | "Hero not chosen: type it or let the app detect it" | Learning failed: Try again | "Learn this hero" needs internet | — |
| Play style | — | No combo yet: "Built-in combo in use" | Combo test failed: reason | — | Clear manual combo, Forget level badges, Forget Coach data, Reset learned aggression, Delete my recorded play (all confirmed) |
| Draft | Waiting for the draft screen: the word "Waiting" (not a bar) | "No draft yet" | — | Online meta for picks: "Needs internet" | — |
| Build | "Making experimental builds…" | "Choose your hero first" (today's "no hero") | — | Experimental builds may need online models | — |

### Knowledge
| Sub-page | Loading | Empty | Error | Network | Freshness |
|---|---|---|---|---|---|
| Library | Learn database: progress "12 of 133" + Stop | Search with no result: "No hero named …" | Fetch failed for N items: count + See log | Learn and load icons need internet | "Last run: date", today "Last run: -" |
| Meta | Update: progress per hero | "Not downloaded yet: Update the patch meta" | Online model limit reached: `warning` + when it resets | Needs internet and a key | Per card: "Updated <date> from <source>" (finding 8) |

### AI & models
| Sub-page | Loading | Empty | Error | Permission / other | Confirmation |
|---|---|---|---|---|---|
| Models | Scan: progress. Model load: bar + model name | No model in Input: where to put it, plus "Where to get them" | Load failed (e.g. a GGUF): `danger` + reason + See log | All files access: Fix | Remove a GGUF model, Clear the comparison |
| Learning | Training: progress ring per model | "No result yet: train it" (as today), with the train button | Kaggle failed: reason + See log | Kaggle key missing: Fix (where to put it) | Each delete / forget / clear (8 buttons) |
| Practice | — | "No scorecards yet" + how a match counts | — | — | Clear the scorecards |
| Performance | — | Speed data empty: "Play a match with the AI to see its speed" | — | — | Clear the speed data |

### Settings & help
| Sub-page | Loading | Empty | Error | Permission | Confirmation |
|---|---|---|---|---|---|
| Setup | Calibration in progress: steps "2 of 5" | Not calibrated: `warning` "Not calibrated" + Calibrate | Calibration failed: reason + Try again | Each permission row: granted (`success` + "Granted") or missing (`danger` + "Missing" + Fix) | Clear icon samples, Reset sync offsets, Clear map scenery, Forget stuck places, Reset all positions |
| Help & data | Upload: progress per file | "0 files waiting" | Upload failed: reason + Try again | GitHub token missing: where to put it (never shown) | Delete history |
| What's new | — | — | — | — | — |

## 3. Interaction rules
1. **One primary action per card,** in the `primary` style. Every other button is secondary. Destructive buttons are always last in their card.
2. **Choices.** A choice between 2–4 options that exclude each other is a **segmented control**: Team / Solo, the lane. Switches are only for on/off.
3. **Help.** The "?" sits at the end of each control that has a description; a tap opens the bubble (owner rule, 2026-11-14).
4. **Disabled controls** say why, on tap or in their "?" bubble. For example: "Needs the accessibility service". No silently dead buttons.
5. **Long work keeps going** when the user changes page. Its progress is visible on Play and on its own card.
6. **STOP** stops every engine and closes nothing else. Nothing asks "are you sure" before stopping.
7. **Toasts** are for short confirmations only. Anything the user must act on is a card state, not a toast. Today "Turn on the accessibility service first" is a toast; it becomes a Fix row.

## 4. Confirmation gaps found in the code (all must ask in Phase 4)

| Page today | Button | Asks now? |
|---|---|---|
| Player | Clear manual combo (use Built-In) | No |
| Player | Forget the remembered level badges | No |
| Player | Forget what Coach learned for this hero | No |
| Player | Reset learned aggression for this hero | No |
| Model | Clear the comparison | No |
| Model (GGUF) | Remove <model> | No |
| Learn | Clear the match log | No |
| Setup | Clear the samples of this icon type | No |
| Setup | Reset scenery-minimap sync offsets | No |
| Setup | Clear saved map scenery | No |
| Setup | Reset all positions to defaults | No |

The other 15 destructive buttons already ask: Practice, Home ×2, Player "Delete my recorded play", Meta, Performance, Learn ×7, Logs, and Setup "Forget the remembered stuck places". This confirms the audit's count: 11 of 26 act without asking (counted from the code on 2026-11-14).
