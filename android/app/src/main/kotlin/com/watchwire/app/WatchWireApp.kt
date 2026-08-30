package com.watchwire.app

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class WatchWireApp : Application() {
    override fun onCreate() {
        super.onCreate()
        isOpenCvAvailable = OpenCVLoader.initLocal()
        Log.i("WatchWireApp", "OpenCV initialized: $isOpenCvAvailable")
        WatchWireRepository.init(this)
    }

    companion object {
        var isOpenCvAvailable: Boolean = false
            private set
    }
}
