package com.daijunhao.classroommanager.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object ApiCache {
    private const val PREFS_DIR = "api_cache"
    private var cacheDirectory: File? = null
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (cacheDirectory == null) {
                cacheDirectory = File(context.cacheDir, PREFS_DIR).apply {
                    mkdirs()
                }
            }
        }
    }

    fun read(namespace: String, identity: String, maxAgeMillis: Long): String? {
        if (maxAgeMillis <= 0) return null
        synchronized(lock) {
            val file = fileFor(namespace, identity) ?: return null
            if (!file.isFile) return null
            return runCatching {
                val envelope = JSONObject(file.readText(Charsets.UTF_8))
                val savedAt = envelope.optLong("savedAt", 0)
                if (System.currentTimeMillis() - savedAt > maxAgeMillis) {
                    file.delete()
                    null
                } else {
                    envelope.optString("body").takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }
    }

    fun write(namespace: String, identity: String, body: String) {
        if (body.isBlank()) return
        synchronized(lock) {
            val file = fileFor(namespace, identity) ?: return
            runCatching {
                val envelope = JSONObject().apply {
                    put("savedAt", System.currentTimeMillis())
                    put("body", body)
                }
                file.writeText(envelope.toString(), Charsets.UTF_8)
            }
        }
    }

    fun clearNamespace(namespace: String) {
        synchronized(lock) {
            val directory = cacheDirectory ?: return
            directory.listFiles()
                ?.filter { it.name.startsWith("${safeName(namespace)}_") }
                ?.forEach { it.delete() }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            cacheDirectory?.listFiles()?.forEach { it.delete() }
        }
    }

    fun sizeBytes(): Long {
        synchronized(lock) {
            return cacheDirectory?.listFiles()?.sumOf { it.length() } ?: 0L
        }
    }

    fun tokenIdentity(token: String): String = sha256(token)

    private fun fileFor(namespace: String, identity: String): File? {
        val directory = cacheDirectory ?: return null
        return File(directory, "${safeName(namespace)}_${sha256(identity)}.json")
    }

    private fun safeName(value: String): String {
        return value.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(40)
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
