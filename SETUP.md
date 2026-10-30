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
4. Setup page: calibrate the button positions with "Show markers on the game".

## How it is organised (matches your note)

| Folder | Job | Speed |
|---|---|---|
| capture/ | MediaProjection screen frames (fallback: accessibility 1 fps) | 10-15 fps |
| vision/ | HP-bar hero/enemy/minion detection, enemy tracking, minimap | every frame |
| game/ | Game state, hero profiles, primary + counter builds | every frame |
| ai/TacticalEngine | Kotlin rules: lane clear, engage, retreat, skills, ultimate | 5 per second |
| ai/GemmaEngine + StrategicAdvisor | Gemma 3n E2B on LiteRT-LM: plan, draft, build | event-driven |
| controller/ | Accessibility touches, overlay, item swap | on demand |
| learning/ | SQLite experiences, scoring, CSV export | 2 per second |
| models/ | Model file import and loading | once |
