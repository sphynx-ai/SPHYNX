package com.example.mlbbresearch.core

// Screen positions of MLBB's buttons, as fractions of the screen (landscape).
// CALIBRATE them on the Setup page: turn on "Show markers" and adjust until each
// marker sits on the real button.
object Layout {

    class Control(val key: String, val label: String, val x: Float, val y: Float)

    val defaults: List<Control> = listOf(
        Control("joystick", "Joystick centre", 0.14f, 0.72f),
        Control("attack", "Basic attack", 0.86f, 0.74f),
        Control("s1", "Skill 1", 0.76f, 0.80f),
        Control("s2", "Skill 2", 0.80f, 0.62f),
        Control("ult", "Ultimate", 0.90f, 0.56f),
        Control("spell", "Battle spell", 0.68f, 0.86f),
        Control("recall", "Recall", 0.47f, 0.90f),
        Control("regen", "Regen", 0.53f, 0.90f),
        Control("quickBuy", "Quick-buy item (left side)", 0.06f, 0.55f),
        Control("shopOpen", "Shop button", 0.06f, 0.45f),
        Control("shopClose", "Shop close", 0.96f, 0.07f),
        Control("shopBuy", "Shop: Buy", 0.80f, 0.88f),
        Control("shopSell", "Shop: Sell", 0.80f, 0.80f),
        Control("shopLastSlot", "Shop: last owned slot", 0.90f, 0.50f),
        Control("activeItem", "Active item button", 0.92f, 0.42f),
        Control("itemImmortality", "Shop list: Immortality", 0.50f, 0.30f),
        Control("itemWinterCrown", "Shop list: Winter Crown", 0.50f, 0.42f),
        Control("itemRoseGold", "Shop list: Rose Gold Meteor", 0.50f, 0.54f),
        Control("itemFeather", "Shop list: Feather of Heaven", 0.50f, 0.66f),
        Control("lockIn", "Draft: confirm / lock-in", 0.50f, 0.88f)
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

    // value as "x,y" in percent
    fun text(key: String): String {
        val (x, y) = get(key)
        return "%.1f,%.1f".format(x * 100f, y * 100f)
    }

    fun set(key: String, percentText: String): Boolean {
        val p = percentText.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (p.size != 2) return false
        Cfg.setRaw("cal_$key", "${p[0]},${p[1]}")
        return true
    }

    fun reset(key: String) = Cfg.setRaw("cal_$key", null)
}
