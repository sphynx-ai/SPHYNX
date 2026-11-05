package com.example.mlbbresearch.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

// Hero and item pictures from the Mobile Legends wiki (Fandom), downloaded once and kept on the
// phone (files/icons). Wiki files: items are "<Item_Name>.png", heroes "Hero<id*10+1>-icon.png";
// the folder is the MD5 of the file name (MediaWiki). Until a picture is there (or offline), a
// coloured circle with the initials shows instead.
object IconCache {

    // hero -> wiki file number (from the wiki's role pages)
    private val HERO_FILE: Map<String, Int> = mapOf(
        "Aamon" to 1091, "Akai" to 91, "Aldous" to 641, "Alice" to 41, "Alpha" to 281, "Alucard" to 71,
        "Angela" to 551, "Argus" to 451, "Arlott" to 1201, "Atlas" to 931, "Aulus" to 1081, "Aurora" to 361,
        "Badang" to 771, "Balmond" to 21, "Bane" to 111, "Barats" to 991, "Baxia" to 871, "Beatrix" to 1051,
        "Belerick" to 701, "Benedetta" to 971, "Brody" to 1001, "Bruno" to 121, "Carmilla" to 921, "Cecilion" to 911,
        "Chang'e" to 611, "Chip" to 1241, "Chou" to 261, "Cici" to 1231, "Claude" to 651, "Clint" to 131,
        "Cyclops" to 331, "Diggie" to 481, "Dyrroth" to 851, "Edith" to 1111, "Esmeralda" to 811, "Estes" to 341,
        "Eudora" to 151, "Fanny" to 171, "Faramis" to 761, "Floryn" to 1121, "Franco" to 101, "Fredrinn" to 1171,
        "Freya" to 221, "Gatotkaca" to 411, "Gloo" to 1041, "Gord" to 231, "Granger" to 791, "Grock" to 441,
        "Guinevere" to 801, "Gusion" to 561, "Hanabi" to 601, "Hanzo" to 691, "Harith" to 731, "Harley" to 421,
        "Hayabusa" to 211, "Helcurt" to 511, "Hilda" to 351, "Hylos" to 491, "Irithel" to 431, "Ixia" to 1211,
        "Jawhead" to 541, "Johnson" to 321, "Joy" to 1181, "Julian" to 1161, "Kadita" to 751, "Kagura" to 251,
        "Kaja" to 621, "Kalea" to 1281, "Karina" to 81, "Karrie" to 401, "Khaleed" to 981, "Khufra" to 781,
        "Kimmy" to 711, "Lancelot" to 471, "Lapu-Lapu" to 371, "Layla" to 181, "Leomord" to 671, "Lesley" to 531,
        "Ling" to 841, "Lolita" to 201, "Lukas" to 1271, "Lunox" to 681, "Luo Yi" to 961, "Lylia" to 861,
        "Marcel" to 1321, "Martis" to 581, "Masha" to 881, "Mathilda" to 1021, "Melissa" to 1141, "Minotaur" to 191,
        "Minsitthar" to 741, "Miya" to 11, "Moskov" to 311, "Nana" to 51, "Natalia" to 241, "Natan" to 1071,
        "Nolan" to 1221, "Novaria" to 1191, "Obsidia" to 1301, "Odette" to 461, "Paquito" to 1031, "Pharsa" to 521,
        "Phoveus" to 1061, "Popol and Kupa" to 941, "Rafaela" to 141, "Roger" to 391, "Ruby" to 291, "Saber" to 31,
        "Selena" to 631, "Silvanna" to 901, "Sora" to 1311, "Sun" to 271, "Suyou" to 1261, "Terizla" to 821,
        "Thamuz" to 721, "Tigreal" to 61, "Uranus" to 591, "Vale" to 661, "Valentina" to 1101, "Valir" to 571,
        "Vexana" to 381, "Wanwan" to 891, "X.Borg" to 831, "Xavier" to 1151, "Yi Sun-shin" to 301, "Yin" to 1131,
        "Yu Zhong" to 951, "Yve" to 1011, "Zetian" to 1291, "Zhask" to 501, "Zhuxin" to 1251, "Zilong" to 161,
        "Hirara" to 1331, "Dori" to 1341      // (confirmed on the wiki page you saved)
    )
    private val HERO_ALT: Map<String, List<Int>> = emptyMap()

    private val mem = LruCache<String, Bitmap>(220)
    // a failed picture is tried again 2 minutes later (v36.63: before, never again until the app
    // restarted - one moment without internet left every icon as initials)
    private val failedAt = HashMap<String, Long>()
    private val failed: Set<String> get() { val now = System.currentTimeMillis(); failedAt.entries.removeAll { now - it.value > 120_000 }; return failedAt.keys }
    @Volatile var lastProblem = "-"; private set
    private var problemsLogged = 0
    private val pending = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val io = Executors.newFixedThreadPool(3) { r -> Thread(r, "icons").apply { priority = Thread.MIN_PRIORITY } }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private val heroByNorm = HERO_FILE.keys.associateBy { norm(it) }

    fun heroFile(hero: String): String? {
        val n = heroByNorm[norm(hero)] ?: com.example.mlbbresearch.game.HeroDb.match(hero)?.let { heroByNorm[norm(it)] } ?: return null
        val id = HERO_FILE[n] ?: return null
        HERO_ALT[n]?.let { ids ->
            val files = ids.map { "Hero%03d-icon.png".format(it) }
            return synchronized(this) { files.firstOrNull { it !in failed } ?: files.first() }
        }
        return "Hero%03d-icon.png".format(id)
    }
    fun itemFile(item: String): String = item.trim().replace(' ', '_') + ".png"

    // the wiki's picture address (v36.64: the format the wiki uses now - "thumbnail-down"; the old
    // "scale-to-width-down" one is kept only as a later fallback)
    fun base(file: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest(file.toByteArray()).joinToString("") { "%02x".format(it) }
        val enc = java.net.URLEncoder.encode(file, "UTF-8").replace("+", "%20")
        return "https://static.wikia.nocookie.net/mobile-legends/images/${md5[0]}/${md5.substring(0, 2)}/$enc/revision/latest"
    }
    fun url(file: String): String = base(file) + "/thumbnail-down/width/100/height/100"

    // the picture for a file (from memory, the phone, or the wiki), delivered on the main thread
    fun load(ctx: Context, file: String, done: (Bitmap?) -> Unit) {
        mem.get(file)?.let { done(it); return }
        synchronized(this) {
            if (file in failed) { done(null); return }
            pending[file]?.let { it.add(done); return }
            pending[file] = mutableListOf(done)
        }
        val dir = File(ctx.applicationContext.filesDir, "icons").apply { mkdirs() }
        io.execute {
            val f = File(dir, file)
            var bmp: Bitmap? = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            // the wiki's small picture, else the full picture, else the wiki's file link (redirects)
            val tries = listOf(url(file), base(file), base(file) + "/scale-to-width-down/100",
                "https://mobile-legends.fandom.com/wiki/Special:FilePath/" + java.net.URLEncoder.encode(file, "UTF-8").replace("+", "%20") + "?width=100")
            val why = ArrayList<String>()
            for (u in tries) {
                if (bmp != null) break
                bmp = try {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 10000
                    c.instanceFollowRedirects = true
                    // (a browser-like name: some servers refuse unknown programs)
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36 MLBB-Research")
                    c.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/*,*/*;q=0.8")
                    val code = c.responseCode
                    if (code == 200) {
                        val bytes = c.inputStream.use { it.readBytes() }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { f.writeBytes(bytes) } ?: run { why.add("not a picture (${bytes.size} bytes)"); null }
                    } else { why.add("HTTP $code"); null }
                } catch (e: Exception) { why.add(e.javaClass.simpleName + (e.message?.let { ": " + it.take(60) } ?: "")); null }
            }
            if (bmp == null && why.isNotEmpty()) {
                lastProblem = "$file: " + why.joinToString(" / ")
                if (problemsLogged++ < 3) com.example.mlbbresearch.core.Diag.info("Icon not loaded - $lastProblem")
            }
            val cbs = synchronized(this) {
                if (bmp == null) failedAt[file] = System.currentTimeMillis() else mem.put(file, bmp)
                pending.remove(file) ?: mutableListOf()
            }
            main.post { cbs.forEach { it(bmp) } }
        }
    }

    // initials in a coloured circle (stable colour per name)
    fun placeholder(name: String, sizePx: Int): Bitmap {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val hues = intArrayOf(0xFF5B7CFA.toInt(), 0xFF3DBE8B.toInt(), 0xFFE0A030.toInt(), 0xFFE0566B.toInt(), 0xFF9B6CF0.toInt(), 0xFF2FB3C9.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hues[(name.hashCode() and 0x7fffffff) % hues.size] }
        c.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, p)
        val words = name.split(' ', '-', '.').filter { it.isNotBlank() }
        val ini = (if (words.size >= 2) "${words[0][0]}${words[1][0]}" else name.take(2)).uppercase()
        p.color = Color.WHITE; p.textSize = sizePx * 0.38f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
        c.drawText(ini, sizePx / 2f, sizePx / 2f - (p.descent() + p.ascent()) / 2, p)
        return b
    }

    // an ImageView showing a hero (round) or an item (rounded square)
    fun view(ctx: Context, name: String, hero: Boolean, sizePx: Int, fileOverride: String? = null): ImageView {
        val iv = ImageView(ctx)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setImageBitmap(placeholder(name, sizePx))
        iv.clipToOutline = true
        iv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                if (hero) o.setOval(0, 0, v.width, v.height) else o.setRoundRect(0, 0, v.width, v.height, v.width * 0.18f)
            }
        }
        val file = fileOverride ?: if (hero) heroFile(name) else itemFile(name)
        if (file != null) {
            iv.tag = file
            load(ctx, file) { bmp -> if (bmp != null && iv.tag == file) iv.setImageBitmap(bmp) }
        }
        return iv
    }

    @Volatile var loadingAll = false; private set
    @Volatile var loadAllStatus = "-"; private set

    @Synchronized
    fun clearFailures() {
        failedAt.clear()
        problemsLogged = 0
    }

    // Force-loads all icons in the Library (heroes, skills, battle spells + Blessed Retributions, and equipment)
    // and pre-computes their in-game detection fingerprints (HeroVerifier.wikiPrint).
    fun forceLoadAll(ctx: Context, done: (String) -> Unit) {
        if (loadingAll) {
            done("Already loading all icons ($loadAllStatus)")
            return
        }
        loadingAll = true
        clearFailures()
        com.example.mlbbresearch.vision.HeroVerifier.clearWikiCache()
        val appCtx = ctx.applicationContext
        val dir = File(appCtx.filesDir, "icons").apply { mkdirs() }
        Thread({
            try {
                val files = LinkedHashSet<String>()
                // 1) All hero portraits
                for (h in com.example.mlbbresearch.game.HeroDb.allNames) {
                    heroFile(h)?.let { files.add(it) }
                }
                // 2) All 12 battle spells + 3 Blessed Retributions
                for (sp in com.example.mlbbresearch.game.SpellDb.names + com.example.mlbbresearch.game.ItemDb.retributions) {
                    files.add(itemFile(sp))
                }
                // 3) All equipment + roaming blessings
                for (itName in com.example.mlbbresearch.game.ItemDb.current) {
                    if (!com.example.mlbbresearch.game.ItemClass.isBlessing(itName)) files.add(itemFile(itName))
                }
                for (b in com.example.mlbbresearch.game.ItemClass.blessings) {
                    files.add(b.file)
                }
                // 4) All hero skill & passive icons (fetch wiki skill icon names if not known yet)
                val heroes = com.example.mlbbresearch.game.HeroDb.allNames
                for ((idx, h) in heroes.withIndex()) {
                    loadAllStatus = "Scanning hero skill icons (${idx + 1}/${heroes.size}: $h)…"
                    if (!com.example.mlbbresearch.game.WikiSkills.iconsKnown(h)) {
                        try { com.example.mlbbresearch.game.WikiSkills.fetchIcons(h) } catch (_: Exception) { }
                    }
                    val real = com.example.mlbbresearch.game.WikiSkills.icons(h)
                    for ((_, fName) in real) files.add(fName)
                    val kit = com.example.mlbbresearch.game.SkillBook.get(h)
                    for (sl in com.example.mlbbresearch.game.HeroKit.slots(h)) {
                        val skName = kit[sl]?.name
                        if (!skName.isNullOrBlank() && skName != sl && sl !in real &&
                            com.example.mlbbresearch.game.ItemDb.canonical(skName) == null &&
                            com.example.mlbbresearch.game.SpellDb.match(skName) == null) {
                            files.add(itemFile(skName))
                        }
                    }
                }
                val total = files.size
                var okCount = 0
                var doneCount = 0
                for (file in files) {
                    doneCount++
                    loadAllStatus = "Loading icons: $doneCount / $total ($okCount ready)…"
                    val f = File(dir, file)
                    var bmp: Bitmap? = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
                    if (bmp == null) {
                        val tries = listOf(url(file), base(file), base(file) + "/scale-to-width-down/100",
                            "https://mobile-legends.fandom.com/wiki/Special:FilePath/" + java.net.URLEncoder.encode(file, "UTF-8").replace("+", "%20") + "?width=100")
                        for (u in tries) {
                            if (bmp != null) break
                            bmp = try {
                                val c = URL(u).openConnection() as HttpURLConnection
                                c.connectTimeout = 8000; c.readTimeout = 10000
                                c.instanceFollowRedirects = true
                                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36 MLBB-Research")
                                c.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/*,*/*;q=0.8")
                                if (c.responseCode == 200) {
                                    val bytes = c.inputStream.use { it.readBytes() }
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { f.writeBytes(bytes) }
                                } else null
                            } catch (_: Exception) { null }
                        }
                    }
                    if (bmp != null) {
                        synchronized(this@IconCache) { mem.put(file, bmp); failedAt.remove(file) }
                        com.example.mlbbresearch.vision.HeroVerifier.wikiPrint(file)
                        okCount++
                    }
                }
                val msg = "✓ Loaded $okCount / $total icons (heroes, skills, spells & equipment ready for in-game detection)"
                loadAllStatus = msg
                com.example.mlbbresearch.core.Diag.info("Library: $msg")
                main.post { done(msg) }
            } catch (e: Exception) {
                val msg = "Load all icons stopped: ${e.message}"
                loadAllStatus = msg
                main.post { done(msg) }
            } finally {
                loadingAll = false
            }
        }, "force-load-icons").start()
    }
}
