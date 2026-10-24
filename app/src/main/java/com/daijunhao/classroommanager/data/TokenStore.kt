package com.daijunhao.classroommanager.data

import android.content.Context
import androidx.core.content.edit

object TokenStore {
    private const val PREFS_NAME = "auth_prefs"
    private const val KEY_TOKEN = "session_token"
    private const val KEY_MEMBER_ID = "member_id" // 新增 member_id 键

    fun save(context: Context, token: String, memberId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_TOKEN, token)
            putString(KEY_MEMBER_ID, memberId)
        }
    }

    fun readToken(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
    }

    fun readMemberId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MEMBER_ID, null)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_TOKEN)
            remove(KEY_MEMBER_ID)
        }
    }
}