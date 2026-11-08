# MLBB Research: hybrid offline AI (v3)

## Install into Code on the Go

1. Use your Sphynx Auto Pilot project (app id `com.sphynx.autopilot` since v37.19; code package `com.example.mlbbresearch`).
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
2. Download the Gemma 4 E2B strategic model `.litertlm` file (Model page link, accept the licence) into the Sphynx-Auto-Pilot-Storage folder,
   then Model page -> *Choose the folder*. The app copies and loads it by itself (see "One folder" below).
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

- **Floating icon**: the ⏻ button turns the AI on and off (it remembers which parts were running). The X hides the
  icon while the AI keeps running ("Show the floating icon" on the Home page brings it back). The status window has an AI ON/OFF button too.
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

- Get a free API key at console.groq.com/keys and save it as `groq_key.txt` (key on the first line) in the Sphynx-Auto-Pilot-Storage folder.
- The default model, `openai/gpt-oss-120b`, searches the web with Groq's built-in browser search tool. It looks
  for the latest patch notes, hero adjustments, tier lists and guides. `openai/gpt-oss-20b` also searches (faster,
  less accurate). Other models answer from memory only. To use another model, put its name in `groq_model.txt` in the folder.
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
- **Coach mode** (replaces Assist): the AI plays and you can help at any moment; see "Coach" below.
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

## Learned from your gameplay video (40 s, Thamuz)

Measured frame by frame and copied into the AI:
- **Skills**: 26 casts, almost all quick taps (MLBB auto-aims); only 2 deliberate aimed drags (291 and 750 ms). The
  AI now taps a skill when the target is near and only drags (220-300 ms) to aim at a target far away.
- **Turning**: 90% of your direction changes are under 27 degrees per 1/12 s. The AI now turns at about 340
  degrees a second, timed by the clock rather than by how often it decides; reversals (running away) are faster.
- **Joystick**: your thumb sits at the edge of the ring (full speed) and your joystick floats (it appears where the
  thumb lands). The AI now pushes to 93-99% of the ring.
- **Attack**: you press about every 330 ms while fighting. The AI now presses attack every 250-330 ms, while skills
  still go out at once.

## Bushes and walls, tuned on your video and screenshots

Measured on 4 sharp screenshots and frames of your video, per small patch of screen:

| | side-to-side texture | blade ratio (side / up-down) | teal share |
|---|---|---|---|
| Bushes (tall grass) | 6.0-6.6 | 1.33-1.48 | 71-96% |
| Stone floor / grass / river | 1.7-2.8 | 0.64-1.13 | varies |
| Rock / cliff | 3.6 | 0.59 | 15% |
| Tree leaves / trunk | 4.4-8.3 | 0.79-0.94 | 22-51% |

- **Bush** = strong texture, more side-to-side than up-down (upright blades), mostly teal, not too bright. Monster
  and hero bodies are skipped (the turtle's shell is textured teal too).
- **Wall on screen** (rock, cliff, tree canopy) = strong up-down texture, more up-down than side-to-side, lighter,
  not teal. It mostly marks wall edges. When the way straight ahead is mostly wall, the hero curves 35 or 70
  degrees to the clear side before bumping; if every way is blocked, it jumps with a dash. The minimap wall map and
  the "stuck" check stay the main wall detection.
- The video is compressed (blurry), which weakens textures there; the app reads sharp frames.

## No idle, thinking ahead

- **Next step planned**: while the current task runs, the next one is worked out (shown as "Next:" in the floating
  window). Jungle: the next camp is chosen while the current one is being killed, and the hero walks off the moment
  the kill is confirmed.
- **No waiting at an empty camp**: it moves on after 4 s instead of 12 s.
- **No waiting at a tower**: instead of standing at the edge of tower range for minions, it walks back along the
  lane to meet the next wave.
- **Shorter ambushes**: only while an enemy is actually walking towards the bush, 3 s at most.
- **No freezing**: if your HP bar vanishes for a moment (effects, animations), the last movement continues for up
  to 0.6 s.
- **Towers are not monsters**: an enemy tower's red bar near an enemy tower on the minimap is a structure, never a
  monster to farm.

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

- Monsters are seen by their RED HP bars with no level badge next to them (enemy heroes have one). A bar about twice
  a hero's width is the turtle or the lord. (v17: the old "learned monster colour" is gone and wiped on start: it had
  learned the teal of grass and leaves, so bushes, walls and your own hero showed up as monsters, the pink boxes,
  and the AI attacked them and stood at the "camp" forever.)
  It is called the turtle before 8:00 and the lord after, counted from when your hero first appears.
- The Player helps take the turtle or lord when an ally is on it, when you jungle or roam, or when it is below half HP,
  and only if no enemy hero is near.
- With Retribution it finishes monsters below the set HP (default: 12% for turtle/lord, 35% for jungle monsters).

## Hero level (v17)

- Your level 1-15 is read from the round badge left of your HP bar. At capture size the digits are about 5 x 6 pixels,
  so it counts: level 1 at the start, +1 when the badge changes and stays changed, or when a new skill point (+) shows.
- Every badge seen for a few seconds at a known level is remembered (levels 1, 2, 9 and 12 are built in, measured on
  your screenshots). A remembered badge gives the level directly and corrects any miscount; two digits = 10 or more.
  The same remembered badges read the nearest enemy hero's level.
- The EXP ring (gold arc around the badge) is read as 0-100%.
- How the level changes play: before level 4 (no ultimate) farm first and fight only easy targets; level 4, 8, 12 and
  being ahead in level make it fight more; 2+ levels behind it avoids even fights; at 15 it stops farming small camps
  and pushes towers; it does not recall while a level-up is moments away (EXP ring 80%+) and a camp / wave is there.
- The Player page shows "Next: ... | level 5 (EXP 40%): ..." with the plan to raise the level.

## Priorities per role (v34)

Shared first (every role): survive -> finish an almost dead monster -> recall / Regen -> kill low enemy heroes ->
bush ambush -> no duels before level 4 -> LAST HITS (turtle / lord at 15% or less, a monster at 20%, a minion at 18%
in reach: secured first unless a good fight is on) -> then the role's own order:

| Jungle | EXP / Mid | Gold | Roam |
|---|---|---|---|
| a very good fight (70+) | a very good fight (70+) | a very good fight (70+) | turtle / lord on screen |
| turtle / lord on screen | minion wave (last hits) | minion wave (last hits) | turtle / lord up on the minimap |
| turtle / lord up on the minimap | turtle / lord on screen | turtle / lord on screen | enemy heroes (tanks start fights, supports protect) |
| monsters on screen (own buffs first) | turtle / lord on the minimap (early game: always rotate) | turtle / lord on the minimap (early: only very close) | minion wave |
| enemy heroes | push the tower (green circle) | push the tower | push the tower |
| next camp (buffs first) | enemy heroes | enemy heroes | defend a tower, join a teamfight |
| minions, tower | join a teamfight (early: from far), defend a tower | defend a tower, nearby camps (gold + levels) | roam with the mid / jungler, check bushes ahead |
| | nearby camp between waves | teamfight (early: only very close) | |

- Rotations: defend a tower under attack, join a teamfight seen on the minimap (2 allies + an enemy together), go to
  a turtle / lord that is up - then the lane route brings it back to its lane.
- Fighting an enemy hero inside its tower range: the tower shoots you first even with our minions there. A green
  circle counts as red / yellow for hero fights, and the engagement score drops by 30.
- Tank: +15 to start a fight when an ally is near. Support: -20 alone; in a fight it stays between its ally and the
  enemy.
- Jungle: own purple buff (Thunder Fenrir) / orange buff (Fiery Hound) always counts as closer when choosing the next camp.

## Tactical network: only the reliable parts are used (v33)

- The notebook writes `policy_info.json` next to `policy.tflite`: how well the joystick and each button did on your
  most recent play. Put both files in the Sphynx-Auto-Pilot-Storage folder.
- The app then uses the network's joystick only with 40%+ (every move counted equally) on 300+ checked moments and
  15+ of each move, and a button only with 20+ presses checked and 40%+ of its presses right. The rest stays with
  the rules. Without the file, everything it suggests is used (as before).
- The notebook prints YES / not yet for each part; the Model page shows what is used.
- Example, your last training: joystick not yet (31% on 102 moments); buttons used: skill 2, ultimate.

## Tactical network: where you are going (v32)

- With good recordings the network still only guessed (13% vs 11% blind): each moment only described the units
  around you, but most walking is about WHERE you are going. Each moment now also records (40 numbers): your place
  on the minimap, the way to the camp you head for, the way to the nearest enemy tower, and the way you were already
  moving.
- The notebook learns and checks the joystick only on moments with enemies / minions / monsters near (that is where
  the app uses the network; walking routes stay with the planner), and a button press counts within 0.2 s.
- The notebook uses the 40-number moments once 1500+ exist (older ones are skipped then), so play a few matches on
  v32 before training again.

## Whole-screen detector (v31)

A second trained detector looks at the WHOLE screen (the first one only reads the minimap). Classes: hero_ally,
hero_enemy, hero_self, minion_ally, minion_enemy, lord, turtle, fiery_hound, thunder_fenrir, lava_golem, horned_lizard, fire_beetle,
lithowanderer, crab, cyclone_eye, jungle_creep, tower_ally, tower_ally_plated, tower_enemy, tower_enemy_plated, base_ally, base_enemy, bush, wall.

1. Play with *Collect detector training pictures* on (Learn page): every 10 s (200 a day) the whole screen is saved
   with boxes drawn from the colour vision into `Download/MLBB-Research/screen_dataset` (heroes and minions from
   their HP bars and team colour, jungle monsters by name from their bars (see "Jungle monsters" below)
   and the minimap icons, enemy towers from the range circle, bushes and walls from the bush / wall finder). Only
   the colour vision's finds are used, never the screen detector's own (it would learn its own mistakes).
2. Quick: zip `screen_dataset`, open `notebooks/train_screen_detector.ipynb` in Colab (T4 GPU), run all, choose the
   zip. Best: upload the folder to a Roboflow project, fix and add boxes (ally towers are never drawn by the app),
   generate a version and use the notebook's Roboflow cell instead.
3. Put `mlbb_screen_detector.tflite` and `screen_labels.txt` in the Sphynx-Auto-Pilot-Storage folder; the app loads them.

What the AI does with it (only filling gaps in the colour vision):
- jungle monsters with no HP bar near them = monsters nobody has hit yet: the AI walks up and attacks
- extra bushes (hiding, ambush) and wall cells (steering around them)
- an enemy tower whose circle is not on screen: a circle of the usual size around it (red inside, green with our
  minions there, yellow otherwise), so the tower rules still apply

Honest limits: trained only on the app's own boxes, it mostly learns what the colour vision already sees (plus the
shapes, so it can also find things without an HP bar); fixed boxes from Roboflow are what make it clearly better.
Pictures are taken from the 800-px capture, so tiny far-away things are hard.

## Turtle and lord (v30)

- The app finds the turtle and lord icons on the minimap by itself: both pits are at fixed spots; the LORD is a
  trophy with a thin stem (pink early, gold when it is up, grey = dead), the TURTLE a pink blob (it can sit in
  either pit). Checked on your screenshots from 00:17 to 11:30: early both found, mid-game the turtle in the top-left
  pit, late the gold lord, a dead lord not counted; one miss (a turtle under the glowing lane highlight).
- Used right away (no detector needed): "turtle / lord is up" sends junglers and roamers there with an ally.
- Detector training pictures now get lord_icon / turtle_icon boxes too (added to the class list when the detector's
  labels.txt does not have them).
- Detector notebook, cell 2b: lord_icon and turtle_icon are ADDED as new classes when the Roboflow version lacks them
  (it has only hero_icon and turrent_icon), boxes are converted by class name, and your older pictures get their
  lord / turtle boxes drawn in the notebook. The exported labels.txt lists the classes the model really learned.

## Your movement is recorded correctly now (v29)

- Your walking direction is read from how the picture shifts. It compared two frames ~0.1 s apart in 8-pixel steps:
  at walking speed the shift is only 2-5 px, so most walking was saved as "standing", diagonal walking as "right",
  and up / down almost never (the camera tilt makes it look shorter). That is why the tactical network learned
  "always stand".
- Now: a 5-px grid with sub-pixel precision, compared with the picture from ~0.25 s ago, up / down corrected for the
  camera tilt. Test (a real game picture panned in each of the 8 directions at walking speed): old 0% right
  (everything "standing"), new 98-100%; standing still is still read as standing.
- Also better: the stuck-on-a-wall check and Coach's "you are steering" check use the same reading.
- **Delete the old recordings** (Player page -> Delete my recorded play) and record new matches before training the
  tactical network again: the old ones mostly say "stand".

## Detector training pictures from your own matches (v28)

- The exported CSV trains the tactical network only; the detector needs PICTURES with boxes.
- Learn page -> *Collect detector training pictures* (on by default): in a match, every 6 s (300 a day at most) the
  app saves the minimap as a picture plus a YOLO label file with boxes already drawn, into
  `Download/MLBB-Research/dataset/` (`images/mm_*.png`, `labels/mm_*.txt`, `classes.txt`), with the same class names
  as the detector (labels.txt).
- Boxes come from: hero portraits found by their coloured ring (red enemy, blue ally, green you), your own icon,
  enemy and ally towers, and the detector's own finds when one is loaded. Checked on your screenshots: most portraits
  and towers are boxed; some are missed and a few extra boxes appear (skill arrows, lane glow), so check them.
- Then either:
  - **best**: upload the dataset folder to your Roboflow project, fix the boxes, export a new version, set `VERSION`
    in the detector notebook; or
  - **quick**: zip the dataset folder, run the new cell *2b* in `train_mlbb_detector.ipynb` and choose the zip: your
    pictures are added to the Roboflow set (85% training, 15% checking) as they are.

## Cleaner Model and Meta pages (v27)

- No more manual file choosing, loading, reloading or removing: everything comes from the MLBB-Research folder.
- Model page: one status card (folder, Gemma, detector, tactical network, Groq key, minimap), *Choose / Change the
  folder*, *Scan the folder now*, links to get the files, and two switches: Gemma on the GPU, and one switch for the
  trained models (detector + tactical network together).
- Meta page: the key and model fields are gone (groq_key.txt / groq_model.txt); one switch turns the online meta on
  for play, auto-update and drafting together.
- Fixed values that had their own fields: detector looks at the minimap, detector confidence 0.4, class names from
  labels.txt, network confidence 0.55, meta refreshed after 7 days.

## One folder for all your files (v26)

1. With your file manager, make the folder **CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage** at the top of the phone's storage (next to Download). (Before v37.19 this was Download/MLBB-Research.)
   App -> Model page -> *Allow all files access* -> turn it on: the app then reads the folder by itself AND saves its exports,
   training pictures, Kaggle results and backups in it. Without it, use the folder picker below and the app's own files go to Download/MLBB-Research.
2. Put the downloaded files in it:
   - the strategic model: Gemma 4 E2B `.litertlm` (for example, `gemma-4-E2B-it.litertlm`); the optional Gemma 3 270M tactical model is a separate file/slot
   - `mlbb_detector.tflite` and `labels.txt` from the detector notebook
   - `policy.tflite` from the tactical network notebook
   - `groq_key.txt`: a text file with your Groq API key on the first line (optional: `groq_model.txt` with a model name)
3. (Only without all files access) App -> Model page -> *Choose the folder* -> pick CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage -> *Use this folder* -> *Allow*.
   This is needed once; Android then lets the app read that folder for good.
4. From then on, every time the app starts (or when you tap *Scan the folder now*), it copies what is new or changed,
   loads the Gemma model, the detector and the tactical network, and applies the class names and the Groq key.
   Big files are only copied again when they changed. The Model page shows what the last scan did.
- From a computer you can also copy the files to `Android/data/com.sphynx.autopilot/files/Sphynx-Auto-Pilot-Storage`
  (the app reads that folder without choosing it; many phone file managers cannot open it).
- The Groq key in a text file can be read by other apps that may read your Downloads; delete `groq_key.txt` after the
  first scan if you prefer (the key stays saved in the app).

## Tactical network + the detector in decisions (v25)

The AI now has three brains, each at its own speed:

| Part | How often | What |
|---|---|---|
| Strategy | every few seconds | Gemma 3n E2B (offline): plan and aggression |
| Tactics | 5-10 times a second | the rules and scores, plus the trained tactical network (if loaded) |
| Eyes | every frame | colour vision + the trained YOLO detector on the minimap |

**Tactical network** (learned from your play):
1. Play matches with screen capture on (Coach records you; it now also saves a richer 32-number situation:
   the 17 from before plus level, EXP, survival score, monsters, bush, tower circle, enemies near you on the
   minimap, lane, match time, spell ready).
2. Learn page -> *Export my recorded play* (CSV in Downloads/MLBB-Research).
3. Open `notebooks/train_tactical_policy.ipynb` in Google Colab, upload the CSV, run all cells. It trains a small
   network (9 joystick choices: the 8 directions or stand; 6 buttons: attack, skill 1, skill 2, ultimate, spell,
   recall), shows how often it matches your most recent play, and makes `policy.tflite` (about 55 KB).
4. Put `policy.tflite` in the Sphynx-Auto-Pilot-Storage folder; the app loads it by itself. It runs in about a millisecond per decision.
- How it is used: its joystick choice in fights and when lining up on a minion wave (from 55% confidence, setting on
  the Model page), its buttons when it is sure (60%, attack 50%) and the skill is ready. Retreat, recall / Regen, the
  tower circle, finishing monsters and no early duels always come first; jungle / lane / objective routes stay with
  the planner; the skill rules still filter its buttons. The Player status shows its suggestion and how often it
  agrees with the rules. Re-train every few matches: more of your play = better.
- Without it, the nearest recorded moments of yours are used as before.

**The YOLO detector is now part of the decisions** (not just shown):
- hero icons on the minimap get their team from the ring colour (red enemy, blue ally, green you)
- enemies close to you on the minimap but not on screen (bushes, fog) lower the survival score (-8 each) and the
  fight score (-15 each); an enemy closing in over ~2 s is a gank warning (-12 / -15)
- missing enemies are counted from the detector's icons
- a turtle / lord icon on the minimap = it is up: junglers and roamers (level 4+, healthy) go there when an ally is
  near it and no enemy is
- without the detector, the colour reading of the minimap is used (rougher, no allies or objectives)

## Monsters at their last HP (v24)

- At the last few % a monster's red bar is only 1-5 pixels wide: it was dropped as too small, so the monster
  "disappeared", the AI stopped attacking and walked off (your 08:04 screenshot: the lion at 284 HP was not found).
  A short coloured stub now counts as a bar when a long grey "missing HP" part follows it (lion found, HP 3%).
  No extra fake units appeared on your other screenshots.
- A monster that was at 20% or less and goes out of sight is hit for 1.5 s more (Retribution too if ready).
- Never walking away from a monster it is fighting or that is almost dead, in any lane (before, a laner could walk
  past a monster at 3% to go back to its lane); with very low HP it still recalls from a healthy monster.

## Tower circle, your bars, finishing monsters, 8-way joystick (v23)

- **Enemy tower circle** (read on screen, every 2nd frame, same colour twice in a row):
  - GREEN = your minions are inside: it walks in and hits the tower.
  - YELLOW = you are just outside and no minions are in: it never steps in; it waits at the edge for its minions
    (a step towards the tower is taken out of the movement).
  - RED = you are inside with no minions: it walks straight out, away from the tower, and does not hit it.
    Only a kill on an enemy under 10% with a healthy survival score may stay a moment.
  - Found by its bright colour away from the HUD and HP bars, kept only when it forms a big arc, then a circle is
    fitted (the ground is seen at a tilt). Checked on your screenshots: green, yellow and red correct, nothing found on
    30+ other match screenshots.
- **Your bars**: the thin line right under your HP bar (red passive, blue mana, yellow energy) is never read as your
  HP or as a unit. Before, a blue mana line looked like an ally standing on you, and when the green part was very
  thin the red passive line stood in for your HP (your lord-fight screenshot: real HP about 18%, it read 3%, now 14%).
  At very low HP your HP is read straight from the bar.
- The shop check no longer fires in dark jungle or the lord pit (it did at 66-79% dark; the shop is 93%).
- **Finishing monsters**: at low HP with no enemy hero near, a monster in reach that is almost dead (creep 35% or
  less, turtle / lord 15% or less) is finished before recalling. A recall or Regen no longer presses attack.
- **Joystick in 8 directions** (N, NE, E, SE, S, SW, W, NW): the heading changes only when the wanted direction is
  clearly closer to another of the eight (37 deg off when walking, 30 in fights), and when walking not sooner than
  ~0.9 s after the last turn. A turn of 90 deg or more slides the thumb along the ring through the directions in
  between, so the hero curves round instead of snapping. A wall detour steps two directions (90 deg) to one side.

## Smarter fights: scores, states, skill knowledge, checked casts (v22)

Hybrid design: vision -> game state -> decision scores -> combo planner -> touches, and every important touch is
checked on the next frames. Gemma / Groq only plan and look things up; they never press buttons.

- **Survival score (0-100)** instead of fixed HP lines: HP, the hero's role (a tank at 20% outlasts a marksman at 40%),
  a ready escape / shield / heal / Flicker, allies near, enemies near and how much HP they still have, enemies missing
  from the minimap, standing in enemy tower range. Retreat when it falls under 30 with an enemy near (and always
  under 15% HP); it stays retreating until it is above 50.
- **Engagement score**: target HP, killable with the ready skills, isolated target, allies, ultimate / crowd control
  ready, own low HP, extra enemies, no escape ready, target under its tower, level difference, passive bonuses, your
  K/D trend (ahead = press, behind = only clear fights), and how SURE the vision is about that enemy (a fresh or
  flickering sighting lowers the score). 50+ fights, 35+ keeps fighting, 25-49 keeps a safe distance and waits,
  below 25 stays away. A turtle / lord the team is taking comes first unless the fight scores 70+.
- **Target choice** by role, not the nearest: exposed (far from its team) and low-HP enemies; tanks and supports
  peel the enemy closest to their allies; assassins dive exposed targets; marksmen and mages pick what they can hit
  from range; fighters look for 1v1s; heroes whose passive stacks stay on one target.
- **Fight states** FARMING / POSITIONING / ENGAGING / RETREATING / OBJECTIVE with hysteresis (a switch must hold for
  0.6 s, retreating is at once), so it does not change its mind every tick. The Player status shows the state and
  why ("+20 isolated, -15 no escape ready, ...").
- **Kills**: a target under 10% is chased only when survival is 35+, no other healthy enemy is next to it, and no
  objective would be lost.
- **Skill knowledge (SkillBook)**: per skill range, target type (target / direction / area / self / dash), crowd
  control, mobility, shield / heal, damage share, cast mode (tap / aim / hold to charge and release), hold time and
  travel time; the passive becomes play rules (basic attack after every skill, stay on one target, stronger at low
  HP, regen out of combat = fewer recalls, bush bonus = more ambushes, execute bonus = chase at higher HP, bonus
  after a dash = open with the dash). Learned from the skill descriptions online (the hero you play is learned by
  itself; a button learns all heroes), or from descriptions you paste.
- **Combo planner**: before every step: is the target still there, in THIS skill's range, still alone, not at its
  tower, is your HP still fine, is it still a good fight? If not, the combo stops and the engine reassesses. The
  ultimate is saved unless the ready skills can kill (lethal estimate), it is a teamfight, or the engine asked;
  an enemy about to die to basic attacks does not get the ultimate.
- **Checked casts**: after each skill the button must go dark (it fired); a skill that did not go off is tried once
  more, and after two misfires with one cast mode the next mode is learned for that skill (tap -> aim -> hold). A
  held skill is pressed, held for its charge time while aiming, and released; nothing else touches the screen
  meanwhile. Aimed skills aim where the enemy will be (its speed x travel time x a learned lead); a miss (the target
  lost no HP) nudges the lead, the other way after a second miss.
- Not done: reading enemies' skill cooldowns and baiting skills (enemy skill effects cannot be read yet), mana /
  energy (no bar reading), which enemy hero each on-screen enemy is (roles come from behaviour, not names).

## Joystick, kills, early game (v20)

- Joystick: press the centre, hold, then swipe out to one direction and keep holding past the ring's edge. Turns are
  one straight slide to the new direction (no curving), and only after holding the current one for a while (0.9 s
  when walking, or what Coach learned from you; 0.25 s in fights; at once for a reversal or a wall detour). The thumb
  never lifts while walking, chasing or retreating; ranged heroes side-step around their target in fights instead of
  standing. It lifts only to stand and hit minions, monsters, turtle / lord or a tower, and to recall / regen / hide.
- Enemies under 10% HP are always chased when possible: further out, Flicker / dash to catch them, attack pressed
  while running after them, even under their tower when you have 40%+ HP and it is a quick kill.
- No duels before level 4: alone with an enemy hero close (above 10% HP), it steps back and keeps farming; with an
  ally near it may fight together. Kills under 10% are still taken.

## Walking straight, attacking, learning (v19)

- Joystick: the hero keeps one heading and walks straight. It only turns when the course really changes: more than
  40 degrees off while walking (a new camp or lane, the path bends round a wall or tower, a stuck detour), more than
  25 degrees in a chase or an escape. No more small constant corrections or thumb wobble.
- Attack is pressed while chasing an enemy hero (walking after it), and while standing hidden in a bush.
- Learn from me is ON by default and starts with the Player. It records you whenever you play yourself, the ally
  you watch while you are dead (the camera follows them), and enemy heroes moving on screen while your camera is
  still. Watched heroes count a bit less than you in Play like me.
- Play like me no longer takes over: it used to replace the AI's whole decision, and a remembered "stand still,
  press nothing" moment froze the joystick and skills. Now the AI keeps its goal and skills; your way of moving is
  used while walking / fighting when the moment is clearly like one you played, and your skills are added.
  Also fixed: with Learn from me on, a button that only looked dark (shop, death screen) was taken as a cast and
  the AI then thought the skill was cooling down.

## Jungle camps and the joystick (v18)

- A jungle monster shows no HP bar until it is hit. Standing at a camp the minimap still shows, the AI now presses
  attack anyway (the button locks on to the nearest monster); the skills follow once the bars appear. Before, the
  "nothing to hit" rule cancelled the attack, so the fight never started. An empty camp is left after 6 s.
- Joystick: the thumb stays down, pushed firmly just past the edge of the ring, and turns without lifting while
  walking, chasing an enemy hero or running away. Melee heroes keep walking into their target in a fight. It is
  lifted only to stand and hit minions, monsters, turtle / lord or a tower, and to recall, regen or hide in a bush.
  A brief "stand still" in a chase keeps the last direction for 0.7 s instead of lifting and pressing again.

## HP bar fix (v17)

- Your HP bar's tick marks split it into pieces, so it was often missed or read as 3-40% HP at full health. The AI
  then went back to base (Recall / Regen / retreat) again and again. Pieces are now joined, and the grey empty part
  is never read past the bar's full width (dark bushes right after a full bar no longer count as lost HP).
- Red damage numbers are no longer read as HP bars (a bar has at least one solid row).

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

## Coach: help the AI, it learns from you (v20, replaces Assist / Learn from me / Play like me)

- The AI plays. Take the joystick or press a skill whenever you want: while your finger is on the screen the AI
  touches nothing (its touches would cancel yours) and it takes over 1.2 s after you let go.
- It knows you are playing when the system cancels its own touch because you touched the screen, when the camera moves
  a different way than it steers (3 checks in a row), or when a skill button goes dark that it did not press.
- What it learns and uses:
  - **Combo**: the order you chain skills in a fight (within 2.5 s, an enemy hero near). Seen twice = it becomes the
    hero's combo.
  - **Aim / timing**: how close the enemy was when you cast each skill. After 3 casts the AI waits until a target is
    that close before using it. Skills you use on minions / camps become its wave-clear skill.
  - **Joystick**: how long you hold one direction before turning; the AI holds its heading as long.
  - **Your moves**: every moment you play is saved; your moves are mixed into the AI's when a moment is clearly like
    one you played (it keeps its goal and skills). Also the ally you watch while dead and enemy heroes on screen.
- Player page: "Coach mode" switch (on by default), what was learned, and "Forget what Coach learned for this hero".
- Recording you also works with the Player OFF (v21): it starts by itself as soon as screen capture runs. Then
  everything you do is yours: your moments, combos, cast distances and joystick holding are all learned. The Player
  page shows "Recording you: ON".
- Limits: your finger itself cannot be seen, only its effects. Skill aim direction (where you dragged) cannot be read
  from the screen; the AI keeps aiming at where the target will be.

## Optional: trained vision detector (YOLO on LiteRT)

1. Add `implementation("com.google.ai.edge.litert:litert:1.4.2")` to `dependencies { }` (see build-gradle-additions.txt).
2. Open `notebooks/train_mlbb_detector.ipynb` in Google Colab (on GitHub, tap the file, then "Open in Colab";
   or go to colab.research.google.com > GitHub > paste this repo's URL). Pick a T4 GPU and run every cell.
   You need a free Roboflow account and its API key. Default dataset: "Mobile Legends Minimap Dataset" by gladi
   (Roboflow Universe, CC BY 4.0).
3. It downloads `mlbb_detector.tflite` and `labels.txt`. Put both files in the Sphynx-Auto-Pilot-Storage folder; the app loads them by itself.
4. The Home preview shows detections as cyan boxes, and Gemma's strategy prompt gets the counts
   (for example "1 turtle_icon, 4 hero_icon").

The app works without it; the colour-based detector stays on either way.

## Universal tactical engine: all five roles, any hero (v35)

One engine plays every role. A role is data (`game/RoleProfile.kt`), not separate code, and the hero on
top of it comes from the hero adapter. It is built from five parts:

| Part | Code | What it does |
|---|---|---|
| Perception | vision/* (HP bars, tower circle, level, minimap, YOLO detectors) | what is on the screen and the map |
| Game state | GameStateTracker, MatchSense, MapIntel | phase (early < 8:00, mid < 15:00, late), team advantage (towers, levels, K/D/A), ganks |
| Universal tactical engine | TacticalEngine + RoleProfile + Utility | the same jobs for everyone, tried in the role's order |
| Hero adapter | HeroProfile, SkillBook (seeds), ComboBook | range, mobility, crowd control, combos, cast modes, passive |
| Execution | ActionExecutor, ComboRunner | joystick, casts, aiming, verification |

Role profiles (the order the jobs are tried, plus a few style numbers):
- **EXP**: lane pressure, engage, split push. In mid / late game it pushes a side lane alone, but only when
  2+ enemies are seen elsewhere on the minimap.
- **Jungle**: farm, gank, objectives. From level 4 with the ultimate (or 2 skills) ready and 60%+ HP, it walks
  to an enemy who is alone in a lane on the minimap (0.08-0.35 map units away), preferring one near our allies or towers.
- **Mid**: wave clear, rotate, control. It pushes the wave with skills, then rotates to fights and turtle.
- **Gold**: farm, positioning, survival. It freezes the wave (last hits only) in the early game, retreats a bit
  earlier (+6% HP), and only rotates when an objective is very close before 8:00.
- **Roam**: vision, initiation, protection. A tank starts fights with an ally; a support does not fight alone.

Team advantage makes it braver when ahead and more careful when behind. The status line shows
"Game: early game, ahead (+0.21: towers 9 vs 8, ...) | role: ...".

Heroes seeded first (skills, cast modes, passive, combo): Ruby, Yu Zhong, Chou, Balmond, Fredrinn, Hayabusa, Eudora,
Lylia, Kagura, Miya, Brody, Claude, Tigreal, Minotaur, Mathilda. Other heroes use the Groq lookup (Player page)
or the defaults, and every hero keeps learning cast modes and aiming from play. The seeds are general knowledge:
a patch can change a skill, so a Groq fetch or a learned result replaces them.

Tactical networks (v36.9) - nothing to set in the notebook: upload all your mlbb-my-play and mlbb-live CSV files, run
all cells, download `mlbb_policies.zip`, put it in the Sphynx-Auto-Pilot-Storage folder. Inside: one network for ALL heroes (it also
gets each hero's type - role, melee / ranged, starts fights / protects, early / late game - from the hero_type column of
the export) and one network per hero with 1500+ moments. The app unpacks it and, while you play, loads your hero's own
network, else the all-heroes one. The Model page lists the networks on the phone. Older single policy.tflite files still work.

Evaluation: at the end of each match (2+ minutes) the app saves the hero, role, K/D/A, level, length, and whether
the AI or you played. The Learn page "HOW THE AI DOES PER HERO AND ROLE" shows the averages per role and per hero,
so you can see which heroes and roles it plays well. Wins and losses are not read from the screen yet.

## Screen / game phase detection (v35.2)

The app reads which MLBB screen is up from colours alone (no Gemma needed), on every frame:

| Screen | What it looks for | App phase |
|---|---|---|
| Home | the big gold CLASSIC / RANKED button (bottom right) | home screen |
| Lobby | the gold Start Game button | lobby |
| Match found | the gold Enter button (bottom middle) | match found |
| Hero select, choosing | the Confirm button (grey, or gold with a hero chosen), Hero tab lit | hero select / draft |
| Hero select, locked in | the same, with the Skins tab lit | hero locked in |
| Loading | blue progress bars under your team's cards, red under the enemies' | loading |
| Death | the orange-red V around the respawn countdown | dead |
| Shop / in-game scoreboard / big map | dark shop panel / blue-red VS header / enemy portrait column | shop / scoreboard / big map |
| Victory / Defeat | gold VICTORY letters, or silver DEFEAT letters on a red band, on a dark screen | match end |
| MVP / ratings | the light-blue Confirm button with the VICTORY / DEFEAT title (top left) | match end |
| Match-end scoreboard | the light-blue Data and Quit buttons | match end |

A screen must show on 2 frames in a row before the phase changes. Menus darken the middle of the screen;
the match view does not, so skill effects cannot look like a menu (tested on 1,000+ gameplay frames).

At the match end the app logs the match once, with the result: gold VICTORY letters = win, silver DEFEAT
letters = loss. The Learn page then shows "won X of Y" per role and per hero.

Floating icon: its X hides the icon; the AI, screen capture and recording keep running in the background.
Bring it back with "Show the floating icon" on the Home page. To stop the AI: STOP ALL in the floating status
window, the ⏻ button on the icon, STOP AI in the notification, or EMERGENCY STOP on the Home page.

## Learning from live matches (v36)

Watch another player's live match in MLBB while learning is on (start the AI or Learn). The app sees the
live screen (its info panel along the bottom) and reads it like a match of yours, without ever playing on it:

| What | Where it comes from |
|---|---|
| Their hero | the blue HP bar in the middle (the camera follows them); HP from the green line in their panel |
| Enemies, minions, monsters, bushes, tower circles | their screen, as in your matches |
| Enemies on the map | the minimap (all enemies show in a live match): red portrait rings, not tower icons; a clump of overlapping portraits counts as several |
| Their position on the map | the centre of the cyan camera circles on the minimap, only when the circles are clearly drawn (otherwise unknown) |
| Walking | the camera's motion (no joystick on screen) |
| Skills / battle spell | the panel's skill and spell icons greying out (cooldown started: their colour drops below 88% of normal); ready / cooling from the same icons |
| Attack | standing still next to an enemy, minion or monster (basic attacks cannot be seen) |
| Match time | the timer at the top, read by Gemma about once a minute (-1 without Gemma) |

The live screen is recognised by its panel, or by its own buttons on the right (gift, like, ranking) when the
panel is gone: that happens while the watched hero is dead (the camera then follows another hero, whose bar is red).
Nothing is recorded until they are back.

Masked (never read as units): the right column of buttons, streamer name / likes, chat bar, their panel,
the scoreboard and the "ready in ..." notices.

Learn page, LEARN FROM LIVE MATCHES: type the hero you are watching and their role, see what it reads now,
and export `mlbb-live-*.csv` (same columns as `mlbb-my-play-*.csv` plus source, lane, match_sec). Train with
the tactical notebook: upload it together with your own play - nothing to set.

Training with both: upload your `mlbb-my-play-*.csv` and `mlbb-live-*.csv` files together in the tactical notebook.
Moments of the same hero from both are trained together; `LIVE_WEIGHT` (default 1) sets how much the live ones count.

Deaths: the moments of the 6 s before a death are marked (`died` = 1) - yours when you die in your matches, the
watched hero's when their panel disappears for 2 s. The notebook counts them less (`DIED_WEIGHT`, default 0.3): those
choices led to dying. The icons' "ready" colours are remembered per watched hero, so skills that are already cooling
when you start watching are read right from the first frame.

Not read: their items and gold (shown in the panel, not used by the tactical network), basic attacks directly,
and aiming (no indicators on the live screen).

## Jungle monsters by name (v36.4)

There is no "blue buff" / "red buff": the buffs are the **purple buff (Thunder Fenrir)** and the **orange buff
(Molten Fiend** - the wiki's name; this app first called it Fiery Hound)**. The app's monster list (game/JungleDb.kt):

| Name | Kind | Looks like |
|---|---|---|
| Lord | objective | a human-like giant holding a trident |
| Turtle | objective | a giant turtle |
| Molten Fiend (orange buff) | buff | a fire monster with orange flames |
| Thunder Fenrir (purple buff) | buff | a purple monster with a cub beside it |
| Lava Golem | camp | a human-like rock |
| Horned Lizard | camp | a lizard |
| Fire Beetle (+ its little Crammer), Lithowanderer, Scavenger Crab | camp / river | see Game knowledge below |
| Cyclone Eye | terrain (blink pad), not a monster | blue diamond on the minimap |

HP bars: only the **Lord** shows its HP bar before it is attacked. Every other monster (Turtle, buffs, small camps) shows
its bar only after it takes damage - from you, your teammates or the enemies. So the AI starts camps and the Turtle with a
basic attack (skills once the bar shows); at the Lord pit with no bar on screen it steps in, because no bar there means the
Lord is not in view yet. Monsters nobody has hit are only seen by the trained screen detector.

How the colour vision names them (measured on 20 screenshots): the HP bar's full width inside its orange frame
(Lord / Turtle 0.10+ and 5 rows thick with a purple skull icon; buffs 0.078-0.095; small camps less), then Lord or
Turtle by the pit you stand by, the buffs by body colour (orange vs blue / lavender), the Horned Lizard by colour.
A long red bar without the skull is not a boss (82 false "turtles" on old gameplay frames are gone), and a bar with a
red turret emblem is a turret, not the Lord. The other small camps are `jungle_creep` until the trained screen
detector names them: rename those boxes in Roboflow.

Turrets: outer turrets have a gold energy shield (yellow HP bar) until 5:00 (`tower_enemy_plated` /
`tower_ally_plated`), then `tower_enemy` / `tower_ally`; the base is `base_enemy` / `base_ally`.


## Game knowledge from the wiki (v36.5)

From the wiki pages you saved (Battlefield Guide, Jungle, Battle spells, Equipment, EXP / Gold / Mid Laner, Roamer);
only what is current (removed items, spells and monsters left out). Code: game/JungleDb.kt and game/WikiData.kt.
The Player page shows it under GAME KNOWLEDGE.

- **Jungle**: names, gold (Molten Fiend 120, Thunder Fenrir 100 + cub 22, Lithowanderer 92, Horned Lizard 92, Lava Golem 81,
  Crab 36), what each gives (Orange / Purple / Healing / Gold / EXP buffs, Walkie Grass, Turtle Buff, Lord push), buffs at
  0:30 with 2:00 respawns, what the minimap dots mean.
- **Timeline**: 0:30 buffs; 2:00 Turtle, Crab upgraded; 5:00 outer turret shields drop, jungler's minion penalty ends;
  8:00 Lord, inner turrets lose their damage reduction; 12:00 Lord enhanced; 18:00 Lord evolved. Shown in the status
  ("Timeline: next at ...") and given to Gemma's strategy prompt with a role tip for the moment.
- **Battle spells**: the real cooldown of each spell (Flicker 120 s, Retribution 35 s, Execute 90 s, ...) instead of one
  default; Execute is now used as a finisher (target at 25% HP or less) - it scales with the target's lost HP.
- **Equipment**: the 105 current items. Builds are checked against them: names corrected (Haas' Claws), items no longer
  in the game left out and shown on the Build page. Athena's Shield and Necklace of Durance are not in the list:
  replaced by Guardian Helmet / Radiant Armor; there is no listed magic anti-heal item, so none is suggested for mages.
- **Laning**: the wiki's lists (EXP utility / hybrid / damage, gold late / burst / magic, mid utility / hybrid / damage /
  high dive, roamer setter / initiator / utility / damage) pick the lane automatically when no online meta is known;
  setter / initiator heroes start fights with an ally near, utility roamers do not fight alone, late-game carries are
  more patient before 10:00, early-game heroes press before 8:00.
- **Jungler**: with Retribution it leaves lane minions alone until 5:00 (70% less reward, not shared) unless a tower needs
  defending. Jungler kinds (Jungler page): assassin junglers press early and invade, late-game junglers farm and avoid even
  fights, tank junglers protect and start fights, ranged junglers stay behind the frontline; Fanny and Ling are weak until
  the Purple Buff (take the Thunder Fenrir first, careful before 2:00). Heroes only in the jungler lists go jungle
  automatically.

## Detector training pictures: zipped automatically (v36.10)

No daily limit any more. Each collector (Learn page, two switches) saves pictures until its capacity: **minimap 1500**,
**whole screen 1000** pictures since the last zip. Then the app:
1. zips the batch into Download/MLBB-Research (`mlbb-minimap-dataset-<date>.zip` for train_mlbb_detector.ipynb,
   `mlbb-screen-dataset-<date>.zip` for train_screen_detector.ipynb; inside: images/, labels/, classes.txt),
2. removes the zipped pictures from the dataset folder (the zip holds them; the phone keeps its space),
3. switches that collector OFF. Turn the switch on again to collect the next batch.
"Zip ... now" buttons zip early. Each zip holds only pictures not zipped before, so several zips can be uploaded together.

## Retraining keeps what was learned (v36.12)

- Tactical networks: each notebook run continues from the last networks (trainable copies `policy_models/*.keras`, saved
  in My Drive/MLBB-Research/policy_models and inside mlbb_policies.zip) and trains on ALL your moments (every export holds
  everything recorded since the start), so it refines instead of starting over. A network whose inputs changed starts fresh.
- Detectors: each run continues from `minimap_detector_best.pt` / `screen_detector_best.pt` in My Drive/MLBB-Research
  and uses every dataset zip there. Keep the zips and those files in Drive.

## Faster, and the rules you asked for (v36.13)

Speed (think -> touch):
- the AI decides again the moment a new frame is seen (no fixed 40 ms pause); the minimap is fully analysed every 3rd
  frame and the K/D/A every 2nd (the minimap changes slowly), so frames come faster
- aimed skills are a quick 90 ms flick (the old 220-300 ms drag was often cut off by the next touch and had to be
  cast again - the "decision right, skill late" lag); combo steps follow 80-350 ms apart; a skill that did not go off is
  re-cast after 0.3 s
- basic attack every 150 ms, also between and alongside skills and while they cool down
- no random "human" variation in touches; joystick turns go straight to the new heading (no slide through the ring)
- being hit (6%+ HP in half a second): the fight state switches on that frame (no 0.6 s wait)

Rules:
- retreating goes to the nearest allied tower (around walls), unless that way runs into the enemies
- turrets: basic attacks only (skills do not damage them)
- Retribution only on the Purple / Orange Buff, the Turtle, the Lord, and in a close fight with a hero (when no buff /
  boss is on screen to save it for); never on minions or common creeps
- the battle spell is used by what it does: Execute to finish, Petrify when enemies are on you, Inspire in a melee duel,
  Sprint to escape / catch, Aegis / Revitalize when low in a fight, Purify when hit hard up close, Vengeance in a brawl,
  Flameshot to finish or knock back, Retribution as above, Flicker to chase / escape / pass walls
- the dash skill (not the ultimate) speeds up long walks: to camps, lanes, objectives, roaming (nobody near, 6 s apart)
- passing walls with a skill (dash / Flicker) when stuck against one
- recall from inside a nearby bush; hide in bushes when hurt
- finish the current walk (to a camp, an objective, a lane, a fight) before switching to another - up to 2.5 s; anything
  urgent takes over at once
- skill upgrades automatic: the online meta's skill order (guides), else from the hero's skill data (ult first, then the
  main damage skill)
- Player page: the switches for behaviour that should always be on (joystick, combos, aiming, walls, dash, bushes,
  recall, Regen, objectives, monsters, upgrades, fastest mode...) are gone; natural thumb variation removed

## Walls and bushes from the minimap (v36.14)

Alongside the screen detection (bushes / walls seen on screen and the trained screen detector), the minimap ground is
now read by its shade, 40 x 40 cells, each cell keeping the class it shows most often (icons passing over are ignored):

| Minimap shade | Terrain | Used for |
|---|---|---|
| lightest grey (a little green) | bush | hiding when hurt, recalling hidden, "in a bush" even when the screen misses it |
| light grey | lane / tower paths | walking routes prefer them a little (open ground) |
| grey | jungle | normal ground |
| dark grey | wall | paths go around them (as before); a wall the hero gets stuck on is added |
| aquamarine blue | river | normal ground (no longer mistaken for an icon) |

- retreating hurt: no bush on screen -> a minimap bush close by and on the way out
- recall: no bush on screen -> walks into a minimap bush a few steps away first
- Player page: the terrain counts (walls, bushes, lane paths, jungle, river) show once the map is ready

## Smooth touches, no idle (v36.15)

- touches never cut each other off: Android cancels a gesture still on the screen when a new one is sent, so a skill
  tap could be erased by the next joystick turn (skill never cast) and a cancelled joystick stroke lifted the thumb
  (hero stops). Now one gesture is on the screen at a time (30-90 ms); new touches wait, are merged (newest joystick
  direction + every tap / skill) and go out the moment it ends. Touch callbacks run on their own thread, not the UI one
- the joystick press is one quick stroke from the centre to the edge (no second delayed swipe)
- a cancelled gesture now really means your finger (Coach mode no longer pauses the AI by mistake)
- skill cast-mode learning only switches tap <-> aim; "hold" (which blocked every touch while charging) comes only from
  the skill's description, and old wrong "hold" guesses are ignored
- no idle in a match: when nothing is decided (your HP bar hidden a moment) the hero keeps walking the last way for up
  to 3 s, then heads for the nearest enemy tower / base (around walls); a late frame keeps the thumb down instead of
  lifting it. The thumb is lifted only to attack, recall, Regen or hide
- a walk is finished for up to 1.2 s (was 2.5 s) before switching, so the direction stays fresh
- wall paths are cached for 0.4 s (decisions ask for the same path many times a second)
- Player page "Speed" line (and the Logs every 15 s): frame to touch in ms, split into vision / waiting / deciding /
  touch queue - send it if it still feels slow, it shows which part is

## Human-style game sense: memory and the "why" (v36.16)

How the human loop (observe, understand, evaluate, decide, execute, learn) maps onto the app:

| Human ability | In the app |
|---|---|
| Seeing the battlefield | vision/ (HP bars, minimap, HUD, bushes, walls, tower ring) + the trained detectors |
| Understanding the situation | game/GameStateTracker, MatchSense (phase, team advantage) |
| Remembering enemy positions | **game/GameMemory (new)**: last known spot of every enemy on the map; missing enemies |
| Predicting enemy actions | enemy tracks with speed (aim lead), MapIntel gank detection, GameMemory fog danger |
| Understanding strategy | StrategicAdvisor (Gemma / Groq, slow, optional), RoleProfile, MetaAdvisor |
| Choosing targets | ai/Utility.bestTarget |
| Knowing when to fight | ai/Utility engagement + survival + **ai/Deliberation scores (new)** |
| Executing combos | ai/ComboRunner (conditional steps, retries), SkillBook (what each skill does) |
| Dodging / retreating | retreat to nearest ally tower, bushes, escape skills, battle spell by its effect |
| Learning from mistakes | Brain, PolicyNet (trained on your play), Coach, **Deliberation weights (new)** |

New:
- GameMemory: an enemy that vanishes from the minimap is remembered; after t seconds it could be anywhere within about
  0.025 x t of where it was last seen (forgotten after 45 s). Spots in reach of missing enemies count as dangerous
  ("fog danger"): lower survival score, more careful farming, fewer risky fights
- Deliberation: each decision scores Attack / Farm / Retreat / Objective / Push / Recall (0-1).
  Attack = wK*kill chance + wH*HP edge + wS*skills ready + wT*teammates - wR*risk (start 0.30/0.20/0.20/0.20/0.10;
  each role starts differently: jungler kill-minded, roamer team-minded, gold lane risk-averse)
  - a fight the scores call clearly worse than leaving (by 0.15+, not a finishing blow) becomes a retreat
  - a lone enemy at 25% or less in reach, scores clearly for the kill, you at 30%+ and level 4+: the kill is taken
    even if the rules wanted to leave
  - the weights learn per role: a fight ending in your kill / assist makes the high factors count more; one ending in
    your death makes risk count more (shown in the Logs as "Learned: fight won / lost ...")
- Player page: the score table, the factors, the role weights, the last lesson, and the enemies remembered / missing
- the fast rules still decide every frame; the LLM is never in the frame-by-frame loop

## Two Gemma models: Gemma 4 E2B strategic + small tactical model (v36.17)

Files (put them in the Sphynx-Auto-Pilot-Storage folder; the app sorts them by itself):
- strategic: `gemma-4-E2B-it.litertlm` (Hugging Face: litert-community/gemma-4-E2B-it-litert-lm, about 2.6 GB).
  Gemma 3n E2B still works; when both are in the folder, Gemma 4 is used. GPU first, falls back to the CPU.
- small tactical (optional): a Gemma 3 270M `.litertlm` (or Gemma 3 1B). A file with 270m / 1b / tactic in its name, or
  under 1 GB, goes to this slot. It runs on the CPU, text only (the GPU stays with the game and the big model).
- Gemma 4 needs a recent LiteRT-LM: keep `litertlm-android:latest.release` in build.gradle (Kotlin 2.2 or newer). If the
  log says the model cannot be loaded, update that dependency and Kotlin.

How the small model plays (Model page switch "Small tactical model in fights", off by default):
- only in fights (an enemy hero close), about 3 times a second, in the background: the frame-by-frame play never waits
- it gets a short summary (hero, HP, level, skills ready, enemies and their HP, allies, minions, tower, missing enemies,
  the attack / retreat / risk scores) and answers with ONE of: ALL_IN, POKE, FARM, RETREAT, GROUP, RULES
- Kotlin checks the answer first: no all-in at 20% HP or when the scores favour leaving, no poke with a melee hero, no
  farming at high risk or with nothing to farm, no retreating at full HP with nothing to fear, no grouping with no ally.
  A rejected answer is ignored; an answer older than 1 s is dropped
- an allowed answer is played through the Brain, which learns from how it went like any of its own choices; the safety
  rules (critical HP, enemy tower, bad-fight veto) still come after. The model never sends a touch
- Model page: both models' status and answer times, how often the small model was asked / used / rejected, its last
  answer; Home page: both models' answer times
- untrained, a 270M model's advice is rough (the checks catch the worst); the next step is a notebook that fine-tunes it
  on your recorded fights, using the same summary format

## Training the small tactical model on your fights (v36.18)

- the app now saves every fight choice (the Brain's, the small model's, or yours) with the exact summary the small model
  is shown and how it went 4 s later (the Brain's score: damage dealt, kills, HP kept, death). Kept: the newest 30 000
- Learn page: "Export fight moments (CSV)" -> Downloads/MLBB-Research/mlbb-fights-*.csv (and the count so far)
- notebooks/train_tactical_llm.ipynb (Colab, T4 GPU): put the CSVs in My Drive/MLBB-Research (or upload them), add a
  Hugging Face token as the HF_TOKEN secret (accept the Gemma licence on google/gemma-3-270m-it first), Run all
  - answers it learns: choices that went well (yours count twice); your choices that were not a disaster; pushing on
    that went badly -> GROUP (allies close) or RETREAT. No answer above 35% of the examples. Newest 15% kept for a test
  - prints how often it is right on your newest fights vs always giving the most common answer
  - continues from your last trained model (My Drive/MLBB-Research/tactical_llm) on every re-run
  - makes gemma3-270m-mlbb-tactical.litertlm (int8, 256-token prompts, 512 tokens of memory), copied to Drive and
    downloaded: put it in the Sphynx-Auto-Pilot-Storage folder; the app puts it in the small-model slot by itself
- tested here on a tiny stand-in model: loading, labelling, training, testing, saving, continuing, converting to
  .litertlm, and running that file with the LiteRT-LM runtime. Not tested: the real Gemma 3 270M download and how well
  it learns (that needs your fights). Versions that work together: litert-torch 0.10.0 + litert-lm-builder 0.18.1

## Cleaner app, icons, Speed page (v36.19)

- one dark theme: rounded cards, accent section headers, pill page tabs, styled buttons / switches / text boxes; the
  header shows a live status line and the only STOP button (the second emergency stop at the bottom of Home is gone;
  the floating icon and the notification still have theirs)
- long explanations replaced by one-line summaries; long labels shortened
- icons from the Mobile Legends wiki, downloaded once on the phone and kept (files/icons); until then (or offline) a
  coloured circle with initials shows: 132 heroes (from the wiki's role pages) and all 105 current items
  - Build: your build (online meta build when there is one, else your role's build) as item icons with emblem / spell,
    counter items as icons with the reason, recommended picks and bans as hero icons, an icon preview under every role
    build you type
  - Draft: picks and bans as hero icons (from the draft screen, else the patch meta for your role)
  - Meta: patch card (top bans and best picks per role as hero icons, how old it is) and hero card (portrait, build as
    item icons, emblem / spell / lane / combo / dash / skill order, laning / jungle / rotation tips, counters as icons)
  - Home: your hero's portrait
- new Speed page: frame to touch (big number, what it is made of as a stacked bar, the last 2 minutes as a chart) and a
  card per engine and model with its current value, a bar against its target and a 2-minute chart: screen reading,
  waiting, deciding, touch queue, capture and read frame rates, tactical network, minimap / screen detectors, small
  tactical model, strategic model (Gemma), online meta (Groq). Green = fast, amber = ok, red = slow

## How to play the hero you are on (v36.20)

- the AI now works out a game plan for the hero you play from what its skills really do (learned online / pasted /
  built-in) plus the online guide: its style (e.g. burst assassin, tank initiator, poke mage), the skill that opens a
  fight (reach + crowd control first), the crowd control, the finisher, the escape, the wave-clear skill, the heal /
  shield, the self-buff, when the ultimate is used, and plain-language tips
- it is APPLIED in play, instead of the role's defaults:
  - the hero's profile: combo order, escape skill, wave-clear skill, ultimate timing (engage / execute / teamfight /
    survive / buff, from what the ultimate does), how far it starts fights
  - the combo when you have not set one (and there is no built-in or online combo): buff > opener > crowd control >
    damage > ultimate > basic attack
  - in fights: a fragile hero (assassin, marksman, mage, support) keeps its dash for leaving while the enemy is still
    healthy and no ally is near; the heal / shield goes out when hurt; the self-buff is cast first
- the online hero update now also asks for how to play it (playstyle), when and how to use each skill, and when it is
  strongest; these are added to the tips
- the hero you play is learned by itself (Groq key); Player page: "How to play your hero" card (style, combo, roles of
  each skill, tips) and "Learn this hero (skills + online guide)"
- crowd control is now also recognised from "kick", "launch", "grab", "charm", "fear", "trap", "imprison"

## One-tap training in the cloud (v36.21)

Google Colab cannot be started by another app (it always waits for someone to press Run all). Kaggle Notebooks can:
it has an API, free GPUs (about 30 h a week) and runs notebooks unattended. So the four Train buttons use Kaggle.

Once:
1. make a free account on kaggle.com and verify your phone number there (needed for GPU and internet in notebooks)
2. kaggle.com > Settings > API > Create New Token: it downloads kaggle.json - put it in the Sphynx-Auto-Pilot-Storage folder and tap
   Scan the folder now (Model page)
3. for the small tactical model: open the Gemma model page on kaggle.com and accept the licence
4. optional, for the minimap detector: roboflow_key.txt (your Roboflow API key) in the folder adds the public minimap
   set; without it, only your own pictures are used

Then Learn page > Train in the cloud > Train (minimap detector, screen detector, tactical networks, small tactical model):
- inputs come from Download/MLBB-Research: picture zips (uploaded once each), fresh exports of your play / live matches /
  fight moments (made automatically when you tap), and the last results so training continues (minimap_detector_best.pt,
  screen_detector_best.pt, mlbb_policies.zip)
- they go to private Kaggle datasets; the notebook is read from this GitHub repository and started on Kaggle (T4 GPU for
  the detectors and the small model, CPU for the tactical networks)
- the app checks it every minute (also after a restart) and shows queued / training N min / done / failed
- results download straight into Download/MLBB-Research and are loaded by the folder scan: mlbb_detector.tflite +
  labels.txt, mlbb_screen_detector.tflite + screen_labels.txt, mlbb_policies.zip, gemma3-270m-mlbb-tactical.litertlm
  (plus the detectors' *_best.pt, so the next run continues from them)
- "Open on Kaggle" shows the run and its log
- Google Drive is not used by these runs (Kaggle cannot read your Drive); the phone folder is the source. The notebooks
  still work in Colab by hand as before (their Kaggle mode switches itself off there)
- notebooks: a first cell detects Kaggle and makes everything unattended (the uploaded files appear as "My Drive /
  MLBB-Research", downloads go to Kaggle's output, the Roboflow question is answered from roboflow_key.txt, Gemma comes
  from Kaggle Models); a last cell gathers the results. Tested here: the tactical networks notebook end to end, and the
  minimap notebook's data preparation without a Roboflow key; the app's Kaggle calls against a stand-in server (request
  formats taken from Kaggle's official client). Not yet run on Kaggle itself
- v36.22: the four notebooks are bundled inside the app (app/src/main/assets/notebooks, copies of notebooks/), so cloud
  training works with a private GitHub repository; after changing a notebook, copy it there too. The app checks there is
  enough to train on BEFORE uploading: 300+ recorded moments (tactical networks), 150+ fight moments (small model), at
  least one picture zip (detectors), with a message saying how to get them
- v36.23: the Train buttons do the preparation themselves: new detector pictures are zipped and your play / live matches /
  fight moments exported when tapped (the manual Zip now and Export buttons are gone). A Train button is green and
  tappable only when there is enough: 100+ new pictures or an earlier zip (detectors), 300+ recorded moments (tactical
  networks), 150+ fight moments (small model); red with the count otherwise. Model page: a coloured dot per part -
  green loaded / running, yellow loading or loaded but switched off, red missing; plus a Kaggle key line
- v36.24 (fixes from the first real Kaggle runs): Kaggle unzips uploaded picture zips, so the notebooks' Kaggle cell
  zips those folders again under their own name (and no longer copies every picture); the Roboflow key is read from
  roboflow_key.txt without a prompt (Jupyter replaces the password prompt before each cell); the stand-in for Colab's
  module now has the attribute libraries look up (the small-model run stopped at "google.colab.__spec__ is None")
- v36.25 (second round of Kaggle runs: the minimap detector trained, the tactical networks are done): the app waits for
  Kaggle's READY status (5) before starting a notebook - it took status 1 (only received) as ready, so a fresh screen
  picture upload was not attached yet and that run had no files; it also refuses to start when Kaggle reports a dataset
  it could not attach. The notebooks stop at once when no files arrived; the Colab stand-in accepts any part a library
  asks for (the converter imports google.colab.output, which stopped the minimap detector's last step)
- v36.26: the CSV exports in Download/MLBB-Research count and train even after a reinstall (which empties the app's
  database): the Train cards count the rows in them (newest play + live export, newest fights export), every play / fight
  CSV in the folder is uploaded, a new export is made only when the database has new moments, and the notebooks count
  the same moment once (the small-model notebook already did). Collector batches: minimap 300, whole screen 300 pictures
- v36.27: what Groq finds online is also saved in Download/MLBB-Research and read back by the folder scan (so a
  reinstall keeps it): mlbb-online-meta.json (patch meta + every hero's meta) and mlbb-online-skills.json (every hero's
  learned skills), rewritten a few seconds after new data arrives; on reading back an entry replaces the app's copy only
  when it is newer, and any copy Android renamed after a reinstall ("... (1).json") is read too
- v36.28: cloud training fixes. Each training's files now go up as a fresh, uniquely named Kaggle dataset (a new
  version of the same dataset was reported "ready" before the new files existed, so runs started empty). The app
  writes the run's uploads into the notebook; the notebook waits up to 3 min for any that are not attached and then
  fetches them itself. A status check right after starting no longer picks up the previous run's result. After a
  failed run the picture zips are uploaded again under new names. The minimap notebook trains without a Roboflow key.
- v36.29: Groq rate-limit indicator. When Groq answers 429 the app reads how long to wait (its retry-after header or
  "try again in 7m12.5s"), and shows it: a red countdown in the header on every page, a red/green line on the Meta page
  (with the clock time it is available again) and a yellow/green dot on the Model page. When the wait is over a
  notification "Groq is available again" appears (and a toast if the app is open). Asks during the wait are not sent
  (they would only get another 429); automatic updates pause and "learn all heroes" waits instead of skipping.
- v36.30: Groq uses far fewer tokens. Only "Update the patch meta" searches the web (at most two short searches);
  hero meta, hero skills and the draft answer from the model's own knowledge plus the saved patch summary (switch
  "Web search for each hero" on the Meta page turns hero searches back on, one short search each). Every request uses
  low reasoning effort and a 4096-token answer cap, and a failed search is retried once without search instead of
  searching twice more.
- v36.31: all four cloud trainings run at the same time. Preparing / uploading / downloading has one worker per
  training, and following the runs on Kaggle is one shared timer (a waiting run holds no worker). If Kaggle says all
  its GPU sessions are in use, the card shows "waiting for a free Kaggle GPU" and the app tries again every 2 min
  (up to 2 h; the uploads are not repeated). A used-up weekly GPU quota still fails with Kaggle's message.
- v36.32: the Meta page uses only the ORIGINAL server's latest patch. Every Groq question (patch meta, hero meta,
  skills, draft) says to ignore the Advanced Server. An answer whose patch is an Advanced Server patch is asked again
  once, more firmly, and not saved if it still is; sentences, list items and sources about the Advanced Server are
  removed from answers; an Advanced Server patch saved by an older version is ignored.
- v36.33: a failed cloud training shows WHY on its card (read from the Kaggle run log: the last error, or a plain
  instruction for known ones). The small-model notebook stops with a clear message when the Kaggle account has not
  accepted the Gemma licence yet (kaggle.com/models/google/gemma-3, same account as kaggle.json).
- v36.34: retraining the tactical networks on Kaggle CONTINUES from your last networks (Kaggle unzipped
  mlbb_policies.zip and the notebook did not find the trainable copies, so each run started over).
- v36.35: the small-model training attaches Gemma 3 270M (Kaggle Models, latest version) to the run itself; an
  unattended Kaggle run may not fetch a model on its own ("New Models cannot be attached in non-interactive
  sessions"). The notebook uses the attached copy; the model's files are not listed as files from the app.
- v36.36: your rulebook changes. Last stand (critical HP but a 1v1 you can win: fight). Escape skill / dash /
  defensive ultimate on the way out only under 20% HP. Ranged heroes step back only inside 30% of their range. Melee
  heroes may poke with a ranged skill. Supports stand beside their ally. The ultimate's cooldown is MEASURED on screen
  (and read from skill descriptions): a low-cooldown ultimate (30 s or less) is used on monsters / camps and freely in
  combos. The combo order comes first (no extra skills thrown in). No attacking from a bush until the fight started.
  An execute ultimate finishes the kill. Retribution on buffs at 20%. Camp kill confirmed after 1 s; a vanished
  monster is hit 1 s more. Joystick turns every 0.5 s; fight state switches after 0.5 s. The exposed enemy base is
  attacked. Emergency swap ends with Guardian Helmet. Learning from heroes you watch while dead is removed.
  New: junglers invade enemy buffs / camps when the risk is low (healthy, level 4+, survival 60+, 2+ skills ready,
  no enemy near the camp, at most 2 enemies unaccounted for).
- v36.37: nothing to type. The app version shows on the Home page. Worked out by itself: your hero and spell
  (pick screen, then portrait / icon; the last hero you played is remembered), standard or ranked (read on the
  hero-select screen), the hero you watch in a live match and its role, the enemy tower range (from stepping into
  a tower's circle), the seconds from base to your lane, the button positions on the first match and hero-select
  screen (only buttons you never placed yourself), and the patch meta (refreshed when a week old). The rule numbers
  (Retribution, chase, Regen / Recall, item swap...) come from the rulebook defaults; their fields, the push
  direction, range / dash buttons, the combo editor, the skill paste box and the tuning fields are gone.
  Still yours to do (Android or your accounts require it): the permissions, choosing the folder once, the model
  downloads, groq_key.txt and kaggle.json, and the shop / hero-select tap positions that auto-detect cannot find.
- v36.38: small-model training. The conversion to the phone's format installs protobuf 6 (litert-lm-builder
  needs it; Kaggle ships 5) and runs in a fresh Python process. Training balances the answers (the biggest is cut to
  twice the next, small ones repeated to half of the biggest) and trains longer on small data (8 passes under 600
  examples), so the model no longer learns to answer FARM to everything.
- v36.39: your hero and battle spell can be chosen by hand again (Player page) for when detection does not work.
  Your choice beats detection, and the app learns the hero's look and the spell's icon from it in your matches,
  so detection can take over later. Leave the hero empty / the spell on "detect it" for automatic.
- v36.40: Model page "Small model: on vs off". Every match the AI plays is saved with whether the small model was
  on (and loaded) the whole match: the learning brain's fight scores, deaths, KDA, win / loss and how much of the
  model's advice was used. It shows the difference in percent and says whether it is real (5+ matches each way,
  bigger than the normal spread of fight results) or too early to tell.
- v36.41: better data for the small model. Your fight choices are labelled only when they are clear (walking at /
  away from the enemy, skills from range, trading blows, hitting minions with the enemy out of reach) - unclear
  moments are no longer called FARM. The fight scores (attack / retreat / risk) are always in the model's input.
  Live matches you watch add fight moments too (that player's choices, scored by how they went). The notebook leaves
  out the old guessed FARM labels.
- v36.42: 15 heroes were missing from the hero list (so they could not be typed or recognised): Alpha, Bane,
  Harith, Julian, Kalea, Kimmy, Lukas, Marcel, Minsitthar, Obsidia, Phoveus, Roger, Sora, Valentina, Zetian - all
  132 heroes are known now. A hero name the app does not know (a brand-new hero) is used as typed, with role
  defaults until its skills are learned online.
- v36.43: the screen picture collector also shoots WHILE ATTACKING a monster (every 2.5 s instead of 10 s): when
  the AI presses attack next to a monster, a monster's HP bar is dropping (you, an ally or anyone hitting it), or you
  stand still next to one. A monster shows its bar only once it is hit - exactly when its box can be drawn. A new kind
  of monster is pictured at once, the same kind again after 8 s, so the pictures cover every monster.
- v36.44: only ONE mlbb-online-meta.json (and mlbb-online-skills.json) in the folder. Every meta / hero update
  overwrites it through the folder chosen on the Model page; copies Android made after a reinstall
  ("mlbb-online-meta (1).json" ...) are read in first (nothing is lost) and then deleted.
- v36.45: faster skills, a fight-choice network, enemy knowledge.
  COMBO BURSTS: up to 3 combo skills go out in ONE gesture, each when the one before it has been taken (140 ms after
  an aimed skill, 90 ms after a tapped one, then tuned per hero and skill from which casts really go off), with basic
  attacks woven between them and the joystick still held; hold-to-charge skills still go alone.
  FIGHT-CHOICE NETWORK: a small numeric network trained in the tactical-network notebook (same Train button, inside
  mlbb_policies.zip) from every fight moment, picking all in / poke / farm / retreat / group in well under 1 ms. Model
  page switch "Fight-choice network in fights"; it advises first, through the same checks as the small model; the
  on/off panel now compares each adviser with none.
  ENEMY KNOWLEDGE: the enemy heroes' skills are learned in the background (one a minute); the Player page shows each
  enemy's likely opener, crowd control, dash and ultimate; ranged heroes keep outside the enemy team's crowd-control
  reach; an enemy that just dashed is a better target (+12: it cannot escape); a dash AT you makes the AI step aside.
- v36.46: "⚔ COMBO" on the floating window plays your hero's combo once, in bursts, at the nearest enemy (or straight
  ahead) - to check the order, aim and speed. The LINE-UP is read from the loading screen (top row = your team, you
  first on the left; bottom row = the enemies) and checked on the scoreboard (your team left, you on top; enemies
  right): all 10 heroes and their battle spells (needs the Gemma model). The enemy panel shows each enemy's spell and
  what it means (Flicker, Execute, Retribution = their jungler, Petrify, Vengeance...); enemy Flicker widens the
  crowd-control reach the AI keeps away from.
- v36.47: LIBRARY page - everything the app knows, with icons: Heroes (role, skills: learned / built in / role
  defaults, online guide, each skill's type / range / crowd control / cast / cooldown, passive, how it plays, build,
  emblem, counters, measured ultimate cooldown), Spells (cooldown, effect, how the AI uses it, what it means on an
  enemy), Equipment (type, price, stats, effect). "Learn the whole database online" learns every hero's skills and
  online guide, then all equipment (12 items per question), skipping what is known and waiting out Groq's limit by
  itself. Equipment knowledge is kept in mlbb-online-meta.json too.
- v36.48: MORE ONLINE MODELS share Groq's work: Gemini 3.8 Flash and Gemini 3.5 Flash-Lite (gemini_key.txt, from
  aistudio.google.com; they search the web with Google Search) and Cerebras gpt-oss-120b (cerebras_key.txt, from
  cloud.cerebras.ai; fast, no web search). Questions with web search go to Gemini 3.8 Flash -> Groq -> Gemini Flash-Lite
  -> Cerebras; without search to Cerebras -> Gemini Flash-Lite -> Groq -> Gemini 3.8 Flash. A model without a key or
  resting after its rate limit is skipped; one that fails hands over. Model names can be changed with
  gemini_flash_model.txt / gemini_lite_model.txt / cerebras_model.txt. The indicators count only when ALL are resting.
  AUTOMATIC online requests are now OFF by default (Meta page switch "Automatic online updates"): online questions
  are asked only when you press a button (Update the patch meta / this hero, Learn skills, Learn the whole database).
- v36.49: Groq uses TWO models with the same groq_key.txt (Groq's free limits are per model): openai/gpt-oss-120b
  and openai/gpt-oss-20b (also with Groq's web search; change it with groq_model2.txt). Each rests separately after its
  own limit. Order with search: Gemini 3.8 Flash -> Groq 120b -> Groq 20b -> Gemini Flash-Lite -> Cerebras; without:
  Cerebras -> Gemini Flash-Lite -> Groq 20b -> Groq 120b -> Gemini 3.8 Flash.
- v36.50: a third Gemini model on the same gemini_key.txt: Gemini 3.6 Flash (Google counts free limits per model;
  change it with gemini_mid_model.txt). Order with search: Gemini 3.8 Flash -> Groq 120b -> Groq 20b -> Gemini 3.6 Flash
  -> Gemini 3.5 Flash-Lite -> Cerebras; without: Cerebras -> Gemini 3.5 Flash-Lite -> Groq 20b -> Gemini 3.6 Flash ->
  Groq 120b -> Gemini 3.8 Flash.
- **v36.51**: play-learned data is backed up too. `mlbb-learned.json` in the MLBB-Research folder holds the learning brain, Coach, measured ultimate cooldowns, combo burst gaps, aim leads, cast modes, learned hero/spell icons, level badges, minimap colours, tower range, lane travel, layout presets, the comparison numbers, the match log and your settings. API keys are not included; they stay in their own .txt files. The file is rewritten at most once a minute while playing, and again when you leave the app or stop the AI. After a reinstall, choose the folder again: only values the new install doesn't have are filled in, so nothing it already learned is overwritten.
- **v36.52**: the screen picture collector now zips its batch and switches off at 300 pictures (was 150).
- **v36.53**:
  - **Real skills only.** A hero's passive and skills come from the hero's page on the Mobile Legends wiki, read by the phone. The online model only copies them from that text, and every skill and passive name must appear on the page. A made-up one is thrown away, and a hero with a dropped skill is asked again next time. If the page can't be read, nothing is saved. Skills an AI wrote from memory before this version are no longer used and are re-learned. The Library shows each hero's own passive (name and text) first.
  - **Retribution kinds.** Ice, Flame and Bloody Retribution are now listed under Spells in the Library, not Equipment. They're still learned online and still accepted in builds, and the AI treats them as Retribution.
  - **Weak fight models are ignored.** A fight-choice network or small tactical model whose report is no better than "always the most common answer" (or whose balanced accuracy is under 30%) gives no advice, even with its switch on. The Model page shows "TOO WEAK, NOT USED". The small-model notebook now writes `tactical_llm_info.json` with its scores.
  - **Clean screen pictures.** The collector takes a picture only about 1 s after a skill or a basic attack: 1.0 s after a skill or the spell, 1.5 s after the ultimate, and a newer skill restarts the wait. The app's boxes, touch dots and floating window are hidden for that moment. No picture during a banner or while the camera pans fast.
  - **⚔ COMBO works with the AI and Player off.** It needs only the Accessibility service. It uses your chosen, detected or last-played hero, and aims at the nearest enemy or to the right of your hero.
- **v36.54**: hero kits that aren't "passive + 2 skills + ultimate" (new `game/HeroKit.kt`):
  - **2, 3 or 4 active skills.** Yu Zhong and Pharsa have four (s1, s2, s3, ult). The 4th button "Skill 3" is a new calibration point, and auto-detect snaps it to a skill icon when one shows. Hirara has two (s1, s2, no ultimate button). The wiki reader now records how many active skills a hero has. The AI also learns from the screen: a button it pressed that never went off, 4 times in tap and aim, is marked as absent for that hero, and one seen going off is confirmed. Combos, the playbook and ⚔ COMBO use only the hero's real buttons. A 4th skill a combo doesn't name is cast before the ultimate.
  - **Skill types.** Skillshot (a projectile that stops on the first unit hit): the AI holds it back while an enemy minion stands in its line. Transform: Roger and Selena's ultimate switches form (melee form for a close fight, back to the ranged form when nothing is in reach), and Yu Zhong and Sora get a timed form. Cast modes and aim are learned separately per form.
  - **Special casts.** Hirara drags Skill 1 onto Skill 2 in one gesture, her combo. Suyou holds a skill for Immortal form (trading, team fights, hurt) and taps it for Mortal form (chasing, escaping). Sora swipes the ultimate right for Torrent (2+ enemies or low HP) and left for Thunder (one target).
  - **HP bars.** A light segment right after the HP colour, filling the bar's height inside a standard-width hero bar, is read as shield, armour or stored energy instead of stopping the HP reading. Decisions use HP plus shield or armour, plus half of stored energy. Esmeralda (shield) and Masha (segmented HP) stay a little longer; X.Borg with his armour broken leaves earlier. The HUD shows "HP x% + shield/armour y%". The 160 saved screenshots read exactly as before.
  - **Hirara** is added to the hero list (jungle assassin).
- **v36.55**: tuned on your in-match screenshots, plus HP back from hitting and the game's terms:
  - **Button layouts per kit.** 4-skill heroes (Fredrinn, Yu Zhong, Pharsa) have their own Skill 2, Skill 3 and Ultimate places, measured on your screenshots. 2-skill Hirara uses "Mobility" (between the usual Skill 1 and 2) and "AOE" (near the ultimate), so her kit is s1 + ult. The AI drags Mobility onto AOE to engage and AOE onto Mobility when leaving. Each has its own calibration point, and auto-detect estimates all of them. Fredrinn is now a 4-skill hero.
  - **HP bars, read from your screenshots.** Esmeralda's light-grey shield, e.g. HP 66% + shield 33%. X.Borg's salmon armour (HP 42% + armour 57%), counted only on your own bar, since enemy bars flash light red for just-lost HP. Masha's dark-green bar shown as one of three ("×3"): her total HP = (bars left − 1 + this bar) / 3. Your own bar's thicker segment dividers (~5 px) no longer split it; that fixes a full Sora bar and a 100% Hirara bar that weren't found. The 160 saved screenshots read the same, apart from that Hirara bar now being found.
  - **HP back from hitting (lifesteal, spell vamp, hybrid lifesteal, healing passives).** The app measures how much HP you regain per second while you or the AI hit something, learned per hero and shown in the Library. A hero that heals by hitting, from its measured rate or its own passive or skills, stays to farm the camp or wave in front of it and recalls only below 15% HP. Lifesteal and spell-vamp items are listed too.
  - **Library > Terms:** the game's Tutorial terms (Attribute Guide, State Guide and Common Terms), with crowd control grouped by level. Each skill in a hero's details shows the crowd control its description names, with that level.
- **v36.56**:
  - **Masha's HP, read from your 5 screenshots.** Her bar is three HP layers in three greens: dark (×3), medium (×2) and bright (the last layer, no label). The top layer drains and shows the one under it. Her whole HP = (layers under the top one + the top layer's visible part) ÷ 3. On your screenshots: 0.75, 0.56, 0.32, 0.15, 0.99 and 0.99, expected 0.76, 0.58, 0.33, 0.15, 1.00 and 1.00.
  - **Hirara:** two skills, no ultimate. Skill 1 is "Mobility" and Skill 2 is "AOE", whose button sits where an ultimate would. Drag combos go both ways. Target out of reach: Mobility onto AOE (dash in, then the area). Target close: AOE onto Mobility. One skill ready: it is cast on its own. A drag that doesn't fire (no stack) doesn't mark her buttons as missing. Calibration points: "2-skill hero: Skill 1 / Skill 2".
  - **Library > Equipment** has its own "Active skills" part (Winter Crown, Wind of Nature, Conceal), with what each active does, above the rest of the equipment.
- **v36.57**:
  - **More 4-skill heroes:** Popol and Kupa, Chip, Sun, Lunox and Zhask, alongside Fredrinn, Yu Zhong and Pharsa. The game's button hints (e.g. Zhask: Summon, CC, Slow, Morph; Chip: CC, Speed Up, Teleport, Teleport) show in the Library.
  - **Button positions re-measured** on your 2712 x 1220 screenshots for the 3-, 4- and 2-skill layouts. Auto-detect lands within ~25 px on all of them; a button is ~110 px wide. An attack-button candidate far from its usual place is ignored: on two Pharsa screenshots something lower down was taken for it, which made her layout look shifted.
  - **Active item button fixed:** it sits at 82.4%, 38.3% (where "Frozen", "Wind Chant" or "Conceal" shows); before it was 93%, 31%. The low-HP Winter Crown swap presses the right place now.
  - **The AI presses actives by itself.** The icon tells which one it is: Frozen (blue) at very low HP with an enemy on you; Wind Chant (teal) at low HP with physical enemies on you; Conceal (purple) to sneak up on a fight or slip away when hurt. Pressed only while the button shows. All 12 of your screenshots are identified correctly.
- **v36.58**: heroes with skill variations, from your list. Selena, Roger, Lapu-Lapu, Edith, Sora, Julian, Lunox, Beatrix, Kagura, Gusion, Yi Sun-shin, Lukas and Suyou each show their variation in the Library (forms, weapons, enhanced skills, multi-stage skills, tap or hold). Lapu-Lapu, Edith and Lukas join Roger, Selena, Yu Zhong and Sora as transforming heroes, with cast modes and aim learned per form. Lapu-Lapu's two forms are both melee, so the AI doesn't switch them by distance. Julian's combo uses two different skills first, then the enhanced one.
- **v36.59**: **Experimental builds** (Build page). One tap asks the online model for 3 builds for your current hero that differ from its meta build. Each comes with a battle spell, an emblem, the synergy idea, when to use it and its weakness. The model is given the hero's real wiki skills and passive, its kit (skill count, forms, special casts, HP bar, variations), its playstyle and combo, the original server's patch and meta, the hero's meta build, and the equipment with what the Library learned about each item. Invented items are dropped, there's at most one pair of boots, each build needs 5+ real items, and the spell must be a real one. "Use this build" makes one your build on the Build page; tap it again to go back to the meta.
- **v36.60**: **Phone metrics on the Speed page** ("Phone" card, every 2 s, with 2-minute graphs):
  - **CPU:** this app's share of all cores, plus each core's clock against its maximum. The whole phone's CPU use is closed to apps since Android 8.
  - **GPU:** the load and clock from the chip maker's files (MediaTek GED for your Dimensity, Qualcomm KGSL, Mali), or "not readable" if the phone blocks them. It also shows which AI parts run on the GPU: the Gemma models show GPU or CPU, and the detectors and networks always run on the CPU.
  - **RAM:** the phone's used / total, this app's own use (Java, native, models and screen buffers), and a low-memory warning.
  - **ROM:** storage used / total and how much the app's files (models, icons, cache) take.
- **v36.61**:
  - **Skill layout read from the screen (ScreenKit).** Two HUD sizes exist: normal, and LARGE (bigger buttons and Flicker; your Masha and first Pharsa screenshots). The large HUD's attack, spell, Recall, Regen and skill places were measured on those screenshots and have their own calibration page ("In-game buttons (large HUD)"). The screen tells which HUD and how many skill buttons (2, 3 or 4) are showing. A layout counts only when all its buttons are lit: a skill not learned yet is a dark button, with no gold crescent until upgraded. It also needs a clear winner and several frames in agreement. On your 25 screenshots: 19 right, 6 not read (the hero list is used then), 0 wrong. The HUD size always follows the screen. The skill count follows the hero list for known heroes, the screen for others (10 agreeing readings, then remembered for that hero), or the screen when it keeps disagreeing with the list.
  - Your new 2-skill (Hirara) and 3-skill screenshots confirm the normal positions (within 16 px).
  - **The ultimate is always upgraded first** whenever its "+" shows (hard-coded).
  - **Skill-upgrade "+" places per kit**, from your level-1 screenshots: 4 skills (three "+" plus the ultimate's), 3 skills, and Hirara's 2. Before, the 3-skill places were used for every hero, so upgrades on Hirara and 4-skill heroes tapped the wrong spot. Skill 3's "+" is read too. **At level 1** the skill buttons are dark, so the layout is read from where the "+" signs are: 3 in a row means 4 skills, 2 means 3 skills, and Hirara's 2 sit elsewhere. All three of your level-1 screenshots are read correctly.
- **v36.62**:
  - **Upgrade "+" places** confirmed on your level-4/8 screenshots: the ultimate's "+" for 3- and 4-skill heroes (measured, within ~9 px), and the other "+" re-measured. A 2-skill hero (Hirara) never reads an ultimate "+": its spot is her Skill 2's.
  - **Hirara:** her picture in the Library (wiki numbers 1331 / 1341 / 1351 tried in turn, since her number isn't confirmed yet), and she's in the jungle-assassin list too.
  - **Library > Equipment:** a "Passive skills (roaming boots)" part with Encourage, Favor and Dire Hit (Dire Hit with the revamped Force stacks). Conceal stays under Active skills. Texts from your list.
  - **Aulus (and any hero with a revamp on the Advanced Server):** the wiki's skills section for the Advanced Server / an upcoming version is skipped, and so are such sub-sections inside the live one. The guide question gets a third, firmer try asking for the live pre-revamp kit and the live patch number, instead of being dropped. A hero that can't be learned now shows **why** in the Library ("⚠ last try failed", with the reason in its details). **Long-press a hero** to learn its skills and guide right away.
- **v36.63**: hero and item pictures. A picture that failed to download is tried again 2 minutes later; before, it wasn't retried until the app restarted, so one moment without internet, or a reinstall that cleared the saved pictures, left every icon as initials. Each picture now tries three addresses: the small one, the full-size one, and the wiki's file link. Downloads identify like a normal Android browser plus the app name. The reason for a failure (e.g. HTTP 403) is shown at the top of the Library and in the Logs.
- **v36.64** (from the Aulus wiki page you saved):
  - **Aulus's skills:** his page has an "Abilities" heading in his story (lore) before the real skills section. The app took that first heading, so no skill name matched and nothing was saved. Now the section that really holds Passive / Skill 1 / Skill 2 / Ultimate is used. His skills are Fighting Spirit, Aulus, Charge!, Cleaving Axe and The Power of Axe. The same fix helps any hero whose page has story "Abilities" first.
  - **Pictures:** the wiki now serves its images as ".../revision/latest/thumbnail-down/width/..": that's the first address tried, then the full picture, the old "scale-to-width-down" form, and the wiki's file link. Hirara's picture number is confirmed as 1331, and 1341 is Dori.
- **v36.65**: **skill icons in the Library.** Tap a hero to see its passive and every skill as icons, with labels (Passive, Skill 1, 2, 3, Ultimate, or the game's hints such as "Mobility" / "AOE"). The pictures come from the wiki, which names each one after the skill (e.g. Fighting_Spirit.png, The_Power_of_Axe.png on Aulus's page). They show once the hero's real skill names are learned from the wiki: tap "Learn the whole database online", or long-press the hero.
- v36.66: Library icons read from each hero's real wiki skill headings (fixes Layla/Arlott showing item icons with the same names, and missing icons for Hirara, Hayabusa, Hanzo, Hanabi, Benedetta, Barats, Aldous); Alpha's ultimate no longer marked missing. NEW: the app detects, identifies and verifies your hero automatically from its hero icon (minimap portrait) and its skill icons (learned in your matches + the wiki pictures); the Player page shows "Hero check" and warns if the screen shows a different hero than the one you typed.

- v36.67: the hero detected in game from its skill icons is now ABSOLUTE - it overrides even the hero you typed on the Player page (a different hero than the typed one needs 6 agreeing readings instead of 3; forgotten when the match ends). Skill looks are not learned under a typed name the wiki pictures contradict.

- v36.68: missing skill icons for 4-skill heroes (Chip, Fredrinn) fixed - the wiki reader accepts headings like "Ultimate 1 / Ultimate 2", "Skill 1: name", a heading with the skill's own name, or two pictures under one heading, and fills every button of the hero's kit; a button still without a wiki picture shows the picture named after the skill (never an item / spell of the same name). Icons are read again once after updating. If a hero still misses one, its Library entry shows the headings found on its wiki page. The in-game hero check uses only the active skill buttons, never the passive.

- v36.69: from your saved Fredrinn and Chip wiki pages - Fredrinn's two Ultimates ("Ultimate 1" Energy Eruption = 3rd button, "Ultimate 2" Appraiser's Wrath = ultimate) and Chip's "Special" extra skill (Why Walk? = 3rd button, Shortcut = ultimate) are read as 4-skill kits, for the icons and the learned skills. Greyed / cooldown / skin versions of an icon are skipped, and icons shown inside another skill's text no longer push out the real one. Fredrinn's Energy Eruption added to his built-in skills; Chip's and Fredrinn's skills are learned again once.

- v36.70: skill-icon detection also while WATCHING a live match - the watched hero's skill icons in the bottom panel (skill 1, skill 2, ultimate) are compared with the looks learned while watching and with the wiki pictures; once they show a hero it replaces the one Gemma read (a different hero than Gemma's needs 6 agreeing readings, the same one 3). Live-panel looks are kept apart from your own buttons. Live page shows "Skill icons: ...". (3-skill heroes only for now: the panel positions of a 4th icon are not measured yet.)

- v36.71: live-match panel re-measured on your 4 live screenshots - skill icons are packed from the left (one every 0.0387 of the width; 2, 3 or 4 of them), battle spell at 0.564. The number of icons gives the watched hero's kit (2 / 4 read right on all 4), so the skill-icon detection and the live cooldown reading now work for 2-, 3- and 4-skill heroes. (The old s2 / ultimate positions were between two icons.) Tested: the same hero matches 0.85-1.00 (even with every skill on cooldown), different heroes below 0.

- v36.72: live panel, from your 3-skill hero screenshots - a 3-skill hero's icons are spread wider (0.4163 / 0.4599 / 0.5033, 0.0435 apart; 2- and 4-skill heroes 0.0387). The icon count now uses texture (an icon, even dark on cooldown, spread 28+; an empty spot 9 or less), so the kit (2 / 3 / 4) reads right on all 6 live screenshots. Icons cooling down (dark, with a number) are left out of the comparison (2 ready icons needed) and only fully-ready panels are learned: the same hero now matches 0.81-1.00.

- v36.73: EQUIPMENT TIERS AND TYPES (game/ItemClass.kt). Every item has a tier (Advanced / Intermediate / Basic / potion) and stat types: Attack (Physical Attack), Magic (Magic Power), Adaptive Attack (fits both), Physical Defense, Magic Defense, Neutral Defense (both / flat HP), Movement - from the item's wiki page first, then a built-in table, then the Library's learned stats. Builds (Build page, role builds, the online meta's, experimental builds) use ADVANCED equipment only; the experimental-build prompt gets only Advanced items with their types and the hero's damage type. Library > Equipment: Advanced (Active-skill items first, then by type), Intermediate, Basic, Potions; the roaming blessings are a separate list (Conceal = active skill, Encourage / Favor / Dire Hit = passives; no cost, not equipment). Heroes: damage type read from the hero's wiki page ("Damage type"), shown in the Library with the equipment type it uses. Drafter: once the enemy heroes are known, their damage types and the defense type to build (Physical / Magic / Neutral). Blessed Retributions: effect text from the wiki only (the AI-written texts were deleted).

- v36.74: the 19 items the app did not know (Allow Throw, Azure Blade, Broken Heart, Chastise Pauldron, Dreadnaught Armor, Elegant Gem, Exotic Veil, Expert Gloves, Flower of Hope, Iron Hunting Bow, Javelin, Lantern of Hope, Magic Blade, Mystic Container, Resonating Heart, Silence Robe, Throw Forbidden, Valkyrie's Shield, Vampire Mallet) are Basic equipment (your word) - never in builds; their stat types come from the wiki / the Library. An item that costs nothing shows "no cost".

- v36.75: from your saved Blade of Despair and Flame Retribution wiki pages. TIERS follow the wiki's "List of equipment" (on every equipment page: Attack / Magic / Defense with Basic, Intermediate, Advanced rows, Movement with Basic and Advanced): all 98 current items found, the built-in table corrected to match it exactly (e.g. Molten Essence, Silence Robe, Dreadnaught Armor = Intermediate; Valkyrie's Shield, Chastise Pauldron = Advanced; Hero's Ring, Book of Sages, Mystery Codex = Basic); the list is read again whenever the Library reads an equipment page, so tiers follow the wiki. Item pages: stats from the info box's "bonus" field, passive / active from "unique", price from the Price row (Blade of Despair: +160 Physical Attack, +5% Movement Speed, 3010 gold). Blessed Retributions: the effect from their page's "unique" field (Flame Retribution read right).

- v36.76: your Blessed Retribution guide in Library > Spells: what each one does (Ice: steals movement speed - chasing / mobile enemies; Flame: steals Physical Attack and Magic Power - burst / 1v1; Bloody: damage + HP steal, heals from Extra HP - tank / sustain / frontline jungler), shown always, with the wiki's exact effect text added once read. The experimental builds get the same guide to choose a jungler's Retribution.

- v36.77: Thunder Belt, Dominance Ice and Oracle are listed under Neutral Defense (your word; they keep their physical / magic defense as a second type). The roaming blessings show their wiki icons (Active_-_Conceal.png, Passive_-_Encourage.png, Passive_-_Favor.png, Passive_-_Dire_Hit.png - the file names from your saved Equipment page).

- v36.78: skill icon choice fixed for sections that show other pictures first (Beatrix's passive section shows her weapons' item icons - Demon Hunter Sword - before her own): the picture named after the skill's name at the start of the section wins; equipment / spell / hero pictures go last (a passive with its own file, e.g. "Malefic_Gun_(Passive).png", beats the item "Malefic_Gun.png"). Skill icons are read again once.

- v36.79: Chip's buttons (your word): the Ultimate Shortcut is the 3rd button, the Special skill Why Walk? the 4th - for the icons, the in-game skill-icon check and the skill learning; Chip's icons and skills are read again once.

- v36.80: HERO PRIORITY (your order): 1. the hero ENTERED on the Player page (new Enter button, or the keyboard's Done; typing alone changes nothing; empty + Enter = detect) - absolute; 2. the skill set seen in game; 3. hero icon / in-game model / minimap portrait; then drafted, last played, Gemma. SKILL-COUNT GATE: the number of skill buttons is read from the screen alone (3 agreeing readings) and every guess after the entered hero must have that many skills - a 4-button screen can only be a 4-skill hero (Fredrinn is no longer taken for Zilong), same for 2 and 3; the skill icons are read at that layout's own button places. Unknown heroes count as 3 skills (the 2- and 4-skill heroes are listed or learned from the wiki). Screen skill counts saved under a wrongly-detected hero are dropped once. The hero icon on the scoreboard is not read yet.

- v36.81: the in-game MODEL and the minimap PORTRAIT are now two separate checks (IconMemory.matchModel / matchPortrait), in your order: entered hero > skill set > (scoreboard icon - not read yet) > in-game model > minimap portrait > drafted > last played > Gemma. Each counts only when its best hero is clearly closer than any other (portrait < 0.18, margin 0.06; model < 0.22, margin 0.08 - first values, not yet measured on your screens) and has the skill count the screen shows. The Player page says which one found the hero.

- v36.82: the hero select / draft screens no longer tell which hero is yours (neither Gemma's draft reading nor the hero the AI picks); the LOADING screen does: Gemma reads the hero name on your card (first card, top row; the skin name above it is ignored). The SCOREBOARD read now also gives your hero (your row, top left) and runs until it has. Priority: entered > skill set > scoreboard > in-game model > minimap portrait > loading screen name > last played > Gemma's in-game guess (each after the first must fit the skill count on screen). The Drafter's pick / ban advice is unchanged.

- v36.83: the loading-screen name now comes before the in-game model. Priority: entered > skill set > scoreboard > loading screen name > in-game model > minimap portrait > last played > Gemma's in-game guess.

- v36.84: "My player name" box on the Player page (with an Enter name button). On the loading screen and the scoreboard Gemma now copies every card's player name with its hero; your card is the one with your name (small reading slips allowed: one letter per 5, a clan tag in front is fine; a different name like Griffin for Griffith is not taken), and if Gemma swapped the two teams, your name puts them back. No name set or not found = the first card of your team, as before. Tested with "Griffith" on sample answers.

- v36.85: the loading screen's cards are in random places and the two teams' rows can swap (your word): nothing is taken from positions any more. Gemma reports the top and bottom rows as they are; your team = the row with your player name, you = the card with your name. No name entered or not found = nothing decided (tried again 2 s later while the loading screen lasts). On the scoreboard (your team left) your hero is also taken only by your name; found on the right = the sides are swapped. When your name matches, the floating panel shows it ("Name: ✓ \"Grifflth\" = your name (Griffith) on the loading: Chip").

- v36.86: (1) DEAD HEROES (vision/DeathTimers.kt): the red respawn countdowns next to the score - enemies right of it, allies left - are counted (own matches show an enemy's portrait only while it is dead; live matches show all 10): right on your 9 screenshots. Used by the AI: dead enemies are not counted as hiding/possibly near, each dead enemy makes fights safer (+4 survival, +6 engage), more allies dead than enemies makes them riskier; also in the strategy model's state text. (2) Priority: entered > LOADING screen card with your name (hero) > skill set > scoreboard > loading > model > portrait; the spell on your loading card comes right after the one you chose. (3) Floating panel: Loading screen hero + spell, Spell (and where from), Dead counts. (4) The scoreboard read is told the in-game scoreboard differs from the end-of-match screen (which is skipped). (5) Screen capture button only on the Home page. (6) Speed page keeps 5 minutes (was 2).

- v36.87: (1) FLOATING PANEL steady: one single-line row per line (… when too long), rows never removed while open, a row is redrawn only when its text changes, every line always present ("-" when empty) - nothing jumps; smooth scrolling (no over-scroll). (2) END-OF-MATCH SCOREBOARD (vision/EndBoard.kt; your screenshot is recognised as RESULT_BOARD, your in-game scoreboard as SCOREBOARD): your row = the highlighted one (85-112 bright vs 41-51; row 5 on your screenshot) and, with your player name entered, Gemma reads the 5 names on your side and your row must carry it; that row's PORTRAIT is matched with the heroes' portrait pictures (the Library's icons; detected heroes first, fetched if missing). The hero found VERIFIES the match's detection and LOCKS it: the portrait/model look, the skill-button look and the skill count learned this match are kept for that hero (wrong labels replaced), and it becomes your last-played hero. Floating panel line "Match end: ✓ <hero> LOCKED … - matches the detection / corrected from X".

- v36.88: PORTRAITS vs the wiki's portrait pictures (vision/PortraitMatch.kt; the same HeroNNN-icon.png the Library shows, all heroes' pictures fetched once): (1) IN-GAME SCOREBOARD - your highlighted row (92 bright vs about 60 on your screenshot) gives your hero without Gemma (as the scoreboard step), and the enemy portraits fill the enemy line-up when it is not known; (2) END-OF-MATCH scoreboard - as before (EndBoard), now with all pictures fetched; (3) MINIMAP - your icon vs the pictures, a last resort below the learned portrait (it is about 11 px wide: a clear winner only, 0.6 and 0.12 ahead). Each must fit the skill count on screen. Floating panel line "Portraits: scoreboard … · minimap …". Thresholds are first values (the pictures cannot be downloaded in the build environment to measure).

- v36.89: LEVEL fix (it counted up to 15 at level 12): the level can no longer exceed what the match time allows (5 by 2:00, then about one per 55 s - only when the app saw the loading screen, so its clock is the real one); the in-game scoreboard read now also takes the LEVEL number on your portrait and it sets the true level (also downward) and teaches that level's badge (a wrongly remembered badge is replaced); a skill "+" flickering back within 1.5 s no longer counts as a level-up; badge patterns remembered so far are forgotten once (they may have been saved at miscounted levels; the built-in ones stay).

- v36.90: MATCH RECORDING follows the match: it starts at the LOADING screen (if that was missed, when your hero is first seen) and ends at the end-of-match screens; moments are recorded only inside a match. Every recorded moment (experiences, your demonstrations, fights - database v7, column match_id) carries its match, and when the end scoreboard locks the hero, all of that match's moments and its Learn-page match line are relabelled with that hero. Logs: "Match recording started / ended", "N recorded moments relabelled as X". Floating panel buttons and title smaller.

- v36.91: Learn page buttons "Delete all fight moments" (the fights table) and "Delete all recorded moments" (everything recorded in your matches: the AI's saved decisions, your recorded play, the fight moments; live-match moments keep their own button). Both ask first.

- v36.92: from your 15 screenshots of a whole match (newest win over older ones). SCREENS: the new loading screen (cards on a blue band / red line; also with the rows swapped), the hero-locked screen (blue skin button), searching for a match, the Rank and Win-streak screens (gold Continue - they were taken for hero select, which could start a new match at the end of yours) and the new end scoreboard (Quit moved, gold Return to Lobby - also taken for hero select) are recognised: all 15 right, and the 194 older screenshots unchanged except those 6 fixes. LOADING: your card = the one with the GREEN player name (173 green pixels on your card only) - works without a typed name; Gemma is told it too. Your highlighted rows found on the new in-game scoreboard (row 3) and end scoreboard (row 2). The hero portrait on the Victory / Defeat and MVP screens backs up the scoreboard's. An ENTERED hero is locked at the end scoreboard as it is and the entry is then cleared, ready for the next match. Priority: minimap portrait now before the in-game model.

- v36.93: Vision tuning (show what the AI sees / where it touches) moved from the Player page to the Home page, under the screen capture button.

- v36.94: fixes from your first real-match log (Aurora, then Karina - loading card found by your name both times, end scoreboard lock + 2763 moments relabelled worked): no "live match" readings within a minute of your own match (a live clock and watched hero were read right after your match ended); the end-scoreboard names count only if that screen was still showing when Gemma answered (names from another screen came back); a match starts once (hero-select false sightings started 3); level: the two-digit-badge jump only from level 8 (6 -> 7 and 2 -> 7 happened), a scoreboard level more than 3 below the count or above the time limit is ignored (7 -> 1 happened); learning how the hero looks / its skill icons at most every 15 s, logged once per match (it logged every second); the model / minimap portrait need 3 readings in a row to switch to another hero (Karina / Aurora / Alucard flipped).

## How it is organised (matches your note)

| Folder | Job | Speed |
|---|---|---|
| capture/ | MediaProjection screen frames (fallback: accessibility 1 fps) | 10-15 fps |
| vision/ | HP-bar hero/enemy/minion detection, enemy tracking, minimap, screen phase, HUD, button auto-detect | every frame |
| game/ | Game state, hero profiles, primary + counter builds | every frame |
| ai/TacticalEngine | Kotlin rules: lane clear, engage, retreat, skills, ultimate | 5 per second |
| ai/GemmaEngine + StrategicAdvisor | Gemma 4 E2B on LiteRT-LM: strategic plan, draft, build | event-driven |
| controller/ | Accessibility touches, overlay, item swap | on demand |
| learning/ | SQLite experiences, scoring, "learn from me" demos + copying, CSV export | 2-5 per second |
| models/ | Model file import and loading | once |
