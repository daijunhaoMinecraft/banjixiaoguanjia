package com.daijunhao.classroommanager.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedScoreExport(
    val displayName: String,
    val displayPath: String,
    val uri: Uri?
)

object ScoreExportStore {
    fun fileName(export: ClassScoreExport): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
            .format(Date())
        return "${sanitize(export.title)}_${timestamp}.xlsx"
    }

    fun buildWorkbook(export: ClassScoreExport): ByteArray {
        val headers = buildList {
            add("姓名")
            addAll(export.subjects)
            add("查询状态")
            add("成员 ID")
        }
        val rows = export.rows.map { row ->
            buildList {
                add(row.name)
                export.subjects.forEach { subject ->
                    add(row.scores[subject].orEmpty())
                }
                add(row.status)
                add(row.memberId)
            }
        }
        val sheetName = "${export.title} ${export.examTime}".trim()
        return XlsxWriter.build(sheetName, headers, rows)
    }

    suspend fun save(
        context: Context,
        displayName: String,
        bytes: ByteArray
    ): Result<SavedScoreExport> = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(context, displayName, bytes)
            } else {
                saveToAppDocuments(context, displayName, bytes)
            }
        }
    }

    private fun saveToMediaStore(
        context: Context,
        displayName: String,
        bytes: ByteArray
    ): SavedScoreExport {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(
                MediaStore.MediaColumns.MIME_TYPE,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/ClassroomManager"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException("无法创建导出文件")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
            } ?: throw IllegalStateException("无法写入导出文件")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SavedScoreExport(
                displayName = displayName,
                displayPath = "下载/ClassroomManager/$displayName",
                uri = uri
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToAppDocuments(
        context: Context,
        displayName: String,
        bytes: ByteArray
    ): SavedScoreExport {
        val directory = context.getExternalFilesDir(
            Environment.DIRECTORY_DOCUMENTS
        ) ?: context.filesDir
        val exportDirectory = File(directory, "exports").apply { mkdirs() }
        val file = File(exportDirectory, displayName)
        file.writeBytes(bytes)
        return SavedScoreExport(
            displayName = displayName,
            displayPath = file.absolutePath,
            uri = null
        )
    }

    private fun sanitize(value: String): String {
        return value.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "班级成绩" }
            .take(80)
    }
}
