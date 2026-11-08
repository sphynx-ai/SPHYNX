package com.example.mlbbresearch.universal

import kotlin.math.hypot

// THE SHARED SPATIAL MODEL (game-independent). A game adapter supplies a map as landmarks in
// normalised minimap coordinates (x, y in 0..1; your side's base is where the adapter says) and a
// region classifier. The strategic engine gets short, structured context from it ("you are in the
// enemy jungle, 0.18 from the Lord pit") - never raw geometry, and never live positions: those come
// from the vision layer (e.g. GameState.minimapSelf).

enum class LandmarkKind { BASE, TURRET, MAJOR_OBJECTIVE, BUFF_CAMP, CAMP }

enum class RegionKind { BASE, LANE, RIVER, JUNGLE }

data class Landmark(val id: String, val kind: LandmarkKind, val side: Side, val label: String, val x: Float, val y: Float)

data class Region(val kind: RegionKind, val side: Side, val name: String)

fun interface RegionClassifier {
    fun regionAt(x: Float, y: Float): Region
}

class SpatialMap(
    val gameId: String,
    val landmarks: List<Landmark>,
    private val classifier: RegionClassifier,
    val provenance: String
) {
    fun regionAt(x: Float, y: Float): Region = classifier.regionAt(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))

    fun nearest(x: Float, y: Float, kind: LandmarkKind? = null, side: Side? = null): Pair<Landmark, Float>? =
        landmarks.filter { (kind == null || it.kind == kind) && (side == null || it.side == side) }
            .map { it to hypot(it.x - x, it.y - y) }.minByOrNull { it.second }

    // One line of map context for the strategic engine; distances are in map widths.
    fun describe(x: Float, y: Float): String {
        val r = regionAt(x, y)
        val parts = mutableListOf("position: ${r.name}")
        nearest(x, y, LandmarkKind.MAJOR_OBJECTIVE)?.let { (l, d) -> parts += "nearest objective: ${l.label} %.2f away".format(java.util.Locale.US, d) }
        nearest(x, y, LandmarkKind.TURRET, Side.ENEMY)?.let { (l, d) -> parts += "nearest enemy turret: ${l.label} %.2f away".format(java.util.Locale.US, d) }
        nearest(x, y, LandmarkKind.BASE, Side.ALLY)?.let { (_, d) -> parts += "your base %.2f away".format(java.util.Locale.US, d) }
        return parts.joinToString("; ")
    }
}
