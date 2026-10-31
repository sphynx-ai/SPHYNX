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

## Online meta with Groq (Meta page)

- Get a free API key at console.groq.com/keys and paste it on the Meta page. It is stored only on the phone.
- The default model, `openai/gpt-oss-120b`, searches the web with Groq's built-in browser search tool. It looks
  for the latest patch notes, hero adjustments, tier lists and guides. `openai/gpt-oss-20b` also searches (faster,
  less accurate). Other models answer from memory only. Change the model name on the Meta page if Groq retires it.
  A phone that still has `groq/compound` saved moves to the new default by itself.
- If Groq answers "Parsing failed" (gpt-oss sometimes writes output Groq cannot read, mostly while searching), the app
  takes the answer from Groq's raw output when it is there. Otherwise it retries once, then asks without web search.
  Such results are listed with the source "model knowledge, no web search". A wrong key or a usage limit stops at
  once with Groq's message.
- **Update the patch meta**: patch, top bans, top picks per role.
- **Update this hero**: build, emblem, battle spell, lane, combo, dash skill, laning / jungle / rotation tips, counters
  and recent buffs / nerfs, with the sources it searched.
- **Auto-update**: the hero you play is refreshed in the background when its meta is older than 7 days.
- **How the app uses it**, offline once downloaded. Your own settings always win.
  - Combo: yours > online meta > built-in.
  - Dash skill: yours > online meta > built-in.
  - Lane: Auto uses the meta lane. Retribution still means jungle.
  - The Build page shows the meta build.
  - Gemma's plan gets the laning, jungle and rotation tips.
  - Ranked drafting asks Groq for bans and picks whenever the line-ups change.
- The app needs internet only for this and for "Get my hero's combo online".

## Recognising your hero, banners, towers, walls

- **Your hero** is recognised by its look, not its skill icons:
  - the portrait inside your green-ringed minimap icon (its centre is found by fitting a circle through the ring)
  - the colours of its in-game model under your HP bar

  Each hero is learned once: from the AI's own pick, Gemma reading the hero select / loading screen, or
  "This is my hero now". Tested: the same hero scores up to 0.14, a different hero 0.39; the match limit is 0.24.
- **Kill / assist / FIRST BLOOD banners** are recognised by their long red or blue border lines, and that part of the
  screen is ignored while they show. Their portraits and lines are no longer read as enemies.
- **Towers are told apart by side, not colour**: the river runs from the top-left to the bottom-right corner of the
  minimap. Towers below it are yours, towers above it are the enemy's. Both teams' outer towers can be drawn yellow
  (bonus gold).
- **Walls**:
  - Dark shapes on the minimap, ignoring moments when an icon covers the spot.
  - Lone dark specks (icon outlines) are not walls.
  - Places where the hero got stuck while walking become walls for the rest of the match.
  - Paths keep a small distance from walls.
- **Minimap icon calibration** (Setup page): pick an icon type (my icon, enemy tower, bonus tower, my tower, buff
  camp, small camp, wall). Tap "Pick it on the game", switch to MLBB and tap that icon on the minimap. Its colour is
  then recognised too, up to 4 samples per type.

## Faster early game

- **Jungle**: every ready skill except the ultimate goes into each camp. Camps are cleared in walking-distance order
  around walls, so the route doesn't double back, with buffs first in the opening. It stands in reach of a monster
  it can see and aims at it.
- **Lanes**: in the first 6 minutes, waves of 2+ minions are cleared with the wave-clear skill, and with all skills
  on bigger waves, when no enemy hero is close. Laners take jungle camps up to 22% of the map away between waves.
- **Recall**: in the first 3 minutes it recalls only below 20% HP and uses Regen otherwise, so it stays on the map
  farming.

## Detectors, movement, aiming and assist (Player page)

- **Neutral monster detector**: jungle monsters, turtle and lord are found by their HP bars on screen.
  - It starts with orange / yellow bars. At the first jungle camp where it finds no monster, it learns the real bar
    colour: a colour seen 3 times next to your hero.
  - "Learn the monster bar colour now" does it in one tap while you stand next to a monster.
  - **Kill confirmation**: a camp counts as killed when monsters were seen and then none are left for 1.5 s. It does not
    wait for the minimap icon, which your own icon covers. If no monster is ever seen, it attacks for 12 s and moves on.
- **K/D/A detector**: it watches the kill / death / assist numbers (top right) and counts +1 whenever one changes.
  It does not read the digits. A hero kill is confirmed by the kill number going up. The learning AI gets +2 for a
  confirmed kill and +1 for an assist, and a death counts as dying. Calibrate "K/D/A" on the Setup page; Auto-detect
  sets it too.
- **Wall detector**: walls are the dark shapes on the minimap. They are mapped on a 40 x 40 grid, and paths to camps,
  towers and home go around them (A*). On screen: if the hero is walking but the camera stops moving, it is stuck,
  so it steps around at an angle.
- **Natural movement**: the finger stays on the joystick, turns at a human speed (no snapping), with a small
  variation in how far it pushes and in timing.
- **Skill aiming**: it aims where a moving enemy will be (enemy speed x skill travel time). It drags less far for
  close targets and quick-casts when on top of the target.
- **Assist mode**: you move. The AI only presses skills and combos when you are in a fight. Android cancels the
  finger on the screen when an app taps, so you may need to re-press the joystick after a cast.
- **MOBA rules**:
  - Retreat towards your own tower.
  - Never fight under the enemy tower.
  - Don't wander alone deep in the enemy half.
  - Last-hit minions.
  - Push only with your minions in front.
  - Defend your towers.
  - Recall and Regen when safe.

## Screen size, minimap and attacking (fixes)

- **Whole screen**: vision, touches and the overlay drawings all use the whole screen including the camera cut-out
  (2712 x 1220 on a POCO X6 Pro). Before, the overlay window sat beside the cut-out, so the drawings, and
  possibly the taps, were shifted sideways. The Setup page shows the size in use.
- **The overlay no longer covers what it marks**: screen capture records the overlay too. Labels drawn on the HP
  bars had hidden your own bar from the AI, so boxes now start under the bars and your own bar gets no box.
- **Minimap**:
  - Towers only count on the three lanes.
  - The two flying-cloud pick-ups (yellow dots, always at the same two mirrored spots) and the golden Lord / turtle
    pit icon are never towers or enemies.
- **No false units**:
  - The top of the ultimate button is masked.
  - Coloured parts of your own hero (e.g. a blue skin) are not units.
  - A minion or monster must show on two frames in a row.
- **Attacking**:
  - With no enemy hero, minion or monster in reach, the hero does not press attack or skills. Escape skills, wall
    jumps, Regen and Recall still work.
  - With something in reach and no skill ready, it keeps pressing attack.
- **Skill availability is read from the buttons** (on by default): a dark button means cooling down or not learned
  yet. The reading uses a ring around the button's centre, so the white countdown digits in the middle don't fool
  it. The Player page shows what each button reads now.

## Monsters, melee / ranged heroes and aggro circles

- **Monsters have red HP bars** like enemies, with their HP number above. An enemy hero's bar has a dark level badge
  on its left and the hero shows on the minimap near you; a monster's bar has none. Wide red bars without a badge,
  or with no enemy near you on the minimap, are read as monsters; the widest (turtle / lord) as the boss.
  Tested on your screenshots: golem, dragon, crab and turtle all read as monsters, no false enemy heroes.
- **Turtle / lord HP** comes from the bar under the scoreboard while you fight it (read 97% for 11436/12005), so
  Retribution lands at the right moment.
- **Your own bar**: the camera keeps your hero at the same spot, so a red bar there is your passive bar (not an
  enemy). At very low HP, when the green part is too thin to see, it still counts as yours.
- **Melee or ranged**: marksmen, mages and a few supports (Estes, Rafaela, Angela, Floryn...) are ranged, others
  melee. You can change it per hero on the Player page.
  - Against enemy **heroes**, a melee hero walks up close and stays there. A ranged hero keeps a safe distance at
    the edge of its attack range, steps back if the enemy gets too close (kiting) and steps in if it is out of range.
    Only ranged heroes can poke; the learning AI never picks "poke" for a melee hero.
  - Against **minions and monsters** there is no poking: walk into reach and hit.
- **Aggro circle**: a monster only fights inside the round indicator around it (a wide ellipse on screen: about
  0.6 screen heights across, 0.27 up / down). The hero stays inside it while fighting a monster so it doesn't reset.

## Skill use (Player page)

- **Reserve the ultimate for heroes** (on by default): the ultimate is only used against enemy heroes, in a fight or
  to escape them. It is never used on minions, jungle monsters, turtle or lord.
- **Cast Skill 1 and 2 as soon as ready** (on by default): while fighting minions, a jungle camp, turtle / lord or an
  enemy hero in reach, Skill 1 and 2 are pressed the moment they are off cooldown.
- These are the last step before the touches go out, so they also apply to combos and "Play like me".

## Wall jumps, bushes and speed (Player page)

- **Jumping walls**: when a thin wall (up to 3 minimap cells) blocks the straight way and walking around is much
  longer, the hero walks straight at it and uses its dash / mobility skill to go over it. It also does this when it
  gets stuck against a wall. Flicker is used for this only while escaping or chasing, because of its long cooldown.
- **Bushes**: tall grass is found on screen by its texture (thin blades) and colour, and the app knows when you
  stand in one.
  - **Hiding**: hurt (below 40% HP) and chased, the hero runs into a bush on the way out and waits there hidden,
    using Regen if nobody is close.
  - **Ambush**: as roamer or jungler, or with an ally near, it hides in a bush between you and an approaching enemy
    and attacks when the enemy walks into reach (it waits 6 s at most).
- **Fastest mode** (on by default):
  - Screen capture up to 30 fps.
  - A new decision on every new frame (up to ~25 per second instead of 5).
  - Combo pauses 40% shorter.
  - 35 ms taps, sent straight from the AI's thread.
  - The joystick only gets a new touch when the direction changes.
  - Intents switch after 0.3 s instead of 0.6 s.

  Turn it off to save battery.

## Kills, towers and gold (Player page)

What the Player does, in order:

1. **Survive**: at low HP it retreats. It uses its dash skill aimed away from the enemy.
2. **Finish low enemies**: it chases enemy heroes below 20% HP (you can change this). It dashes to close the gap and
   uses its skills and ultimate. It never chases an enemy standing under their own tower.
3. **Fights**: the learning brain decides how to fight (see Smart fights). It dashes to chase when it goes in.
4. **Turtle / lord** (see below).
5. **Jungle camps**: it walks to the camps shown on the minimap (purple = buff camp, green dot = small creeps). At a
   camp it keeps attacking until the camp's icon disappears from the minimap, so the camp is really killed.
   Retribution finishes it.
6. **Minions**: it hits the lowest-HP minion first so minions actually die.
7. **Push the tower**: only when your minions are inside the enemy tower's range and no enemy hero is seen.
8. **Defend**: it goes to one of your towers (blue) when enemy minions are at it and it is close.
9. **Laners grab camps**: between waves, laners take a jungle camp right next to them.
10. **Walk the lane**: it walks to the next enemy tower of its lane, along the edge for EXP and gold lanes.
    Yellow towers with a red cap (bonus gold) come first, then the enemy base, which wins the game.
    It waits at the edge of the tower's range for your minions.

**Tower range**: it never walks into enemy tower range without your minions there. If it is inside, it walks out.

**Reading towers from the minimap**: red tower = enemy. Yellow with a red cap = enemy tower with bonus gold.
Blue, or yellow with a blue cap = yours. The big red icon = the enemy base. The range is set as a % of the minimap
width (default 8%). It is drawn on the minimap when "Show what the AI sees" is on, so you can check it.

**Dash skill per hero**: built-in for about 40 heroes, otherwise a default for the role. Change it on the Player page.

## Regen, Recall and the jungle path (Player page)

- **Regen**: used when your HP is below the set level (default 60%) and no enemy hero or minion is close. The hero
  stands still for 5 s while it heals.
- **Recall**: used below the recall level (default 35%) when no enemy is near. The hero stands still while it channels.
  If an enemy comes close, Regen and Recall are cancelled.
- **Jungle path**: when you jungle (or Retribution makes you jungle), the hero walks from camp to camp using the
  minimap. It does not walk a lane.
  - It finds your icon on the minimap by its green ring, and finds your side's camps by their icons (magenta buffs,
    small green / cyan camps).
  - It goes to buffs first in the opening, then to the nearest camp still up.
  - It skips a camp that turns out to be empty, and steps sideways if it gets stuck on a wall.
  - Calibrate "Minimap: top-left / bottom-right corner of the map" on the Setup page. Auto-detect sets them too.
- Second bars under a hero's HP bar (passive, energy, mana) are no longer mistaken for units, and the HP reading
  skips the tick marks on the bar.

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

## Smart fights: the AI learns from its mistakes (Learn page)

The learning method is LinUCB, a contextual bandit (Li et al., 2010, "A contextual-bandit approach to personalized
news article recommendation"). It is a simple kind of reinforcement learning that can learn on the phone in a few
matches.

- **The choices**: when an enemy hero is close, the AI picks one of six ways to handle it: all in (full combo +
  ultimate), poke from range, back off, fall back to allies, keep farming, or the normal rules.
- **The situation**: it describes the moment with 20 numbers: your HP vs the enemy's, how many on each side,
  distances, skills and ultimate ready, minions, and so on.
- **The score**: 4 seconds later it scores what happened:
  - plus for damage dealt and for kills (an enemy that disappears after being brought low)
  - plus for HP kept, minus for HP lost
  - a big minus for dying
- **The learning**: one small model per choice learns how well that choice does in each kind of situation. The AI
  picks the choice with the best expected score, plus a bonus for choices it is still unsure about, so it keeps
  trying alternatives (Curiosity setting). It also tries a random choice now and then, more often while it is new.
- **Per hero**: each hero gets its own memory. A shared memory is used until a hero has 40 fights of its own.
- **From your play too**: with "Learn from me" on, your own fights are scored the same way. It learns from your
  mistakes and your good plays.
- **Safety**: low HP always retreats, whatever it has learned.
- **The Learn page** shows each choice's tries and average result, what it is choosing right now, and the latest
  lessons. You can make it forget one hero or everything.

In a simulated test where going all in only pays off when you have more HP than the enemy, it went from 79% good
choices in its first 100 fights to about 90-96% after that, finding the rule by itself. In real matches expect it to
need a few matches per hero.

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

## Optional: trained vision detector (YOLO on LiteRT)

1. Add `implementation("com.google.ai.edge.litert:litert:1.4.2")` to `dependencies { }` (see build-gradle-additions.txt).
2. Open `notebooks/train_mlbb_detector.ipynb` in Google Colab (on GitHub, tap the file, then "Open in Colab";
   or go to colab.research.google.com > GitHub > paste this repo's URL). Pick a T4 GPU and run every cell.
   You need a free Roboflow account and its API key. Default dataset: "Mobile Legends Minimap Dataset" by gladi
   (Roboflow Universe, CC BY 4.0).
3. It downloads `mlbb_detector.tflite` and `labels.txt`. In the app: Model page > Choose detector file (.tflite).
4. The Home preview shows detections as cyan boxes, and Gemma's strategy prompt gets the counts
   (for example "1 turtle_icon, 4 hero_icon").

The app works without it; the colour-based detector stays on either way.

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
