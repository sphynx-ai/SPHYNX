package com.example.mlbbresearch.app

import android.app.Application
import com.example.mlbbresearch.capture.ScreenCaptureService
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.CrashLog
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.learning.ExperienceRecorder

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Cfg.init(this)
        com.example.mlbbresearch.vision.HeroVerifier.init(this)
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
            for (n in com.example.mlbbresearch.game.ItemDb.retributions + com.example.mlbbresearch.game.ItemDb.current.filter { com.example.mlbbresearch.game.ItemClass.isBlessing(it) })
                Cfg.setRaw(com.example.mlbbresearch.game.ItemBook.key(n), null)
            Cfg.setRaw("mig_v73", "1")
        }
        Diag.init(this)
        CrashLog.install()
        ExperienceRecorder.init(this)
        com.example.mlbbresearch.learning.OnlineBackup.init(this)
        com.example.mlbbresearch.learning.DatasetCollector.init(this)
        com.example.mlbbresearch.learning.ScreenDatasetCollector.init(this)
        ScreenCaptureService.ensureChannel(this)
        // load the trained detector / tactical network added before
        Thread { if (com.example.mlbbresearch.vision.ObjectDetector.modelFile(this).exists()) com.example.mlbbresearch.vision.ObjectDetector.load(this) }.start()
        Thread { if (com.example.mlbbresearch.vision.ScreenDetector.modelFile(this).exists()) com.example.mlbbresearch.vision.ScreenDetector.load(this) }.start()
        // the tactical networks (one per hero + all heroes): the right one loads when your hero is known
        com.example.mlbbresearch.learning.PolicyNet.init(this)
        Thread { com.example.mlbbresearch.learning.PolicyNet.load(this) }.start()
        Thread { com.example.mlbbresearch.learning.FightNet.load(this) }.start()
        // and pick up anything new in the MLBB-Research folder (model, detector, network, Groq key)
        com.example.mlbbresearch.models.AutoFolder.scan(this)
        // cloud training runs that were going when the app closed
        com.example.mlbbresearch.learning.CloudTrain.resume(this)
        // the patch meta refreshes by itself when it is a week old (a minute after start, after the folder scan)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ com.example.mlbbresearch.ai.MetaAdvisor.autoGeneral() }, 60_000)
        // Groq rate limit: a notification the moment it can be asked again
        com.example.mlbbresearch.ai.GroqClient.onAvailable = { groqAvailableNotice() }
        com.example.mlbbresearch.ai.GroqClient.schedule()
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
