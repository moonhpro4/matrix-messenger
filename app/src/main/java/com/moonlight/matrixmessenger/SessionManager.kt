package com.moonlight.matrixmessenger

import android.content.Context

object SessionManager {
    private const val PREFS_NAME = "matrix_session"
    private const val KEY_IDENTITY = "logged_in_identity"

    fun saveSession(context: Context, fullIdentity: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_IDENTITY, fullIdentity).apply()
    }

    fun getSession(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_IDENTITY, null)
    }

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_IDENTITY).apply()
    }
}
