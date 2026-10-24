package com.daijunhao.classroommanager.data

import android.content.Context
import androidx.core.content.edit

data class DebugOptions(
    val forceSubmit: Boolean = false,
    val singleRead: Boolean = false,
    val showHiddenAnswers: Boolean = false
) {
    val isActive: Boolean
        get() = forceSubmit || singleRead || showHiddenAnswers
}

object AppSettings {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_FORCE_SUBMIT = "debug_force_submit"
    private const val KEY_SINGLE_READ = "debug_single_read"
    private const val KEY_SHOW_HIDDEN_ANSWERS = "debug_show_hidden_answers"

    fun readDebugOptions(context: Context): DebugOptions {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return DebugOptions(
            forceSubmit = prefs.getBoolean(KEY_FORCE_SUBMIT, false),
            singleRead = prefs.getBoolean(KEY_SINGLE_READ, false),
            showHiddenAnswers = prefs.getBoolean(KEY_SHOW_HIDDEN_ANSWERS, false)
        )
    }

    fun saveDebugOptions(context: Context, options: DebugOptions) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_FORCE_SUBMIT, options.forceSubmit)
            putBoolean(KEY_SINGLE_READ, options.singleRead)
            putBoolean(KEY_SHOW_HIDDEN_ANSWERS, options.showHiddenAnswers)
        }
    }

    fun resetDebugOptions(context: Context) {
        saveDebugOptions(context, DebugOptions())
    }
}
