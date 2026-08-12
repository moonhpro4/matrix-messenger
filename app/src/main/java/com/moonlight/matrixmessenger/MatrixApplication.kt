package com.moonlight.matrixmessenger

import android.app.Application

class MatrixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.applySavedTheme(this)
    }
}
