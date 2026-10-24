package com.daijunhao.classroommanager.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

data class TaskSetData(
    val mainId: String,
    val notifyId: String,
    val title: String,
    val subject: String,
    val startDay: String,
    val endDay: String,
    val media: List<SubmissionMedia>,
    val tasks: List<TaskSetTask>
) {
    val completedTaskCount: Int
        get() = tasks.count { it.status == 3 }
}

data class TaskSetTask(
    val id: String,
    val title: String,
    val startAt: Long,
    val endAt: Long,
    val startLabel: String,
    val endLabel: String,
    val status: Int,
    val media: List<SubmissionMedia>,
    val feedbacks: List<TaskFeedbackSummary>,
    val memberStatuses: Map<String, Int> = emptyMap(),
    val memberAcceptIds: Map<String, String> = emptyMap()
)

data class TaskFeedbackSummary(
    val id: String,
    val type: Int,
    val title: String,
    val subTitle: String,
    val unitTitle: String,
    val sheetId: String
)

data class TaskHistory(
    val taskId: String,
    val taskTitle: String,
    val submittedAt: String?,
    val feedbacks: List<TaskFeedbackHistory>
)

data class TaskFeedbackHistory(
    val feedbackId: String,
    val type: Int,
    val title: String,
    val subTitle: String,
    val submitted: Boolean,
    val status: Int?,
    val score: String?,
    val durationSeconds: Int?,
    val media: List<SubmissionMedia>,
    val error: String? = null
)

data class TaskCompletionState(
    val acceptId: String?,
    val submittedSheetIds: Set<String>,
    val allSheetsSubmitted: Boolean
)

enum class SubmissionMediaKind {
    IMAGE,
    VIDEO,
    AUDIO,
    FILE
}

data class SubmissionMedia(
    val path: String,
    val url: String,
    val kind: SubmissionMediaKind,
    val name: String
)

object TaskSetApi {
    private const val TAG = "TaskSetApi"
    private const val BASE_URL = "https://b.welife001.com"
    private const val COS_IMAGE_URL = "https://img.banjixiaoguanjia.com/"
    private const val COS_VIDEO_URL = "https://video.banjixiaoguanjia.com/"
    private const val COS_AUDIO_URL = "https://record.banjixiaoguanjia.com/"
    private const val COS_FILE_URL = "https://file.banjixiaoguanjia.com/"
    private const val OSS_IMAGE_URL = "https://img.welife001.com/"
    private const val OSS_VIDEO_URL = "https://video.welife001.com/"
    private const val OSS_AUDIO_URL = "https://record.welife001.com/"
    private const val OSS_FILE_URL = "https://file.welife001.com/"
    private const val TOS_IMAGE_URL = "https://tosimg.banjixiaoguanjia.com/"
    private const val TOS_VIDEO_URL = "https://tosvideo.banjixiaoguanjia.com/"
    private const val TOS_AUDIO_URL = "https://tosrecord.banjixiaoguanjia.com/"
    private const val TOS_FILE_URL = "https://tosfile.banjixiaoguanjia.com/"

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getTaskSet(
        token: String,
        memberId: String,
        cid: String,
        mainId: String
    ): Result<TaskSetData> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("main_id", mainId)
                put("member_id", memberId)
                put("cid", cid)
                put("page", 0)
                put("size", 100)
            }
            val response = postData(
                token = token,
                path = "/applet/task/details/getParentTasks",
                body = body
            )
            val data = response.optJSONObject("data")
                ?: return@withContext Result.failure(Exception("主任务数据为空"))
            Result.success(parseTaskSet(data, cid, mainId))
        } catch (e: Exception) {
            Log.e(TAG, "getTaskSet failed", e)
            Result.failure(e)
        }
    }

    suspend fun getTaskHistory(
        token: String,
        memberId: String,
        cid: String,
        mainId: String,
        notifyId: String,
        task: TaskSetTask
    ): Result<TaskHistory> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("main_id", mainId)
                put("member_id", memberId)
                put("cls", cid)
                put("task_id", task.id)
                put("notify_id", notifyId)
            }
            val response = postData(
                token = token,
                path = "/applet/task/details/getParentTaskDesc",
                body = body
            )
            val data = response.optJSONObject("data")
                ?: return@withContext Result.failure(Exception("任务详情数据为空"))
            val accepts = data.optJSONArray("accepts") ?: JSONArray()
            val firstAccept = accepts.optJSONObject(0)
            val acceptedBySheet = parseAcceptedFeedback(firstAccept)

            val histories = coroutineScope {
                task.feedbacks.map { feedback ->
                    async {
                        val accepted = acceptedBySheet[feedback.sheetId]
                        if (accepted == null) {
                            TaskFeedbackHistory(
                                feedbackId = feedback.id,
                                type = feedback.type,
                                title = feedback.title,
                                subTitle = feedback.subTitle,
                                submitted = false,
                                status = null,
                                score = null,
                                durationSeconds = null,
                                media = emptyList()
                            )
                        } else {
                            parseSubmittedFeedback(
                                token = token,
                                feedback = feedback,
                                accepted = accepted
                            )
                        }
                    }
                }.awaitAll()
            }

            Result.success(
                TaskHistory(
                    taskId = task.id,
                    taskTitle = task.title,
                    submittedAt = firstAccept?.optString("create_at")
                        ?.takeIf { it.isNotBlank() },
                    feedbacks = histories
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "getTaskHistory failed", e)
            Result.failure(e)
        }
    }

    suspend fun getTaskCompletionState(
        token: String,
        memberId: String,
        cid: String,
        mainId: String,
        notifyId: String,
        taskId: String
    ): Result<TaskCompletionState> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("main_id", mainId)
                put("member_id", memberId)
                put("cls", cid)
                put("task_id", taskId)
                put("notify_id", notifyId)
            }
            val response = postData(
                token = token,
                path = "/applet/task/details/getParentTaskDesc",
                body = body
            )
            val data = response.optJSONObject("data")
                ?: return@withContext Result.failure(Exception("任务详情数据为空"))
            val task = data.optJSONObject("task") ?: JSONObject()
            val taskFeedbacks = task.optJSONArray("feedback") ?: JSONArray()
            val allSheetIds = buildSet {
                for (index in 0 until taskFeedbacks.length()) {
                    val feedback = taskFeedbacks.optJSONObject(index) ?: continue
                    feedback.optString("attach")
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
            val accepts = data.optJSONArray("accepts") ?: JSONArray()
            val accept = (0 until accepts.length())
                .mapNotNull { accepts.optJSONObject(it) }
                .firstOrNull { it.optBoolean("is_feedback", false) }
                ?: accepts.optJSONObject(0)
            val acceptedFeedbacks = accept
                ?.optJSONObject("attach")
                ?.optJSONArray("feedback")
                ?: JSONArray()
            val submittedSheetIds = buildSet {
                for (index in 0 until acceptedFeedbacks.length()) {
                    val feedback = acceptedFeedbacks.optJSONObject(index) ?: continue
                    feedback.optString("pub")
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
            Result.success(
                TaskCompletionState(
                    acceptId = accept
                        ?.optJSONObject("attach")
                        ?.optString("accept_id")
                        ?.takeIf { it.isNotBlank() },
                    submittedSheetIds = submittedSheetIds,
                    allSheetsSubmitted = allSheetIds.isNotEmpty() &&
                        submittedSheetIds.containsAll(allSheetIds)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "getTaskCompletionState failed", e)
            Result.failure(e)
        }
    }

    private fun parseTaskSet(
        data: JSONObject,
        cid: String,
        fallbackMainId: String
    ): TaskSetData {
        val notify = data.optJSONObject("notify") ?: JSONObject()
        val tasksJson = data.optJSONArray("tasks") ?: JSONArray()
        val tasks = buildList {
            for (index in 0 until tasksJson.length()) {
                val task = tasksJson.optJSONObject(index) ?: continue
                val startAt = parseTimestamp(task.opt("start"))
                val endAt = parseTimestamp(task.opt("end"))
                val feedbacksJson = task.optJSONArray("feedback") ?: JSONArray()
                val classFeedbackInfo = task.optJSONObject("feedback_info")
                    ?.optJSONArray(cid)
                    ?: task.optJSONObject("feedback_info")?.let { info ->
                        val keys = info.keys()
                        var selected: JSONArray? = null
                        while (keys.hasNext() && selected == null) {
                            selected = info.optJSONArray(keys.next())
                        }
                        selected
                    }
                    ?: JSONArray()
                val memberStatuses = linkedMapOf<String, Int>()
                val memberAcceptIds = linkedMapOf<String, String>()
                for (feedbackIndex in 0 until classFeedbackInfo.length()) {
                    val memberFeedback = classFeedbackInfo.optJSONObject(feedbackIndex)
                        ?: continue
                    val memberId = memberFeedback.optString("mid")
                    if (memberId.isBlank()) continue
                    memberStatuses[memberId] = memberFeedback.optInt("status", 0)
                    memberFeedback.optString("aid")
                        .takeIf { it.isNotBlank() }
                        ?.let { memberAcceptIds[memberId] = it }
                }
                val feedbacks = buildList {
                    for (feedbackIndex in 0 until feedbacksJson.length()) {
                        val feedback = feedbacksJson.optJSONObject(feedbackIndex) ?: continue
                        val id = feedback.optString("_id")
                        if (id.isBlank()) continue
                        add(
                            TaskFeedbackSummary(
                                id = id,
                                type = feedback.optInt("type", -1),
                                title = feedback.optString("title", "任务模块"),
                                subTitle = feedback.optString("sub_title"),
                                unitTitle = feedback.optString("unit_title"),
                                sheetId = feedback.optString("attach")
                            )
                        )
                    }
                }
                add(
                    TaskSetTask(
                        id = task.optString("_id"),
                        title = task.optString("title", "未命名任务"),
                        startAt = startAt,
                        endAt = endAt,
                        startLabel = formatTimestamp(startAt),
                        endLabel = formatTimestamp(endAt),
                        status = task.optInt("status", 0),
                        media = extractSubmissionMedia(task),
                        feedbacks = feedbacks,
                        memberStatuses = memberStatuses,
                        memberAcceptIds = memberAcceptIds
                    )
                )
            }
        }

        return TaskSetData(
            mainId = notify.optString("attach").ifBlank { fallbackMainId },
            notifyId = notify.optString("id")
                .ifBlank { notify.optString("_id") },
            title = notify.optString("title", "任务合集"),
            subject = notify.optString("subject"),
            startDay = formatServerDate(notify.optString("daka_start_day")),
            endDay = formatServerDate(notify.optString("daka_end_day")),
            media = extractSubmissionMedia(notify),
            tasks = tasks
        )
    }

    private data class AcceptedFeedback(
        val answerPaperId: String,
        val status: Int,
        val score: String?,
        val durationSeconds: Int?,
        val raw: JSONObject
    )

    private fun parseAcceptedFeedback(accept: JSONObject?): Map<String, AcceptedFeedback> {
        val feedbackArray = accept
            ?.optJSONObject("attach")
            ?.optJSONArray("feedback")
            ?: return emptyMap()
        return buildMap {
            for (index in 0 until feedbackArray.length()) {
                val feedback = feedbackArray.optJSONObject(index) ?: continue
                val sheetId = feedback.optString("pub")
                val answerPaperId = feedback.optString("attach")
                if (sheetId.isBlank() || answerPaperId.isBlank()) continue
                val grade = feedback.optInt("grade", -1)
                val duration = feedback.optInt("duration", 0)
                put(
                    sheetId,
                    AcceptedFeedback(
                        answerPaperId = answerPaperId,
                        status = feedback.optInt("status", 0),
                        score = grade.takeIf { it >= 0 }?.toString(),
                        durationSeconds = duration.takeIf { it > 0 },
                        raw = feedback
                    )
                )
            }
        }
    }

    private fun parseSubmittedFeedback(
        token: String,
        feedback: TaskFeedbackSummary,
        accepted: AcceptedFeedback
    ): TaskFeedbackHistory {
        val paths = linkedSetOf<String>()
        extractSubmissionMedia(accepted.raw).forEach { paths.add(it.path) }
        val error = try {
            val answerPaper = fetchAnswerPaper(token, accepted.answerPaperId)
            if (answerPaper != null) {
                extractSubmissionMedia(answerPaper).forEach { paths.add(it.path) }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Unable to load answer paper ${accepted.answerPaperId}", e)
            e.message ?: "加载提交内容失败"
        }
        val media = paths.mapNotNull { resolveSubmissionMedia(it) }
            .distinctBy { it.path }
        return TaskFeedbackHistory(
            feedbackId = feedback.id,
            type = feedback.type,
            title = feedback.title,
            subTitle = feedback.subTitle,
            submitted = true,
            status = accepted.status,
            score = accepted.score,
            durationSeconds = accepted.durationSeconds,
            media = media,
            error = error
        )
    }

    internal fun extractSubmissionMedia(value: Any?): List<SubmissionMedia> {
        val paths = linkedSetOf<String>()
        collectMediaPaths(value, paths)
        return paths.mapNotNull { resolveSubmissionMedia(it) }
            .distinctBy { it.path }
    }

    private fun fetchAnswerPaper(
        token: String,
        answerPaperId: String
    ): JSONObject? {
        val url = "$BASE_URL/applet/content/getAnswerPaper".toHttpUrl().newBuilder()
            .addQueryParameter("id", answerPaperId)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { addCommonHeaders(token) }
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw httpError(response.code, responseBody)
            }
            val envelope = JSONObject(responseBody)
            if (envelope.optInt("code", -1) != 0) {
                throw Exception(envelope.optString("msg", "读取提交内容失败"))
            }
            return envelope.optJSONObject("data")
        }
    }

    private fun postData(
        token: String,
        path: String,
        body: JSONObject
    ): JSONObject {
        val cacheIdentity = "$token|$path|${body}"
        ApiCache.read(
            namespace = "tasks",
            identity = cacheIdentity,
            maxAgeMillis = 90_000L
        )?.let { cached ->
            return JSONObject(cached)
        }
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .post(body.toString().toRequestBody(jsonMedia))
            .apply { addCommonHeaders(token) }
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw httpError(response.code, responseBody)
            }
            val envelope = JSONObject(responseBody)
            if (envelope.optInt("code", -1) != 0) {
                throw Exception(envelope.optString("msg", "请求任务数据失败"))
            }
            ApiCache.write("tasks", cacheIdentity, responseBody)
            return envelope
        }
    }

    private fun Request.Builder.addCommonHeaders(token: String) {
        header("User-Agent", "Dart/3.11 (dart:io)")
        header("App-info", "1/3.11.7/1072")
        header("Authorization", token)
        header(
            "Other-info",
            "${System.currentTimeMillis()}/${UUID.randomUUID().toString().replace("-", "")}"
        )
    }

    private fun collectMediaPaths(value: Any?, paths: MutableSet<String>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    collectMediaPaths(value.opt(keys.next()), paths)
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectMediaPaths(value.opt(index), paths)
                }
            }

            is String -> {
                MEDIA_PATH_REGEX.findAll(value).forEach { match ->
                    paths.add(match.groupValues[1])
                }
                MEDIA_URL_REGEX.findAll(value).forEach { match ->
                    paths.add(match.value)
                }
            }
        }
    }

    internal fun resolveSubmissionMedia(path: String): SubmissionMedia? {
        val cleanPath = path.trim().trim('"', '\'')
        if (cleanPath.isBlank()) return null
        val kind = mediaKind(cleanPath)
        val url = when {
            cleanPath.startsWith("http://") || cleanPath.startsWith("https://") -> {
                normalizeKnownMediaUrl(cleanPath, kind)
            }
            cleanPath.contains("_cos/") -> storageUrl(kind, "_cos") + cleanPath
            cleanPath.contains("_oss/") -> storageUrl(kind, "_oss") + cleanPath
            cleanPath.contains("_tos/") -> storageUrl(kind, "_tos") + cleanPath
            else -> return null
        }
        return SubmissionMedia(
            path = cleanPath,
            url = url,
            kind = kind,
            name = cleanPath.substringAfterLast('/').ifBlank { cleanPath }
        )
    }

    private fun mediaKind(path: String): SubmissionMediaKind {
        val extension = path.substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase(Locale.US)
        return when {
            extension in IMAGE_EXTENSIONS -> SubmissionMediaKind.IMAGE
            extension in VIDEO_EXTENSIONS -> SubmissionMediaKind.VIDEO
            extension in AUDIO_EXTENSIONS -> SubmissionMediaKind.AUDIO
            path.contains("/app_video/") -> SubmissionMediaKind.VIDEO
            path.contains("/app_image/") -> SubmissionMediaKind.IMAGE
            path.contains("/app_record/") -> SubmissionMediaKind.AUDIO
            path.startsWith("_cos/") -> SubmissionMediaKind.IMAGE
            path.startsWith("_oss/") -> SubmissionMediaKind.VIDEO
            path.startsWith("_tos/") -> SubmissionMediaKind.AUDIO
            else -> SubmissionMediaKind.FILE
        }
    }

    private fun storageUrl(
        kind: SubmissionMediaKind,
        prefix: String
    ): String {
        return when (prefix) {
            "_cos" -> when (kind) {
                SubmissionMediaKind.IMAGE -> COS_IMAGE_URL
                SubmissionMediaKind.VIDEO -> COS_VIDEO_URL
                SubmissionMediaKind.AUDIO -> COS_AUDIO_URL
                SubmissionMediaKind.FILE -> COS_FILE_URL
            }
            "_oss" -> when (kind) {
                SubmissionMediaKind.IMAGE -> OSS_IMAGE_URL
                SubmissionMediaKind.VIDEO -> OSS_VIDEO_URL
                SubmissionMediaKind.AUDIO -> OSS_AUDIO_URL
                SubmissionMediaKind.FILE -> OSS_FILE_URL
            }
            else -> when (kind) {
                SubmissionMediaKind.IMAGE -> TOS_IMAGE_URL
                SubmissionMediaKind.VIDEO -> TOS_VIDEO_URL
                SubmissionMediaKind.AUDIO -> TOS_AUDIO_URL
                SubmissionMediaKind.FILE -> TOS_FILE_URL
            }
        }
    }

    private fun normalizeKnownMediaUrl(
        url: String,
        kind: SubmissionMediaKind
    ): String {
        val legacy = KNOWN_MEDIA_HOSTS.firstOrNull {
            url.startsWith(it)
        } ?: return url
        val relative = url.removePrefix(legacy)
        val prefix = when {
            relative.startsWith("_cos/") -> "_cos"
            relative.startsWith("_oss/") -> "_oss"
            relative.startsWith("_tos/") -> "_tos"
            else -> return url
        }
        return storageUrl(kind, prefix) + relative
    }

    private fun parseTimestamp(value: Any?): Long {
        val raw = value?.toString()?.trim().orEmpty()
        if (raw.isBlank() || raw == "null") return 0L
        raw.toLongOrNull()?.let { return it }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        patterns.forEach { pattern ->
            val timestamp = try {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }.parse(raw)?.time
            } catch (_: Exception) {
                null
            }
            if (timestamp != null) return timestamp
        }
        return 0L
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0) return ""
        return SimpleDateFormat("MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(timestamp))
    }

    private fun formatServerDate(raw: String): String {
        if (raw.isBlank()) return ""
        val timestamp = parseTimestamp(raw)
        if (timestamp <= 0) return raw
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(timestamp))
    }

    private fun httpError(statusCode: Int, body: String): Exception {
        val preview = body.replace(Regex("\\s+"), " ").take(240)
        return Exception(
            if (preview.isBlank()) "请求失败（HTTP $statusCode）"
            else "请求失败（HTTP $statusCode）：$preview"
        )
    }

    private val MEDIA_PATH_REGEX = Regex(
        """([^\s"'<>]*(?:_oss|_cos|_tos)/[^\s"'<>]+)"""
    )
    private val KNOWN_MEDIA_HOSTS = listOf(
        "https://img-1302562365.cos.ap-beijing.myqcloud.com/",
        "https://campusvideo.oss-cn-beijing.aliyuncs.com/",
        "https://img.banjixiaoguanjia.com/",
        "https://video.banjixiaoguanjia.com/",
        "https://record.banjixiaoguanjia.com/",
        "https://file.banjixiaoguanjia.com/",
        "https://img.welife001.com/",
        "https://video.welife001.com/",
        "https://record.welife001.com/",
        "https://file.welife001.com/",
        "https://tosimg.banjixiaoguanjia.com/",
        "https://tosvideo.banjixiaoguanjia.com/",
        "https://tosrecord.banjixiaoguanjia.com/",
        "https://tosfile.banjixiaoguanjia.com/"
    )
    private val MEDIA_URL_REGEX = Regex(
        """https?://(?:img|video|record|file|album|tosimg|tosvideo|tosrecord|tosfile)\.(?:welife001|banjixiaoguanjia)\.com/[^\s"'<>]+""" +
            """|https?://(?:campusvideo\.oss-cn-beijing\.aliyuncs\.com|img-1302562365\.cos\.ap-beijing\.myqcloud\.com)/[^\s"'<>]+"""
    )
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "webm", "m3u8", "3gp", "mkv")
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "wav", "aac", "ogg")
}
