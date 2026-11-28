# Phase 13 — MLBB Companion and Game-Specific Intelligence: audit and execution plan

**Status:** **APPROVED by the owner 2026-11-28**, together with Phases 11–16. **Owner decision on sources: "Fetch from the web."** The owner also supplied the Wiki capture `Emblems _ Mobile Legends_ Bang Bang Wiki _ Fandom.mht`. **All steps D1–D8 built in code (v38.65–v38.72); parts are blocked by source decisions (section 8); phone test pending (section 7).**
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
| D6 | **Recommended rotations** (in code v38.70: `MlbbRotations` on `JungleDb`'s timers, tested; Meta page ROTATIONS card): opening plans by role (jungle start, roam timing, turtle/lord set-up) checked against the app's objective timers (turtle at 2:00, lord at 8:00: `LaneLogic.objectiveName`). Each comes with prerequisites, risks and a fallback, labelled as concepts and not universal rules |
| D7 | **Pro Match Hub (model and pipeline, no live source)** (in code v38.71: `MlbbProHub`, tested; Meta page PRO MATCH HUB card): regions (MPL Philippines, the other MPL regions, M-Series, Esports World Cup, SEA Games). Live / upcoming / completed / postponed / cancelled states, time zones, official watch links only, provenance. A draft-level evidence pipeline that feeds D5. A provider slot that reports "no source connected" |
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

## 7. User guide and phone checklist

**Library → Emblems:** the 7 Wiki sets with their icons, attributes and talents, with the Wiki as the source and the dates, offline. Anywhere else the app talks about emblems, it says that the Wiki has no per-hero recommendation and names the set the Wiki calls after the hero's role.

**Meta page:**

| Card | What it shows |
|---|---|
| META SOURCES | official numbers (*not available to the app*, with a button that opens the official page) and the app's own meta (labelled, with its age) |
| PRO MATCH HUB | the competitions and the source state (none connected yet) |
| ROTATIONS | the plans for now (in a match) or for each phase |
| RECOMMENDED COMPOSITIONS | three teams around your draft, with their evidence and confidence |

**Phone checklist (version 38.72):**

1. Library → Emblems: 7 sets, each with its icon and talents. Check that each icon matches the game's own. The attribution line names the Wiki page, 2026-11-10 and 2026-11-28. Search "Killing" finds Killing Spree.
2. Turn the phone offline and open Library → Emblems again: the icons still show.
3. A hero's guide card, the build lab and the Library hero details show "Emblem: No Wiki-supported recommendation … (The Wiki names a set after this role: Custom … Emblem.)". No other emblem advice appears anywhere.
4. Meta page → META SOURCES: "Official MLBB rank page: not available to the app …". *Open the official rank page* opens it in the browser. The app-generated line shows its hero count and age.
5. In a draft (or after one): RECOMMENDED COMPOSITIONS keeps your picks in their lanes, never suggests a banned or picked hero, and every team has one hero per lane.
6. In a match: ROTATIONS shows the plans for the current time (for example "Turtle set-up" between 1:30 and 8:00).
7. PRO MATCH HUB: "No esports source is connected yet …", with your time zone.

## 8. Closing record and acceptance criteria

| Step | Version | State |
|---|---|---|
| D1 emblem data and icons | 38.65 | In code (PR #147, the ten-step PR) |
| D2 Emblem Library, Wiki-only rule | 38.66 | In code |
| D3 meta sources | 38.67 | In code |
| D4 compositions | 38.68 | In code |
| D5 hypotheses and promotion | 38.69 | In code |
| D6 rotations | 38.70 | In code |
| D7 Pro Match Hub (no source) | 38.71 | In code; live data **blocked** |
| D8 guide, checklist, closing | 38.72 | In code |

**Roadmap section 5 criteria:**

| Criterion | State |
|---|---|
| Emblem recommendation data only from the Wiki, with attribution and dates | **In code** (D1, D2; `WikiOnlyEmblemsTest`) |
| Emblem icons mapped and shown in the Library | **In code** (D1, D2); visual check on the phone pending |
| Official pick/win/ban rates shown when available, with freshness and unavailable states | **Partly:** the unavailable state and the link are in code (D3); the numbers are **blocked** (no permitted access) |
| Coherent five-hero compositions with evidence-backed explanations | **In code** (D4) |
| Experimental compositions separate, traceable, confidence-labelled | **In code** (D5) |
| Rotations account for composition, matchup, phase, objectives and lane state where available | **In code** (D6): composition, phase, objectives and deaths. Matchup and wave state are not in its inputs yet |
| Experimental rotations remain hypotheses until validated | **In code** (D5) |
| Rotation concepts checked against current patch mechanics | **In code** (D6, `JungleDb` timers from the Wiki) |
| Pro Match Hub: MPL PH, other MPL regions, international events | **Model in code** (D7); data **blocked** |
| Live / upcoming / completed states, time zones, official watch links | **In code** (D7); no data yet |
| Professional analysis feeds composition and rotation systems with provenance | **Pipeline in code** (D7 → D5); no data yet |
| Evidence thresholds, sample sizes, confidence and promotion rules documented and tested | **In code** (D5) |
| Strategy tags searchable and consistent | **In code** (D5) |
| Provider adapters modular; nothing fabricated | **In code** (D3, D7) |
| Loading, empty, error, stale, confidence and time-zone states | **In code** for the states that apply; loading and error states wait for a live source |
| Tests and documentation | **In code** |
| 13.12 combat perception (MLBB part) | **Moved to Phase 15**, with its perception work |

So far the only verification is CI. Nothing has been tested on a phone.
