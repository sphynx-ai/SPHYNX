package com.sphynx.universal.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 11 P1: workloads and phone classes
class WorkloadsTest {

    @Test fun theTwoWorkloads() {
        assertEquals(1_000L, Workloads.TACTICAL.budgetMs)
        assertEquals(Workloads.Budget.USABLE_P90, Workloads.TACTICAL.budgetOn)
        assertTrue(Workloads.TACTICAL.askEveryMs < Workloads.STRATEGIC.askEveryMs)
        assertTrue(Workloads.STRATEGIC.wantsPictures)
        assertEquals(Workloads.STRATEGIC, Workloads.of(Workloads.Job.STRATEGIC))
    }

    @Test fun phoneClasses() {
        assertEquals(Workloads.PhoneClass.UNKNOWN, Workloads.phoneClass(0))
        assertEquals(Workloads.PhoneClass.ENTRY, Workloads.phoneClass(3_800))
        assertEquals(Workloads.PhoneClass.MIDRANGE, Workloads.phoneClass(5_800))     // a "6 GB" phone
        assertEquals(Workloads.PhoneClass.MIDRANGE, Workloads.phoneClass(7_600))
        assertEquals(Workloads.PhoneClass.FLAGSHIP, Workloads.phoneClass(11_400))    // the owner's phone (Phase 5 snapshot)
    }

    @Test fun memoryAndDeclaredClass() {
        assertEquals(1_500L, Workloads.modelRoomMb(4_000))
        assertEquals(0L, Workloads.modelRoomMb(1_000))
        assertEquals(Workloads.PhoneClass.ENTRY, Workloads.declaredClass(290))       // Gemma 3 270M
        assertEquals(Workloads.PhoneClass.MIDRANGE, Workloads.declaredClass(2_468))  // Gemma 4 E2B
        assertEquals(Workloads.PhoneClass.FLAGSHIP, Workloads.declaredClass(4_000))
    }
}
