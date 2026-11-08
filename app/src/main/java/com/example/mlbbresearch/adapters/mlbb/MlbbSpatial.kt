package com.example.mlbbresearch.adapters.mlbb

import com.example.mlbbresearch.universal.Landmark
import com.example.mlbbresearch.universal.LandmarkKind
import com.example.mlbbresearch.universal.Region
import com.example.mlbbresearch.universal.RegionClassifier
import com.example.mlbbresearch.universal.RegionKind
import com.example.mlbbresearch.universal.Side
import com.example.mlbbresearch.universal.SpatialMap
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

// MLBB's map for the shared spatial model. Landmarks come from assets/spatial/mlbb_5v5.json (built
// offline by tools/spatial/extract_mlbb_map.py from the Imperial 3D reference model and checked
// against the owner's minimap; PRIVATE RESEARCH USE ONLY - see assets/spatial/NOTICE.md).
// Coordinates are the app's normalised minimap space (Layout mapTL..mapBR, as GameState.minimapSelf):
// your base bottom-left, the enemy base top-right.
object MlbbSpatial {

    const val ASSET = "spatial/mlbb_5v5.json"
    const val BASE_RADIUS = 0.16f      // around each base icon
    const val LANE_HALF = 0.07f        // mid lane: within this of the base-to-base diagonal (x + y = 1)
    const val EDGE_LANE = 0.13f        // side lanes run along the map edges
    const val RIVER_HALF = 0.05f       // river: within this of the other diagonal (x = y)

    // MLBB's layout, in the normalised minimap space (verified: turrets lie on these lines)
    val classifier = RegionClassifier { x, y ->
        when {
            hypot(x - 0.12f, y - 0.89f) < BASE_RADIUS -> Region(RegionKind.BASE, Side.ALLY, "your base")
            hypot(x - 0.87f, y - 0.09f) < BASE_RADIUS -> Region(RegionKind.BASE, Side.ENEMY, "enemy base")
            abs(x + y - 1f) < LANE_HALF -> Region(RegionKind.LANE, laneSide(x, y), "mid lane" + sideText(x, y))
            x < EDGE_LANE || y < EDGE_LANE -> Region(RegionKind.LANE, laneSide(x, y), "EXP lane" + sideText(x, y))
            x > 1f - EDGE_LANE || y > 1f - EDGE_LANE -> Region(RegionKind.LANE, laneSide(x, y), "Gold lane" + sideText(x, y))
            abs(x - y) < RIVER_HALF -> Region(RegionKind.RIVER, Side.NEUTRAL, if (x + y < 1f) "river (Lord side)" else "river (turtle side)")
            y > x -> Region(RegionKind.JUNGLE, Side.ALLY, "your jungle (" + (if (x + y < 1f) "EXP side" else "Gold side") + ")")
            else -> Region(RegionKind.JUNGLE, Side.ENEMY, "enemy jungle (" + (if (x + y < 1f) "EXP side" else "Gold side") + ")")
        }
    }

    // a lane point is on your half when it is closer to your base than to the enemy base
    private fun laneSide(x: Float, y: Float) = if (hypot(x - 0.12f, y - 0.89f) <= hypot(x - 0.87f, y - 0.09f)) Side.ALLY else Side.ENEMY
    private fun sideText(x: Float, y: Float) = if (laneSide(x, y) == Side.ALLY) ", your half" else ", enemy half"

    fun parse(json: String): SpatialMap {
        val o = JSONObject(json)
        val arr = o.getJSONArray("landmarks")
        val lm = (0 until arr.length()).map { i ->
            val l = arr.getJSONObject(i)
            Landmark(l.getString("id"), LandmarkKind.valueOf(l.getString("kind")), Side.valueOf(l.getString("side")),
                l.getString("label"), l.getDouble("x").toFloat(), l.getDouble("y").toFloat())
        }
        return SpatialMap("mlbb", lm, classifier, o.getJSONObject("source").getString("model"))
    }

    @Volatile private var cached: SpatialMap? = null
    fun load(ctx: android.content.Context): SpatialMap? = cached ?: try {
        parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() }).also { cached = it }
    } catch (e: Exception) { null }
}
