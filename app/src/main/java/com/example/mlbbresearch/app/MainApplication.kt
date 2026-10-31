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
        Diag.init(this)
        CrashLog.install()
        ExperienceRecorder.init(this)
        com.example.mlbbresearch.learning.DatasetCollector.init(this)
        com.example.mlbbresearch.learning.ScreenDatasetCollector.init(this)
        ScreenCaptureService.ensureChannel(this)
        // load the trained detector / tactical network added before
        Thread { if (com.example.mlbbresearch.vision.ObjectDetector.modelFile(this).exists()) com.example.mlbbresearch.vision.ObjectDetector.load(this) }.start()
        Thread { if (com.example.mlbbresearch.vision.ScreenDetector.modelFile(this).exists()) com.example.mlbbresearch.vision.ScreenDetector.load(this) }.start()
        com.example.mlbbresearch.learning.PolicyNet.applyInfo(Cfg.raw("policy_info"))
        Thread { if (com.example.mlbbresearch.learning.PolicyNet.modelFile(this).exists()) com.example.mlbbresearch.learning.PolicyNet.load(this) }.start()
        // and pick up anything new in the MLBB-Research folder (model, detector, network, Groq key)
        com.example.mlbbresearch.models.AutoFolder.scan(this)
    }
}
