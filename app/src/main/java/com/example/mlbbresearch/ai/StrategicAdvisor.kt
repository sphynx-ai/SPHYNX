package com.example.mlbbresearch.ai

import com.example.mlbbresearch.capture.FrameHub
import com.example.mlbbresearch.controller.ActionExecutor
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.game.BuildBook
import com.example.mlbbresearch.game.CounterRules
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.GameStateTracker
import com.example.mlbbresearch.game.HeroDb
import com.example.mlbbresearch.game.Phase
import com.example.mlbbresearch.game.Role
import com.example.mlbbresearch.game.SpellDb
import com.example.mlbbresearch.vision.ObjectDetector
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
    private var lastEnemyCount = 0
    private var lastHpBand = 2
    private var buildAdviceKey = ""
    private var lastDraftScreenAt = 0L
    private var lastDraftAction = ""
    private var lastDraftActionAt = 0L

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
        val inGame = s.phase.inMatch

        // 1. Draft (outside the match)
        if (drafterOn && !inGame && now - lastDraft >= Cfg.draftEverySec * 1000L) {
            lastDraft = now
            runDraft(builderOn)
            return
        }
        // 1b. Which hero / battle spell did I pick? (hero select and loading screens)
        val pickPhase = s.phase == Phase.DRAFT || s.phase == Phase.LOADING
        if ((playerOn || builderOn) && pickPhase && GameStateTracker.draftedHero == null && now - lastIdentify >= 6000) {
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
        // 3. Builder / hero ID: read items, gold and your hero from the screen
        val needHero = (GameStateTracker.ownHero() == null || GameStateTracker.spell() == null) && (playerOn || builderOn)
        val every = if (builderOn) Cfg.buildEverySec * 1000L else 15_000L
        if (inGame && (builderOn || needHero) && now - lastBuildRead >= every) {
            lastBuildRead = now
            runScreenRead()
            return
        }
        // 4. Player strategy: on a timer, or right away when the fight changes
        if (playerOn && s.phase == Phase.IN_GAME) {
            val enemies = s.enemies.size
            val band = when { s.selfHp < 0.25f -> 0; s.selfHp < 0.5f -> 1; else -> 2 }
            val event = kotlin.math.abs(enemies - lastEnemyCount) >= 2 || band < lastHpBand ||
                (enemies >= 2 && lastEnemyCount < 2)
            lastEnemyCount = enemies
            lastHpBand = band
            val due = now - lastStrategy >= Cfg.strategyEverySec * 1000L
            if ((due || event) && now - lastStrategy >= 3000) {
                lastStrategy = now
                runStrategy(s, if (event) "fight changed" else "timer")
            }
        }
    }

    private suspend fun runStrategy(s: GameState, why: String) {
        val hero = s.ownHero ?: "unknown hero"
        val role = HeroDb.role(s.ownHero).name.lowercase()
        val prompt = "You are the strategic brain of a Mobile Legends bot playing $hero ($role). " +
            "Situation: ${s.summary()}${ObjectDetector.summary().let { if (it.isEmpty()) "" else "; minimap $it" }}. " +
            "Reply with ONLY one JSON object, no other words: " +
            "{\"plan\":\"farm|push|fight|retreat|group|defend\",\"aggression\":0.5,\"focus\":\"weakest|nearest\",\"note\":\"max 8 words\"}"
        val reply = ask("strategy ($why)", prompt, if (Cfg.strategyWithImage) FrameHub.bitmap() else null) ?: return
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
        val prompt = "This is a Mobile Legends: Bang Bang screenshot. $type$mode " +
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
        if (d.myHero != null) GameStateTracker.draftedHero = d.myHero

        // never suggest a hero that is already taken
        val taken = (GameStateTracker.myTeam + GameStateTracker.enemyTeam).toSet()
        var picks = d.pick.filter { it !in taken }
        if (picks.isEmpty() && d.screen == Phase.DRAFT) {
            val need = Role.values().firstOrNull { r -> r != Role.UNKNOWN && GameStateTracker.myTeam.none { HeroDb.role(it) == r } }
            picks = (HeroDb.fallbackPicks[need] ?: emptyList()).filter { it !in taken }
        }
        val bans = d.ban.filter { it !in taken }
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
    private suspend fun runIdentify(phase: Phase) {
        if (!GemmaEngine.visionOk) return
        val img = FrameHub.bitmap() ?: return
        val where = if (phase == Phase.DRAFT) "This is the hero select screen. The hero I selected has its name written in big letters at the top."
                    else "This is the loading screen. My card is the first one on the top row, the one with my player name."
        val prompt = "Mobile Legends: Bang Bang screenshot. $where " +
            "Reply with ONLY one JSON object, no other words: {\"hero\":\"my hero's name or unknown\",\"spell\":\"my battle spell name or unknown\"}"
        val reply = ask("which hero did I pick", prompt, img) ?: return
        val hero = HeroDb.match(Regex("\"hero\"\\s*:\\s*\"([^\"]*)\"").find(reply)?.groupValues?.get(1))
        val spell = SpellDb.match(Regex("\"spell\"\\s*:\\s*\"([^\"]*)\"").find(reply)?.groupValues?.get(1))
        if (hero != null) {
            GameStateTracker.draftedHero = hero
            Diag.info("Gemma read your picked hero: $hero")
        }
        if (spell != null) GameStateTracker.gemmaSpell = spell
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

    private suspend fun ask(what: String, prompt: String, img: android.graphics.Bitmap?, temp: Double = 0.2): String? {
        busyWith = what
        return try {
            GemmaEngine.ask(prompt, img, temp)
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
        if (buildAiText.isNotBlank()) sb.append("\nGemma's suggestion:\n").append(buildAiText)
        return sb.toString().trim()
    }
}
