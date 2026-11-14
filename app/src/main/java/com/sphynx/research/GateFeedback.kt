package com.sphynx.research

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.sphynx.core.Diag

// THE GATE CAN BE FELT (owner, 2026-11-10): the floating window is hidden on the loading screen and the
// scoreboard, exactly where the practice gate decides - so the phone vibrates when it does:
//  - unlocked (verified practice vs Computer): one short buzz;
//  - locked (a real player was seen): three buzzes.
// Nothing is drawn on the screen (screen capture would record it over the names).
object GateFeedback {

    fun changed(state: PracticeGate.State) {
        val pattern = when (state) {
            PracticeGate.State.VERIFIED -> longArrayOf(0, 120)
            PracticeGate.State.REVOKED -> longArrayOf(0, 90, 110, 90, 110, 90)
            else -> return
        }
        try {
            val ctx = com.sphynx.vision.HeroVerifier.appContext() ?: return
            @Suppress("DEPRECATION")
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (t: Throwable) { Diag.error("Gate vibration", t) }
    }
}
