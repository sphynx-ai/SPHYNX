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
2. Download the Gemma 3n E2B `.litertlm` file (Model page link, accept the licence) into Download/MLBB-Research,
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

- Get a free API key at console.groq.com/keys and save it as `groq_key.txt` (key on the first line) in Download/MLBB-Research.
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
- Jungle: own blue / red buff always counts as closer when choosing the next camp.

## Tactical network: only the reliable parts are used (v33)

- The notebook writes `policy_info.json` next to `policy.tflite`: how well the joystick and each button did on your
  most recent play. Put both files in Download/MLBB-Research.
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
hero_enemy, hero_self, minion_ally, minion_enemy, creep, buff, turtle, lord, tower_enemy, tower_ally, bush, wall.

1. Play with *Collect detector training pictures* on (Learn page): every 10 s (200 a day) the whole screen is saved
   with boxes drawn from the colour vision into `Download/MLBB-Research/screen_dataset` (heroes and minions from
   their HP bars and team colour, creeps / buffs from monster bars and the camp type, turtle / lord from the boss bar
   and the minimap icons, enemy towers from the range circle, bushes and walls from the bush / wall finder). Only
   the colour vision's finds are used, never the screen detector's own (it would learn its own mistakes).
2. Quick: zip `screen_dataset`, open `notebooks/train_screen_detector.ipynb` in Colab (T4 GPU), run all, choose the
   zip. Best: upload the folder to a Roboflow project, fix and add boxes (ally towers are never drawn by the app),
   generate a version and use the notebook's Roboflow cell instead.
3. Put `mlbb_screen_detector.tflite` and `screen_labels.txt` in Download/MLBB-Research; the app loads them.

What the AI does with it (only filling gaps in the colour vision):
- creeps, buffs, turtle, lord with no HP bar near them = monsters nobody has hit yet: the AI walks up and attacks
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

1. With your file manager, make the folder **Download/MLBB-Research** on the phone.
2. Put the downloaded files in it:
   - the Gemma 3n E2B model: the `.litertlm` file from Hugging Face (any name ending in .litertlm)
   - `mlbb_detector.tflite` and `labels.txt` from the detector notebook
   - `policy.tflite` from the tactical network notebook
   - `groq_key.txt`: a text file with your Groq API key on the first line (optional: `groq_model.txt` with a model name)
3. App -> Model page -> *Choose the folder* -> pick Download/MLBB-Research -> *Use this folder* -> *Allow*.
   This is needed once; Android then lets the app read that folder for good.
4. From then on, every time the app starts (or when you tap *Scan the folder now*), it copies what is new or changed,
   loads the Gemma model, the detector and the tactical network, and applies the class names and the Groq key.
   Big files are only copied again when they changed. The Model page shows what the last scan did.
- From a computer you can also copy the files to `Android/data/com.example.mlbbresearch/files/MLBB-Research`
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
4. Put `policy.tflite` in Download/MLBB-Research; the app loads it by itself. It runs in about a millisecond per decision.
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
3. It downloads `mlbb_detector.tflite` and `labels.txt`. Put both files in Download/MLBB-Research; the app loads them by itself.
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

Tactical network per hero: the notebook trains one network for one hero (`HERO = "YourMainHero"`, or empty = the hero
you recorded most) and only from your own play (`WATCHED_WEIGHT = 0`). policy_info.json says which hero; the app uses the
network only while you play that hero and uses that hero's recorded moments otherwise.

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
the tactical notebook: upload it (with or without your own play) and set HERO to that hero.

Not read: their items and gold (shown in the panel, not used by the tactical network), basic attacks directly,
and aiming (no indicators on the live screen).

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
