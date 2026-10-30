# MLBB Research: hybrid offline AI (v3)

## Install into Code on the Go

1. Use your "MLBB Research" project (package `com.example.mlbbresearch`).
   In `app/src/main/java/com/example/mlbbresearch/`, DELETE the old `MainActivity.kt`
   (and any other old `.kt` files there). The new code lives in sub-folders.
2. Copy this zip's `app/src/main` folder over your project's `app/src/main`
   (replace files when asked). Delete `res/xml/accessibility_service_config.xml` if it is still there.
3. Edit `app/build.gradle.kts` (see `build-gradle-additions.txt`):
   - `minSdk = 30`
   - in `dependencies { }` REMOVE every `com.google.mediapipe` line and ADD:
     ```
     implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
     implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
     ```
   - do not add a `repositories { }` block.
4. Save, sync, build.

If the build says `compiled with an incompatible version of Kotlin ... metadata version is 2.4.0`,
your Kotlin is older than 2.3: change `latest.release` to `0.16.1`.

## First run

1. Setup page: turn on the accessibility service (it is a NEW service name, so turn it on again),
   allow Display over other apps and Notifications.
2. Model page: open the Gemma 3n E2B page, accept the licence, download the `.litertlm` file,
   choose it, then Load. (The old MediaPipe `.task` file no longer works.)
3. Home: Start screen capture, then switch on Player / Drafter / Builder.
4. Setup page: calibrate the buttons (see below).

## Calibrating the buttons

- Start screen capture, open MLBB in a match, go back to the app and tap
  **Calibrate in-game buttons on the game**. After 5 seconds, switch back to MLBB: yellow markers appear.
  - **Auto-detect** finds the attack button and places the other buttons using MLBB's default layout.
    It uses the golden "+" and the quick-buy icon when they are showing.
  - Drag any marker that is off, then tap **Save**. **Next screen** goes on to the shop and hero-select markers.
- If you use a custom MLBB button layout, drag the markers by hand. Auto-detect assumes the default layout.
- **Presets**: save the positions under a name (one per phone or MLBB layout) and load them later.
- You can still type x,y percentages by hand at the bottom of the Setup page.

## Match type (Draft page, only one can be on)

- **Standard**: no bans. On the hero-select screen the AI taps your hero, can change the battle spell and the emblem,
  then taps Confirm. This does not need Gemma. It needs Drafter + Autonomous on, and the hero-select markers calibrated.
  Keep your hero in the same spot in MLBB's list (for example in favourites).
- **Ranked**: ban & pick through Gemma, as before (Drafter + Builder + Autonomous).

## In a match

- **Floating icon**: the ⏻ button turns the AI on and off (it remembers which parts were running). The red X is the
  emergency stop. The status window has an AI ON/OFF button too.
- **Player** levels up skills when the golden "+" shows (order is set on the Player page; the ultimate comes first
  by default). It buys the quick-buy item when it shows and no enemy is close. It holds the joystick down and turns
  smoothly, instead of tapping it again on every move.
- **Phases** recognised from the screen colours: hero select / draft, loading, in game, dead, equipment shop,
  scoreboard, big map. The Player pauses while the shop or the scoreboard is open.

## Your hero, battle spell and lane (Player page)

- **Hero**: the app checks these in order:
  1. The skill icons on screen. Each hero is learned once.
  2. The hero picked this match: the AI's own standard pick, or Gemma reading the hero-select / loading screen.
  3. The name you typed.
  4. Gemma's guess during the match.

  When the hero is known from a pick, its skill icons are remembered, so after one match the hero is recognised
  without Gemma. You can also teach it: type the name and tap **This is my hero now** while the Player or
  Learn from me is running in a match.
- **Battle spell**: choose it, or leave it on "detect". Gemma reads the name under the spell button, and the icon is
  remembered for next time. **This is my spell now** teaches the icon by hand.
- **Lane** (only one switch on): Auto, EXP, Gold, Mid, Roam or Jungle. **Retribution equipped = jungle**.
  - Auto picks by role: marksman = gold, mage = mid, fighter = EXP, tank/support = roam, assassin = jungle.
  - Routes from base: EXP walks up, then right. Gold walks right, then up. Mid uses the push direction.
    Roam follows the team. Jungle farms monsters.

## Combos (Player page)

- In a fight the Player presses the hero's combo in order, with short pauses. It does not press every ready skill at
  once. Skills are aimed at the target by dragging the skill button.
- Write a combo as `s1 > s2 > a > ult` (a = basic attack). `s2(500)` waits 500 ms after that step.
- Which combo is used: yours, then a built-in starting combo (35 heroes, check them against the patch), then a
  default for the hero's role.
- **Get my hero's combo online** downloads a guide page and copies the combo written in it. The page comes from the
  address template (default: the hero's fandom wiki page) or an address you paste. If the page only describes the
  skills, Gemma works a combo out of them. You can also paste guide text. This is the only time the app uses the
  internet.

## Jungle, turtle and lord

- Monsters are seen by their orange / yellow HP bars. A bar about twice a hero's width is the turtle or the lord.
  It is called the turtle before 8:00 and the lord after, counted from when your hero first appears.
- The Player helps take the turtle or lord when an ally is on it, when you jungle or roam, or when it is below half HP,
  and only if no enemy hero is near.
- With Retribution it finishes monsters below the set HP (default: 12% for turtle/lord, 35% for jungle monsters).

## Learn from me

1. Turn on **Learn from me** (Home or Player page) and play a few matches yourself. The app never touches the screen
   in this mode. It saves the situation 5 times a second, along with what you did:
   - where you walked (from the camera movement)
   - when you used a skill, spell or recall (the button suddenly goes dark)
   - when you attacked (standing still next to a target)
2. Turn on **Play like me** on the Player page. The Player finds the moments most like the current one and does what
   you did then. Retreating at low HP and recalling stay with the safety rules. It starts copying after 300 recorded
   moments (about a minute of play) and falls back to the rules when nothing similar was recorded.
3. The Learn page can export your recorded play as CSV.

## How it is organised (matches your note)

| Folder | Job | Speed |
|---|---|---|
| capture/ | MediaProjection screen frames (fallback: accessibility 1 fps) | 10-15 fps |
| vision/ | HP-bar hero/enemy/minion detection, enemy tracking, minimap, screen phase, HUD, button auto-detect | every frame |
| game/ | Game state, hero profiles, primary + counter builds | every frame |
| ai/TacticalEngine | Kotlin rules: lane clear, engage, retreat, skills, ultimate | 5 per second |
| ai/GemmaEngine + StrategicAdvisor | Gemma 3n E2B on LiteRT-LM: plan, draft, build | event-driven |
| controller/ | Accessibility touches, overlay, item swap | on demand |
| learning/ | SQLite experiences, scoring, "learn from me" demos + copying, CSV export | 2-5 per second |
| models/ | Model file import and loading | once |
