package com.example.mlbbresearch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonRepairTest {

    @Test fun aCompleteAnswerIsReadAsItIs() {
        val o = JsonRepair.parse("Here you go:\n```json\n{\"patch\":\"2.2.23\",\"bans\":[\"A\",\"B\"]}\n```")!!
        assertEquals("2.2.23", o.getString("patch"))
        assertEquals(2, o.getJSONArray("bans").length())
    }

    @Test fun anAnswerCutOffInAListKeepsItsCompletePart() {
        val o = JsonRepair.parse("{\"patch\":\"2.2.23 (November 11, 2026)\",\"bans\":[\"Hirara\",\"Belerick\",\"Gloo\",\"Su")!!
        assertEquals("2.2.23 (November 11, 2026)", o.getString("patch"))
        assertEquals(3, o.getJSONArray("bans").length())
    }

    @Test fun cutOffAfterAKeyOrInsideAString() {
        assertEquals(1, JsonRepair.parse("{\"a\":1,\"b\":")!!.getInt("a"))
        assertEquals("x, y", JsonRepair.parse("{\"a\":\"x, y\",\"b\":[1,2],\"c")!!.getString("a"))
    }

    @Test fun nothingUsable() {
        assertNull(JsonRepair.parse("no json here"))
        assertNull(JsonRepair.parse("{\"a"))
    }
}
