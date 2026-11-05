package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg

// One step of a combo: a button and how long to wait before the next step.
// slot: "s1", "s2", "s3", "ult", "a" (basic attack) or "spell"
class ComboStep(val slot: String, val waitMs: Long)

// Complete built-in multi-combo profile for a hero (v37.11):
//  - burst: Full Lv4+ all-in / teamfight combo
//  - poke: Short trade / harass combo
//  - chase: Gap-close / low-HP finisher combo
//  - pre4: Pre-Level-4 (or Ultimate on cooldown) combo
//  - retreat: Escape / peel / counter-disengage combo
//  - variants: Additional named hero-specific combo options
class HeroComboSet(
    val burst: String,
    val poke: String,
    val chase: String,
    val pre4: String,
    val retreat: String,
    val variants: List<Pair<String, String>> = emptyList()
)

// Built-in optimized skill combos & all possible combo variations for all heroes (v37.11).
// Online combo updates are disabled: the AI strictly uses Manual Input (if set for the hero)
// and automatically falls back to this Built-In Multi-Combo Library when Manual Input is empty.
object ComboBook {

    private val builtIn: Map<String, String> = mapOf(
        // ---- TANKS (19) ----
        "Tigreal" to "s2 > s2 > ult > s1 > a",
        "Franco" to "s1 > ult > s2 > a",
        "Akai" to "s1 > s2 > ult > a",
        "Johnson" to "ult > s1 > s2 > a",
        "Grock" to "s1 > s2 > ult > a",
        "Hylos" to "s2 > ult > s1 > a",
        "Uranus" to "s2 > s1 > a > ult > s1 > a",
        "Belerick" to "ult > s1 > s2 > a",
        "Khufra" to "s1 > ult > s2 > a",
        "Atlas" to "s2 > s2 > ult > s1 > a",
        "Baxia" to "s1 > ult > s2 > s2 > a",
        "Lolita" to "s1 > a > ult > s2 > a",
        "Minotaur" to "s1 > ult > s2 > s1 > a",
        "Gatotkaca" to "ult > s2 > s1 > a",
        "Barats" to "s2 > s1 > a > ult > a",
        "Edith" to "s2 > s1 > a > ult > s2 > s1 > a > a",
        "Fredrinn" to "s1 > a > s2 > a > s3 > s1 > s2 > ult",
        "Gloo" to "s1 > s2 > ult > ult > s1 > s2 > a",
        "Marcel" to "s2 > s1 > ult > a",

        // ---- FIGHTERS (37) ----
        "Thamuz" to "s1 > s2 > a > ult > a > s1 > a",
        "Balmond" to "s1 > s2 > a > ult",
        "Alucard" to "s1 > a > s2 > a > ult > a > s1 > a",
        "Chou" to "s1 > s1 > s2 > s1 > a > ult > ult > a",
        "Freya" to "ult > s1 > s2 > a > s2 > a > s2 > a > s2 > a",
        "Zilong" to "ult > s2 > a > s1 > a > a",
        "Sun" to "ult > s1 > s3 > a > s2 > a",
        "Martis" to "s1 > s2 > s2 > a > ult > a",
        "Paquito" to "s2 > s1 > ult > s2 > s1 > a",
        "Yu Zhong" to "ult > s3 > s1 > s2 > a",
        "Terizla" to "s1 > ult > s2 > s2 > s2 > a",
        "X.Borg" to "s1 > s2 > ult > ult > s1 > a",
        "Dyrroth" to "s2 > s2 > s1 > a > ult > a",
        "Badang" to "s2 > s1 > ult > a > s1 > a",
        "Aldous" to "ult > s1 > a > s2 > a",
        "Leomord" to "ult > s2 > s1 > a > s2 > s1 > a",
        "Lapu-Lapu" to "s1 > s2 > ult > ult > s2 > s1 > a",
        "Masha" to "s1 > ult > a > s2 > a > a",
        "Silvanna" to "ult > s1 > s2 > a > s1 > a",
        "Khaleed" to "ult > a > s1 > s1 > s1 > a",
        "Arlott" to "s2 > s1 > s2 > ult > s2 > a",
        "Yin" to "s1 > s2 > a > ult > s2 > s1 > a",
        "Cici" to "ult > s1 > s2 > s2 > a",
        "Ruby" to "s1 > a > s2 > a > ult > s2 > s1 > a",
        "Argus" to "s1 > s2 > a > s1 > ult > a > a",
        "Jawhead" to "ult > s2 > s2 > s1 > a",
        "Hilda" to "s1 > a > s2 > s2 > s2 > ult > a",
        "Guinevere" to "s2 > ult > s1 > s2 > a",
        "Aulus" to "ult > s1 > s2 > a > a > a",
        "Alpha" to "ult > s2 > a > s1 > a",
        "Bane" to "ult > s1 > a > s2 > a",
        "Julian" to "s2 > ult > s1 > a > a",
        "Lukas" to "ult > s2 > a > s1 > a > ult > a",
        "Minsitthar" to "s1 > ult > s2 > a > a",
        "Phoveus" to "s1 > a > s2 > ult > s1 > ult > a",
        "Roger" to "s1 > s2 > a > ult > s2 > s1 > a > a",
        "Sora" to "s1 > s2 > ult > s1 > s2 > a",

        // ---- ASSASSINS (18) ----
        "Saber" to "s1 > s2 > a > ult > a",
        "Karina" to "s1 > a > s2 > ult > ult > a",
        "Fanny" to "s2 > s2 > ult > s1 > a",
        "Hayabusa" to "s2 > s1 > a > ult > s1 > s2 > a",
        "Natalia" to "a > s1 > a > s2 > ult > a > s1 > a",
        "Lancelot" to "s1 > s2 > a > ult > s1 > a",
        "Gusion" to "s2 > ult > s2 > s1 > s2 > s1 > a > ult > a",
        "Helcurt" to "ult > s1 > a > s2 > a",
        "Ling" to "s2 > a > ult > s2 > s2 > s2 > s2 > s1 > a",
        "Benedetta" to "ult > s1 > a > s2 > a",
        "Aamon" to "s2 > a > s1 > a > ult > a",
        "Joy" to "s1 > s2 > s2 > s2 > s2 > ult > a",
        "Nolan" to "s2 > s1 > a > ult > s2 > s1 > a",
        "Hanzo" to "ult > s2 > a > a > s1 > a",
        "Yi Sun-shin" to "s2 > a > s1 > a > ult > a",
        "Selena" to "s1 > s2 > ult > s2 > s1 > s2 > a",
        "Suyou" to "s1 > s2 > a > ult > a",
        "Hirara" to "s1 > s2 > a",

        // ---- MAGES (28) ----
        "Eudora" to "s2 > ult > s1 > a",
        "Alice" to "s1 > ult > s1 > s2 > a",
        "Nana" to "s2 > ult > s1 > a",
        "Kagura" to "s1 > ult > s2 > ult > s2 > s1 > a",
        "Cyclops" to "ult > s2 > s1 > a",
        "Aurora" to "s2 > ult > s1 > a",
        "Vale" to "s2 > ult > s1 > a",
        "Lunox" to "s3 > s2 > ult > s2 > s2 > s1 > a",
        "Harley" to "s2 > ult > s1 > a > s2",
        "Odette" to "s2 > s1 > a > ult",
        "Zhask" to "s1 > ult > s2 > s3 > a > a",
        "Pharsa" to "s1 > s2 > ult > s3",
        "Kadita" to "s1 > s2 > ult > s1 > a",
        "Cecilion" to "s2 > s1 > ult > s1 > a",
        "Luo Yi" to "s1 > s2 > ult > s1 > a",
        "Yve" to "s2 > s1 > ult > s1 > a",
        "Valir" to "s1 > ult > s1 > s2 > s1 > a",
        "Chang'e" to "s2 > s1 > ult > a",
        "Xavier" to "s1 > s2 > ult > a",
        "Novaria" to "ult > s1 > s2 > a",
        "Lylia" to "s2 > s1 > s2 > s2 > a > ult",
        "Vexana" to "s1 > s2 > ult > a",
        "Zhuxin" to "s1 > ult > s2 > a",
        "Esmeralda" to "ult > s1 > s2 > a > s1 > s2 > a",
        "Gord" to "s1 > s2 > ult > a",
        "Harith" to "ult > s2 > a > s1 > s2 > a > s2 > a",
        "Valentina" to "s1 > s2 > s1 > s2 > ult > a",
        "Zetian" to "s2 > s1 > ult > a",

        // ---- MARKSMEN (20) ----
        "Layla" to "s1 > a > s2 > a > ult > a",
        "Miya" to "ult > s1 > s2 > a > a > a",
        "Bruno" to "s1 > a > s2 > a > ult > a",
        "Clint" to "s1 > a > s2 > a > ult > a",
        "Granger" to "s2 > a > s1 > ult > a",
        "Moskov" to "s1 > s2 > a > a > ult > a",
        "Beatrix" to "s1 > a > ult > s2 > a > a",
        "Brody" to "s1 > a > s2 > a > ult",
        "Claude" to "s1 > s2 > ult > s2 > a",
        "Wanwan" to "s1 > s1 > a > a > ult > s2 > a",
        "Karrie" to "ult > s1 > s2 > a > a > s2 > a",
        "Irithel" to "ult > s1 > s2 > a > a > a",
        "Lesley" to "a > s1 > a > s2 > a > ult",
        "Hanabi" to "ult > s1 > s2 > a > a > a",
        "Melissa" to "s2 > s1 > a > a > ult > a",
        "Natan" to "ult > s1 > s2 > ult > s1 > a > a",
        "Ixia" to "s2 > s1 > ult > a > a",
        "Popol and Kupa" to "ult > s1 > s3 > s2 > a > a",
        "Kimmy" to "s1 > s2 > a > ult > a",
        "Obsidia" to "s2 > s1 > a > ult > a",

        // ---- SUPPORTS (11) ----
        "Rafaela" to "s1 > ult > s2 > a",
        "Estes" to "s2 > s1 > ult > a",
        "Angela" to "s2 > s1 > s1 > ult > s1 > a",
        "Diggie" to "s2 > s1 > ult > a",
        "Floryn" to "s2 > s1 > ult > a",
        "Mathilda" to "s1 > ult > s2 > a",
        "Kaja" to "ult > s2 > s1 > a",
        "Faramis" to "s1 > s2 > ult > a",
        "Carmilla" to "s2 > ult > s2 > s1 > a",
        "Chip" to "s2 > s1 > ult > s3 > a",
        "Kalea" to "s2 > s1 > a > ult > a"
    )

    // Hero-specific named multi-combo variations for complex / signature heroes
    private val specialVariants: Map<String, List<Pair<String, String>>> = mapOf(
        "Gusion" to listOf(
            "10-Dagger Full Burst" to "s2 > ult > s2 > s1 > s2 > s1 > a > ult > a",
            "5-Dagger Fast Pickoff" to "s1 > s2 > s1 > s2 > a > ult",
            "Double-S2 Shotgun Burst" to "s2 > ult > s2 > s2 > a > ult > s1 > s1 > a",
            "Pre-Lv4 Dagger Trade" to "s1 > s2 > s1 > s2 > a"
        ),
        "Fanny" to listOf(
            "Double-Cable Cut Throat" to "s2 > s2 > ult > s1 > a",
            "Triple-Cable Dive" to "s2 > s2 > s2 > ult > s1 > a",
            "Single-Cable Quick Slash" to "s2 > s1 > a > s2",
            "Pre-Lv4 Double-Cable" to "s2 > s2 > s1 > a"
        ),
        "Ling" to listOf(
            "4-Blade Tempest Full Combo" to "s2 > a > ult > s2 > s2 > s2 > s2 > s1 > a",
            "Wall Leap Crit -> 4-Blade" to "s1 > a > s2 > a > ult > s2 > s2 > s2 > s2 > a",
            "Quick Pickoff" to "s1 > a > s2 > a > ult > s2 > a",
            "Pre-Lv4 Crit Poke" to "s1 > a > s2 > a"
        ),
        "Chou" to listOf(
            "Jeet Kune Do -> Shunpo -> Kick" to "s1 > s1 > s2 > s1 > a > ult > ult > a",
            "Fast Dash Knockup -> Kick" to "s1 > s1 > s2 > s1 > ult > ult > a",
            "Pre-Lv4 Knockup Trade" to "s1 > s1 > s1 > a > s2 > a"
        ),
        "Kagura" to listOf(
            "Full 6-Skill Yin Yang Burst" to "s1 > ult > s2 > ult > s2 > s1 > a",
            "Fast Umbrella Catch" to "s1 > s2 > ult > s1 > s2 > a",
            "Long-Range Poke & Pull" to "s1 > ult > s1 > a"
        ),
        "Julian" to listOf(
            "Enhanced S1 (Single-Target Javelin)" to "s2 > ult > s1 > a > a",
            "Enhanced Ult (AOE Knockup Control)" to "s1 > s2 > ult > a > a",
            "Enhanced S2 (Untargetable Sword Dive)" to "s1 > ult > s2 > a > a"
        ),
        "Suyou" to listOf(
            "Immortal Cleave -> Mortal Finisher" to "s1 > s2 > a > ult > a",
            "Triple Immortal Hold Combo" to "s1 > s2 > ult > a",
            "Fast Mortal Tap Assassination" to "s1 > a > s2 > a > ult > a"
        ),
        "Hirara" to listOf(
            "Drag S1->S2 (Mobility Dash into AOE)" to "s1 > s2 > a",
            "Drag S2->S1 (AOE Burst into Escape Dash)" to "s2 > s1 > a"
        ),
        "Sora" to listOf(
            "Shifting Skies -> Ult Reset Combo" to "s1 > s2 > ult > s1 > s2 > a",
            "Instant Ult -> Double Skill Chain" to "ult > s1 > s2 > a > s1 > s2 > a"
        ),
        "Selena" to listOf(
            "Elven Trap + Arrow -> Abyssal Reset" to "s1 > s2 > ult > s2 > s1 > s2 > a",
            "Close-Range Abyssal Burst" to "s1 > ult > s2 > s1 > s2 > a",
            "Abyssal Form Melee Chain" to "s2 > s1 > s2 > a > ult"
        ),
        "Roger" to listOf(
            "Human Slow/Shred -> Wolf Pounce" to "s1 > s2 > a > ult > s2 > s1 > a > a",
            "Wolf Form Ambush Execute" to "ult > s2 > s1 > a > a",
            "Human Form Kite" to "s1 > a > s2 > a"
        ),
        "Hayabusa" to listOf(
            "Quad Shadow -> Shuriken -> Ougi -> Return" to "s2 > s1 > a > ult > s1 > s2 > a",
            "Instant Ougi Assassination" to "s2 > ult > s1 > a > s2",
            "Shadow Poke & Return" to "s2 > s1 > a > s2"
        ),
        "Lancelot" to listOf(
            "Puncture -> Thorned Rose -> Execution" to "s1 > s2 > a > ult > s1 > a",
            "Double-Dash Gap-Close Burst" to "s1 > s1 > s2 > ult > a",
            "Pre-Lv4 Triangle Poke" to "s1 > s2 > a > s1"
        ),
        "Paquito" to listOf(
            "Champ Stance Double-Jab -> Knockout" to "s2 > s1 > ult > s2 > s1 > a",
            "Double Knockout Strike CC Lock" to "ult > ult > s2 > s1 > a",
            "Double-Dash Burst" to "s2 > s2 > s1 > ult > a"
        ),
        "Yu Zhong" to listOf(
            "Black Dragon Dive -> Sha Essence Burst" to "ult > s3 > s1 > s2 > a",
            "Lane 5-Stack All-In" to "s1 > s3 > s2 > a > ult > a",
            "Poke & Sustain" to "s1 > s2 > a > s3"
        ),
        "Benedetta" to listOf(
            "Alecto -> Phantom Slash -> Guard" to "ult > s1 > a > s2 > a",
            "Passive Dash -> S1 -> Ult" to "a > s1 > ult > s2 > a",
            "Counter-Stun Trade" to "s2 > a > s1 > a"
        ),
        "Zilong" to listOf(
            "Supreme Warrior Charge -> Flip -> Flurry" to "ult > s2 > a > s1 > a > a",
            "Ambush Flip -> Spear Strike -> Continuous BA" to "s1 > s2 > ult > a > a > a",
            "Pre-Lv4 Spear Flip Trade" to "s2 > a > s1 > a > a"
        ),
        "Argus" to listOf(
            "Demonic Grip -> Meteoric Sword -> Eternal Evil" to "s1 > s2 > a > s1 > ult > a > a",
            "All-In Immortal Continuous Attack" to "ult > s1 > s2 > a > a > s1 > a",
            "Poke & Disengage" to "s1 > s2 > a > s1"
        ),
        "Thamuz" to listOf(
            "Molten Scythes -> Chasm Leap -> Cauterant" to "s1 > s2 > a > ult > a > s1 > a",
            "Cauterant Inferno Continuous Attack" to "ult > s2 > s1 > a > a > a",
            "Scythe Poke & Pull" to "s1 > a > s1 > s2 > a"
        ),
        "Layla" to listOf(
            "Malefic Bomb -> Void Projectile -> Continuous BA -> Ult" to "s1 > a > s2 > a > a > ult > a",
            "Long-Range Snipe Finisher" to "s2 > s1 > ult > a",
            "Kite & Continuous Attack" to "s1 > s2 > a > a > a"
        ),
        "Miya" to listOf(
            "Hidden Moonlight Ambush -> Arrow of Eclipse -> Continuous BA" to "ult > s1 > s2 > a > a > a",
            "Root Trap -> Moon Arrow Split-Shot" to "s2 > s1 > a > a > ult > a",
            "Continuous Basic Attack DPS" to "s1 > a > a > s2 > a > a"
        ),
        "Freya" to listOf(
            "Valkyrie Descent -> Leap -> 4x Spirit Combo" to "ult > s1 > s2 > a > s2 > a > s2 > a > s2 > a",
            "Pre-Lv4 Shield Bash Chain" to "s1 > s2 > a > s2 > a > s2 > a"
        ),
        "Lunox" to listOf(
            "Darkening Chaos Assault Burst" to "s3 > s2 > ult > s2 > s2 > s1 > a",
            "Light Brilliance Safe Poke" to "s3 > s1 > s2 > a > ult"
        ),
        "Harith" to listOf(
            "Zaman Force Infinite Chrono Dash" to "ult > s2 > a > s1 > s2 > a > s2 > a > s2 > a",
            "Pre-Lv4 Dash Poke" to "s2 > a > s1 > a"
        ),
        "Edith" to listOf(
            "Phylax CC Throw -> Primal Wrath Marksman Burst" to "s2 > s1 > a > ult > s2 > s1 > a > a",
            "Peel & Lockdown" to "s2 > s1 > a"
        ),
        "Fredrinn" to listOf(
            "4-Point Combo -> Appraiser's Wrath Execute" to "s1 > a > s2 > a > s3 > s1 > s2 > ult",
            "Taunt Lockdown Peel" to "s2 > a > s3 > s1 > a"
        ),
        "Wanwan" to listOf(
            "Swallow's Path Weakness Break -> Crossbow of Tang" to "s1 > s1 > a > a > ult > s2 > a",
            "Purify Kiting Trade" to "s1 > a > s2 > a > a"
        ),
        "Beatrix" to listOf(
            "Weapon Burst -> Tactical Reposition -> Ult" to "s1 > a > ult > s2 > a > a",
            "Dash Reload Double-Volley" to "a > a > s2 > a > a > ult"
        )
    )

    private val roleDefault: Map<Role, String> = mapOf(
        Role.TANK to "s2 > s1 > ult > a",
        Role.FIGHTER to "s1 > s2 > a > ult > a",
        Role.ASSASSIN to "s1 > s2 > ult > a",
        Role.MAGE to "s2 > s1 > ult > a",
        Role.MARKSMAN to "s1 > a > s2 > a > ult",
        Role.SUPPORT to "s2 > s1 > ult > a",
        Role.UNKNOWN to "s1 > s2 > ult > a"
    )

    // Builds the full situational HeroComboSet (burst, poke, chase, pre4, retreat, and named variants) for any hero
    fun comboSet(hero: String?): HeroComboSet {
        val h = HeroDb.match(hero) ?: hero ?: "Unknown"
        val role = HeroDb.role(h)
        val burst = builtIn[h] ?: roleDefault[role] ?: "s1 > s2 > ult > a"
        val dash = HeroDb.dashSlot(h)?.takeIf { it != "none" }
        val four = HeroKit.has(h, "s3")
        val two = HeroKit.count(h) == 2

        val pre4 = when {
            two -> "s1 > s2 > a"
            h == "Fanny" -> "s2 > s2 > s1 > a"
            h == "Gusion" -> "s1 > s2 > s1 > s2 > a"
            h == "Chou" -> "s1 > s1 > s1 > a > s2 > a"
            h == "Freya" -> "s1 > s2 > a > s2 > a > s2 > a"
            four -> "s1 > s2 > s3 > a"
            dash == "s2" -> "s2 > s1 > a"
            else -> "s1 > s2 > a"
        }

        val poke = when {
            two -> "s2 > s1 > a"
            h == "Kagura" -> "s1 > ult > s1 > a"
            h == "Hayabusa" -> "s2 > s1 > a > s2"
            h == "Fanny" -> "s2 > s1 > a > s2"
            role == Role.MAGE -> "s1 > s2 > a"
            role == Role.MARKSMAN -> "s1 > a > s2 > a"
            dash == "s2" -> "s1 > a > s2"
            else -> "s1 > a > s2 > a"
        }

        val chase = when {
            two -> "s1 > s2 > a"
            dash != null && dash != "ult" -> {
                val rest = burst.split(">").map { it.trim() }.filter { it.isNotEmpty() && it != dash }
                (listOf(dash) + rest).joinToString(" > ")
            }
            else -> burst
        }

        val retreat = when {
            dash != null && dash != "ult" -> if (dash == "s1") "s2 > s1" else "s1 > $dash"
            role == Role.TANK || role == Role.SUPPORT -> "s2 > s1"
            else -> "s1 > s2"
        }

        val customVars = specialVariants[h] ?: listOf(
            "Burst / All-In (Lv4+)" to burst,
            "Chase / Finisher" to chase,
            "Poke / Short Trade" to poke,
            "Pre-Lv4 / Ult on Cooldown" to pre4
        )
        return HeroComboSet(burst, poke, chase, pre4, retreat, customVars)
    }

    fun allVariants(hero: String?): List<Pair<String, String>> = comboSet(hero).variants

    // Dynamic form-aware / situational built-in combo for Julian, Selena, Roger, Suyou, Hirara, and Sora
    fun dynamicComboText(hero: String?, enemiesClose: Int = 1, selfHp: Float = 1f, targetDist: Float = 0.25f): String? {
        if (hero == null || Cfg.manualCombo(hero) != null) return null
        return when (hero) {
            "Julian" -> HeroKit.julianCombo(enemiesClose, selfHp, targetDist)
            "Selena" -> if (HeroKit.formOf("Selena") == 1) {
                "s2 > s1 > s2 > a > ult"
            } else {
                "s1 > s2 > ult > s2 > s1 > s2 > a"
            }
            "Roger" -> if (HeroKit.formOf("Roger") == 1) {
                "s2 > s1 > a > a"
            } else {
                "s1 > s2 > a > ult > s2 > s1 > a > a"
            }
            "Hirara" -> if (targetDist > 0.30f) "s1 > s2 > a" else "s2 > s1 > a"
            else -> null
        }
    }

    // Selects the active combo for the current situation (v37.11):
    //  1. Manual Input (if the user entered a manual combo for this hero) -> always used first!
    //     If Cfg.autoSituationalCombo is ON and the hero is pre-Lv4 / ult is on cooldown, adapts smoothly.
    //  2. Built-In Multi-Combo Library (never uses online meta):
    //     - When Cfg.autoSituationalCombo is ON: automatically selects between All-In Burst, Teamfight AOE,
    //       Chase/Finisher, Poke/Short Trade, Pre-Lv4/No-Ult, Retreat/Peel, and Form/Enhanced combos!
    //     - When Cfg.autoSituationalCombo is OFF: always uses the hero's primary Burst combo.
    fun selectForSituation(
        hero: String?,
        level: Int = 4,
        ultReady: Boolean = true,
        targetHp: Float = 0.5f,
        targetDist: Float = 0.25f,
        enemiesClose: Int = 1,
        selfHp: Float = 1f,
        retreating: Boolean = false
    ): Pair<String, String> {
        if (hero != null) {
            Cfg.manualCombo(hero)?.let { manual ->
                val parsed = parse(manual)
                if (parsed.isNotEmpty()) {
                    if (Cfg.autoSituationalCombo && !ultReady && !HeroKit.canRecastNow(hero, "ult") && parsed.any { it.slot != "ult" }) {
                        val noUlt = parsed.filter { it.slot != "ult" }.joinToString(" > ") { it.slot }
                        return noUlt to "manual input (no-ult adapted)"
                    }
                    return manual to "manual input (yours)"
                }
            }
            val set = comboSet(hero)
            if (!Cfg.autoSituationalCombo) {
                return set.burst to "built-in (fixed primary)"
            }
            dynamicComboText(hero, enemiesClose, selfHp, targetDist)?.let { dyn ->
                return dyn to "auto situational (form/enhanced)"
            }
            val twoSkill = HeroKit.count(hero) == 2
            return when {
                retreating || (selfHp in 0.01f..0.28f && targetHp > 0.45f) -> set.retreat to "auto situational (retreat/peel)"
                !twoSkill && (level < 4 || (!ultReady && !HeroKit.canRecastNow(hero, "ult"))) -> set.pre4 to "auto situational (pre-Lv4 / no-ult)"
                enemiesClose >= 2 && ultReady -> set.burst to "auto situational (teamfight burst)"
                targetHp in 0f..0.38f || targetDist > 0.40f -> set.chase to "auto situational (chase/finisher)"
                targetHp > 0.72f && enemiesClose <= 1 -> set.poke to "auto situational (poke/trade)"
                else -> set.burst to "auto situational (all-in burst)"
            }
        }
        return (roleDefault[HeroDb.role(hero)] ?: "s1 > s2 > ult > a") to "built-in (role default)"
    }

    // Full multi-line summary of all built-in situational combos for a hero
    fun describeAllCombos(hero: String?): String {
        val set = comboSet(hero)
        return buildString {
            append("• All-In Burst: ").append(set.burst)
            append("\n• Chase / Finisher: ").append(set.chase)
            append("\n• Poke / Trade: ").append(set.poke)
            append("\n• Pre-Lv4 (No Ult): ").append(set.pre4)
            append("\n• Retreat / Peel: ").append(set.retreat)
        }
    }

    // Primary display text for the selected hero: Manual Input if set, otherwise Built-In Optimized
    fun text(hero: String?): Pair<String, String> {
        if (hero != null) {
            Cfg.manualCombo(hero)?.let { manual ->
                if (parse(manual).isNotEmpty()) return manual to "manual input (yours)"
            }
            val set = comboSet(hero)
            val mode = if (Cfg.autoSituationalCombo) "built-in auto-situational (${set.variants.size} combos)" else "built-in primary"
            return set.burst to mode
        }
        return (roleDefault[HeroDb.role(hero)] ?: "s1 > s2 > ult > a") to "built-in (role default)"
    }

    fun steps(hero: String?, overrideText: String? = null): List<ComboStep> {
        val hasManual = hero != null && Cfg.manualCombo(hero)?.let { parse(it).isNotEmpty() } == true
        val four = HeroKit.has(hero, "s3")
        val rawText = overrideText ?: text(hero).first
        var st = parse(rawText).map { if (it.slot == "s3" && !four) ComboStep("ult", it.waitMs) else it }
            .filter { it.slot !in HeroKit.ALL || HeroKit.has(hero, it.slot) }
        // Only auto-insert s3 on 4-skill heroes when using a generic default, never overwrite user manual input
        if (!hasManual && four && st.none { it.slot == "s3" }) {
            val i = st.indexOfFirst { it.slot == "ult" }.let { if (it < 0) st.size else it }
            st = st.take(i) + ComboStep("s3", 350L) + st.drop(i)
        }
        return st
    }

    fun parse(text: String): List<ComboStep> {
        val out = ArrayList<ComboStep>()
        for (raw in text.split(">", ",", "→", "+", "-", " ")) {
            val t = raw.trim().lowercase()
            if (t.isEmpty()) continue
            val wait = Regex("\\((\\d{2,4})\\)").find(t)?.groupValues?.get(1)?.toLongOrNull()
            val slot = when (t.substringBefore('(').trim()) {
                "s1", "1", "skill1", "skill 1" -> "s1"
                "s2", "2", "skill2", "skill 2" -> "s2"
                "s3", "3", "skill3", "skill 3" -> "s3"
                "ult", "s4", "4", "ultimate", "skill 4", "skill4", "u" -> "ult"
                "a", "ba", "aa", "basic", "attack", "basic attack" -> "a"
                "spell", "sp", "flicker", "battle spell" -> "spell"
                else -> null
            } ?: continue
            out.add(ComboStep(slot, wait ?: 350L))
        }
        return out.take(16)
    }

    fun format(steps: List<ComboStep>): String = steps.joinToString(" > ") { it.slot }
}

object ComboParser {

    private val tokenRules: List<Pair<Regex, String>> = listOf(
        Regex("\\b(ultimate|ulti|ult|s3|skill ?3|3rd skill|third skill)\\b") to " ULT ",
        Regex("\\b(s1|skill ?1|1st skill|first skill|skill one)\\b") to " S1 ",
        Regex("\\b(s2|skill ?2|2nd skill|second skill|skill two)\\b") to " S2 ",
        Regex("\\b(basic attacks?|basic|auto attacks?|ba|aa)\\b") to " BA ",
        Regex("\\b(flicker)\\b") to " SP "
    )
    private val seq = Regex("(?:S1|S2|ULT|BA|SP)(?:(?:\\s*(?:>|→|->|=>|»|,|\\+|-|then|and|followed by|/|use|the|a)\\s*)+(?:S1|S2|ULT|BA|SP))+")

    fun find(text: String): String? {
        var t = " " + text.lowercase().replace('\n', ' ') + " "
        for ((re, rep) in tokenRules) t = re.replace(t, rep)
        var best: String? = null
        var bestScore = 0
        for (m in seq.findAll(t)) {
            val toks = Regex("S1|S2|ULT|BA|SP").findAll(m.value).map { it.value }.toList()
            if (toks.size < 3 || toks.size > 12) continue
            if (toks.none { it == "S1" || it == "S2" }) continue
            val before = t.substring(maxOf(0, m.range.first - 120), m.range.first)
            val score = toks.size + (if ("combo" in before) 20 else 0) + (if ("ULT" in toks) 2 else 0)
            if (score > bestScore) {
                bestScore = score
                best = toks.joinToString(" > ") {
                    when (it) { "S1" -> "s1"; "S2" -> "s2"; "ULT" -> "ult"; "BA" -> "a"; else -> "spell" }
                }
            }
        }
        return best
    }

    fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&").replace("&nbsp;", " ").replace("&#8594;", "→")
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n")
}
