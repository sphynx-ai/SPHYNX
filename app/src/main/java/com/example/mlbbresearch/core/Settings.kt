package com.example.mlbbresearch.core

import android.content.Context
import android.content.SharedPreferences

// All saved settings live here. Every value has a safe default.
object Cfg {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        }
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
    var useGpu: Boolean get() = b("gpu", true); set(v) = put("gpu", v)

    // ---- player ----
    var myHero: String get() = s("my_hero", ""); set(v) = put("my_hero", v)
    var showTouches: Boolean get() = b("touches", true); set(v) = put("touches", v)
    var showDetections: Boolean get() = b("show_det", true); set(v) = put("show_det", v)
    var strategyEverySec: Int get() = i("strat_sec", 8); set(v) = put("strat_sec", v)
    var strategyWithImage: Boolean get() = b("strat_img", false); set(v) = put("strat_img", v)
    var pushDir: String get() = s("push_dir", "up-right"); set(v) = put("push_dir", v)
    var spellIsDefensive: Boolean get() = b("spell_def", true); set(v) = put("spell_def", v)
    // read skill availability from the buttons (dark + countdown = cooling down); on by default
    var readCooldownsFromScreen: Boolean get() = b("cd_vision2", true); set(v) = put("cd_vision2", v)

    var autoUpgradeSkills: Boolean get() = b("auto_upgrade", true); set(v) = put("auto_upgrade", v)
    // ult first, then the others in this order (comma separated: ult,s1,s2)
    var upgradeOrder: String get() = s("upgrade_order", "ult,s1,s2"); set(v) = put("upgrade_order", v)
    var useRecall: Boolean get() = b("use_recall", true); set(v) = put("use_recall", v)
    var recallHpPct: Int get() = i("recall_hp", 35); set(v) = put("recall_hp", v)
    var useRegen: Boolean get() = b("use_regen", true); set(v) = put("use_regen", v)
    var regenHpPct: Int get() = i("regen_hp", 60); set(v) = put("regen_hp", v)
    var jungleRoute: Boolean get() = b("jungle_route", true); set(v) = put("jungle_route", v)
    var playerQuickBuy: Boolean get() = b("player_qb", true); set(v) = put("player_qb", v)
    // reserve the ultimate for enemy heroes: never on minions, jungle monsters, turtle or lord
    var noUltOnCreeps: Boolean get() = b("no_ult_creeps", true); set(v) = put("no_ult_creeps", v)
    // Skill 1 / 2 go out as soon as they are ready while fighting creeps or heroes (target in reach)
    var skillsWhenReady: Boolean get() = b("skills_ready", true); set(v) = put("skills_ready", v)
    var wallHop: Boolean get() = b("wall_hop", true); set(v) = put("wall_hop", v)     // dash / Flicker over thin walls
    var useBushes: Boolean get() = b("bushes", true); set(v) = put("bushes", v)      // hide and ambush in bushes
    var fastMode: Boolean get() = b("fast_mode", true); set(v) = put("fast_mode", v)  // fastest decisions and combos
    var naturalMove: Boolean get() = b("natural_move", true); set(v) = put("natural_move", v)
    var wallAvoid: Boolean get() = b("wall_avoid", true); set(v) = put("wall_avoid", v)
    // coach: the AI plays, YOU can take over the joystick or skills at any moment; it steps back
    // while you touch, and learns your combos, when / how far you cast, and how you walk
    var coachMode: Boolean get() = b("coach", true); set(v) = put("coach", v)
    var smoothJoystick: Boolean get() = b("smooth_joy", true); set(v) = put("smooth_joy", v)

    // ---- hero, spell, lane, combos, objectives ----
    // battle spell: "auto" = recognise it, or a spell name you chose
    var mySpell: String get() = s("my_spell", "auto"); set(v) = put("my_spell", v)
    // lane: auto | exp | gold | mid | roam | jungle
    var lane: String get() = s("lane", "auto"); set(v) = put("lane", v)
    var retriMeansJungle: Boolean get() = b("retri_jungle", true); set(v) = put("retri_jungle", v)
    var laneTravelSec: Int get() = i("lane_travel", 12); set(v) = put("lane_travel", v)
    var useCombos: Boolean get() = b("combos", true); set(v) = put("combos", v)
    var aimSkills: Boolean get() = b("aim_skills", true); set(v) = put("aim_skills", v)
    var comboUrl: String get() = s("combo_url", "https://mobile-legends.fandom.com/wiki/{hero}"); set(v) = put("combo_url", v)
    fun heroCombo(hero: String): String? = prefs.getString("combo_" + hero.lowercase(), null)
    fun setHeroCombo(hero: String, v: String?) {
        if (v == null) prefs.edit().remove("combo_" + hero.lowercase()).apply() else put("combo_" + hero.lowercase(), v)
    }
    var detectMonsters: Boolean get() = b("monsters", true); set(v) = put("monsters", v)
    var takeObjectives: Boolean get() = b("objectives", true); set(v) = put("objectives", v)
    // Retribution on a monster when its HP is below this (%), turtle / lord and small creeps
    var retriBossPct: Int get() = i("retri_boss", 12); set(v) = put("retri_boss", v)
    var retriCreepPct: Int get() = i("retri_creep", 35); set(v) = put("retri_creep", v)

    // ---- match type: "standard" (pick hero, spell, emblem) or "ranked" (ban & pick) ----
    var matchMode: String get() = s("match_mode", "standard"); set(v) = put("match_mode", v)
    val ranked: Boolean get() = matchMode == "ranked"
    var stdPickHero: Boolean get() = b("std_hero", true); set(v) = put("std_hero", v)
    var stdChangeSpell: Boolean get() = b("std_spell", false); set(v) = put("std_spell", v)
    var stdChangeEmblem: Boolean get() = b("std_emblem", false); set(v) = put("std_emblem", v)

    // ---- online meta (Groq API) ----
    var groqKey: String get() = s("groq_key", ""); set(v) = put("groq_key", v)
    // openai/gpt-oss-120b (default) and -20b search the web with Groq's browser search tool;
    // other models answer from memory only. An old saved "groq/compound" moves to the new default.
    var groqModel: String
        get() = s("groq_model", DEFAULT_GROQ_MODEL).let { if (it.trim() in OLD_GROQ_MODELS) DEFAULT_GROQ_MODEL else it }
        set(v) = put("groq_model", v)
    const val DEFAULT_GROQ_MODEL = "openai/gpt-oss-120b"
    private val OLD_GROQ_MODELS = setOf("groq/compound", "groq/compound-mini", "compound-beta", "compound-beta-mini", "")
    var useOnlineMeta: Boolean get() = b("online_meta", true); set(v) = put("online_meta", v)
    var onlineAuto: Boolean get() = b("online_auto", true); set(v) = put("online_auto", v)
    var onlineDraft: Boolean get() = b("online_draft", true); set(v) = put("online_draft", v)
    var metaMaxAgeDays: Int get() = i("meta_age", 7); set(v) = put("meta_age", v)

    // ---- kills, towers, farming ----
    var executeHpPct: Int get() = i("exec_hp", 20); set(v) = put("exec_hp", v)       // chase enemies below this
    var towerGuard: Boolean get() = b("tower_guard", true); set(v) = put("tower_guard", v)
    var towerRangePct: Int get() = i("tower_range", 8); set(v) = put("tower_range", v) // % of the minimap
    var useDash: Boolean get() = b("use_dash", true); set(v) = put("use_dash", v)
    var lanersTakeCamps: Boolean get() = b("laner_camps", true); set(v) = put("laner_camps", v)
    fun heroRange(hero: String): String = s("range_" + hero.lowercase(), "auto")     // auto | ranged | melee
    fun setHeroRange(hero: String, v: String) = put("range_" + hero.lowercase(), v)
    fun heroDash(hero: String): String = s("dash_" + hero.lowercase(), "auto")
    fun setHeroDash(hero: String, v: String) = put("dash_" + hero.lowercase(), v)

    // save minimap pictures with pre-drawn boxes to train the detector (Download/MLBB-Research/dataset)
    var collectDataset: Boolean get() = b("collect_ds", true); set(v) = put("collect_ds", v)

    // ---- learning from live matches (watching another player) ----
    var liveLearn: Boolean get() = b("live_learn", true); set(v) = put("live_learn", v)
    var liveHero: String get() = s("live_hero", ""); set(v) = put("live_hero", v)
    // the watched player's role: exp | gold | mid | roam | jungle
    var liveLaneName: String get() = s("live_lane", "exp"); set(v) = put("live_lane", v)
    val liveLane: com.example.mlbbresearch.game.Lane get() = when (liveLaneName) {
        "gold" -> com.example.mlbbresearch.game.Lane.GOLD; "mid" -> com.example.mlbbresearch.game.Lane.MID
        "roam" -> com.example.mlbbresearch.game.Lane.ROAM; "jungle" -> com.example.mlbbresearch.game.Lane.JUNGLE
        else -> com.example.mlbbresearch.game.Lane.EXP
    }

    // ---- trained tactical network (policy.tflite) ----
    var policyOn: Boolean get() = b("policy_on", true); set(v) = put("policy_on", v)
    var policyMinConf: Float get() = f("policy_conf", 0.55f); set(v) = put("policy_conf", v)

    // ---- smart fights (the learning brain) ----
    var smartFights: Boolean get() = b("smart_fights", true); set(v) = put("smart_fights", v)
    var exploreLevel: Int get() = i("explore", 1); set(v) = put("explore", v)     // 0 low, 1 normal, 2 high
    var brainLearnsFromYou: Boolean get() = b("brain_you", true); set(v) = put("brain_you", v)

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
    var detectorOn: Boolean get() = b("det_on", true); set(v) = put("det_on", v)
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
}
