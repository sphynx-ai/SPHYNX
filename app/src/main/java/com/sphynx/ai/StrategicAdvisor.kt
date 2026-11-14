package com.sphynx.ai

import com.sphynx.capture.FrameHub
import com.sphynx.controller.ActionExecutor
import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import com.sphynx.game.BuildBook
import com.sphynx.game.CounterRules
import com.sphynx.game.GameState
import com.sphynx.game.GameStateTracker
import com.sphynx.game.HeroDb
import com.sphynx.game.LaneLogic
import com.sphynx.game.Phase
import com.sphynx.game.Role
import com.sphynx.game.SpellDb
import com.sphynx.game.Lane
import com.sphynx.game.MetaStore
import com.sphynx.vision.ObjectDetector
import kotlinx.coroutines.launch
import org.json.JSONObject

// Decides WHEN to ask Gemma and what to ask. Event-driven: Gemma is only consulted
// for the draft, the build, and when the fight situation changes, never every frame.
object StrategicAdvisor {

    @Volatile var plan: StrategyPlan = StrategyPlan.DEFAULT
        private set
    @Volatile var draft: DraftAdvice? = null
        private set
    @Volatile var draftText: String = ""
        private set
    @Volatile var buildAiText: String = ""
        private set
    @Volatile var lastError: String = ""
        private set
    @Volatile var busyWith: String = ""
        private set

    private var lastStrategy = 0L
    private var lastDraft = 0L
    private var lastBuildRead = 0L
    private var lastIdentify = 0L
    private var lastClockAsk = 0L
    private var lastModeCheck = 0L
    private var lastLineup = 0L
    private var lastEnemyCount = 0
    private var lastHpBand = 2
    private var buildAdviceKey = ""
    private var lastDraftScreenAt = 0L
    private var lastDraftAction = ""
    private var lastDraftActionAt = 0L
    @Volatile private var onlineDraft: MetaAdvisor.Draft? = null
    private var onlineDraftKey = ""

    fun reset() {
        plan = StrategyPlan.DEFAULT
        lastStrategy = 0L
        lastDraft = 0L
        lastBuildRead = 0L
        busyWith = ""
    }

    // Called about twice a second by AiRuntime. Runs at most one Gemma request.
    suspend fun tick(s: GameState, playerOn: Boolean, drafterOn: Boolean, builderOn: Boolean) {
        if (!GemmaEngine.ready) return
        val now = System.currentTimeMillis()
        // watching a live match: only read its clock (every minute or so), nothing else is ours
        if (s.phase == Phase.LIVE) {
            if (Cfg.liveLearn && GemmaEngine.visionOk && GameStateTracker.liveClockAge() > 60_000 && now - lastClockAsk > 30_000) {
                lastClockAsk = now
                readLiveClock()
            }
            return
        }
        // a new match's hero-select screen: is it RANKED (a ban stage) or STANDARD? (no switch to set)
        if (s.phase == Phase.DRAFT && GemmaEngine.visionOk && now - lastModeCheck > 300_000) {
            lastModeCheck = now
            readMatchMode()
            return
        }
        val inGame = s.phase.inMatch

        // 1. Draft (outside the match)
        if (drafterOn && !inGame && now - lastDraft >= Cfg.draftEverySec * 1000L) {
            lastDraft = now
            runDraft(builderOn)
            return
        }
        // 1a. THE LINE-UP: the loading screen (top row = my team, me first on the left; bottom row =
        // the enemies) and the scoreboard (my team left, me on top; enemies right) show all 10 heroes
        // and their battle spells. CUT DOWN (owner, 2026-11-12 - the 2026-11-11 recording: the Gemma line-up read
        // took 50 s on the loading screen and 70 s on the scoreboard, holding Gemma all that time): read ONCE per
        // match, on the loading screen only; never on the scoreboard (the enemy spells are read there from the
        // icons, by pixels); never in Solo practice (one Computer). The practice gate no longer uses it.
        val lineupDue = s.phase == Phase.LOADING && GameStateTracker.lineupReadAt == 0L &&
            com.sphynx.game.MatchKind.current() != com.sphynx.game.MatchKind.Kind.SOLO
        // (the loading screen: tried again 2 s after a read that did not find your name - your word)
        // the END-OF-MATCH scoreboard: the names of my team's rows (my row = my player name) - v36.87
        if (com.sphynx.vision.EndBoard.needsName() && GemmaEngine.visionOk && now - lastLineup > 2000) {
            lastLineup = now
            val img = FrameHub.bitmap()
            val reply = if (img != null) ask("end scoreboard names", com.sphynx.vision.EndBoard.namePrompt(), img, 0.0) else null
            val names = try { reply?.let { r -> org.json.JSONObject(r.substring(r.indexOf('{'), r.lastIndexOf('}') + 1)).optJSONArray("names") }
                ?.let { a -> (0 until a.length()).map { a.optString(it) } } } catch (e: Exception) { null }
            // (the end scoreboard must still be on screen after the answer - v36.94: your log had names from another screen)
            if (names != null && com.sphynx.vision.EndBoard.seenAt > now) com.sphynx.vision.EndBoard.gotNames(names)
            else if (names != null) Diag.info("End scoreboard: names ignored (the screen changed while reading)")
            return
        }
        if (lineupDue && GemmaEngine.visionOk && now - lastLineup > (if (s.phase == Phase.LOADING) 2000 else 8000)) {
            lastLineup = now
            runLineup(s.phase)
            return
        }
        // 1b. Which hero / battle spell is mine? The LOADING screen only (v36.82, your rule: the hero
        // select / draft screens are not used for this any more) - my card names my hero
        val pickPhase = s.phase == Phase.LOADING
        if ((playerOn || builderOn) && pickPhase && GameStateTracker.loadingHero == null && now - lastIdentify >= 2000) {
            lastIdentify = now
            runIdentify(s.phase)
            return
        }
        // 2. Builder: primary + counter build text (once per hero / enemy line-up)
        if (builderOn) {
            val hero = GameStateTracker.ownHero()
            val key = "$hero|${GameStateTracker.enemyTeam.sorted()}"
            if (hero != null && key != buildAdviceKey) {
                buildAdviceKey = key
                runBuildAdvice(hero)
                return
            }
        }
        // 3. Builder / hero ID: skip when fast vision has already verified hero & spell and Builder is off (v37.01 workload reduction)
        val needHero = (!GameStateTracker.isHeroVerified() || !GameStateTracker.isSpellVerified()) && (playerOn || builderOn)
        val every = if (builderOn) (Cfg.buildEverySec * 1000L).coerceAtLeast(25_000L) else 25_000L
        if (inGame && (builderOn || needHero) && now - lastBuildRead >= every) {
            lastBuildRead = now
            runScreenRead()
            return
        }
        // 4. Player strategy: event-driven when fight state changes, or relaxed timer so GPU/CPU stay cool and responsive (v37.01)
        if (playerOn && s.phase == Phase.IN_GAME) {
            val enemies = s.enemies.size
            val band = when { s.selfHp < 0.25f -> 0; s.selfHp < 0.5f -> 1; else -> 2 }
            val event = kotlin.math.abs(enemies - lastEnemyCount) >= 2 || band < lastHpBand ||
                (enemies >= 2 && lastEnemyCount < 2)
            lastEnemyCount = enemies
            lastHpBand = band
            val due = now - lastStrategy >= (Cfg.strategyEverySec * 1000L).coerceAtLeast(20_000L)
            if ((due || event) && now - lastStrategy >= 4500) {
                lastStrategy = now
                runStrategy(s, if (event) "fight changed" else "timer")
            }
        }
    }

    private suspend fun runStrategy(s: GameState, why: String) {
        val hero = s.ownHero ?: "unknown hero"
        val role = HeroDb.role(s.ownHero).name.lowercase()
        // Compact, low-token prompt for fast strategic reasoning response (v37.01)
        val prompt = "MLBB strategy for $hero ($role, ${s.lane.label}). " +
            "State: ${s.summary()}${ObjectDetector.summary().let { if (it.isEmpty()) "" else "; map $it" }}. " +
            (if (s.matchSec >= 0) "Time ${s.matchSec / 60}:%02d. ".format(s.matchSec % 60) else "") +
            "Do not think; reply JSON only: " +
            "{\"plan\":\"farm|push|fight|retreat|group|defend\",\"aggression\":0.5,\"focus\":\"weakest|nearest\",\"note\":\"max 6 words\"}"
        val reply = ask("strategy ($why)", prompt, if (Cfg.strategyWithImage) FrameHub.bitmap() else null, 0.1) ?: return
        val p = DecisionParser.strategy(reply)
        if (p != null) {
            plan = p
            ActionExecutor.note("Gemma plan: ${p.plan}, aggression ${"%.1f".format(p.aggression)} - ${p.note}")
        } else {
            lastError = "strategy reply was not JSON: ${reply.take(80)}"
        }
    }

    private suspend fun runDraft(builderOn: Boolean) {
        if (!GemmaEngine.visionOk) { lastError = "Gemma loaded in text-only mode, so it cannot read the draft screen"; return }
        val img = FrameHub.bitmap() ?: return
        val mode = if (Cfg.draftSolo) "I am playing SOLO (no coordination): prefer heroes that can carry alone."
                   else "I am playing with a TEAM: prefer heroes that fit my team's composition."
        val known = GameStateTracker.myTeam
        val missing = Role.values().filter { r -> r != Role.UNKNOWN && known.none { HeroDb.role(it) == r } }
            .joinToString { it.name.lowercase() }
        val type = if (Cfg.ranked) "This is a RANKED match with a ban stage and a pick stage. "
                   else "This is a STANDARD match: there is NO ban stage, each player just picks a hero, so leave ban empty. "
        val metaHint = onlineDraft?.let { o ->
            "The current online meta recommends picking ${o.pick.joinToString()} and banning ${o.ban.joinToString()}: prefer these if available. "
        } ?: MetaStore.general()?.let { g -> if (g.bans.isEmpty()) "" else "Strong heroes in the current patch: ${g.bans.joinToString()}. " } ?: ""
        val prompt = "This is a Mobile Legends: Bang Bang screenshot. $type$mode $metaHint" +
            (if (known.isNotEmpty()) "My team so far: ${known.joinToString()}. Roles still missing: $missing. " else "") +
            "Reply with ONLY one JSON object like this example, no other words: " +
            "{\"screen\":\"draft\",\"stage\":\"pick\",\"my_turn\":true,\"my_team\":[\"Layla\"],\"enemy_team\":[\"Tigreal\"]," +
            "\"my_hero\":null,\"ban\":[\"A\",\"B\",\"C\"],\"pick\":[\"A\",\"B\",\"C\"],\"tap\":{\"x\":50,\"y\":40},\"reason\":\"short\"} " +
            "screen is one of draft, loading, lobby, game, result, other. stage is ban, pick, waiting or none. " +
            "my_turn is true only if the game is asking ME to ban or pick right now. my_hero is the hero I locked in, or null. " +
            "ban: 3 strong heroes to ban. pick: 3 best heroes for me that counter the enemy picks. " +
            "tap: position of the first recommended hero's portrait as percent of the screen, or null if not visible."
        val reply = ask("draft", prompt, img) ?: return
        val d = DecisionParser.draft(reply)
        if (d == null) {
            lastError = "draft reply was not JSON: ${reply.take(80)}"
            return
        }
        if (d.screen != Phase.UNKNOWN) GameStateTracker.reportScreen(d.screen)
        if (d.screen == Phase.DRAFT) {
            // first draft screen in 5 minutes = a new match: forget the old teams
            if (System.currentTimeMillis() - lastDraftScreenAt > 300_000) GameStateTracker.newMatch()
            lastDraftScreenAt = System.currentTimeMillis()
        }
        if (d.myTeam.isNotEmpty()) GameStateTracker.myTeam = (GameStateTracker.myTeam + d.myTeam).distinct().take(5)
        if (d.enemyTeam.isNotEmpty()) GameStateTracker.enemyTeam = (GameStateTracker.enemyTeam + d.enemyTeam).distinct().take(5)
        // (the hero picked in the draft is no longer taken as yours - the loading screen tells it, v36.82)

        // never suggest a hero that is already taken
        val taken = (GameStateTracker.myTeam + GameStateTracker.enemyTeam).toSet()
        var picks = d.pick.filter { it !in taken }
        if (picks.isEmpty() && d.screen == Phase.DRAFT) {
            val need = Role.values().firstOrNull { r -> r != Role.UNKNOWN && GameStateTracker.myTeam.none { HeroDb.role(it) == r } }
            picks = ((need?.let { MetaStore.general()?.picks?.get(it) } ?: emptyList()) +
                (HeroDb.fallbackPicks[need] ?: emptyList())).distinct().filter { it !in taken }
        }
        // online meta (Groq): asked again whenever the line-ups change; its picks go first
        val online = onlineDraft
        if (online != null) {
            picks = (online.pick + picks).distinct().filter { it !in taken }
        }
        if (d.screen == Phase.DRAFT && Cfg.autoOnline && Cfg.onlineDraft && GroqClient.ready) {
            val key = "${d.stage}|${GameStateTracker.myTeam.sorted()}|${GameStateTracker.enemyTeam.sorted()}"
            if (key != onlineDraftKey) {
                onlineDraftKey = key
                val my = GameStateTracker.myTeam
                val en = GameStateTracker.enemyTeam
                AiRuntime.scope.launch {
                    MetaAdvisor.draft(d.stage, my, en, Cfg.draftSolo)?.let { onlineDraft = it }
                }
            }
        }
        val bans = ((online?.ban ?: emptyList()) + d.ban).distinct().filter { it !in taken }.take(3)
        val final = DraftAdvice(d.screen, d.stage, d.myTurn, GameStateTracker.myTeam, GameStateTracker.enemyTeam,
            d.myHero, bans, picks, d.tapX, d.tapY, d.reason)
        draft = final
        draftText = buildString {
            append("Screen: ").append(d.screen.name.lowercase()).append(" | stage: ").append(d.stage)
            if (d.myTurn) append(" | YOUR TURN")
            append("\nBAN: ").append(if (bans.isEmpty()) "n/a" else bans.joinToString())
            append("\nPICK: ").append(if (picks.isEmpty()) "n/a" else picks.joinToString())
            if (GameStateTracker.myTeam.isNotEmpty()) append("\nMy team: ").append(GameStateTracker.myTeam.joinToString())
            if (GameStateTracker.enemyTeam.isNotEmpty()) append("\nEnemy team: ").append(GameStateTracker.enemyTeam.joinToString())
            if (d.reason.isNotBlank()) append("\nWhy: ").append(d.reason)
            if (online != null) append("\nOnline meta: ").append(online.reason)
        }

        // Autonomous ranked drafting: both Drafter and Builder on + autonomous switch.
        // (Standard matches are handled by AiRuntime with calibrated taps.)
        if (Cfg.ranked && Cfg.autonomous && builderOn && d.screen == Phase.DRAFT && d.myTurn && d.tapX >= 0f &&
            (d.stage == "ban" || d.stage == "pick")
        ) {
            val key = "${d.stage}|${GameStateTracker.myTeam.size}|${GameStateTracker.enemyTeam.size}"
            val now = System.currentTimeMillis()
            if (key != lastDraftAction || now - lastDraftActionAt > 20_000) {
                lastDraftAction = key
                lastDraftActionAt = now
                val who = if (d.stage == "ban") bans.firstOrNull() else picks.firstOrNull()
                ActionExecutor.draftSelect(d.tapX, d.tapY, "${d.stage} ${who ?: "?"}")
            }
        }
    }

    // Hero select: the picked hero's name is written at the top; loading: my card is the one
    // with my player name. Also the battle spell. Works in standard and ranked matches.
    // all 10 heroes and their battle spells, from the loading screen or the scoreboard
    private suspend fun runLineup(phase: Phase) {
        val img = FrameHub.bitmap() ?: return
        // every card's player name is read: YOUR card is found by your player name (Player page), else
        // it is the first card of your team (v36.84)
        val prompt = com.sphynx.game.PlayerName.lineupPrompt(phase == Phase.LOADING)
        val reply = ask("line-up (${phase.label})", prompt, img, 0.0) ?: return
        val lu = com.sphynx.game.PlayerName.parseLineup(reply, phase == Phase.LOADING, green = GameStateTracker.loadingGreen)
        // (the practice gate no longer reads Gemma's answer - owner, 2026-11-12: its pixel check decides; Gemma
        // misread the Computer cards as "9 other cards are not Computer")
        if (lu == null) {
            // (the cards' places are random: without your name on the screen nothing is decided)
            Diag.info("Line-up (${phase.label}): " + (if (com.sphynx.game.PlayerName.get().isBlank())
                "your green name / player name was not found on it" else "your player name was not found"))
            // (read once per match: not again - owner, 2026-11-12)
            GameStateTracker.lineupReadAt = System.currentTimeMillis()
            return
        }
        val mine = lu.mine.filter { it.hero != null }; val theirs = lu.theirs.filter { it.hero != null }
        if (mine.isNotEmpty()) {
            GameStateTracker.myTeam = mine.map { it.hero!! }.distinct()
            GameStateTracker.allySpells = mine.mapNotNull { c -> c.spell?.let { c.hero!! to it } }.toMap()
            val myCardName = lu.me?.player?.trim()?.takeIf { it.isNotEmpty() } ?: com.sphynx.game.PlayerName.get().ifBlank { null }
            if (myCardName != null) {
                GameStateTracker.verifiedPlayerName = myCardName
                if (phase == Phase.LOADING) GameStateTracker.loadingCardName = myCardName
            }
            // me: on the LOADING screen (its hero name) and on the SCOREBOARD - each its own step of the priority
            lu.me?.hero?.let { me ->
                if (phase == Phase.LOADING) GameStateTracker.loadingHero = me
                else if (phase == Phase.SCOREBOARD) {
                    GameStateTracker.scoreboardHero = me
                    // my level as the scoreboard writes it corrects the counted one (v36.89)
                    lu.me.level.takeIf { it in 1..15 }?.let { com.sphynx.vision.LevelReader.correct(it) }
                }
                // shown on the floating panel (your word): what was read and that it matched
                val shownSp = GameStateTracker.spellDisplay() ?: lu.me.spell
                GameStateTracker.nameMatch = "✓ \"${lu.me.player}\" = your name (${com.sphynx.game.PlayerName.get()}) on the ${phase.label}: $me" +
                    (shownSp?.let { " · $it" } ?: "")
            }
            lu.me?.spell?.let { sp ->
                if (phase == Phase.LOADING) {
                    if (GameStateTracker.loadingSpell == null) GameStateTracker.gemmaLoadingSpell = sp
                } else if (GameStateTracker.gemmaSpell == null) {
                    GameStateTracker.gemmaSpell = sp
                }
            }
        }
        if (theirs.isNotEmpty()) {
            GameStateTracker.enemyTeam = theirs.map { it.hero!! }.distinct()
            GameStateTracker.enemySpells = theirs.mapNotNull { c -> c.spell?.let { c.hero!! to it } }.toMap()
        }
        GameStateTracker.lineupReadAt = System.currentTimeMillis()
        Diag.info("Line-up read (${phase.label}): me ${lu.me?.hero ?: "?"} (found by your name \"${lu.me?.player}\")" +
            "; my team ${mine.joinToString { it.hero!! }}; enemies ${theirs.joinToString { it.hero!! + (it.spell?.let { s -> " ($s)" } ?: "") }}")
    }

    private suspend fun runIdentify(phase: Phase) {
        if (!GemmaEngine.visionOk) return
        val img = FrameHub.bitmap() ?: return
        val name = com.sphynx.game.PlayerName.get()
        // (the cards are in random places: only the card with your name tells your hero - v36.85; or the
        // card whose name is GREEN - v36.92)
        val green = GameStateTracker.loadingGreen
        if (name.isBlank() && green == null) return
        val where = "This is the LOADING screen: the cards are in random places. My card is the one whose player name is written in GREEN" +
            (if (green != null) " (card ${green.second + 1} from the left on the ${if (green.first == 0) "TOP" else "BOTTOM"} row)" else "") +
            (if (name.isNotBlank()) ", my player name \"$name\"" else "") + "; " +
            "my hero's name is written on it above my player name (a skin name, if any, is above the hero name - do not use it). " +
            "If no card has my player name, answer unknown."
        val prompt = "Mobile Legends: Bang Bang screenshot. $where " +
            "Reply with ONLY one JSON object, no other words: {\"hero\":\"my hero's name or unknown\",\"spell\":\"my battle spell name or unknown\"}"
        val reply = ask("which hero did I pick", prompt, img) ?: return
        val hero = HeroDb.match(Regex("\"hero\"\\s*:\\s*\"([^\"]*)\"").find(reply)?.groupValues?.get(1))
        val spell = SpellDb.match(Regex("\"spell\"\\s*:\\s*\"([^\"]*)\"").find(reply)?.groupValues?.get(1))
        if (hero != null) {
            GameStateTracker.loadingHero = hero
            val cardName = name.ifBlank { "green name" }
            GameStateTracker.loadingCardName = cardName
            if (name.isNotBlank()) GameStateTracker.verifiedPlayerName = name
            GameStateTracker.nameMatch = "✓ your card ($cardName) on the loading screen: $hero"
            Diag.info("Loading screen: your hero is $hero (card with your name $cardName)")
        }
        if (spell != null) {
            if (hero != null && GameStateTracker.loadingSpell == null) GameStateTracker.gemmaLoadingSpell = spell
            else if (GameStateTracker.gemmaSpell == null) GameStateTracker.gemmaSpell = spell
        }
    }

    private suspend fun runBuildAdvice(hero: String) {
        val role = HeroDb.role(hero)
        val enemies = GameStateTracker.enemyTeam
        val prompt = "Mobile Legends: Bang Bang. My hero is $hero (${role.name.lowercase()}). " +
            (if (enemies.isNotEmpty()) "Enemy heroes: ${enemies.joinToString()}. " else "") +
            "Reply with ONLY one JSON object, no other words: " +
            "{\"core\":[\"6 items in buying order\"],\"situational\":[\"2 items against these enemies\"],\"emblem\":\"emblem and talent\",\"note\":\"max 12 words\"}"
        val reply = ask("build for $hero", prompt, null, 0.3) ?: return
        buildAiText = try {
            val o = JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1))
            fun arr(k: String): String {
                val a = o.optJSONArray(k) ?: return "-"
                return (0 until a.length()).joinToString { a.optString(it) }
            }
            "AI core: ${arr("core")}\nAI situational: ${arr("situational")}\nEmblem: ${o.optString("emblem", "-")}" +
                (o.optString("note", "").let { if (it.isBlank()) "" else "\nNote: $it" })
        } catch (e: Exception) {
            reply.take(400)
        }
    }

    private suspend fun runScreenRead() {
        if (!GemmaEngine.visionOk) return
        val img = FrameHub.bitmap() ?: return
        val prompt = "Mobile Legends in-game screenshot. Reply with ONLY one JSON object, no other words: " +
            "{\"hero\":\"name of the hero I control (green health bar near the centre) or unknown\"," +
            "\"spell\":\"the battle spell name written under the spell button (bottom middle, e.g. Retribution, Flicker)\"," +
            "\"items\":\"number of items I own 0-6, or -1\",\"gold\":\"my gold number, or -1\"}"
        val reply = ask("read items/gold", prompt, img) ?: return
        val r = DecisionParser.screenRead(reply) ?: return
        GameStateTracker.reportItemsGold(r.items, r.gold)
        if (r.spell != null && GameStateTracker.gemmaSpell == null) {
            GameStateTracker.gemmaSpell = r.spell
            Diag.info("Gemma read your battle spell: ${r.spell}")
        }
        if (r.hero != null && GameStateTracker.ownHero() == null) {
            GameStateTracker.guessedHero = r.hero
            Diag.info("Gemma recognised your hero: ${r.hero}")
        }
    }

    private fun topBand(b: android.graphics.Bitmap, fracH: Float = 0.28f): android.graphics.Bitmap = try {
        android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, (b.height * fracH).toInt().coerceIn(32, b.height))
    } catch (_: Throwable) { b }

    // the hero-select screen: ranked (a ban row) or standard (just picks)
    private suspend fun readMatchMode() {
        val img = FrameHub.bitmap()?.let { topBand(it, 0.30f) } ?: return
        val reply = ask("match mode", "Mobile Legends: Bang Bang hero-select screenshot. Is this a RANKED draft with a ban stage " +
            "(slots for banned heroes, a 'Ban' prompt) or a STANDARD / classic pick with no bans? Reply with ONLY one word: ranked or standard.", img, 0.0) ?: return
        val t = reply.lowercase()
        val mode = when { "ranked" in t -> "ranked"; "standard" in t || "classic" in t -> "standard"; else -> return }
        if (mode != Cfg.matchMode) { Cfg.matchMode = mode; Diag.info("Match mode detected: $mode") }
    }

    // the match timer of the live match (top centre, mm:ss) - and, once, WHICH hero is being watched
    private suspend fun readLiveClock() {
        val img = FrameHub.bitmap() ?: return
        val needHero = HeroDb.match(Cfg.liveHero) == null || GameStateTracker.liveClockAge() > 10 * 60_000L
        val reply = ask("live match clock", "Mobile Legends: Bang Bang screenshot of a live match. Read the match timer at the " +
            "top centre of the screen, between the two teams' kill counts. Reply with ONLY the time as mm:ss, for example 07:00." +
            (if (needHero) " Then, on a second line, the name of the hero the camera follows (in the centre of the screen; its " +
                "skills are shown at the bottom)." else ""), img, 0.0) ?: return
        // (a new live match: the skill icons are read again; once they show a hero it beats Gemma's)
        if (needHero) com.sphynx.vision.HeroVerifier.resetLive()
        if (needHero) reply.lines().drop(1).firstNotNullOfOrNull { HeroDb.match(it.trim()) }?.let { h ->
            if (com.sphynx.vision.HeroVerifier.liveSkillHero == null && !h.equals(Cfg.liveHero, true)) {
                Cfg.liveHero = h
                // its role: the usual lane of the hero (a Retribution hero is found jungling by its route)
                Cfg.liveLaneName = LaneLogic.effective(h, null).first.name.lowercase()
                Diag.info("Live match: watching $h (${Cfg.liveLaneName})")
            }
        }
        val m = Regex("(\\d{1,2})\\s*[:.]\\s*(\\d{2})").find(reply) ?: return
        val sec = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        if (sec in 0..3600 && m.groupValues[2].toInt() < 60) {
            GameStateTracker.setLiveClock(sec)
            Diag.info("Live match clock: ${m.value}")
        }
    }

    private suspend fun ask(what: String, prompt: String, img: android.graphics.Bitmap?, temp: Double = 0.2): String? {
        busyWith = what
        val askedAt = System.currentTimeMillis()
        return try {
            com.sphynx.core.SessionRecorder.snapBitmap("gemma-" + what.substringBefore(' '), img)
            GemmaEngine.ask(prompt, img, temp, purpose = what).also { reply ->
                // the recording keeps what was asked and answered (owner, 2026-11-12)
                com.sphynx.core.SessionRecorder.event(org.json.JSONObject().put("k", "gemma").put("what", what)
                    .put("ms", System.currentTimeMillis() - askedAt).put("prompt", prompt.take(1500)).put("reply", (reply ?: "").take(1500)))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            lastError = "$what: ${t.message}"
            Diag.error("Gemma $what failed", t)
            null
        } finally {
            busyWith = ""
        }
    }

    // Text for the Build page: primary build from your hero + counter items from the enemies
    fun buildSummary(): String {
        val hero = GameStateTracker.ownHero()
        val role = HeroDb.role(hero)
        val sb = StringBuilder()
        sb.append("Your hero: ").append(hero ?: "unknown").append(" (").append(GameStateTracker.ownHeroSource()).append(")")
        sb.append("\n\nPRIMARY build (").append(role.name.lowercase()).append("):\n")
        BuildBook.primary(role).forEachIndexed { i, it -> sb.append("${i + 1}. $it\n") }
        val counters = CounterRules.counters(hero, GameStateTracker.enemyTeam)
        sb.append("\nSECONDARY / counter items")
        if (GameStateTracker.enemyTeam.isEmpty()) {
            sb.append(": waiting for the enemy line-up (from the Drafter)\n")
        } else {
            sb.append(" vs ").append(GameStateTracker.enemyTeam.joinToString()).append(":\n")
            if (counters.isEmpty()) sb.append("none needed, keep the primary build\n")
            counters.forEach { sb.append("- ${it.item}: ${it.reason}\n") }
        }
        MetaStore.hero(hero)?.let { m ->
            sb.append("\nONLINE META (").append(m.patch).append(", ").append("%.0f".format(MetaStore.ageDays(m.updatedAt))).append(" days old):\n")
            m.build.forEachIndexed { i, it -> sb.append("${i + 1}. $it\n") }
            if (m.emblem.isNotBlank()) sb.append("Emblem: ").append(m.emblem).append('\n')
            if (m.spell != null) sb.append("Spell: ").append(m.spell).append('\n')
            if (m.counters.isNotEmpty()) sb.append("Countered by: ").append(m.counters.joinToString()).append('\n')
            if (m.changes.isNotBlank()) sb.append("Recent changes: ").append(m.changes).append('\n')
        }
        if (buildAiText.isNotBlank()) sb.append("\nGemma's suggestion:\n").append(buildAiText)
        return sb.toString().trim()
    }
}
