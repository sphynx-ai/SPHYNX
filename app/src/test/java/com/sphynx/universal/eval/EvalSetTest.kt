package com.sphynx.universal.eval

import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 10 E2: an evaluation set is checked before it counts
class EvalSetTest {

    @Test fun aBrokenSetIsRefused() {
        val good = """{"schema":1,"id":"x","version":1,"role":"tactical","items":[{"id":"a","kind":"choice","prompt":"p",
            "options":["A","B"],"best":["A"],"ok":["B"]}]}"""
        assertTrue(EvalSet.parse(good).isSuccess)
        assertTrue(EvalSet.parse(good.replace("\"ok\":[\"B\"]", "\"ok\":[\"C\"]")).isFailure)        // not an option
        assertTrue(EvalSet.parse(good.replace("\"ok\":[\"B\"]", "\"ok\":[\"A\"]")).isFailure)        // in two lists
        assertTrue(EvalSet.parse(good.replace("\"best\":[\"A\"],", "")).isFailure)                    // no best answer
        assertTrue(EvalSet.parse(good.replace("\"schema\":1", "\"schema\":2")).isFailure)
        assertTrue(EvalSet.parse(good.replace("\"kind\":\"choice\"", "\"kind\":\"plan\"")).isFailure) // a plan needs a field
        assertTrue(EvalSet.parse("{}").isFailure)
    }
}
