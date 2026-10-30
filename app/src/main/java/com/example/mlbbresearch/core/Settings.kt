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
    var readCooldownsFromScreen: Boolean get() = b("cd_vision", false); set(v) = put("cd_vision", v)

    var autoUpgradeSkills: Boolean get() = b("auto_upgrade", true); set(v) = put("auto_upgrade", v)
    // ult first, then the others in this order (comma separated: ult,s1,s2)
    var upgradeOrder: String get() = s("upgrade_order", "ult,s1,s2"); set(v) = put("upgrade_order", v)
    var playerQuickBuy: Boolean get() = b("player_qb", true); set(v) = put("player_qb", v)
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

    // ---- learn from me (imitation) ----
    var learnFromMe: Boolean get() = b("learn_me", false); set(v) = put("learn_me", v)
    var playLikeMe: Boolean get() = b("play_like_me", false); set(v) = put("play_like_me", v)
    var imitationMinDemos: Int get() = i("imit_min", 300); set(v) = put("imit_min", v)

    // ---- calibration ----
    var activePreset: String get() = s("active_preset", ""); set(v) = put("active_preset", v)

    // ---- vision tuning (percent of frame width / height) ----
    var heroBarMinWidthPct: Float get() = f("hero_bar_w", 4.5f); set(v) = put("hero_bar_w", v)
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
