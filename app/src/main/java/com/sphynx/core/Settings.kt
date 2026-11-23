package com.sphynx.core

import android.content.Context
import android.content.SharedPreferences

// All saved settings live here. Every value has a safe default.
object Cfg {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            keepOldDefaults()
        }
    }

    /** Unit tests only: keep the settings in [p] (an in-memory store) instead of the phone's storage. */
    fun useForTests(p: SharedPreferences) { prefs = p }

    // ALL SWITCHES OFF BY DEFAULT (owner, 2026-11-12) - on a NEW install only. A phone that already ran the app
    // keeps how it behaved: every switch it never changed is saved once with its old default (on).
    // Safety guards keep their safe position everywhere: Dry run on, the Quick buy guards on.
    const val DEFAULTS_GEN = 2
    val OLD_ON_DEFAULTS = listOf(
        "gpu", "decision_log", "touches", "show_det", "player_qb", "coach", "auto_sit_combo", "auto_record",
        "auto_upload", "scenery_auto_sync", "std_hero", "online_meta", "online_auto", "online_draft", "collect_ds",
        "collect_sds", "live_learn", "policy_on", "smart_fights", "brain_you", "det_on", "builder_actives")

    // the first-run setup (owner, 2026-11-12): shown on a new install until "Finish setup"
    var firstRunPending: Boolean get() = s("first_run", "") == "pending"; set(v) = put("first_run", if (v) "pending" else "done")

    // what to save once (pure, unit tested): nothing on a new install or when already done
    fun oldDefaultsToKeep(saved: Set<String>): List<String> =
        if (saved.isEmpty() || "defaults_gen" in saved) emptyList() else OLD_ON_DEFAULTS.filter { it !in saved }

    private fun keepOldDefaults() {
        if (prefs.contains("defaults_gen")) return
        val e = prefs.edit()
        if (prefs.all.isEmpty()) e.putString("first_run", "pending")      // a new install: the first-run setup opens
        oldDefaultsToKeep(prefs.all.keys).forEach { e.putBoolean(it, true) }
        e.putInt("defaults_gen", DEFAULTS_GEN).apply()
    }

    private fun b(k: String, d: Boolean) = prefs.getBoolean(k, d)
    private fun i(k: String, d: Int) = prefs.getInt(k, d)
    private fun f(k: String, d: Float) = prefs.getFloat(k, d)
    private fun s(k: String, d: String) = prefs.getString(k, d) ?: d
    private fun put(k: String, v: Any) {
        val e = prefs.edit()
        when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Float -> e.putFloat(k, v)
            is String -> e.putString(k, v)
        }
        e.apply()
    }

    // ---- model ----
    // (ON also on a new install - owner, 2026-11-13: it is a speed setting, not a feature; on the CPU the line-up
    // read took 122 s and each strategy answer 12-17 s)
    var useGpu: Boolean get() = b("gpu", true); set(v) = put("gpu", v)
    // Phase 7 L8 (owner, 2026-11-23): the strategic model on the NPU, experimental, OFF by default (NPU, GPU, CPU)
    var useNpu: Boolean get() = b("npu", false); set(v) = put("npu", v)
    // owner, 2026-11-23: the minimap and screen detectors on the NPU, their own switch, experimental, OFF by default
    var detectorsNpu: Boolean get() = b("detectors_npu", false); set(v) = put("detectors_npu", v)
    // model speed recorder: LiteRT-LM's detailed figures (tokens per second); takes effect when the models load
    var modelSpeedDetailed: Boolean get() = b("model_speed_detailed", false); set(v) = put("model_speed_detailed", v)
    // the small tactical language model (second slot): off until you turn it on (Model page)
    var tacticalLlmOn: Boolean get() = b("tactical_llm", false); set(v) = put("tactical_llm", v)
    // Phase 8 G11 (owner, 2026-11-23): the tactical model also reads the minimap picture now and then; OFF on a new install
    var tacticalSightOn: Boolean get() = b("tactical_sight", false); set(v) = put("tactical_sight", v)
    // the fight-choice network (numeric, from the tactical-network notebook) advises in fights
    var fightNetOn: Boolean get() = b("fight_net", false); set(v) = put("fight_net", v)
    // logging mode: record every engine's advice next to what is played (never acts on it)
    var decisionLogOn: Boolean get() = b("decision_log", false); set(v) = put("decision_log", v)

    // ---- player ----
    var myHero: String get() = s("my_hero", ""); set(v) = put("my_hero", v)        // the last hero you played (automatic)
    var manualHero: String get() = s("manual_hero", ""); set(v) = put("manual_hero", v) // chosen by you: beats detection
    var showTouches: Boolean get() = b("touches", false); set(v) = put("touches", v)
    var showDetections: Boolean get() = b("show_det", false); set(v) = put("show_det", v)
    var strategyEverySec: Int get() = i("strat_sec", 8); set(v) = put("strat_sec", v)
    var strategyWithImage: Boolean get() = b("strat_img", false); set(v) = put("strat_img", v)
    var pushDir: String get() = s("push_dir", "up-right"); set(v) = put("push_dir", v)
    // (v36.13: these are always on / off - the Player page switches were removed)
    var spellIsDefensive: Boolean get() = false; set(v) {}
    // read skill availability from the buttons (dark + countdown = cooling down); on by default
    var readCooldownsFromScreen: Boolean get() = true; set(v) {}

    var autoUpgradeSkills: Boolean get() = true; set(v) {}
    // ult first, then the others in this order (comma separated: ult,s1,s2)
    var upgradeOrder: String get() = s("upgrade_order", "ult,s1,s2"); set(v) = put("upgrade_order", v)
    var useRecall: Boolean get() = true; set(v) {}
    var recallHpPct: Int get() = i("recall_hp", 35); set(v) = put("recall_hp", v)
    var useRegen: Boolean get() = true; set(v) {}
    var regenHpPct: Int get() = i("regen_hp", 60); set(v) = put("regen_hp", v)
    var jungleRoute: Boolean get() = true; set(v) {}
    var playerQuickBuy: Boolean get() = b("player_qb", false); set(v) = put("player_qb", v)
    // reserve the ultimate for enemy heroes: never on minions, jungle monsters, turtle or lord
    var noUltOnCreeps: Boolean get() = true; set(v) {}
    // Skill 1 / 2 go out as soon as they are ready while fighting creeps or heroes (target in reach)
    var skillsWhenReady: Boolean get() = true; set(v) {}
    var wallHop: Boolean get() = true; set(v) {}     // dash / Flicker over thin walls
    var useBushes: Boolean get() = true; set(v) {}      // hide and ambush in bushes
    var fastMode: Boolean get() = true; set(v) {}  // fastest decisions and combos
    var naturalMove: Boolean get() = false; set(v) {}
    var wallAvoid: Boolean get() = true; set(v) {}
    // coach: the AI plays, YOU can take over the joystick or skills at any moment; it steps back
    // while you touch, and learns your combos, when / how far you cast, and how you walk
    var coachMode: Boolean get() = b("coach", false); set(v) = put("coach", v)
    var smoothJoystick: Boolean get() = true; set(v) {}

    // ---- hero, spell, lane, combos, objectives ----
    // battle spell: "auto" = recognise it, or a spell name you chose
    var mySpell: String get() = s("my_spell", "auto"); set(v) = put("my_spell", v)
    // lane: auto | exp | gold | mid | roam | jungle
    var lane: String get() = s("lane", "auto"); set(v) = put("lane", v)
    var retriMeansJungle: Boolean get() = true; set(v) {}
    var laneTravelSec: Int get() = i("lane_travel", 12); set(v) = put("lane_travel", v)
    var useCombos: Boolean get() = true; set(v) {}
    // Automatic selection of skill combo based on the live combat situation (Burst, Chase, Poke, Pre-Lv4, Retreat) (v37.11)
    var autoSituationalCombo: Boolean get() = b("auto_sit_combo", false); set(v) = put("auto_sit_combo", v)
    var aimSkills: Boolean get() = true; set(v) {}
    var comboUrl: String get() = s("combo_url", "https://mobile-legends.fandom.com/wiki/{hero}"); set(v) = put("combo_url", v)
    private fun normHeroKey(hero: String): String = hero.trim().lowercase().filter { it.isLetterOrDigit() }

    // Manual skill combo entered by the user for a specific hero (v37.11).
    // If non-blank, the AI uses this manual combo; if blank/null, the AI uses the built-in optimized combo library.
    fun manualCombo(hero: String?): String? {
        if (hero.isNullOrBlank()) return null
        val k1 = "manual_combo_" + normHeroKey(hero)
        val k2 = "combo_" + hero.trim().lowercase()
        return prefs.getString(k1, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs.getString(k2, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setManualCombo(hero: String?, v: String?) {
        if (hero.isNullOrBlank()) return
        val k1 = "manual_combo_" + normHeroKey(hero)
        val k2 = "combo_" + hero.trim().lowercase()
        val clean = v?.trim().orEmpty()
        if (clean.isEmpty()) {
            prefs.edit().remove(k1).remove(k2).apply()
        } else {
            put(k1, clean)
            put(k2, clean)
        }
    }

    // the hero's recommended lanes as YOU set them (owner, 2026-11-11), e.g. "exp, jungle" or "exp, jungle, flex";
    // they come before the online guide and the wiki (game.HeroLanes). null = not set
    fun manualLanes(hero: String?): String? =
        if (hero.isNullOrBlank()) null else prefs.getString("manual_lanes_" + normHeroKey(hero), null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setManualLanes(hero: String?, v: String?) {
        if (hero.isNullOrBlank()) return
        val k = "manual_lanes_" + normHeroKey(hero)
        val clean = v?.trim().orEmpty()
        if (clean.isEmpty()) prefs.edit().remove(k).apply() else put(k, clean)
    }

    // start a session recording by itself at the start of every match (owner, 2026-11-12; can be switched off)
    var autoRecord: Boolean get() = b("auto_record", false); set(v) = put("auto_record", v)
    // Phase 5 (P5, owner 2026-11-21): under low memory or heat, "warn" (default) or "stepdown" (core/PressurePolicy)
    var pressureMode: String get() = s("pressure_mode", "warn"); set(v) = put("pressure_mode", v)
    // upload the debug data to GitHub by itself after each recording (needs github_token.txt; can be switched off)
    var autoUpload: Boolean get() = b("auto_upload", false); set(v) = put("auto_upload", v)

    fun heroCombo(hero: String): String? = manualCombo(hero)
    fun setHeroCombo(hero: String, v: String?) = setManualCombo(hero, v)

    // Scenery & Minimap synchronization calibration settings (v37.11)
    var sceneryAutoSync: Boolean get() = b("scenery_auto_sync", false); set(v) = put("scenery_auto_sync", v)
    var cameraHeightMode: String get() = s("cam_height_mode", "high"); set(v) = put("cam_height_mode", v) // "high", "low", "wide"
    var sceneryScaleX: Float get() = f("scenery_scale_x", 0.158f); set(v) = put("scenery_scale_x", v)
    var sceneryScaleY: Float get() = f("scenery_scale_y", 0.112f); set(v) = put("scenery_scale_y", v)
    var sceneryOffsetX: Float get() = f("scenery_off_x", 0f); set(v) = put("scenery_off_x", v)
    var sceneryOffsetY: Float get() = f("scenery_off_y", 0f); set(v) = put("scenery_off_y", v)
    var detectMonsters: Boolean get() = true; set(v) {}
    var takeObjectives: Boolean get() = true; set(v) {}
    // Retribution on a monster when its HP is below this (%), turtle / lord and small creeps
    var retriBossPct: Int get() = i("retri_boss", 12); set(v) = put("retri_boss", v)
    var retriCreepPct: Int get() = i("retri_creep", 20); set(v) = put("retri_creep", v)

    // ---- match type: "standard" (pick hero, spell, emblem) or "ranked" (ban & pick) ----
    var matchMode: String get() = s("match_mode", "standard"); set(v) = put("match_mode", v)
    val ranked: Boolean get() = matchMode == "ranked"
    var stdPickHero: Boolean get() = b("std_hero", false); set(v) = put("std_hero", v)
    var stdChangeSpell: Boolean get() = b("std_spell", false); set(v) = put("std_spell", v)
    var stdChangeEmblem: Boolean get() = b("std_emblem", false); set(v) = put("std_emblem", v)

    // ---- online meta (Groq API) ----
    var groqKey: String get() = s("groq_key", ""); set(v) = put("groq_key", v)
    var geminiKey: String get() = s("gemini_key", ""); set(v) = put("gemini_key", v)       // gemini_key.txt
    var cerebrasKey: String get() = s("cerebras_key", ""); set(v) = put("cerebras_key", v) // cerebras_key.txt
    // AUTOMATIC online requests (hero meta / skills / enemy skills / weekly patch meta / online draft):
    // OFF by default - online questions are then asked only when you press a button
    var autoOnline: Boolean get() = b("auto_online", false); set(v) = put("auto_online", v)
    var kaggleUser: String get() = s("kaggle_user", ""); set(v) = put("kaggle_user", v)
    var kaggleKey: String get() = s("kaggle_key", ""); set(v) = put("kaggle_key", v)
    // openai/gpt-oss-120b (default) and -20b search the web with Groq's browser search tool;
    // other models answer from memory only. An old saved "groq/compound" moves to the new default.
    var groqModel: String
        get() = s("groq_model", DEFAULT_GROQ_MODEL).let { if (it.trim() in OLD_GROQ_MODELS) DEFAULT_GROQ_MODEL else it }
        set(v) = put("groq_model", v)
    const val DEFAULT_GROQ_MODEL = "openai/gpt-oss-120b"
    private val OLD_GROQ_MODELS = setOf("groq/compound", "groq/compound-mini", "compound-beta", "compound-beta-mini", "")
    var useOnlineMeta: Boolean get() = b("online_meta", false); set(v) = put("online_meta", v)
    var onlineAuto: Boolean get() = b("online_auto", false); set(v) = put("online_auto", v)
    var onlineDraft: Boolean get() = b("online_draft", false); set(v) = put("online_draft", v)
    // web search costs Groq's free tier a lot (one search ~100-300K tokens; the daily limit is ~200K):
    // only the patch meta searches by default; heroes, skills and the draft answer from the model + the saved patch
    var heroWebSearch: Boolean get() = b("hero_web_search", false); set(v) = put("hero_web_search", v)
    var metaMaxAgeDays: Int get() = i("meta_age", 7); set(v) = put("meta_age", v)

    // ---- kills, towers, farming & meta rotations ----
    var executeHpPct: Int get() = i("exec_hp", 20); set(v) = put("exec_hp", v)       // chase enemies below this
    var towerGuard: Boolean get() = true; set(v) {}
    // Reduced default tower range from 8% to 5% of the minimap (v37.16) so heroes do not stop far outside turret range
    var towerRangePct: Int get() = i("tower_range_v2", 5); set(v) = put("tower_range_v2", v) // % of the minimap
    // Role rotation & pathing source (v37.16): "hybrid" (Built-In Pro Meta + Online Meta & Trend), "online" (Online Meta & Trend), "builtin" (Built-In Pro Meta)
    var rotationMetaMode: String get() = s("rotation_meta_mode", "hybrid"); set(v) = put("rotation_meta_mode", v)
    var useDash: Boolean get() = true; set(v) {}
    var lanersTakeCamps: Boolean get() = true; set(v) {}
    fun heroRange(hero: String): String = s("range_" + hero.lowercase(), "auto")     // auto | ranged | melee
    fun setHeroRange(hero: String, v: String) = put("range_" + hero.lowercase(), v)
    fun heroDash(hero: String): String = s("dash_" + hero.lowercase(), "auto")
    fun setHeroDash(hero: String, v: String) = put("dash_" + hero.lowercase(), v)

    // save minimap pictures with pre-drawn boxes to train the detector (Download/Sphynx-Storage/dataset)
    var collectDataset: Boolean get() = b("collect_ds", false); set(v) = put("collect_ds", v)          // minimap pictures
    var collectScreenDataset: Boolean get() = b("collect_sds", false); set(v) = put("collect_sds", v)  // whole-screen pictures

    // ---- learning from live matches (watching another player) ----
    var liveLearn: Boolean get() = b("live_learn", false); set(v) = put("live_learn", v)
    // the heading font (app.Fonts): "pixelify" or "silkscreen" - the owner's pick on the Setup page
    // the colour theme (app.Theme): "signal" (default), "arcane" or "ember" - the user's choice in Settings
    var theme: String get() = s("theme", "signal"); set(v) = put("theme", v)
    var headFont: String get() = s("head_font", "pixelify"); set(v) = put("head_font", v)
    var liveHero: String get() = s("live_hero", ""); set(v) = put("live_hero", v)
    // the watched player's role: exp | gold | mid | roam | jungle
    var liveLaneName: String get() = s("live_lane", "exp"); set(v) = put("live_lane", v)
    // (the Lane it names is the game's business: game.Lane.live(), ADR 0001)

    // ---- trained tactical network (policy.tflite) ----
    var policyOn: Boolean get() = b("policy_on", false); set(v) = put("policy_on", v)
    var policyMinConf: Float get() = f("policy_conf", 0.55f); set(v) = put("policy_conf", v)

    // ---- smart fights (the learning brain) ----
    var smartFights: Boolean get() = b("smart_fights", false); set(v) = put("smart_fights", v)
    var exploreLevel: Int get() = i("explore", 1); set(v) = put("explore", v)     // 0 low, 1 normal, 2 high
    var brainLearnsFromYou: Boolean get() = b("brain_you", false); set(v) = put("brain_you", v)

    // ---- learn from me (imitation) ----
    // learning from you is part of the coach mode (no separate switches any more)
    val learnFromMe: Boolean get() = true
    val playLikeMe: Boolean get() = coachMode
    var imitationMinDemos: Int get() = i("imit_min", 300); set(v) = put("imit_min", v)

    // ---- calibration ----
    var activePreset: String get() = s("active_preset", ""); set(v) = put("active_preset", v)

    // ---- vision tuning (percent of frame width / height) ----
    var heroBarMinWidthPct: Float get() = f("hero_bar_w", 4.5f); set(v) = put("hero_bar_w", v)

    // ---- optional trained detector (.tflite from the Colab notebook) ----
    var detectorOn: Boolean get() = b("det_on", false); set(v) = put("det_on", v)
    // the whole-screen detector's class names (screen_labels.txt)
    var screenLabels: String get() = s("screen_labels", "hero_ally,hero_enemy,hero_self,minion_ally,minion_enemy,lord,turtle,molten_fiend,thunder_fenrir,lava_golem,horned_lizard,fire_beetle,lithowanderer,crab,cyclone_eye,jungle_creep,tower_ally,tower_ally_plated,tower_enemy,tower_enemy_plated,base_ally,base_enemy,bush,wall"); set(v) = put("screen_labels", v)
    var detectorLabels: String get() = s("det_labels", "hero_icon,lord_icon,turrent_icon,turrets-heros,turtle_icon")
        set(v) = put("det_labels", v)
    var detectorMinScore: Float get() = f("det_score", 0.4f); set(v) = put("det_score", v)
    var detectorEveryMs: Int get() = i("det_ms", 300); set(v) = put("det_ms", v)
    var bodyBelowBarPct: Float get() = f("body_below", 7f); set(v) = put("body_below", v)

    // ---- drafter / builder ----
    var draftSolo: Boolean get() = b("draft_solo", false); set(v) = put("draft_solo", v)
    var draftEverySec: Int get() = i("draft_sec", 12); set(v) = put("draft_sec", v)
    var buildEverySec: Int get() = i("build_sec", 30); set(v) = put("build_sec", v)
    var autonomous: Boolean get() = b("auto", false); set(v) = put("auto", v)
    var dryRun: Boolean get() = b("dry_run", true); set(v) = put("dry_run", v)

    // ---- auto quick buy (item swap when about to die) ----
    var qbOn: Boolean get() = b("qb_on", false); set(v) = put("qb_on", v)
    // Builder presses item actives (Frozen, Wind Chant, Conceal) in every match the Player does not play
    var builderActives: Boolean get() = b("builder_actives", false); set(v) = put("builder_actives", v)
    var qbRequireFull: Boolean get() = b("qb_full", true); set(v) = put("qb_full", v)
    var qbRequireGold: Boolean get() = b("qb_gold", true); set(v) = put("qb_gold", v)
    var qbHpPercent: Int get() = i("qb_hp", 25); set(v) = put("qb_hp", v)
    var qbMinGold: Int get() = i("qb_mingold", 3000); set(v) = put("qb_mingold", v)

    // ---- editable role builds (comma separated item names) ----
    fun roleBuild(role: String, default: String): String = s("build_$role", default)
    fun setRoleBuild(role: String, v: String) = put("build_$role", v)

    // ---- learning ----
    fun learnedBias(hero: String): Float = f("bias_" + hero.lowercase(), 0f)
    fun setLearnedBias(hero: String, v: Float) = put("bias_" + hero.lowercase(), v)

    // ---- generic string store (calibration) ----
    fun raw(k: String): String? = prefs.getString(k, null)
    fun setRaw(k: String, v: String?) {
        if (v == null) prefs.edit().remove(k).apply() else put(k, v)
    }
    /** Like setRaw, but on disk before it returns (Phase 6 load sentinel: a crash right after must not lose it). */
    fun setRawNow(k: String, v: String?) {
        if (v == null) prefs.edit().remove(k).commit() else prefs.edit().putString(k, v).commit()
    }

    // ---- for the learned-data backup (OnlineBackup) ----
    fun all(): Map<String, *> = prefs.all
    fun has(k: String) = prefs.contains(k)
    fun putAny(k: String, v: Any) = put(k, v)
    fun putLong(k: String, v: Long) = prefs.edit().putLong(k, v).apply()
    // the listener is kept here (SharedPreferences holds it weakly)
    private var watcher: SharedPreferences.OnSharedPreferenceChangeListener? = null
    fun watch(onChange: (String) -> Unit) {
        watcher?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        val w = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k != null) onChange(k) }
        watcher = w
        prefs.registerOnSharedPreferenceChangeListener(w)
    }
}
