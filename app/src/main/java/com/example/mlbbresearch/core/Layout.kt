package com.example.mlbbresearch.core

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
    val defaults: List<Control> = listOf(
        Control("joystick", "Joystick centre", 0.20f, 0.78f),
        Control("attack", "Basic attack", 0.83f, 0.84f),
        Control("s1", "Skill 1", 0.715f, 0.85f),
        Control("s2", "Skill 2", 0.77f, 0.655f),
        Control("ult", "Ultimate", 0.85f, 0.54f),
        Control("s1Up", "Skill 1 upgrade (+)", 0.675f, 0.76f),
        Control("s2Up", "Skill 2 upgrade (+)", 0.73f, 0.57f),
        Control("ultUp", "Ultimate upgrade (+)", 0.81f, 0.45f),
        Control("spell", "Battle spell", 0.634f, 0.89f),
        Control("recall", "Recall", 0.51f, 0.89f),
        Control("regen", "Regen", 0.57f, 0.89f),
        Control("quickBuy", "Quick-buy item (top right)", 0.873f, 0.155f),
        Control("activeItem", "Active item button", 0.93f, 0.31f),
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
        Control("itemFeather", "Shop list: Feather of Heaven", 0.50f, 0.66f, "shop"),
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

    fun get(key: String): Pair<Float, Float> {
        val saved = Cfg.raw("cal_$key")
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
        val (x, y) = get(key)
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
