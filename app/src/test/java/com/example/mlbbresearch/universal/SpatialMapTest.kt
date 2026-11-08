package com.example.mlbbresearch.universal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpatialMapTest {

    private val map = SpatialMap("test",
        listOf(Landmark("a", LandmarkKind.TURRET, Side.ENEMY, "t1", 0.2f, 0.2f), Landmark("b", LandmarkKind.TURRET, Side.ALLY, "t2", 0.8f, 0.8f),
            Landmark("o", LandmarkKind.MAJOR_OBJECTIVE, Side.NEUTRAL, "boss", 0.5f, 0.5f)),
        RegionClassifier { x, _ -> if (x < 0.5f) Region(RegionKind.JUNGLE, Side.ALLY, "left") else Region(RegionKind.JUNGLE, Side.ENEMY, "right") },
        "test")

    @Test fun nearestFiltersByKindAndSide() {
        assertEquals("a", map.nearest(0.7f, 0.7f, LandmarkKind.TURRET, Side.ENEMY)!!.first.id)
        assertEquals("b", map.nearest(0.7f, 0.7f, LandmarkKind.TURRET)!!.first.id)
        assertNull(map.nearest(0.1f, 0.1f, LandmarkKind.BASE))
    }

    @Test fun coordinatesAreClampedIntoTheMap() {
        assertEquals("left", map.regionAt(-3f, 0f).name)
        assertEquals("right", map.regionAt(5f, 0f).name)
    }
}
