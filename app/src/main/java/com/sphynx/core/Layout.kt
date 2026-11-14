package com.sphynx.core

import org.json.JSONObject
import java.util.Locale

// Screen positions of MLBB's buttons, as fractions of the screen (landscape).
// CALIBRATE them on the Setup page: drag the markers on the game ("Calibrate on the game"),
// let the app find them ("Auto-detect"), or type the numbers. Whole layouts can be saved
// as named presets (one per phone / per custom MLBB button layout).
object Layout {

    // group: "game" = in-match HUD, "shop" = equipment screen, "select" = hero select / draft
    class Control(val key: String, val label: String, val x: Float, val y: Float, val group: String = "game")

    // Defaults measured on a 20:9 phone with MLBB's standard button layout
    // Separate calibration groups for 2-skill ("skills2"), 3-skill ("skills3"), and 4-skill ("skills4") buttons (v37.01)
    val defaults: List<Control> = listOf(
        // ---- 2-skill buttons ("skills2") ----
        Control("s1of2", "2-skill hero: Skill 1 (Hirara: Mobility)", 0.724f, 0.802f, "skills2"),
        Control("s2of2", "2-skill hero: Skill 2 (Hirara: AOE)", 0.8275f, 0.594f, "skills2"),
        Control("s1Up2", "2-skill hero: Skill 1 upgrade (+)", 0.68f, 0.71f, "skills2"),
        Control("s2Up2", "2-skill hero: Skill 2 upgrade (+)", 0.785f, 0.493f, "skills2"),
        // ---- 3-skill buttons ("skills3") ----
        Control("s1", "3-skill hero: Skill 1", 0.711f, 0.888f, "skills3"),
        Control("s2", "3-skill hero: Skill 2", 0.7575f, 0.694f, "skills3"),
        Control("ult", "3-skill hero: Ultimate", 0.8415f, 0.589f, "skills3"),
        Control("s1Up", "3-skill hero: Skill 1 upgrade (+)", 0.669f, 0.774f, "skills3"),
        Control("s2Up", "3-skill hero: Skill 2 upgrade (+)", 0.7166f, 0.594f, "skills3"),
        Control("ultUp", "3-skill hero: Ultimate upgrade (+)", 0.7985f, 0.491f, "skills3"),
        // ---- 4-skill buttons ("skills4") ----
        Control("s1of4", "4-skill hero: Skill 1", 0.711f, 0.888f, "skills4"),
        Control("s2of4", "4-skill hero: Skill 2", 0.7375f, 0.744f, "skills4"),
        Control("s3", "4-skill hero: Skill 3", 0.7875f, 0.639f, "skills4"),
        Control("ultOf4", "4-skill hero: Ultimate", 0.8525f, 0.572f, "skills4"),
        Control("s1Up4", "4-skill hero: Skill 1 upgrade (+)", 0.677f, 0.814f, "skills4"),
        Control("s2Up4", "4-skill hero: Skill 2 upgrade (+)", 0.7027f, 0.671f, "skills4"),
        Control("s3Up4", "4-skill hero: Skill 3 upgrade (+)", 0.7525f, 0.5623f, "skills4"),
        Control("ultUp4", "4-skill hero: Ultimate upgrade (+)", 0.818f, 0.4954f, "skills4"),
        // ---- In-game HUD ("game") ----
        Control("joystick", "Joystick centre", 0.20f, 0.78f, "game"),
        Control("attack", "Basic attack", 0.83f, 0.84f, "game"),
        // the LARGE HUD (bigger buttons and Flicker; your Masha / Pharsa screenshots, v36.61): its own
        // places, used whenever the screen shows it (ScreenKit)
        Control("Lattack", "Large HUD: Basic attack", 0.848f, 0.76f, "large"),
        Control("Lspell", "Large HUD: Battle spell", 0.622f, 0.808f, "large"),
        Control("Lrecall", "Large HUD: Recall", 0.477f, 0.897f, "large"),
        Control("Lregen", "Large HUD: Regen", 0.551f, 0.919f, "large"),
        Control("L3s1", "Large HUD, 3 skills: Skill 1", 0.713f, 0.744f, "large"),
        Control("L3s2", "Large HUD, 3 skills: Skill 2", 0.784f, 0.56f, "large"),
        Control("L3ult", "Large HUD, 3 skills: Ultimate", 0.862f, 0.522f, "large"),
        Control("L4s1", "Large HUD, 4 skills: Skill 1", 0.707f, 0.833f, "large"),
        Control("L4s2", "Large HUD, 4 skills: Skill 2", 0.744f, 0.666f, "large"),
        Control("L4s3", "Large HUD, 4 skills: Skill 3", 0.794f, 0.582f, "large"),
        Control("L4ult", "Large HUD, 4 skills: Ultimate", 0.856f, 0.493f, "large"),
        Control("spell", "Battle spell", 0.634f, 0.89f),
        Control("recall", "Recall", 0.51f, 0.89f),
        Control("regen", "Regen", 0.57f, 0.89f),
        Control("quickBuy", "Quick-buy item (top right)", 0.873f, 0.155f),
        // (measured on your screenshot: Winter Crown's "Frozen" button; v36.57 - was 0.93, 0.31)
        Control("activeItem", "Active item button (Frozen / Wind Chant / Conceal)", 0.824f, 0.383f),
        Control("kdaK", "K/D/A: kills number", 0.782f, 0.042f),
        Control("kdaD", "K/D/A: deaths number", 0.8165f, 0.042f),
        Control("kdaA", "K/D/A: assists number", 0.851f, 0.042f),
        Control("mapTL", "Minimap: top-left corner of the map", 0.04f, 0.0f),
        Control("mapBR", "Minimap: bottom-right corner of the map", 0.19f, 0.322f),
        Control("shopOpen", "Shop button", 0.18f, 0.29f),
        Control("shopClose", "Shop close (X)", 0.86f, 0.105f, "shop"),
        Control("shopBuy", "Shop: Purchase", 0.815f, 0.89f, "shop"),
        Control("shopSell", "Shop: Sell", 0.815f, 0.80f, "shop"),
        Control("shopLastSlot", "Shop: last owned slot", 0.47f, 0.89f, "shop"),
        Control("itemImmortality", "Shop list: Immortality", 0.50f, 0.30f, "shop"),
        Control("itemWinterCrown", "Shop list: Winter Crown", 0.50f, 0.42f, "shop"),
        Control("itemRoseGold", "Shop list: Rose Gold Meteor", 0.50f, 0.54f, "shop"),
        Control("itemGuardian", "Shop list: Guardian Helmet", 0.50f, 0.66f, "shop"),
        Control("lockIn", "Hero select: Confirm / lock-in", 0.87f, 0.95f, "select"),
        Control("stdHero", "Standard: your hero's portrait", 0.835f, 0.18f, "select"),
        Control("stdSpellBtn", "Standard: battle spell button", 0.15f, 0.20f, "select"),
        Control("stdSpellPick", "Standard: your battle spell in the list", 0.50f, 0.50f, "select"),
        Control("stdEmblemBtn", "Standard: emblem button", 0.22f, 0.92f, "select"),
        Control("stdEmblemPick", "Standard: your emblem set in the list", 0.50f, 0.50f, "select"),
        Control("stdClose", "Standard: close the spell / emblem list", 0.95f, 0.07f, "select")
    )

    private val byKey = defaults.associateBy { it.key }

    // Joystick drag radius as a fraction of screen width
    const val JOYSTICK_RADIUS = 0.07f

    // how many active skills the hero being played has (HeroKit, set every frame): the skill keys
    // s1 / s2 / ult then mean that kit's buttons
    @Volatile var kitSize = 3
    // the large HUD is showing (ScreenKit reads it from the screen)
    @Volatile var largeHud = false
    private fun kitKey(key: String): String = if (largeHud) when (key) {
        "attack" -> "Lattack"; "spell" -> "Lspell"; "recall" -> "Lrecall"; "regen" -> "Lregen"
        "s1", "s2", "s3", "ult" -> when (kitSize) { 3 -> "L3$key"; 4 -> "L4$key"; else -> smallKit(key) }
        else -> smallKit(key)
    } else smallKit(key)
    private fun smallKit(key: String): String = when (kitSize) {
        4 -> when (key) { "s1" -> "s1of4"; "s2" -> "s2of4"; "ult" -> "ultOf4"; "s1Up" -> "s1Up4"; "s2Up" -> "s2Up4"; "s3Up" -> "s3Up4"; "ultUp" -> "ultUp4"; else -> key }
        2 -> when (key) { "s1" -> "s1of2"; "s2" -> "s2of2"; "s1Up" -> "s1Up2"; "s2Up" -> "s2Up2"; else -> key }
        else -> key
    }

    fun get(key: String): Pair<Float, Float> = point(kitKey(key))

    // Upgrade "+" button position for a skill slot ("s1", "s2", "s3", "ult"):
    // Uses explicit calibration if saved; on Large HUD (when not manually calibrated) offsets from the Large HUD skill button.
    fun upgradePoint(slot: String): Pair<Float, Float> {
        val upKey = smallKit(slot + "Up")
        if (isCalibrated(upKey)) return point(upKey)
        if (largeHud && (kitSize == 3 || kitSize == 4)) {
            val (sx, sy) = get(slot)
            val dx = if (kitSize == 4) -0.035f else -0.042f
            val dy = if (kitSize == 4) -0.076f else -0.098f
            return (sx + dx).coerceIn(0.05f, 0.95f) to (sy + dy).coerceIn(0.05f, 0.95f)
        }
        return point(upKey)
    }

    // the stored / default place of exactly this control (calibration shows and moves these)
    fun point(key: String): Pair<Float, Float> {
        val saved = Cfg.raw("cal_$key") ?: if (key == "s1of4") Cfg.raw("cal_s1") else null
        if (saved != null) {
            val p = saved.split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (p.size == 2) return (p[0] / 100f).coerceIn(0f, 1f) to (p[1] / 100f).coerceIn(0f, 1f)
        }
        val d = byKey[key] ?: return 0.5f to 0.5f
        return d.x to d.y
    }

    fun isCalibrated(key: String) = Cfg.raw("cal_$key") != null

    // value as "x,y" in percent
    fun text(key: String): String {
        val (x, y) = point(key)
        return "%.1f,%.1f".format(Locale.US, x * 100f, y * 100f)
    }

    fun set(key: String, percentText: String): Boolean {
        val p = percentText.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (p.size != 2) return false
        Cfg.setRaw("cal_$key", "${p[0]},${p[1]}")
        return true
    }

    // x, y as fractions (used by drag calibration and auto-detect)
    fun setPoint(key: String, x: Float, y: Float) =
        Cfg.setRaw("cal_$key", "%.2f,%.2f".format(Locale.US, x.coerceIn(0f, 1f) * 100f, y.coerceIn(0f, 1f) * 100f))

    fun reset(key: String) = Cfg.setRaw("cal_$key", null)

    // Separate save for a specific calibration group ("skills2", "skills3", "skills4", "game", "large", "shop", "select")
    fun saveGroup(group: String, pos: Map<String, FloatArray>? = null) {
        val controls = defaults.filter { it.group == group }
        val o = JSONObject()
        for (c in controls) {
            val p = pos?.get(c.key)
            if (p != null && p.size == 2) {
                setPoint(c.key, p[0], p[1])
            } else {
                val (x, y) = point(c.key)
                setPoint(c.key, x, y)
            }
            o.put(c.key, text(c.key))
        }
        Cfg.setRaw("cal_group_$group", o.toString())
        Cfg.setRaw("cal_group_at_$group", System.currentTimeMillis().toString())
    }

    fun isGroupSaved(group: String): Boolean =
        Cfg.raw("cal_group_$group") != null || defaults.filter { it.group == group }.any { isCalibrated(it.key) }

    fun resetGroup(group: String) {
        for (c in defaults.filter { it.group == group }) reset(c.key)
        Cfg.setRaw("cal_group_$group", null)
        Cfg.setRaw("cal_group_at_$group", null)
    }

    // ---------------- presets ----------------

    fun presetNames(): List<String> =
        (Cfg.raw("preset_names") ?: "").split("\n").filter { it.isNotBlank() }

    fun savePreset(name: String): Boolean {
        val n = name.trim().replace("\n", " ")
        if (n.isEmpty()) return false
        val o = JSONObject()
        for (c in defaults) o.put(c.key, text(c.key))
        Cfg.setRaw("preset_$n", o.toString())
        if (n !in presetNames()) Cfg.setRaw("preset_names", (presetNames() + n).joinToString("\n"))
        Cfg.activePreset = n
        return true
    }

    fun loadPreset(name: String): Boolean {
        val json = Cfg.raw("preset_$name") ?: return false
        val o = try { JSONObject(json) } catch (e: Exception) { return false }
        for (c in defaults) {
            val v = o.optString(c.key, "")
            if (v.isNotEmpty()) set(c.key, v) else reset(c.key)
        }
        Cfg.activePreset = name
        return true
    }

    fun deletePreset(name: String) {
        Cfg.setRaw("preset_$name", null)
        Cfg.setRaw("preset_names", presetNames().filter { it != name }.joinToString("\n"))
        if (Cfg.activePreset == name) Cfg.activePreset = ""
    }
}
