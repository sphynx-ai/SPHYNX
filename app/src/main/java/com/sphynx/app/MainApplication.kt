package com.sphynx.app

import android.app.Application
import com.sphynx.capture.ScreenCaptureService
import com.sphynx.core.Cfg
import com.sphynx.core.CrashLog
import com.sphynx.core.Diag
import com.sphynx.learning.ExperienceRecorder

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProbesWiring.install()      // before any core service reads the AI / game / vision (ADR 0001)
        Cfg.init(this)
        // the chosen theme, before any screen or the floating panel draws (the panel's symbol takes its colour)
        Theme.use(Cfg.theme)
        PanelTheme.apply()
        com.sphynx.core.SessionRecorder.init(this)
        com.sphynx.vision.HeroVerifier.init(this)
        // your rule change: Retribution on buffs at 20% (was 35%) - an old saved 35 moves too
        if (Cfg.raw("mig_retri20") == null) { if (Cfg.retriCreepPct == 35) Cfg.retriCreepPct = 20; Cfg.setRaw("mig_retri20", "1") }
        // v36.66: buttons wrongly marked as missing by misfires (Alpha's ultimate before level 4) are forgotten
        if (Cfg.raw("mig_kitabsent") == null) {
            for (k in Cfg.all().keys.filter { it.startsWith("kit_absent_") }) Cfg.setRaw(k, null)
            Cfg.setRaw("mig_kitabsent", "1")
        }
        // v36.68: skill pictures read again with the new reader (4-skill heroes lost one)
        // v36.69: read again (Chip's "Special" skill, Fredrinn's two Ultimates, greyed copies skipped);
        // Chip's and Fredrinn's skills are learned again with the new heading rules
        // v36.78: read again with the better picture choice (Beatrix's passive was an item icon)
        // v36.79: Chip's buttons: Shortcut (ultimate) 3rd, Why Walk? 4th - read and learned again
        // v36.80: skill counts remembered from the screen under the hero the app THOUGHT it was are dropped
        // (Fredrinn's 4 buttons were saved under Zilong) - only known kits are trusted now
        // v36.89: level badges remembered at miscounted levels are forgotten once (built-in ones stay)
        if (Cfg.raw("mig_lvl89") == null) {
            for (k in Cfg.all().keys.filter { it.startsWith("lvl_tpl_") }) Cfg.setRaw(k, null)
            Cfg.setRaw("mig_lvl89", "1")
        }
        if (Cfg.raw("mig_kit80") == null) {
            for (k in Cfg.all().keys.filter { it.startsWith("kit_screen_") }) Cfg.setRaw(k, null)
            Cfg.setRaw("mig_kit80", "1")
        }
        if (Cfg.raw("mig_chip79") == null) {
            for (k in Cfg.all().keys.filter { it == "skillicons_chip" || it == "skills_chip" }) Cfg.setRaw(k, null)
            Cfg.setRaw("mig_chip79", "1")
        }
        if (Cfg.raw("mig_icons78") == null) {
            for (k in Cfg.all().keys.filter { it.startsWith("skillicons_") }) Cfg.setRaw(k, null)
            Cfg.setRaw("mig_icons78", "1")
        }
        if (Cfg.raw("mig_icons69") == null) {
            for (k in Cfg.all().keys.filter { it.startsWith("skillicons_") || it == "skills_chip" || it == "skills_fredrinn" }) Cfg.setRaw(k, null)
            Cfg.setRaw("mig_icons68", "1"); Cfg.setRaw("mig_icons69", "1")
        }
        // v36.73: the AI-written texts of the Blessed Retributions and the roaming blessings are dropped
        // (Retributions: the wiki's words only; blessings are not equipment)
        if (Cfg.raw("mig_v73") == null) {
            for (n in com.sphynx.game.ItemDb.retributions + com.sphynx.game.ItemDb.current.filter { com.sphynx.game.ItemClass.isBlessing(it) })
                Cfg.setRaw(com.sphynx.game.ItemBook.key(n), null)
            Cfg.setRaw("mig_v73", "1")
        }
        // v37.16: reduce default tower range from 8% to 5% and clear old slow castgap_v3_* timings
        if (Cfg.raw("mig_v3716") == null) {
            if (Cfg.towerRangePct > 6) Cfg.towerRangePct = 5
            for (k in Cfg.all().keys.filter { it.startsWith("castgap_v3_") }) Cfg.setRaw(k, null)
            Cfg.setRaw("mig_v3716", "1")
        }
        Diag.init(this)
        CrashLog.install()
        // Phase 5 (P2): how the app ended last time (native crash, ANR, low memory) into the diagnostics log
        com.sphynx.core.ExitHistory.checkAsync(this)
        // Phase 5 (P4): heat changes into the diagnostics log (memory signals: onTrimMemory below)
        com.sphynx.core.PressureLog.install(this)
        // Phase 5 (P3): this phone's capabilities in one record, logged once per start (off the main thread: GPU name)
        Thread({
            try {
                val cpuinfo = try { java.io.File("/proc/cpuinfo").readText() } catch (e: Exception) { null }
                com.sphynx.core.DeviceProfile.collect(this,
                    com.sphynx.inference.LlamaCpu.unsupportedReason(android.os.Build.SUPPORTED_ABIS.toList(), cpuinfo))
            } catch (t: Throwable) { Diag.error("Device profile: could not read", t) }
        }, "device-profile").start()
        ExperienceRecorder.init(this)
        com.sphynx.learning.OnlineBackup.init(this)
        com.sphynx.learning.DatasetCollector.init(this)
        com.sphynx.learning.ScreenDatasetCollector.init(this)
        ScreenCaptureService.ensureChannel(this)
        // load the trained detector / tactical network added before
        Thread { if (com.sphynx.vision.ObjectDetector.modelFile(this).exists()) com.sphynx.vision.ObjectDetector.load(this) }.start()
        Thread { if (com.sphynx.vision.ScreenDetector.modelFile(this).exists()) com.sphynx.vision.ScreenDetector.load(this) }.start()
        // the tactical networks (one per hero + all heroes): the right one loads when your hero is known
        com.sphynx.learning.PolicyNet.init(this)
        Thread { com.sphynx.learning.PolicyNet.load(this) }.start()
        Thread { com.sphynx.learning.FightNet.load(this) }.start()
        // and pick up anything new in the Sphynx-Storage/Input folder (model, detector, network, Groq key)
        com.sphynx.models.AutoFolder.scan(this)
        // cloud training runs that were going when the app closed
        com.sphynx.learning.CloudTrain.resume(this)
        // the patch meta refreshes by itself when it is a week old (a minute after start, after the folder scan)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ com.sphynx.ai.MetaAdvisor.autoGeneral() }, 60_000)
        // Groq rate limit: a notification the moment it can be asked again
        com.sphynx.ai.GroqClient.onAvailable = { groqAvailableNotice() }
        com.sphynx.ai.GroqClient.schedule()
    }

    // Phase 5 (P4): Android asks the app to free memory; written down so a later low-memory close can be explained
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.sphynx.core.PressureLog.onTrim(level)
    }

    private fun groqAvailableNotice() {
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel("groq") == null)
                nm.createNotificationChannel(android.app.NotificationChannel("groq", "Groq available again", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val open = android.app.PendingIntent.getActivity(this, 41, android.content.Intent(this, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE)
            nm.notify(41, android.app.Notification.Builder(this, "groq")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Groq is available again")
                .setContentText("The rate limit is over: the online meta can update again")
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (e: Exception) { /* no notification permission: the in-app indicators still show it */ }
    }
}
