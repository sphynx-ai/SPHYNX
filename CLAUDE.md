# Sphynx Auto Pilot: standing rules from the owner

Android app (Kotlin, package `com.example.mlbbresearch`, app id `com.sphynx.autopilot`). The canonical roadmap is
`docs/MASTER_DEVELOPMENT_PLAN.md`; the current phase and its evidence are in its section 9.

## Workflow
- The owner builds on the phone (Code on the Go) from `test/owner-2026-11-10`. Work goes in draft PRs into that branch.
- Merge a PR as soon as CI ("Android build") passes; then start the next PR in the queue.
- After the queue is merged, post the phone steps (`git pull`, rebuild) and a device-test checklist.
- There is no Android SDK in the cloud container: CI is the build check. Add unit tests for pure logic.
- Every PR adds a What's new entry (`core/WhatsNew.kt`, `needsDeviceCheck = true` until the owner tests it on the phone).
- Only work the owner has tested on the phone goes to `main`.

## Debug data (owner, 2026-11-12)
Any future change that needs data for debugging or for checking its results MUST:
1. **Export** it into the recordings folder (`SessionRecorder.FOLDER`, via `SessionRecorder.exportFile` / `openOut`),
   from the Debug data page (all debugging exports are on that one page);
2. be **uploaded** by the Debug data page's upload button and the automatic upload (`core/GitHubUpload.kt`, branch
   `recordings-for-debugging`); big files (videos, zips) one per commit, at most 95 MB per file;
3. be **deleted from the phone** once its commit is on GitHub (`GitHubUpload.run` already deletes every uploaded file).
Training inputs (picture dataset zips, CSV exports) stay until a successful training used them.

## App rules
- Every feature switch is OFF by default on a new install; safety guards keep their safe position (Dry run ON).
  The GPU switch is ON by default (a speed setting, not a feature).
- No learning at all in Solo practice vs Computer (calibration only): no recorded moments, benchmark, training
  pictures, fight scoring or automatic recording (`learning/LearnGate`). A 5v5 match is learned from by itself.
- All calibration (buttons, minimap icons, scenery & minimap synchronization) is on the Setup page.
- Never show or upload the Kaggle account name or any key / token.
- Notebooks: `notebooks/` and `app/src/main/assets/notebooks/` must stay identical (the app trains from the
  built-in copy; GitHub `Sphynx-Auto-Pilot` is only the fallback).
- Storage: `/CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage` (or `Download/Sphynx-Auto-Pilot-Storage`); the old
  MLBB-Research folder is ignored completely.
