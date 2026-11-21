package com.moviebox.android

import android.app.Application

class MovieBoxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Bridge.init(this)
    }
}
