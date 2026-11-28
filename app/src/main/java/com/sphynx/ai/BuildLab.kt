package com.sphynx.ai

import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import com.sphynx.game.BuildBook
import com.sphynx.game.HeroDb
import com.sphynx.game.HeroKit
import com.sphynx.game.HeroPlaybook
import com.sphynx.game.ItemBook
import com.sphynx.game.ItemDb
import com.sphynx.game.MetaStore
import com.sphynx.game.SkillBook
import com.sphynx.game.SpellDb
import com.sphynx.game.SpellInfo
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// EXPERIMENTAL BUILDS (Build page, v36.59): three builds for your current hero that try something
// different from the meta - each with its battle spell, emblem and why it fits. The online model is
// given everything the app knows: the hero's real skills and passive (from its wiki page), its kit
// (2 / 3 / 4 skills, forms, special casts, HP bar), how it plays (playbook), the original server's
// current patch and meta, the hero's meta build, and the equipment with what the Library learned
// about each item. Every item and spell in the answer must be a real current one (anything else is
// dropped). "Use this build" makes one your build on the Build page until you go back to the meta.
object BuildLab {

    class Build(val name: String, val idea: String, val items: List<String>, val spell: String?, val emblem: String,
                val whenToUse: String, val risk: String)

    @Volatile var busy = ""; private set
    @Volatile var lastMessage = "-"; private set

    private fun key(h: String) = "xbuild_" + h.lowercase().filter { it.isLetterOrDigit() }
    private fun choiceKey(h: String) = "xbuild_use_" + h.lowercase().filter { it.isLetterOrDigit() }

    fun builds(hero: String?): List<Build> {
        val o = hero?.let { Cfg.raw(key(it)) }?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: return emptyList()
        val a = o.optJSONArray("builds") ?: return emptyList()
        val dmg = HeroDb.damage(hero)
        val role = HeroDb.role(hero)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { b ->
            val rawItems = b.optJSONArray("items")?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList()
            val cleanItems = sanitizeBuildItems(hero, role, dmg, rawItems)
            Build(b.optString("name"), b.optString("idea"), cleanItems,
                b.optString("spell").ifBlank { null }, b.optString("emblem"), b.optString("when"), b.optString("risk"))
        }
    }

    // Enforces strict build rules (v37.01):
    // 1. Never mix Physical Attack equipment with Magic Power equipment; only choose offensive equipment matching HeroDb.damage(hero).
    // 2. For Tank heroes (Role.TANK), recommended/experimental builds prioritize defensive items and may only include
    //    physical or magic equipment if it matches the tank's damage type.
    fun sanitizeBuildItems(hero: String?, role: com.sphynx.game.Role, dmg: com.sphynx.game.Damage, raw: List<String>): List<String> {
        val ic = com.sphynx.game.ItemClass
        val real = ArrayList<String>()
        for (r in raw) {
            val c = ItemDb.canonical(r) ?: continue
            if (!(ic.isAdvanced(c) || c in ItemDb.retributions)) continue
            if (!ic.fitsStrict(c, dmg)) continue
            if (c in real) continue
            if (isBoots(c) && real.any { isBoots(it) }) continue
            real.add(c)
        }
        // For Tank heroes: ensure predominantly defensive items (at most 1 matching-damage offensive item)
        if (role == com.sphynx.game.Role.TANK) {
            val kept = ArrayList<String>()
            var offCount = 0
            for (c in real) {
                if (c in ItemDb.retributions || isBoots(c) || ic.isDefensiveItem(c)) {
                    kept.add(c)
                } else if (ic.fitsStrict(c, dmg) && offCount < 1) {
                    kept.add(c)
                    offCount++
                }
            }
            real.clear()
            real.addAll(kept)
        }
        val fillPool = when {
            role == com.sphynx.game.Role.TANK -> listOf(
                "Tough Boots", "Dominance Ice", "Antique Cuirass", "Guardian Helmet", "Radiant Armor", "Immortality", "Thunder Belt", "Oracle", "Cursed Helmet", "Blade Armor"
            )
            dmg == com.sphynx.game.Damage.MAGIC -> listOf(
                "Arcane Boots", "Holy Crystal", "Divine Glaive", "Glowing Wand", "Genius Wand", "Blood Wings", "Enchanted Talisman", "Lightning Truncheon", "Immortality"
            )
            else -> listOf(
                "Warrior Boots", "War Axe", "Hunter Strike", "Blade of Despair", "Malefic Roar", "Sea Halberd", "Queen's Wings", "Brute Force Breastplate", "Immortality"
            )
        }
        for (fb in fillPool) {
            if (real.size >= 6) break
            if (fb in real) continue
            if (isBoots(fb) && real.any { isBoots(it) }) continue
            if (ic.fitsStrict(fb, dmg)) real.add(fb)
        }
        return real.take(6)
    }
    fun madeFor(hero: String?): String = hero?.let { Cfg.raw(key(it)) }?.let { try { JSONObject(it).optString("patch") } catch (e: Exception) { "" } } ?: ""

    // the experimental build you chose (null = the meta / role build)
    fun chosen(hero: String?): Build? {
        val i = hero?.let { Cfg.raw(choiceKey(it))?.toIntOrNull() } ?: return null
        return builds(hero).getOrNull(i)
    }
    fun choose(hero: String, index: Int?) = Cfg.setRaw(choiceKey(hero), index?.toString())

    fun generate(hero: String, done: (String) -> Unit) {
        if (busy.isNotEmpty()) { done("Already working on $busy"); return }
        if (!GroqClient.anyKey) { done("No online model key: put groq_key.txt, gemini_key.txt or cerebras_key.txt in the Sphynx-Storage/Input folder"); return }
        busy = hero
        AiRuntime.scope.launch {
            val msg = try { generateNow(hero) } catch (e: Exception) {
                Diag.error("Experimental builds for $hero", e); "Experimental builds failed: ${e.message}"
            } finally { busy = "" }
            lastMessage = msg
            done(msg)
        }
    }

    suspend fun generateNow(hero: String): String {
        val kit = SkillBook.get(hero)
        val pb = HeroPlaybook.of(hero)
        val meta = MetaStore.hero(hero)
        val g = MetaStore.general()
        val role = HeroDb.role(hero)
        val dmg = HeroDb.damage(hero)
        val heroText = buildString {
            append("$hero: ${role.name.lowercase()}, ${if (HeroDb.isRanged(hero)) "ranged" else "melee"}, damage type ${if (dmg == com.sphynx.game.Damage.MAGIC) "MAGIC" else "PHYSICAL"}.\n")
            append("STRICT RULE: NEVER mix Physical Attack equipment with Magic Power equipment! Only choose equipment matching this hero's ${if (dmg == com.sphynx.game.Damage.MAGIC) "Magic Power" else "Physical Attack"} damage type (plus Defense / Movement / Adaptive Attack).\n")
            if (role == com.sphynx.game.Role.TANK) {
                append("TANK RULE: Recommended equipment for tank heroes must be all defensive items (you may add at most 1 ${if (dmg == com.sphynx.game.Damage.MAGIC) "Magic" else "Physical"} item matching its damage type).\n")
            }
            append("Kit: ").append(HeroKit.describe(hero)).append('\n')
            if (kit.passiveText.isNotBlank()) append("Passive: ").append(kit.passiveText.take(350)).append('\n')
            for (sl in HeroKit.slots(hero)) kit[sl]?.let { k ->
                append("$sl" + (if (k.name.isNotBlank() && k.name != sl) " ${k.name}" else "") + ": ${k.kind.name.lowercase()}" + (if (k.hardCc) ", hard CC" else if (k.slow) ", slow" else "") +
                    (if (k.mobility && k.kind != com.sphynx.game.SkillKind.DASH) ", dash" else "") + (if (k.heal) ", heal" else "") + (if (k.shield) ", shield" else ""))
                if (k.description.isNotBlank() && k.description != k.name) append(" - ").append(k.description.take(260))
                append('\n')
            }
            append("Plays as: ${pb.style}; combo ${pb.combo}; strongest ${pb.spike}\n")
            if (meta != null) {
                append("Its current META (${meta.patch}): build ${meta.build.joinToString()}; spell ${meta.spell ?: "-"}\n")   // no emblem: Wiki only (Phase 13 D2)
                if (meta.playstyle.isNotBlank()) append("How it is played: ${meta.playstyle.take(300)}\n")
                if (meta.changes.isNotBlank()) append("Recent changes: ${meta.changes.take(250)}\n")
            } else append("Role's usual build: ${BuildBook.primary(role).joinToString()}\n")
        }
        val patchText = g?.let { "Patch ${it.patch} (original server). ${it.notes}".take(600) } ?: "Patch: not learned yet (Meta page)."
        // the equipment, with what the Library learned (stats / effect), short
        // ADVANCED equipment only (v36.73), each with its stat types; the Retribution kinds for junglers
        val ic = com.sphynx.game.ItemClass
        // Pre-filter equipment list so the model can ONLY see items matching the hero's damage type (never mixing Physical and Magic)
        val items = (ItemDb.current.filter { ic.isAdvanced(it) && ic.fitsStrict(it, dmg) } + ItemDb.retributions).joinToString("\n") { n ->
            val i = ItemBook.get(n)
            val kinds = if (n in ItemDb.retributions) "jungle; " + (ic.retributionGuide[n]?.let { (w, best) -> w.joinToString(" ") + " Best for: " + best.joinToString(", ") } ?: "") else ic.kindsText(n)
            "$n [$kinds]" + (if (i == null) "" else ": ${(ic.wikiStats(n) ?: i.stats).take(70)}${if (i.effect.isNotBlank()) " | " + i.effect.take(110) else ""}")
        }
        val spells = SpellDb.names.joinToString("; ") { s -> "$s (${SpellInfo.effect[s]?.take(60) ?: ""})" }
        val prompt = "You are a Mobile Legends: Bang Bang theorycrafter. " + MetaAdvisor.ORIGINAL_ONLY +
            "Make 3 EXPERIMENTAL builds for the hero below: different from its meta build, but each with a clear synergy with the " +
            "hero's real skills, passive and playstyle (e.g. items whose effects trigger on its skill pattern, sustain for a fighter " +
            "that stays in fights, penetration against tanky metas). Use ONLY item names from the EQUIPMENT list and spell names from " +
            "the SPELLS list, written exactly (the list holds only ADVANCED - finished - equipment). 6 items each (at most one pair of boots; jungle heroes put their Retribution item " +
            "first). Reply with ONLY one JSON object, no other words:\n" +
            "{\"builds\":[{\"name\":\"short name\",\"idea\":\"the synergy in one or two sentences\",\"items\":[\"6 items in buy order\"]," +
            "\"spell\":\"\",\"emblem\":\"emblem set and its key talent\",\"when\":\"when to use it (enemies, situation)\",\"risk\":\"its weakness\"}]}\n\n" +
            "--- HERO ---\n$heroText\n--- PATCH AND META ---\n$patchText\n--- SPELLS ---\n$spells\n--- EQUIPMENT ---\n$items"
        val (text, _) = GroqClient.ask(prompt, search = false)
        val o = GroqClient.json(text) ?: throw IllegalStateException("answer was not JSON: ${text.take(80)}")
        val arr = o.optJSONArray("builds") ?: throw IllegalStateException("no builds in the answer")
        val out = JSONArray()
        val dropped = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val raw = b.optJSONArray("items")?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList()
            // only real current items matching the hero's damage type (never mixing Physical Attack and Magic Power), no doubles, one pair of boots
            for (r in raw) {
                val c = ItemDb.canonical(r)
                if (c == null || !(ic.isAdvanced(c) || c in ItemDb.retributions) || !ic.fitsStrict(c, dmg)) dropped.add(r)
            }
            val real = sanitizeBuildItems(hero, role, dmg, raw)
            if (real.size < 5) continue
            val spell = SpellDb.match(b.optString("spell"))?.let { if (b.optString("spell").lowercase().endsWith("retribution")) "Retribution" else it }
            out.put(JSONObject().put("name", b.optString("name").take(40)).put("idea", b.optString("idea").take(300)).put("items", JSONArray(real.take(6)))
                .put("spell", spell ?: "").put("emblem", b.optString("emblem").take(80)).put("when", b.optString("when").take(200)).put("risk", b.optString("risk").take(160)))
        }
        if (out.length() == 0) throw IllegalStateException("no build had 5+ real items" + (if (dropped.isNotEmpty()) " (not real: ${dropped.distinct().take(5).joinToString()})" else ""))
        Cfg.setRaw(key(hero), JSONObject().put("at", System.currentTimeMillis()).put("patch", g?.patch ?: "").put("builds", out).toString())
        Cfg.setRaw(choiceKey(hero), null)
        Diag.info("Experimental builds for $hero: ${out.length()}")
        return "${out.length()} experimental builds for $hero" + (if (dropped.isNotEmpty()) " (dropped as not real items: ${dropped.distinct().take(4).joinToString()})" else "")
    }

    private fun isBoots(item: String) = item.contains("Boots", true) || item.equals("Boots", true)
}
