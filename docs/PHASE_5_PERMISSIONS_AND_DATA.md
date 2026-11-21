# Phase 5 — Permissions and data-flow record (P8)

**Date:** 2026-11-21, Claude Code, by reading the code of `sphynx-test-branch` (v37.83). Audit findings F8 and F11 in `docs/PHASE_5_RELIABILITY_AUDIT.md`. Nothing has been changed in the app by this record. Every change it proposes is an **owner decision**.

## 1. Permissions (manifest) and why each is needed

| Permission | Why | When it is used | Without it |
|---|---|---|---|
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`, `controller/AccessibilityController`) | The AI's touches (gestures) and the floating panel window | Only while the Player / AI is on. Turning the service off stops the AI (`onUnbind` → emergency stop) | No touches; advice only |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Screen capture must run as a visible foreground service (Android 10+ / 14+ rules) | While screen capture is on (notification with STOP AI) | No screen reading |
| Screen capture (MediaProjection consent, each start) | Reading the game's screen | Asked each time capture starts | No screen reading |
| `POST_NOTIFICATIONS` | The capture notification (with STOP AI) and the "Groq available again" notice | Android 13+ | The capture notification may be hidden; the emergency stop on the floating panel remains |
| `SYSTEM_ALERT_WINDOW` | Fallback for the floating panel when the accessibility overlay is refused | Only on phones that refuse the accessibility overlay | Panel only through accessibility |
| `MANAGE_EXTERNAL_STORAGE` (All files access) | Read and write `/CodeOnTheGoProjects/Sphynx-Storage` (models, keys, exports) | Turned on by hand by the owner | Writes go to `Download/Sphynx-Storage`; models picked by hand |
| `READ_EXTERNAL_STORAGE` (≤ API 32), `WRITE_EXTERNAL_STORAGE` (≤ API 29) | Calibration backup that survives a reinstall (older Android) | Older Android only | — |
| `INTERNET`, `ACCESS_NETWORK_STATE` | The online features in Section 2 only; the play loop never needs the network | Only from the features listed below | Offline: every online feature shows "needs internet" (`Capability.NEEDS_INTERNET`) |
| `VIBRATE` | A short buzz when the practice gate locks or unlocks | Practice | No buzz |
| `libOpenCL.so`, `libvndksupport.so` (optional native libraries) | LiteRT-LM GPU | Model load on the GPU | CPU |

No location, camera, microphone, contacts or account permissions are requested.

## 2. What leaves the phone

| Destination | What is sent | When | Needs |
|---|---|---|---|
| `api.groq.com`, `generativelanguage.googleapis.com` (Gemini), `api.cerebras.ai` | Text prompts: the patch / hero meta questions, the library learner's questions, Build Lab questions (hero, item and skill names; no screenshots) | When the owner taps an online action, and the weekly automatic meta refresh (`MetaAdvisor.autoGeneral`) | A key file in `Input/` |
| `mobile-legends.fandom.com`, `static.wikia.nocookie.net` | Page and picture requests (hero, item, skill, emblem pages and icons) | Library learning and icon downloads | Internet |
| `api.github.com` (branch `recordings-for-debugging`) | **Debug data:** session recordings (what the AI saw and decided, the log, video of the screen), diagnostics logs, exit traces (v37.80), speed data, training pictures | The Debug data page's upload button, or automatically after a recording when the switch is on | `github_token.txt` |
| `api.kaggle.com`, `www.kaggle.com`, `raw.githubusercontent.com` | Training picture datasets and the training notebook (cloud training) | When the owner starts a cloud training | `kaggle.json` |
| `huggingface.co`, `github.com`, `console.groq.com` | Nothing is sent: only links opened in the browser | When the owner taps them | — |

**Personal data in uploads:** session videos show the game screen, which can show player names (other players' too) and chat. Recordings go to a GitHub branch of the project's own repository. Per the owner's rules, the Kaggle account name and every key / token are never shown or uploaded.

## 3. What stays on the phone and how long

| Data | Where | Retention |
|---|---|---|
| Settings, calibration, learned data, **API keys** (Groq, Gemini, Cerebras, Kaggle user name and key) | App preferences (`Cfg`) | Until reset or uninstall |
| Diagnostics log | App files, `diagnostics.log` | Capped at 200 KB (trimmed to the newest 100 KB) |
| Recordings, exit traces, debug exports | `Sphynx-Storage/Recordings` | Deleted from the phone once uploaded (owner's rule) |
| Training pictures, exports, backups | `Sphynx-Storage/Output` | Picture zips: until a successful training used them; the rest until deleted |
| Models and key files | `Sphynx-Storage/Input` | Until the owner deletes them |

## 4. Findings for the owner

| # | Finding | Proposal | Decision |
|---:|---|---|---|
| D1 | **API keys can leave the phone in Android's own backup.** The manifest has `allowBackup="true"` and no backup rules, so Android Auto Backup (Google Drive) and phone-to-phone transfer include the app's preferences, and the Groq, Gemini, Cerebras and Kaggle keys and the Kaggle user name are stored there. Calibration does not need this backup: it already comes back from the `Output` backups after a reinstall. | Add backup rules (`dataExtractionRules` for Android 12+, `fullBackupContent` for 11) that leave out the preferences file. Alternatively, turn `allowBackup` off. | **Owner decided 2026-11-21: exclude.** Done in v37.88: `res/xml/data_extraction_rules.xml` (Android 12+: cloud backup and device transfer) and `res/xml/backup_rules.xml` (Android 11) leave `cfg.xml` out; `BackupRulesTest` |
| D2 | Session videos can show other players' names and chat, and they go to GitHub. | Keep the recordings branch private; mention it in any public privacy notice before Phase 16. | Owner |
| D3 | The weekly automatic meta refresh uses the network without a tap (only with a key present). | Keep it, and show it in the data-flow notice (it already shows "Updated <date> from <sources>"). | Owner |
| D4 | `MANAGE_EXTERNAL_STORAGE` is a restricted permission on Google Play. | Fine for the owner's own builds. Before any store release, move to the folder picker / MediaStore only (Phase 16). | Owner, before Phase 16 |
