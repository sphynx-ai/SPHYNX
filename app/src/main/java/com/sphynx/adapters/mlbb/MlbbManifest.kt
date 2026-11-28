package com.sphynx.adapters.mlbb

import com.sphynx.universal.Capability
import com.sphynx.universal.sdk.CapabilityManifest
import com.sphynx.universal.sdk.CapabilityManifest.Evidence

/**
 * PHASE 14, step K1: MLBB's capability manifest. Evidence is what the repository really has: unit tests where a reader
 * or engine is tested, and a phone check where the owner tested it on the phone (Phases 5-9 checklists). Unsupported
 * features and source notes come from the Phase 13 source feasibility.
 */
object MlbbManifest {
    val evidence = mapOf(
        Capability.SCREEN_PHASE to Evidence.PHONE, Capability.OWN_CHARACTER_ID to Evidence.PHONE, Capability.UNIT_HEALTH to Evidence.PHONE,
        Capability.ENEMY_TRACKING to Evidence.UNIT_TEST, Capability.MINIMAP to Evidence.PHONE, Capability.COOLDOWNS to Evidence.UNIT_TEST,
        Capability.ITEMS_GOLD to Evidence.UNIT_TEST, Capability.OBJECTIVES to Evidence.UNIT_TEST, Capability.STRATEGIC_ADVICE to Evidence.PHONE,
        Capability.TACTICAL_ADVICE to Evidence.PHONE, Capability.DRAFT_ADVICE to Evidence.UNIT_TEST, Capability.BUILD_ADVICE to Evidence.UNIT_TEST,
        Capability.POST_MATCH to Evidence.UNIT_TEST,
    )

    fun manifest(): CapabilityManifest = CapabilityManifest.of(MlbbAdapter, evidence,
        unsupported = listOf("game recognition (the app assumes MLBB)", "official pick/win/ban statistics (no permitted access)",
            "live esports schedules and results (no source connected)"),
        sourceNotes = listOf("emblems: Mobile Legends Wiki Emblems page only (owner capture 2026-11-10, icons 2026-11-28)",
            "objective timers: the Wiki (JungleDb)", "online meta: app-generated from web search, labelled not official"))
}
