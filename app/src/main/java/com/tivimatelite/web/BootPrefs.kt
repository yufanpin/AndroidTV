package com.tivimatelite.web

import android.content.Context

object BootPrefs {
    private const val PREFS_NAME = "boot_prefs"
    private const val KEY_AUTO_START = "auto_start_enabled"

    fun isAutoStartEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START, false)
    }

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_START, enabled)
            .apply()
        AppLogStore.i("BootPrefs", "Auto-start set to $enabled")
    }
}
