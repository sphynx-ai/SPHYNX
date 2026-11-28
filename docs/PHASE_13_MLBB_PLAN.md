# Phase 13 — MLBB Companion and Game-Specific Intelligence: audit and execution plan

**Status:** **APPROVED by the owner 2026-11-28**, together with Phases 11–16. **Owner decision on sources: "Fetch from the web."** The owner also supplied the Wiki capture `Emblems _ Mobile Legends_ Bang Bang Wiki _ Fandom.mht`. IN PROGRESS.
**Based on:** `sphynx-test-branch` at v38.64 (built), read from the code and from the sources themselves (section 2). No phone was used for this plan.

## 1. Role for this phase (roadmap 3.1)

- **Primary role:** Senior MOBA Game Systems Analyst and MLBB Domain Intelligence Engineer.
- **Supporting roles:** MOBA Strategy Analyst; Esports Data Researcher; Data Pipeline Engineer; Android Feature Engineer; UX Designer; QA/Evidence Analyst.
- **Mission:** MLBB companion intelligence (roadmap section 5, the migrated Issue #37), strictly in the MLBB adapter.
- **Source rules (roadmap 13.1, 13.2, 13.11):**
  - **emblems only from the Mobile Legends Wiki Emblems page**;
  - pick, win and ban rates only from the official rank page;
  - never fabricate data, and mark what is missing as unavailable.

## 2. Source feasibility (checked 2026-11-28 from this environment)

| Source | What was found | Decision |
|---|---|---|
| **Wiki Emblems page** (owner's capture of 10 Nov 2026, `https://mobile-legends.fandom.com/wiki/Emblems`) | The text holds 7 emblem sets (Basic Common; Custom Tank, Assassin, Mage, Fighter, Support, Marksman), each with its attributes, standard talents and core talent(s). The capture names all **33 icons**, each with its Wiki file address, but lazy loading left the images themselves out of the capture | The text is extracted from the capture. The 33 icons were fetched from the Wiki's own image server at **exactly the addresses the capture names** (`static.wikia.nocookie.net/mobile-legends/images/…`, served as WebP with transparency), then checked visually against their names. Ads, trackers and page chrome were left out |
| Wiki: recommendations | The page gives **no per-hero or per-role recommended set or talents**. The only link is the set names ("Custom Tank Emblem" and so on) | The app shows "**No Wiki-supported recommendation**" for heroes. Per role it shows only the set the Wiki names after that role, as a Wiki fact, not as advice |
| Wiki: data quality | Temporal Reign's description is word for word the same as War Cry's | Kept as the Wiki wrote it, with a note |
| **Official rank page** `https://www.mobilelegends.com/rank` | A JavaScript app; its numbers come from an **undocumented Moonton API** (`api.gms.moontontech.com`), not from the page's HTML | **Not scraped.** No documented public source or terms permit it. The meta tracker shows "source data not available to the app" with a button that opens the official page. No numbers are made up |
| **Liquipedia** (`liquipedia.net`, community, CC BY-SA) | API answered **429 rate-limited**: server IP addresses are blocked. Its page structure could not be checked from here | **No live esports source is connected.** The Pro Match Hub is built with its data model, states, time zones and evidence pipeline, with a provider slot. The owner decides on a source (blocker, section 6) |
| Existing app data | `MetaStore.HeroMeta.emblem` (online meta from Groq and web search), `BuildLab` (AI builds) and the strategic AI's build answer all carry **non-Wiki emblem advice**, shown on several pages | **Corrected (D2):** emblem advice anywhere in the app comes only from the Wiki data |

## 3. Plan (one PR each, version +1 each)

| Step | Work |
|---|---|
| D1 | **Emblem data and icons from the Wiki:** `assets/mlbb/emblems/` holds the 33 WebP icons and `emblems.json` (sets, attributes, talents, the icon of each, source, capture date, fetch date, notes). `adapters/mlbb/MlbbEmblems` reads and checks them (pure); recommendation lookups give "unavailable" unless the Wiki says so. Tests: every icon is mapped, every name has its icon, no guessed data. Plus this plan |
| D2 | **Emblem Library in the app, and the Wiki-only rule everywhere:** a Library page lists the sets with icons, attributes and talents, with attribution and dates, offline. Every page that showed emblem advice now shows the Wiki line instead. The AI and web-search emblem text is no longer shown |
| D3 | **Meta tracker with an honest source state:** a provider layer (`MlbbMetaSource`) whose official-rank provider reports *unavailable* with the reason and the official link. The app's own interpretation (the online meta's patch, lanes and builds) is labelled "app-generated, not official statistics", with its age |
| D4 | **Recommended five-hero compositions** (in code v38.68: `MlbbCompositions`, tested; Meta page card): coherent drafts (role and lane coverage, damage mix, front line, engage) from `HeroDb`, the lanes, and the online meta's counters where present. Each shows its evidence, strengths, weaknesses, win condition and confidence; unknown is never filled in |
| D5 | **Experimental compositions and rotations, and the validation/promotion pipeline** (in code v38.69: `MlbbStrategyEvidence`, tested; thresholds: emerging from 5 complete matches on the current patch, validated from 30 matches by at least 3 teams in at least 2 tournaments with the 95% win-rate interval above the baseline; an older patch only = outdated): hypotheses are kept apart. Each records its patch, context, sample size, outcomes and confidence. They are promoted only past the documented thresholds. Searchable, consistent strategy tags. Pure, tested |
| D6 | **Recommended rotations:** opening plans by role (jungle start, roam timing, turtle/lord set-up) checked against the app's objective timers (turtle at 2:00, lord at 8:00: `LaneLogic.objectiveName`). Each comes with prerequisites, risks and a fallback, labelled as concepts and not universal rules |
| D7 | **Pro Match Hub (model and pipeline, no live source):** regions (MPL Philippines, the other MPL regions, M-Series, Esports World Cup, SEA Games). Live / upcoming / completed / postponed / cancelled states, time zones, official watch links only, provenance. A draft-level evidence pipeline that feeds D5. A provider slot that reports "no source connected" |
| D8 | **Guide, phone checklist, closing record** |

13.12 (combat perception, minimap and gameplay sync): its MLBB-specific parts depend on Phase 15's perception work and are planned there; section 5 of the closing record says so.

## 4. Acceptance criteria (roadmap section 5)

See MASTER section 5, "Phase 13 acceptance criteria" and 13.11. Each criterion is marked *In code* with its step as it is built; the closing record names those that are **blocked** by a source decision.

## 5. Risks

- **Sources:** two of the three web sources can't be used from the app as they are (section 2). The features are built so that they show their unavailable state instead of empty or invented numbers.
- **Wiki changes:** the emblem data is a capture (10 Nov 2026) plus icons fetched on 28 Nov 2026. A later update replaces both through a PR, with new dates.

## 6. Owner decisions needed (blockers, not guessed)

1. **Esports source for the Pro Match Hub:** an official source with permitted access (an MPL or Moonton feed or API), or Liquipedia used from the phone (community source; its rate limits and CC BY-SA attribution apply).
2. **Official rank statistics:** only if Moonton offers a documented or permitted access; otherwise they stay "open the official page".
