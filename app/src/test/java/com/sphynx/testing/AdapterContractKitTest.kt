package com.sphynx.testing

import com.sphynx.universal.CapabilitySet
import com.sphynx.universal.CharacterRole
import com.sphynx.universal.GameAdapter
import com.sphynx.universal.GameKnowledge
import com.sphynx.universal.LanePosition
import com.sphynx.universal.MatchPhase
import com.sphynx.universal.ObjectiveInfo
import com.sphynx.universal.UniversalMatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The kit itself: a small made-up game passes it, and each kind of mistake is reported.
class AdapterContractKitTest {

    private open class Knows(
        override val characters: List<String> = listOf("Ann", "Bo"),
        override val lanes: List<LanePosition> = listOf(LanePosition("top", "Top"), LanePosition("bottom", "Bottom")),
        override val objectives: List<ObjectiveInfo> = listOf(ObjectiveInfo("dragon", "Dragon", 300))
    ) : GameKnowledge {
        override fun matchCharacter(raw: String?) = characters.firstOrNull { it.equals(raw?.trim(), ignoreCase = true) }
        override fun role(character: String?) = if (character == "Ann") CharacterRole.MAGE else CharacterRole.UNKNOWN
        override fun ranged(character: String?) = character == "Ann"
        override val spells = listOf("Blink")
        override val items = emptyList<String>()
    }

    private class Game(
        override val gameId: String = "tiny_moba",
        override val knowledge: GameKnowledge = Knows(),
        private val badState: Boolean = false
    ) : GameAdapter<Int> {
        override val displayName = "Tiny MOBA"
        override val capabilities = CapabilitySet.NONE
        override fun toUniversal(state: Int) = UniversalMatchState(
            gameId = gameId, timestampMs = if (badState) -1L else 1000L, phase = MatchPhase.IN_MATCH,
            matchTimeSec = if (state < 0) null else state, capabilities = capabilities)
    }

    @Test fun aCorrectAdapterPasses() {
        assertEquals(emptyList<String>(), AdapterContractKit.check(Game(), listOf(0, 120, -1)))
    }

    @Test fun itsMistakesAreReported() {
        assertTrue(AdapterContractKit.check(Game(gameId = "Tiny MOBA"), listOf(1)).any { "gameId" in it })
        assertTrue(AdapterContractKit.check(Game(badState = true), listOf(1)).any { "sample 0: timestampMs" in it })
        assertTrue(AdapterContractKit.check(Game(), emptyList()).any { "no sample" in it })
        assertTrue(AdapterContractKit.check(Game(knowledge = Knows(characters = emptyList())), listOf(1))
            .any { "characters is empty" in it })
        assertTrue(AdapterContractKit.check(Game(knowledge = Knows(characters = listOf("Ann", "Ann"))), listOf(1))
            .any { "more than once" in it })
        assertTrue(AdapterContractKit.check(Game(knowledge = Knows(lanes = listOf(LanePosition("Top Lane", "Top")))), listOf(1))
            .any { "lane key" in it })
        assertTrue(AdapterContractKit.check(Game(knowledge = Knows(objectives = listOf(ObjectiveInfo("dragon", "", -5)))), listOf(1))
            .let { r -> r.any { "blank label" in it } && r.any { "negative time" in it } })
    }
}
