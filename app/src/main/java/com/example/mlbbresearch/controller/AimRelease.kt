package com.example.mlbbresearch.controller

import com.example.mlbbresearch.ai.Intent
import com.example.mlbbresearch.ai.TacticalDecision

// AIM AT RELEASE (issue #2, from the owner's 13 session recordings): an aimed skill is pressed, held
// ~0.3 s, then dragged and released. The release used to re-aim with whatever the AI decided by then -
// in the recordings that decision had changed for a third of all skills (target lost -> "walking
// direction", or switched to retreat -> "away"), so the skill went 90+ degrees off: "aimed at the right
// direction but cast elsewhere" (median change 40 degrees, 246 of 715 over 90).
// Now the release decision is chosen here:
//  - the planned aim had no target, or was a retreat: keep the planned direction;
//  - the AI is still after the SAME target (same id, or the same spot for a monster): re-aim at it
//    with its live position (moving targets are still followed);
//  - anything else (target lost or changed, intent changed): aim at the PLANNED target, tracked live
//    if it is still seen, otherwise keep the planned direction.
object AimRelease {

    // which decision to re-aim with at release; null = keep the planned end as it is
    fun releaseDecision(planned: TacticalDecision?, latest: TacticalDecision, sameSpot: (Float, Float, Float, Float) -> Boolean): TacticalDecision? {
        val p = planned ?: return null
        if (p.targetX < 0f || p.intent == Intent.RETREAT) return null
        val same = latest.intent == p.intent && latest.targetX >= 0f &&
            (if (p.targetId >= 0) latest.targetId == p.targetId else sameSpot(latest.targetX, latest.targetY, p.targetX, p.targetY))
        return if (same) latest else p
    }
}
