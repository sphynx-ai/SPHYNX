package com.example.mlbbresearch.adapters.mlbb

import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.Lane
import com.example.mlbbresearch.game.Phase
import com.example.mlbbresearch.universal.Capability
import com.example.mlbbresearch.universal.CapabilitySet
import com.example.mlbbresearch.universal.GameAdapter
import com.example.mlbbresearch.universal.MatchPhase
import com.example.mlbbresearch.universal.ResourceState
import com.example.mlbbresearch.universal.Side
import com.example.mlbbresearch.universal.SupportStatus
import com.example.mlbbresearch.universal.UniversalMatchState
import com.example.mlbbresearch.universal.UnitKind
import com.example.mlbbresearch.universal.UnitState
import com.example.mlbbresearch.universal.Vec2
import com.example.mlbbresearch.vision.Seen

// THE FIRST GAME ADAPTER: Mobile Legends: Bang Bang.
// Translates the existing MLBB GameState (built by GameStateTracker from the vision readers) into
// the universal model. A pure mapping: it reads, never changes, the existing state, and nothing
// in the app depends on it yet - MLBB behaviour is unchanged (Stage 2, step 1).
// MLBB-only details (battle spell, bushes, the boss bar...) go into gameSpecific, not the
// universal fields.
object MlbbAdapter : GameAdapter<GameState> {

    override val gameId = "mlbb"
    override val displayName = "Mobile Legends: Bang Bang"

    // from the support matrix in docs/ARCHITECTURE_AUDIT.md section 10 (2026-11-07). Nothing is
    // device-verified, so nothing is above PARTIAL. Update both together.
    override val capabilities = CapabilitySet(mapOf(
        Capability.GAME_RECOGNITION to SupportStatus.UNSUPPORTED,   // the app assumes MLBB
        Capability.SCREEN_PHASE to SupportStatus.PARTIAL,
        Capability.OWN_CHARACTER_ID to SupportStatus.PARTIAL,
        Capability.UNIT_HEALTH to SupportStatus.PARTIAL,
        Capability.ENEMY_TRACKING to SupportStatus.PARTIAL,
        Capability.MINIMAP to SupportStatus.PARTIAL,
        Capability.COOLDOWNS to SupportStatus.PARTIAL,
        Capability.ITEMS_GOLD to SupportStatus.EXPERIMENTAL,
        Capability.OBJECTIVES to SupportStatus.PARTIAL,
        Capability.STRATEGIC_ADVICE to SupportStatus.PARTIAL,
        // owner decision 2026-11-09: the small tactical model (Gemma 3 270M) is no longer Experimental -
        // its advice may be used like the other engines' (it still passes the Kotlin checks and the validator)
        Capability.TACTICAL_ADVICE to SupportStatus.PARTIAL,
        Capability.DRAFT_ADVICE to SupportStatus.PARTIAL,
        Capability.BUILD_ADVICE to SupportStatus.PARTIAL,
        Capability.POST_MATCH to SupportStatus.EXPERIMENTAL
    ))

    fun phase(p: Phase): MatchPhase = when (p) {
        Phase.UNKNOWN -> MatchPhase.UNKNOWN
        Phase.HOME, Phase.LOBBY, Phase.QUEUE -> MatchPhase.OUT_OF_MATCH
        Phase.DRAFT, Phase.HERO_LOCKED -> MatchPhase.CHARACTER_SELECT
        Phase.LOADING -> MatchPhase.LOADING
        Phase.IN_GAME, Phase.SHOP, Phase.SCOREBOARD, Phase.MAP -> MatchPhase.IN_MATCH
        Phase.DEAD -> MatchPhase.PLAYER_DEAD
        Phase.RESULT -> MatchPhase.POST_MATCH
        Phase.LIVE -> MatchPhase.SPECTATING
    }

    fun zone(l: Lane): String = when (l) {
        Lane.EXP -> "exp_lane"; Lane.GOLD -> "gold_lane"; Lane.MID -> "mid_lane"
        Lane.ROAM -> "roam"; Lane.JUNGLE -> "jungle"
    }

    private fun known(v: Float): Float? = if (v < 0f) null else v
    private fun known(v: Int): Int? = if (v < 0) null else v

    private fun seen(s: Seen, side: Side, kind: UnitKind, id: String? = null) = UnitState(
        side = side, kind = kind, characterId = id,
        health = known(s.hp), screenPos = Vec2(s.x, s.y),
        gameSpecific = if (s.species.isNotEmpty()) mapOf("species" to s.species) else emptyMap()
    )

    override fun toUniversal(state: GameState): UniversalMatchState {
        val s = state
        val units = ArrayList<UnitState>()
        s.allies.forEach { units.add(seen(it, Side.ALLY, if (it.hero) UnitKind.CHARACTER else UnitKind.MINION)) }
        s.allyMinions.forEach { units.add(seen(it, Side.ALLY, UnitKind.MINION)) }
        s.enemies.forEach { t ->
            units.add(UnitState(Side.ENEMY, UnitKind.CHARACTER, health = known(t.hp), screenPos = Vec2(t.x, t.y),
                velocity = Vec2(t.vx, t.vy), gameSpecific = mapOf("track_id" to t.id.toString())))
        }
        s.enemyMinions.forEach { units.add(seen(it, Side.ENEMY, UnitKind.MINION)) }
        s.monsters.forEach { m ->
            units.add(seen(m, Side.NEUTRAL, if (m.hero) UnitKind.MAJOR_OBJECTIVE else UnitKind.NEUTRAL_MONSTER,
                m.species.ifEmpty { null }))
        }
        s.towers.forEach { units.add(UnitState(Side.ENEMY, UnitKind.STRUCTURE, mapPos = Vec2(it.x, it.y))) }
        s.allyTowers.forEach { (x, y) -> units.add(UnitState(Side.ALLY, UnitKind.STRUCTURE, mapPos = Vec2(x, y))) }
        s.map.objective?.let { (x, y) -> units.add(UnitState(Side.NEUTRAL, UnitKind.MAJOR_OBJECTIVE, mapPos = Vec2(x, y),
            health = known(s.bossHp), gameSpecific = mapOf("source" to "minimap"))) }

        val self = s.self?.let { me ->
            UnitState(Side.SELF, UnitKind.CHARACTER, characterId = s.ownHero,
                health = known(s.selfHp), screenPos = Vec2(me.x, me.y),
                mapPos = s.minimapSelf?.let { Vec2(it.first, it.second) })
        } ?: if (s.ownHero != null || s.minimapSelf != null) UnitState(Side.SELF, UnitKind.CHARACTER,
            characterId = s.ownHero, health = known(s.selfHp), mapPos = s.minimapSelf?.let { Vec2(it.first, it.second) }) else null

        val extra = LinkedHashMap<String, String>()
        s.spell?.let { extra["battle_spell"] = it }
        if (s.inBush) extra["in_bush"] = "true"
        known(s.bossHp)?.let { extra["boss_hp"] = "%.2f".format(java.util.Locale.US, it) }
        if (s.minimapEnemies > 0) extra["minimap_enemies"] = s.minimapEnemies.toString()

        return UniversalMatchState(
            gameId = gameId,
            timestampMs = s.ts,
            phase = phase(s.phase),
            matchTimeSec = known(s.matchSec),
            self = self,
            units = units,
            resources = ResourceState(gold = known(s.gold), itemCount = known(s.items),
                level = s.level.takeIf { it in 1..15 }, levelProgress = known(s.xp)),
            assignedZone = zone(s.lane),
            allyCharacters = s.myTeam,
            enemyCharacters = s.enemyTeam,
            gameSpecific = extra,
            capabilities = capabilities
        )
    }
}
