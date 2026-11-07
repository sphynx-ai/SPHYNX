# Testing

## Running the tests

```
./gradlew testDebugUnitTest
```

They run on the JVM (no device). CI runs them on every pull request (`.github/workflows/android-build.yml`).
Reports: `app/build/reports/tests/testDebugUnitTest/index.html`.

## How the tests avoid real screenshots

- **Synthetic frames** (`testutil/SynthFrame`): the vision code reads plain ARGB pixel arrays, so a test paints only
  the signature a rule measures (a red respawn countdown, the loading screen's red line, a green player name) on a
  flat background, in the same screen fractions the code uses.
- **Synthetic text/HTML**: Gemma's JSON answers and the wiki page layouts are written inline in the tests.
- **Settings**: `TestSupport.freshCfg()` installs an in-memory `SharedPreferences` into `Cfg`, so no production
  test hooks are needed.
- **No personal data**: no gameplay screenshots, player names from real matches, or wiki images are committed.

## What synthetic tests can and cannot prove

They prove the **logic** (which rule fires, thresholds relative to each other, parsing, matching). They cannot prove
that the **colour thresholds still fit the real game's rendering** (e.g. that MLBB's countdown red is still above
r=200). For that, a small set of real reference images is needed; see "Real-image fixtures" below.

## Real-image fixtures (not committed; owner decides)

Rules tuned on real screenshots, and the minimum real input each needs. Wherever possible only the **cropped region the
rule reads** is needed, not a whole screenshot:

| Rule | Needed | Crop that suffices |
|---|---|---|
| Screen phases (`ScreenReader.classify`) | 1 per phase: home, lobby, queue, match found, hero select, hero locked, loading, in game, in-game scoreboard, victory/defeat, rank, win streak, MVP, end scoreboard | The button / band areas each rule samples (bottom strip, right panel, top centre); names and chat can be cut out |
| Respawn countdowns (`DeathTimers`) | 3 (0, some, all enemies dead) | Top bar only (top 7% of the screen) |
| Own / enemy HP bars (`HeroDetector`, `EnemyDetector`) | About 10 covering shields, Masha/X.Borg/Fredrinn bars, crowded fights | Areas around the bars |
| Skill-button layout (`ScreenKit`, `HeroVerifier`) | 2-, 3- and 4-skill HUDs, normal and large HUD | Bottom-right button area |
| Live-match panel | 2-, 3-, 4-skill heroes | Bottom panel strip |
| End scoreboard / in-game scoreboard rows | 1 each | The row brightness bands (player names can be blurred) |

Until real fixtures are approved, these rules are covered only by the synthetic tests and by manual checks.
