# Ai-App: MLBB Research

Offline AI companion for Mobile Legends: Bang Bang, built for Code on the Go on Android.

- Hybrid AI: fast colour-based vision, Kotlin tactical rules, and Gemma 3n E2B (LiteRT-LM) for strategy, draft and build.
- Pages: Home dashboard, Player, Draft, Build, Model, Learn, Logs, Setup.
- Button calibration: drag markers on the game, auto-detect, and named presets.
- Standard / Ranked match switch, AI on/off from the floating icon, skill upgrade, quick-buy, smooth joystick.
- Screen phase detection (hero select, loading, in game, shop, scoreboard, big map) and Coach mode (you can help any time; it learns your combos, cast timing and movement).
- Hero and battle-spell recognition (skill-icon memory + Gemma), lanes (EXP, gold, mid, roam, jungle; Retribution = jungle),
  per-hero skill combos (built-in, typed, or downloaded from a guide page), jungle monsters, turtle and lord.
- Smart fights: a learning AI (LinUCB contextual bandit) that scores every fight and learns per hero which way to fight works.
- Online meta (optional, Groq API, openai/gpt-oss-120b with browser search): latest patch builds, combos, lanes, rotations, draft picks.
- Optional trained YOLO detector on LiteRT: train it free in Colab with `notebooks/train_mlbb_detector.ipynb`.
- Offline except the online meta and the optional "Get my hero's combo online" button.
- Source: `app/src/main` (packages `ai`, `vision`, `game`, `controller`, `capture`, `learning`, `models`, `core`, `app`).

See [SETUP.md](SETUP.md) to install it into a Code on the Go project and
[build-gradle-additions.txt](build-gradle-additions.txt) for the Gradle lines.

Default training data: "Mobile Legends Minimap Dataset" by gladi on Roboflow Universe, licensed CC BY 4.0.

For research use. Automating the game may break its terms of service.
