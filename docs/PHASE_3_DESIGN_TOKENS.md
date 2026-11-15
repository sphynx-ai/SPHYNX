# Phase 3 — Design tokens (the visual system)

| | |
|---|---|
| **Phase** | 3 — UI/UX Audit and Product Design Blueprint (design-system deliverable) |
| **Based on** | `docs/brand/README.md` (palette, rules); `docs/PHASE_3_UX_AUDIT.md` findings 13–17, 19, 21–24; `docs/PHASE_3_INFORMATION_ARCHITECTURE.md` |
| **Owner decisions** | 2026-11-14: **fonts: a pixel-style font for headings and numbers, Inter for running text and buttons.** The app bundles both, so every phone shows the same thing; today it shows the phone's own font |
| **Status** | **Approved by the owner on 2026-11-15** with the mockups that use them. One decision is open: which pixel font (phone sample in Phase 4). Contrast values are computed (WCAG 2.x) |

## 1. Colour

> **Update, owner 2026-11-15 (v37.40):** the grey-only palette below felt lifeless on the phone. The app now ships three themes the user chooses in Settings (Signal, the default, plus Arcane and Ember), each with an accent colour for selected and on states, main buttons and the logo's diamond. The values and their contrast tests are in `app/Theme.kt` and `ThemeTest`; the structure of the tokens below (grounds, text levels, status colours, STOP) is unchanged.

One palette for the main app **and** the floating panel, taken from the brand. The brand rule stays: **teal (Signal) is only for the focus point** (the diamond in the symbol and the Y), so it is never a button or selection colour.

### Ground and text
| Token | Value | Use | Contrast |
|---|---|---|---|
| `bg` | Obsidian `#0B0D10` | App ground | — |
| `surface` | Graphite `#1B1F26` | Cards, bars, panel | — |
| `surface-2` | `#252B35` (new) | Fields, secondary buttons, rows inside cards | — |
| `line` | Slate `#3A414D` | Dividers and card borders (decorative) | — |
| `outline` | `#737B88` (new) | Field and control outlines, unselected chips | 3.9:1 on surface, 3.3:1 on surface-2 (≥ 3:1 for controls) |
| `text-strong` | Ice `#F4F6F8` | Headings, values, selected labels | 18.0:1 on bg |
| `text` | Titanium `#C9CED6` | Body text, button text | 10.5:1 on surface, 9.0:1 on surface-2 |
| `text-muted` | Mist `#9AA3AF` | Descriptions, metadata | 6.5:1 on surface, 5.6:1 on surface-2, 7.6:1 on bg |

### Actions
| Token | Value | Use | Contrast |
|---|---|---|---|
| `primary` | Ice `#F4F6F8` with Obsidian text | Main button of a card, selected bottom-bar item | 18.0:1. Replaces white on light blue (3.1:1, finding 13) |
| `secondary` | surface-2 with Titanium text and an outline border | Other buttons | 9.0:1 |
| `stop` | `#C8303F` with white text | STOP (header, panel) | 5.3:1. Today's `#FF5A6A` with white is 3.0:1 |
| `destructive` | Text and outline in `danger` on surface-2 | Delete / Forget / Clear / Reset (always confirmed) | 6.0:1 |

### Meaning (always colour **and** a word or icon, never colour alone: finding 24)
| Token | Value on surface | Use |
|---|---|---|
| `success` `#3DD68C` | 8.8:1 | Ready, granted, on |
| `warning` `#F4C25B` | 10.0:1 | Needs attention, switched off, partial |
| `danger` `#FF6B78` | 6.0:1 | Error, missing, destructive |
| `info` `#7FA2FF` | 6.7:1 | Neutral information, links |
| `experimental` `#C3A6FF` | 8.0:1 | Experimental features and results |

### Capability status badge (roadmap Section 7, audit finding 6)
| Status | Badge |
|---|---|
| Operational | `success`: dot + "Operational" |
| Partially operational | `info`: half dot + "Partial" |
| Experimental | `experimental`: flask icon + "Experimental" |
| Planned | `text-muted` with an outline: "Planned" |
| Unsupported | `text-muted` on surface-2, struck-through dot + "Unsupported" |

## 2. Type

**Fonts (owner, 2026-11-14), both bundled in the app and both under the SIL Open Font License:**
- **Display: a pixel-style font** for page titles, section headers, big numbers (memory, FPS, timers), the bottom-bar labels and the panel's status. Candidate: **Pixelify Sans** (open license, has lower case and digits). Alternative: Silkscreen. The final pick needs a phone sample, shown side by side, and the owner's choice.
- **Text: Inter** for descriptions, card text, buttons, fields and chips.
- **Logs and tables** keep a monospace face, as today.

This replaces the brand README's "Sora / JetBrains Mono" line for the app. The wordmark stays the custom SPHYNX letters, never typed in a font.

| Style | Font | Size | Weight | Use |
|---|---|---|---|---|
| `display` | Pixel | 32sp | — | Big numbers (memory ring, FPS) |
| `title` | Pixel | 22sp | — | Page title |
| `section` | Pixel | 15sp, letter-spacing 0.08 | — | Section headers (today's "VISION TUNING", …) |
| `card-title` | Inter | 17sp | SemiBold | Card titles |
| `body` | Inter | 16sp | Regular | Main text |
| `body-small` | Inter | **14sp minimum** | Regular | Descriptions (today 12–13sp, finding 15) |
| `label` | Inter | 15sp | Medium | Buttons, switches, chips |
| `caption` | Inter | 13sp | Regular | Only metadata: dates, versions, "last updated" |
| `panel-status` | Pixel | **11sp minimum** | — | The panel's ON / DRY / ERR / OFF chip (today 9.5sp) |
| `mono` | Monospace | 13sp | — | Logs, tables |

Line height is 1.4× for Inter text and 1.2× for pixel text. No text below 13sp anywhere, except the panel chip at 11sp.

## 3. Space, shape, size

| Token | Value |
|---|---|
| Grid | 4dp. Steps: 4, 8, 12, 16, 24, 32 |
| Page side margin | 16dp |
| Card padding | 16dp. Card gap 12dp, section gap 24dp |
| Radius | Chips 8dp, buttons and fields 12dp, cards 16dp, bottom bar items 20dp (pill) |
| **Touch target** | **≥ 48 × 48dp** for every tap target: bottom bar, chips, switches, icon buttons (today's tabs are 38dp, finding 14) |
| Bottom bar | 64dp high, 5 items, icon 24dp + label |
| Borders | 1dp `line` on cards. 1dp `outline` on fields and controls. 2dp Ice ring for the keyboard and accessibility focus |
| Elevation | None. Depth comes from surface steps and borders, matching the flat brand look |

## 4. Motion
- Page change: 180ms slide and fade, as today.
- Press feedback: a ripple in Ice at 20%.
- Progress: a determinate ring or bar where the length is known, otherwise an indeterminate bar.
- Honour Android's "remove animations" setting: no slides, fades only.
- No looping animation on screen while the AI runs (it costs battery and performance).

## 5. Accessibility rules (apply to every screen spec)
1. Text contrast ≥ 4.5:1. Control outlines and icons ≥ 3:1. All the tokens above meet this.
2. Touch targets ≥ 48dp.
3. State is never shown by colour alone: there's always a word or an icon too.
4. Every drawn icon has a TalkBack label (`contentDescription`). Today there are 6 in the whole app (finding 17).
5. Text scales with the phone's font-size setting. Labels wrap or end with "…", **never break inside a word** ("Equipmen/t", finding 23).
6. Help "?" at each control (owner rule, 2026-11-14): 22dp circle, 44dp tap area, bubble in `surface`-dark with `text`, at most 320dp wide, closed by a tap elsewhere.

## 6. Components (to be built in Phase 4 from these tokens)
- **Navigation and structure:** bottom bar, segmented sub-page row, card, section header.
- **Controls:** primary / secondary / destructive / STOP buttons; switch row; segmented choice (Team / Solo, lane); field.
- **Indicators:** chip, status badge, capability badge, progress ring and bar, empty state, permission row (state word + "Fix").
- **Dialogs:** confirmation dialog, used for every destructive action.
- **Floating panel:** the same tokens. Its drawn icons stay (owner rule: no text symbols or emoji).
