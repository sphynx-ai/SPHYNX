package com.sphynx.core

// WHAT THE APP DOES BY ITSELF UNDER LOW MEMORY OR HEAT (Phase 5, P5; owner decision 2026-11-21). A Settings switch
// chooses one of two answers:
//   ONLY WARN (the default, like every feature switch on a new install): a line on the floating panel and in the
//     diagnostics log; nothing is unloaded.
//   STEP DOWN GENTLY: moderate heat or low memory -> the tactical model is unloaded first; severe heat or critical
//     memory -> the GGUF engine's model is unloaded too; critical memory -> the strategic model (Gemma) as well.
//     What was unloaded comes back only after a cool-down (no pressure for COOL_DOWN_MS) and never during a fight.
// Every step is written into the diagnostics log. The play loop never waits for a language model, so the rules engine
// keeps playing while a model is away. The rules below are pure and unit-tested; the app layer plugs in the model
// calls (core may not call the AI or the models: ADR 0001).
object PressurePolicy {

    enum class Mode(val key: String, val label: String) {
        WARN("warn", "Only warn me"),
        STEP_DOWN("stepdown", "Step down gently");
        companion object { fun of(key: String?): Mode = values().firstOrNull { it.key == key } ?: WARN }
    }

    enum class Level { OK, MODERATE, SEVERE, CRITICAL }

    const val COOL_DOWN_MS = 5 * 60_000L

    /** The memory signal's level (ComponentCallbacks2.TRIM_MEMORY_*); the screen-hidden signal is not pressure. */
    fun memoryLevel(trim: Int): Level = when (trim) {
        PressureLog.RUNNING_CRITICAL, PressureLog.COMPLETE -> Level.CRITICAL
        PressureLog.RUNNING_LOW, PressureLog.BACKGROUND, PressureLog.MODERATE -> Level.MODERATE
        else -> Level.OK
    }

    /** The thermal status's level (PowerManager.THERMAL_STATUS_*); heat never asks for the strategic model. */
    fun heatLevel(status: Int): Level = when {
        status >= 3 -> Level.SEVERE
        status == 2 -> Level.MODERATE
        else -> Level.OK
    }

    /** What step-down mode unloads at a level. */
    data class Plan(val tactical: Boolean, val gguf: Boolean, val strategic: Boolean) {
        val any: Boolean get() = tactical || gguf || strategic
    }

    fun plan(mode: Mode, level: Level): Plan = if (mode == Mode.WARN) Plan(false, false, false) else when (level) {
        Level.OK -> Plan(false, false, false)
        Level.MODERATE -> Plan(true, false, false)
        Level.SEVERE -> Plan(true, true, false)
        Level.CRITICAL -> Plan(true, true, true)
    }

    /** May what was unloaded come back? Only after a quiet cool-down, and never while a fight is on. */
    fun mayRestore(nowMs: Long, lastPressureMs: Long, inFight: Boolean): Boolean =
        !inFight && nowMs - lastPressureMs >= COOL_DOWN_MS

    /** The short line for the floating panel. */
    fun notice(level: Level, mode: Mode, unloaded: List<String>): String = when {
        level == Level.OK -> ""
        mode == Mode.WARN -> "Phone " + (if (level == Level.MODERATE) "warm or low on memory" else "hot or very low on memory") +
            ": consider unloading a model"
        unloaded.isEmpty() -> "Phone under pressure: nothing left to unload"
        else -> "Phone under pressure: unloaded " + unloaded.joinToString(", ")
    }

    // ---- on the phone ----

    /** One model the policy can set aside and bring back (plugged in by the app layer). */
    class Slot(val name: String, val loaded: () -> Boolean, val unload: () -> Unit, val reload: () -> Unit,
               /** What the slot runs after stepping down (a smaller model), or null when it is only unloaded. */
               val now: () -> String? = { null })

    @Volatile var tactical: Slot? = null
    @Volatile var gguf: Slot? = null
    @Volatile var strategic: Slot? = null
    /** Is a fight on now (no model comes back during one)? */
    @Volatile var inFight: () -> Boolean = { false }
    @Volatile var mode: () -> Mode = { Mode.WARN }

    private val setAside = LinkedHashSet<String>()      // names of the slots this policy unloaded
    private var lastPressureAt = 0L
    @Volatile private var noticeLine = ""
    @Volatile private var noticeUntil = 0L

    /** The floating panel's extra line ("" when there is nothing to say). */
    fun panelLine(nowMs: Long = System.currentTimeMillis()): String = if (nowMs < noticeUntil) noticeLine else ""

    /** A memory or heat level came in (from PressureLog). Runs the plan off the screen thread. */
    @Synchronized
    fun onPressure(level: Level, source: String) {
        if (level == Level.OK) return
        val now = System.currentTimeMillis()
        lastPressureAt = now
        val m = mode()
        val p = plan(m, level)
        val slots = listOfNotNull(tactical.takeIf { p.tactical }, gguf.takeIf { p.gguf }, strategic.takeIf { p.strategic })
        val toUnload = slots.filter { s -> try { s.loaded() } catch (e: Exception) { false } }
        for (s in toUnload) setAside.add(s.name)
        noticeLine = notice(level, m, setAside.toList()); noticeUntil = now + 2 * 60_000L
        if (m == Mode.WARN) { Diag.info("Pressure ($source, $level): warning only (Settings: ${m.label})"); return }
        if (toUnload.isEmpty()) { Diag.info("Pressure ($source, $level): nothing more to unload"); return }
        Diag.error("Pressure ($source, $level): unloading " + toUnload.joinToString { it.name })
        Thread({
            for (s in toUnload) try { s.unload() } catch (t: Throwable) { Diag.error("Pressure: could not unload ${s.name}", t) }
            // a slot that stepped down to a smaller model says so on the panel instead of "unloaded"
            toUnload.mapNotNull { try { it.now() } catch (t: Throwable) { null } }.firstOrNull()?.let { noticeLine = it }
        }, "pressure-unload").start()
    }

    /** Called about once a minute: brings back what was set aside, after the cool-down and outside a fight. */
    @Synchronized
    fun tick() {
        if (setAside.isEmpty()) return
        val fight = try { inFight() } catch (e: Exception) { true }
        if (!mayRestore(System.currentTimeMillis(), lastPressureAt, fight)) return
        // bring back in the opposite order of unloading: the strategic model first, the tactical model last
        val back = listOfNotNull(strategic, gguf, tactical).filter { it.name in setAside }
        setAside.clear()
        noticeUntil = 0L
        Diag.info("Pressure over (cool-down passed, no fight): reloading " + back.joinToString { it.name })
        Thread({ for (s in back) try { s.reload() } catch (t: Throwable) { Diag.error("Pressure: could not reload ${s.name}", t) } },
            "pressure-reload").start()
    }
}
