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

data class UploadOptions(
    val parallelUploads: Boolean = true,
    val maxConcurrentUploads: Int = 3,
    val showDetailedProgress: Boolean = true
)

object AppSettings {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_FORCE_SUBMIT = "debug_force_submit"
    private const val KEY_SINGLE_READ = "debug_single_read"
    private const val KEY_SHOW_HIDDEN_ANSWERS = "debug_show_hidden_answers"
    private const val KEY_PARALLEL_UPLOADS = "parallel_uploads"
    private const val KEY_MAX_CONCURRENT_UPLOADS = "max_concurrent_uploads"
    private const val KEY_SHOW_UPLOAD_DETAILS = "show_upload_details"

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

    fun readUploadOptions(context: Context): UploadOptions {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return UploadOptions(
            parallelUploads = prefs.getBoolean(KEY_PARALLEL_UPLOADS, true),
            maxConcurrentUploads = prefs.getInt(
                KEY_MAX_CONCURRENT_UPLOADS,
                3
            ).coerceIn(2, 5),
            showDetailedProgress = prefs.getBoolean(
                KEY_SHOW_UPLOAD_DETAILS,
                true
            )
        )
    }

    fun saveUploadOptions(context: Context, options: UploadOptions) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_PARALLEL_UPLOADS, options.parallelUploads)
            putInt(
                KEY_MAX_CONCURRENT_UPLOADS,
                options.maxConcurrentUploads.coerceIn(2, 5)
            )
            putBoolean(
                KEY_SHOW_UPLOAD_DETAILS,
                options.showDetailedProgress
            )
        }
    }
}
