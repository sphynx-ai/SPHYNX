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
        // load the optional trained detector in the background if it was added before
        Thread { if (com.example.mlbbresearch.vision.ObjectDetector.modelFile(this).exists()) com.example.mlbbresearch.vision.ObjectDetector.load(this) }.start()
        Cfg.init(this)
        Diag.init(this)
        CrashLog.install()
        ExperienceRecorder.init(this)
        ScreenCaptureService.ensureChannel(this)
    }
}
