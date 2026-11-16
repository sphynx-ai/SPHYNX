package com.sphynx.testing

import com.sphynx.universal.CharacterRole
import com.sphynx.universal.GameAdapter
import com.sphynx.universal.StateContract
import com.sphynx.universal.UniversalMatchState

// THE ADAPTER TEST KIT (Phase 2 submilestones 7 and 10, docs/ADAPTER_GUIDE.md). Every game adapter runs it
// from its own test with sample readings of its game, including "nothing read" ones:
//     assertEquals(emptyList<String>(), AdapterContractKit.check(MyAdapter, mySamples))
// It says, as plain sentences, what the adapter does not keep of the universal contract. Game-agnostic.
object AdapterContractKit {

    private val ID = Regex("[a-z][a-z0-9_]*")

    fun <S> check(adapter: GameAdapter<S>, samples: List<S>): List<String> {
        val out = ArrayList<String>()
        // identity and versioning
        if (!ID.matches(adapter.gameId)) out.add("gameId '${adapter.gameId}' is not a lowercase id")
        if (adapter.displayName.isBlank()) out.add("displayName is blank")
        if (adapter.adapterVersion < 1) out.add("adapterVersion ${adapter.adapterVersion} is below 1")
        // knowledge
        out.addAll(knowledge(adapter))
        // the readings: every sample must map to a state that keeps the contract, under this adapter's id
        // and capabilities
        if (samples.isEmpty()) out.add("no sample readings given (give at least a full one and a \"nothing read\" one)")
        samples.forEachIndexed { i, s ->
            val u = try { adapter.toUniversal(s) } catch (e: Exception) { out.add("sample $i: toUniversal threw $e"); null }
            if (u != null) {
                StateContract.violations(u).forEach { out.add("sample $i: $it") }
                if (u.gameId != adapter.gameId) out.add("sample $i: gameId '${u.gameId}', the adapter's is '${adapter.gameId}'")
                if (u.schemaVersion != UniversalMatchState.SCHEMA_VERSION) out.add("sample $i: schemaVersion ${u.schemaVersion}")
                if (u.capabilities.all() != adapter.capabilities.all())
                    out.add("sample $i: the state's capabilities are not the adapter's declared ones")
            }
        }
        return out
    }

    fun <S> knowledge(adapter: GameAdapter<S>): List<String> {
        val out = ArrayList<String>()
        val k = adapter.knowledge
        if (k.characters.isEmpty()) out.add("knowledge.characters is empty")
        names("knowledge.characters", k.characters, out)
        names("knowledge.spells", k.spells, out)
        names("knowledge.items", k.items, out)
        for (c in k.characters) {
            val m = k.matchCharacter(c)
            if (m != c) out.add("matchCharacter('$c') is '$m', not itself")
        }
        if (k.matchCharacter(null) != null) out.add("matchCharacter(null) is not null")
        if (k.matchCharacter("") != null) out.add("matchCharacter(\"\") is not null")
        if (k.matchCharacter("zz qq xx 0000") != null) out.add("matchCharacter matches a made-up name")
        if (k.role(null) != CharacterRole.UNKNOWN) out.add("role(null) is not UNKNOWN")
        if (k.ranged(null)) out.add("ranged(null) is true")
        if (k.lanes.isEmpty()) out.add("knowledge.lanes is empty")
        k.lanes.forEach { if (!ID.matches(it.key)) out.add("lane key '${it.key}' is not a lowercase id"); if (it.label.isBlank()) out.add("lane '${it.key}' has a blank label") }
        dupes("lane key", k.lanes.map { it.key }, out)
        k.objectives.forEach {
            if (!ID.matches(it.key)) out.add("objective key '${it.key}' is not a lowercase id")
            if (it.label.isBlank()) out.add("objective '${it.key}' has a blank label")
            if (it.fromMatchSec < 0) out.add("objective '${it.key}' appears at a negative time")
        }
        dupes("objective key", k.objectives.map { it.key }, out)
        return out
    }

    private fun names(what: String, l: List<String>, out: MutableList<String>) {
        l.forEachIndexed { i, n -> if (n.isBlank()) out.add("$what[$i] is blank") }
        dupes(what, l, out)
    }

    private fun dupes(what: String, l: List<String>, out: MutableList<String>) {
        l.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { out.add("$what has '$it' more than once") }
    }
}
