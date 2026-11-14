package com.sphynx.universal

// THE GAME-STATE CONTRACT CHECK (Phase 2 submilestone 3, docs/contracts/UNIVERSAL_MATCH_STATE.md).
// Says what is wrong with a UniversalMatchState, as plain sentences; empty = it keeps the contract.
// Used by the contract tests (every adapter's output must pass) and available to diagnostics. It never
// changes or repairs a state: a value an adapter cannot read must be null, not fixed up here.
// Pure Kotlin: unit tested.
object StateContract {

    private val KEY = Regex("[a-z][a-z0-9_]*")
    private val ID = Regex("[a-z][a-z0-9_]*")

    fun violations(s: UniversalMatchState): List<String> {
        val out = ArrayList<String>()
        if (s.schemaVersion != UniversalMatchState.SCHEMA_VERSION)
            out.add("schemaVersion ${s.schemaVersion}, this build reads ${UniversalMatchState.SCHEMA_VERSION}")
        if (!ID.matches(s.gameId)) out.add("gameId '${s.gameId}' is not a lowercase id")
        if (s.timestampMs < 0) out.add("timestampMs is negative")
        s.matchTimeSec?.let { if (it < 0) out.add("matchTimeSec is negative") }
        s.assignedZone?.let { if (!ID.matches(it)) out.add("assignedZone '$it' is not a lowercase id") }
        keys("gameSpecific", s.gameSpecific, out)

        s.self?.let { me ->
            if (me.side != Side.SELF) out.add("self has side ${me.side}")
            if (me.kind != UnitKind.CHARACTER) out.add("self is a ${me.kind}")
            unit("self", me, out)
        }
        s.units.forEachIndexed { i, u ->
            if (u.side == Side.SELF) out.add("units[$i] has side SELF (the player belongs in self)")
            unit("units[$i]", u, out)
        }

        with(s.resources) {
            gold?.let { if (it < 0) out.add("resources.gold is negative") }
            itemCount?.let { if (it < 0) out.add("resources.itemCount is negative") }
            level?.let { if (it < 1) out.add("resources.level is below 1") }
            fraction("resources.levelProgress", levelProgress, out)
        }
        s.allyCharacters.forEachIndexed { i, c -> if (c.isBlank()) out.add("allyCharacters[$i] is blank") }
        s.enemyCharacters.forEachIndexed { i, c -> if (c.isBlank()) out.add("enemyCharacters[$i] is blank") }
        return out
    }

    private fun unit(name: String, u: UnitState, out: MutableList<String>) {
        fraction("$name.health", u.health, out)
        fraction("$name.confidence", u.confidence, out)
        finite("$name.screenPos", u.screenPos, out)
        finite("$name.mapPos", u.mapPos, out)
        finite("$name.velocity", u.velocity, out)
        u.characterId?.let { if (it.isBlank()) out.add("$name.characterId is blank (unknown is null)") }
        keys("$name.gameSpecific", u.gameSpecific, out)
    }

    private fun fraction(name: String, v: Float?, out: MutableList<String>) {
        if (v != null && (v.isNaN() || v < 0f || v > 1f)) out.add("$name $v is outside 0..1 (unknown is null)")
    }

    private fun finite(name: String, v: Vec2?, out: MutableList<String>) {
        if (v != null && (!v.x.isFinite() || !v.y.isFinite())) out.add("$name is not a finite number")
    }

    private fun keys(name: String, m: Map<String, String>, out: MutableList<String>) {
        for (k in m.keys) if (!KEY.matches(k)) out.add("$name key '$k' is not lowercase snake_case")
    }
}
