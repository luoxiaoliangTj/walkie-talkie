package com.example.walkie

import android.app.Application

class WalkieApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
    }
}
