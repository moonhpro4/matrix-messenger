package com.moonlight.matrixmessenger

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class ThemeMode { AUTO, LIGHT, DARK }

/**
 * Theme follows the device's system light/dark setting by default.
 * Settings lets the person override it to always-light or always-dark
 * instead — this preference persists locally and gets applied at app
 * startup, before any screen is shown.
 */
object ThemeManager {
    private const val PREFS_NAME = "matrix_theme"
    private const val KEY_MODE = "theme_mode"

    fun saveThemeMode(context: Context, mode: ThemeMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        applyThemeMode(mode)
    }

    fun getThemeMode(context: Context): ThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_MODE, ThemeMode.AUTO.name)
        return try { ThemeMode.valueOf(saved ?: ThemeMode.AUTO.name) } catch (e: Exception) { ThemeMode.AUTO }
    }

    /** Call once at app startup to apply whatever was last saved. */
    fun applySavedTheme(context: Context) {
        applyThemeMode(getThemeMode(context))
    }

    private fun applyThemeMode(mode: ThemeMode) {
        val nightMode = when (mode) {
            ThemeMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
}
