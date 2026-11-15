# Sphynx brand

The symbol is **Lockframe**: two interlocking halves around one point of focus (two intelligences, one thing they both see). Approved by the owner on 2026-11-14.

## Files

| File | Use |
|---|---|
| `sphynx-symbol-on-dark.svg`, `-on-light.svg` | The symbol with the teal focus point |
| `sphynx-symbol-mono-dark.svg`, `-mono-light.svg` | One colour: photos, video, busy grounds |
| `sphynx-wordmark-*.svg` | The custom SPHYNX letters (never retype them in a font) |
| `sphynx-lockup-horizontal-*.svg`, `sphynx-lockup-stacked-*.svg` | Symbol and wordmark together |
| `sphynx-icon-512.svg` | The launcher icon, square |

In the app: `res/drawable/ic_sphynx_fg.xml`, `ic_sphynx_bg.xml`, `ic_sphynx_mono.xml` (adaptive icon), and `controller/PanelIcons.kt` (the symbol drawn in code for the floating panel).

## Colour

| Name | Hex | Use |
|---|---|---|
| Obsidian | `#0B0D10` | App ground |
| Graphite | `#1B1F26` | Cards, icon ground |
| Slate | `#3A414D` | Lines, borders |
| Mist | `#9AA3AF` | Secondary text |
| Titanium | `#C9CED6` | Body text on dark |
| Ice | `#F4F6F8` | Symbol, headings, light ground |
| Signal | `#35C2B0` | The focus point, on dark |
| Signal Deep | `#0F7F73` | The focus point, on light |

## Type

Sora Semibold for titles; Inter for advice, labels and buttons; JetBrains Mono for numbers, timers and logs. (Not yet applied inside the app.)

**Inside the app (owner, 2026-11-14):** a pixel-style font (bundled, open license; candidate Pixelify Sans, to be picked from a phone sample) for headings and numbers, and Inter for running text and buttons. See `docs/PHASE_3_DESIGN_TOKENS.md`. In the app since v37.38: `res/font` (Inter, Pixelify Sans, Silkscreen; SIL Open Font License texts in `docs/brand/fonts/` and in the app's `assets/licenses/`), colours in `app/Theme.kt`.

## Rules

- Clear space: one bar width (a quarter of the symbol) on every side.
- Smallest symbol: 16 px. Below 96 px wide, use the symbol alone.
- Teal is for the focus point only: the diamond in the symbol and in the Y. **Inside the app (owner, 2026-11-15)** the user chooses a colour theme (Signal = this teal, the default; Arcane; Ember: `app/Theme.kt`), and the theme's colour is also the accent for what is selected or on, and the colour of the diamond in the in-app symbol. Outside the app (launcher icon, lockups, documents) the brand colours above stay as they are.
- Do not turn, stretch, outline or recolour the halves, or add gradients, glows or shadows.

## Not done yet

No trademark or look-alike search has been run. Do one before any public release.
