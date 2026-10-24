package com.daijunhao.classroommanager.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class CheckInFile(
    val objectKey: String,
    val name: String,
    val size: Long = 0,
    val durationSeconds: Int = 0,
    val category: String = "video"
)

data class CheckInItem(
    val id: String,
    val title: String,
    val cate: Int,
    val seq: Int,
    val required: Boolean,
    val hasWaterMark: Boolean,
    val hasSplicing: Boolean,
    val limitMaxSelect: Int,
    val savedFiles: List<CheckInFile>,
    val content: String?
)

data class CheckInDetail(
    val notifyId: String,
    val investId: String,
    val title: String,
    val subject: String,
    val textContent: String,
    val endDay: String,
    val cid: String,
    val dakaDay: String,
    val isPrivate: Boolean,
    val isFeedback: Boolean,
    val needFeedback: Boolean,
    val items: List<CheckInItem>,
    val feedbackText: String,
    val hasDraft: Boolean,
    val submittedAt: String?,
    val media: List<SubmissionMedia>
)

enum class CheckInStudentState {
    SUBMITTED,
    WRONG,
    NOT_SUBMITTED
}

data class CheckInClassStudent(
    val memberId: String,
    val name: String,
    val studentNumber: Int,
    val state: CheckInStudentState,
    val statusCode: Int?,
    val acceptId: String?
)

data class CheckInClassAnalysis(
    val title: String,
    val students: List<CheckInClassStudent>
) {
    val submittedCount: Int
        get() = students.count { it.state == CheckInStudentState.SUBMITTED }

    val wrongCount: Int
        get() = students.count { it.state == CheckInStudentState.WRONG }

    val notSubmittedCount: Int
        get() = students.count { it.state == CheckInStudentState.NOT_SUBMITTED }
}

enum class CheckInUploadType(
    val category: String,
    val directory: String,
    val cloud: String,
    val cloudName: String,
    val bucket: String,
    val uploadUrl: String
) {
    VIDEO(
        category = "video",
        directory = "app_video",
        cloud = "oss",
        cloudName = "_oss",
        bucket = "campusvideo",
        uploadUrl = "https://campusvideo.oss-cn-beijing.aliyuncs.com/"
    ),
    IMAGE(
        category = "img",
        directory = "app_image",
        cloud = "cos",
        cloudName = "_cos",
        bucket = "img-1302562365",
        uploadUrl = "https://img-1302562365.cos.ap-beijing.myqcloud.com/"
    )
}

data class UploadCredentials(
    val uploadType: CheckInUploadType,
    val formData: Map<String, String>,
    val expireAt: Long
)

data class LocalMedia(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    val durationSeconds: Int,
    val uploadType: CheckInUploadType
)

data class UploadProgress(
    val uploadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val remainingMillis: Long?
) {
    val fraction: Float
        get() {
            if (totalBytes <= 0) return 0f
            return (uploadedBytes.toDouble() / totalBytes)
                .toFloat()
                .coerceIn(0f, 1f)
        }
}

enum class CheckInSubmitType(val value: String) {
    DRAFT("draft"),
    SUBMIT("submit")
}

object CheckInApi {
    private const val TAG = "CheckInApi"
    private const val BASE_URL = "https://b.welife001.com"

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Large classroom media can take several minutes to upload on a mobile network.
    private val uploadClient = apiClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        })
        .build()
    private val lastObjectTimestamp = AtomicLong(0)

    suspend fun getCheckInDetail(
        token: String,
        memberId: String,
        notifyId: String,
        cid: String
    ): Result<CheckInDetail> = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("_id", notifyId)
                put("cid", cid)
                put("member_id", memberId)
                put("cls_ts", System.currentTimeMillis())
                put("daka_day", "")
            }
            val cacheIdentity = "$token|$memberId|$notifyId|$cid"
            val cachedBody = ApiCache.read(
                namespace = "checkins",
                identity = cacheIdentity,
                maxAgeMillis = 60_000L
            )

            val request = Request.Builder()
                .url("$BASE_URL/applet/notify/checkNew2Parent")
                .post(bodyJson.toString().toRequestBody(jsonMedia))
                .apply { addCommonHeaders(token) }
                .build()

            val responseBody = if (cachedBody != null) {
                cachedBody
            } else {
                apiClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw httpError(response.code, body)
                    }
                    if (body.isNotBlank()) {
                        ApiCache.write("checkins", cacheIdentity, body)
                    }
                    body
                }
            }

            val envelope = JSONObject(responseBody)
            val code = envelope.optInt("code", -1)
            if (code != 0) {
                return@withContext Result.failure(
                    Exception(envelope.optString("msg", "获取打卡详情失败"))
                )
            }

            val data = envelope.optJSONObject("data")
                ?: return@withContext Result.failure(Exception("打卡详情数据为空"))
            Result.success(
                parseDetail(
                    data = data,
                    fallbackCid = cid,
                    memberId = memberId
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "getCheckInDetail failed", e)
            Result.failure(e)
        }
    }

    suspend fun getUploadCredentials(
        token: String,
        uploadType: CheckInUploadType
    ): Result<UploadCredentials> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("cloud", uploadType.cloud)
                    put("cloudName", uploadType.cloudName)
                    put("bucket", uploadType.bucket)
                }.toString().toRequestBody(jsonMedia)

                val request = Request.Builder()
                    .url("$BASE_URL/applet/x-clouder/getCloudStsV3")
                    .post(body)
                    .apply { addCommonHeaders(token) }
                    .build()

                apiClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            httpError(response.code, responseBody)
                        )
                    }

                    val envelope = JSONObject(responseBody)
                    if (envelope.optInt("code", -1) != 0) {
                        return@withContext Result.failure(
                            Exception(envelope.optString("msg", "获取上传凭据失败"))
                        )
                    }

                    val data = envelope.optJSONObject("data")
                        ?: return@withContext Result.failure(Exception("上传凭据数据为空"))
                    val formDataJson = data.optJSONObject("mergeFormData")
                        ?: return@withContext Result.failure(Exception("上传凭据缺少 mergeFormData"))
                    val formData = buildMap {
                        val keys = formDataJson.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (!formDataJson.isNull(key)) {
                                put(key, formDataJson.optString(key))
                            }
                        }
                    }
                    Result.success(
                        UploadCredentials(
                            uploadType = uploadType,
                            formData = formData,
                            expireAt = data.optLong("expireAt", 0)
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "getUploadCredentials failed", e)
                Result.failure(e)
            }
        }

    suspend fun getClassAnalysis(
        token: String,
        memberId: String,
        notifyId: String,
        cid: String
    ): Result<CheckInClassAnalysis> = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("_id", notifyId)
                put("cid", cid)
                put("member_id", memberId)
                put("cls_ts", System.currentTimeMillis())
                put("daka_day", "")
            }
            val cacheIdentity = "$token|$memberId|$notifyId|$cid"
            val cachedBody = ApiCache.read(
                namespace = "checkins",
                identity = cacheIdentity,
                maxAgeMillis = 60_000L
            )
            val request = Request.Builder()
                .url("$BASE_URL/applet/notify/checkNew2Parent")
                .post(bodyJson.toString().toRequestBody(jsonMedia))
                .apply { addCommonHeaders(token) }
                .build()
            val responseBody = if (cachedBody != null) {
                cachedBody
            } else {
                apiClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw httpError(response.code, body)
                    }
                    if (body.isNotBlank()) {
                        ApiCache.write("checkins", cacheIdentity, body)
                    }
                    body
                }
            }
            val envelope = JSONObject(responseBody)
            if (envelope.optInt("code", -1) != 0) {
                return@withContext Result.failure(
                    Exception(envelope.optString("msg", "获取班级打卡情况失败"))
                )
            }
            val data = envelope.optJSONObject("data")
                ?: return@withContext Result.failure(Exception("打卡数据为空"))
            val notifyTitle = data.optJSONObject("notify")
                ?.optString("title")
                .orEmpty()
                .ifBlank { "自定义作业" }
            val membersMap = data.optJSONObject("membersMap") ?: JSONObject()
            val feedbacks = data.optJSONArray("feedbacks") ?: JSONArray()
            val feedbackByMember = buildMap {
                for (index in 0 until feedbacks.length()) {
                    val feedback = feedbacks.optJSONObject(index) ?: continue
                    val feedbackMemberId = feedback.optString("member_id")
                    if (feedbackMemberId.isNotBlank()) {
                        put(feedbackMemberId, feedback)
                    }
                }
            }
            val students = buildList {
                val keys = membersMap.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val member = membersMap.optJSONObject(key) ?: continue
                    val targetMemberId = member.optString("member_id")
                        .ifBlank { key }
                    val feedback = feedbackByMember[targetMemberId]
                    val status = feedback?.optInt("status")
                    add(
                        CheckInClassStudent(
                            memberId = targetMemberId,
                            name = member.optString("name", "未命名学生"),
                            studentNumber = member.optInt(
                                "snum",
                                Int.MAX_VALUE
                            ),
                            state = when {
                                feedback == null -> CheckInStudentState.NOT_SUBMITTED
                                status != null && status < 0 -> CheckInStudentState.WRONG
                                else -> CheckInStudentState.SUBMITTED
                            },
                            statusCode = status,
                            acceptId = feedback?.optString("_id")
                                ?.takeIf { it.isNotBlank() }
                        )
                    )
                }
            }.sortedWith(
                compareBy<CheckInClassStudent> { it.studentNumber }
                    .thenBy { it.name }
            )
            Result.success(CheckInClassAnalysis(notifyTitle, students))
        } catch (e: Exception) {
            Log.e(TAG, "getClassAnalysis failed", e)
            Result.failure(e)
        }
    }

    suspend fun uploadMedia(
        context: Context,
        credentials: UploadCredentials,
        memberId: String,
        media: LocalMedia,
        onProgress: (UploadProgress) -> Unit = {}
    ): Result<CheckInFile> = withContext(Dispatchers.IO) {
        try {
            if (credentials.uploadType != media.uploadType) {
                return@withContext Result.failure(Exception("上传凭据与文件类型不匹配"))
            }
            if (credentials.expireAt > 0 && credentials.expireAt <= System.currentTimeMillis()) {
                return@withContext Result.failure(Exception("上传凭据已过期，请重试"))
            }

            val uploadType = media.uploadType
            val objectKey = buildMediaObjectKey(
                fileName = media.name,
                memberId = memberId,
                uploadType = uploadType
            )
            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("key", objectKey)

            credentials.formData.forEach { (name, value) ->
                if (name != "key") {
                    multipartBuilder.addFormDataPart(name, value)
                }
            }
            multipartBuilder.addFormDataPart("success_action_status", "200")

            val requestMediaType: MediaType?
            val uploadFileName: String
            when (uploadType) {
                CheckInUploadType.VIDEO -> {
                    requestMediaType = media.mimeType.toMediaTypeOrNullSafe()
                    uploadFileName = media.name
                    multipartBuilder.addFormDataPart("x-oss-content-type", media.mimeType)
                }

                CheckInUploadType.IMAGE -> {
                    requestMediaType = "application/octet-stream".toMediaType()
                    uploadFileName = objectKey
                }
            }

            val fileBody = ContentUriRequestBody(
                context = context,
                uri = media.uri,
                mediaType = requestMediaType,
                declaredLength = media.size,
                onProgress = onProgress
            )
            multipartBuilder.addFormDataPart("file", uploadFileName, fileBody)

            val request = Request.Builder()
                .url(uploadType.uploadUrl)
                .post(multipartBuilder.build())
                .header("User-Agent", "Dart/3.11 (dart:io)")
                .build()

            uploadClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val label = if (uploadType == CheckInUploadType.VIDEO) "视频" else "图片"
                    return@withContext Result.failure(
                        httpError(response.code, responseBody, "${label}上传失败")
                    )
                }
            }

            Result.success(
                CheckInFile(
                    objectKey = objectKey,
                    name = media.name,
                    size = media.size.coerceAtLeast(0),
                    durationSeconds = media.durationSeconds,
                    category = uploadType.category
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "uploadMedia failed", e)
            Result.failure(e)
        }
    }

    suspend fun readLocalMedia(
        context: Context,
        uri: Uri,
        uploadType: CheckInUploadType
    ): Result<LocalMedia> =
        withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                var displayName: String? = null
                var fileSize = -1L

                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            fileSize = cursor.getLong(sizeIndex)
                        }
                    }
                }

                if (fileSize < 0) {
                    fileSize = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                }

                val durationMs = if (uploadType == CheckInUploadType.VIDEO) {
                    readVideoDuration(resolver, uri)
                } else {
                    0L
                }
                val safeName = displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: if (uploadType == CheckInUploadType.VIDEO) {
                        "video_${System.currentTimeMillis()}.mp4"
                    } else {
                        "image_${System.currentTimeMillis()}.jpg"
                    }
                val mimeType = resolver.getType(uri) ?: when (uploadType) {
                    CheckInUploadType.VIDEO -> "video/mp4"
                    CheckInUploadType.IMAGE -> "image/jpeg"
                }

                Result.success(
                    LocalMedia(
                        uri = uri,
                        name = safeName,
                        size = fileSize,
                        mimeType = mimeType,
                        durationSeconds = if (durationMs > 0) {
                            ((durationMs + 500) / 1000).toInt().coerceAtLeast(1)
                        } else {
                            0
                        },
                        uploadType = uploadType
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "readLocalMedia failed", e)
                Result.failure(e)
            }
        }

    suspend fun submitFeedback(
        token: String,
        memberId: String,
        detail: CheckInDetail,
        filesByItem: Map<String, List<CheckInFile>>,
        feedbackText: String,
        submitType: CheckInSubmitType
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = buildFeedbackBody(
                memberId = memberId,
                detail = detail,
                filesByItem = filesByItem,
                feedbackText = feedbackText,
                submitType = submitType
            ).toString().toRequestBody(jsonMedia)

            val request = Request.Builder()
                .url("$BASE_URL/applet/notify/feedbackWithOss")
                .post(body)
                .apply { addCommonHeaders(token) }
                .build()

            apiClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        httpError(response.code, responseBody)
                    )
                }
                if (responseBody.isBlank()) {
                    ApiCache.clearNamespace("checkins")
                    ApiCache.clearNamespace("tasks")
                    return@withContext Result.success(Unit)
                }

                val envelope = JSONObject(responseBody)
                if (envelope.optInt("code", -1) != 0) {
                    return@withContext Result.failure(
                        Exception(envelope.optString("msg", "保存打卡失败"))
                    )
                }
                ApiCache.clearNamespace("checkins")
                ApiCache.clearNamespace("tasks")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "submitFeedback failed", e)
            Result.failure(e)
        }
    }

    private fun parseDetail(
        data: JSONObject,
        fallbackCid: String,
        memberId: String
    ): CheckInDetail {
        val notify = data.optJSONObject("notify")
            ?: throw IllegalArgumentException("打卡详情缺少 notify")
        val invest = notify.optJSONObject("invest")
        val draft = data.optJSONObject("draft")
            ?.takeIf { it.length() > 0 }
        val accepts = data.optJSONArray("accepts")
        val accepted = buildList {
            if (accepts != null) {
                for (index in 0 until accepts.length()) {
                    accepts.optJSONObject(index)?.let { add(it) }
                }
            }
        }.firstOrNull { it.optString("member") == memberId }
            ?: accepts?.optJSONObject(0)
        val draftAnswerItems = draft
            ?.optJSONObject("answer")
            ?.optJSONArray("subject")
        val acceptedAnswerItems = accepted
            ?.optJSONObject("answer")
            ?.optJSONArray("subject")

        val draftItemsById = buildMap {
            if (draftAnswerItems != null) {
                for (index in 0 until draftAnswerItems.length()) {
                    val item = draftAnswerItems.optJSONObject(index) ?: continue
                    val id = item.optString("_id")
                    if (id.isNotBlank()) put(id, item)
                }
            }
        }
        val acceptedItemsById = buildMap {
            if (acceptedAnswerItems != null) {
                for (index in 0 until acceptedAnswerItems.length()) {
                    val item = acceptedAnswerItems.optJSONObject(index) ?: continue
                    val id = item.optString("_id")
                    if (id.isNotBlank()) put(id, item)
                }
            }
        }

        val sourceItems = invest?.optJSONArray("subject")
            ?.takeIf { it.length() > 0 }
            ?: draftAnswerItems
            ?: JSONArray()
        val items = buildList {
            for (index in 0 until sourceItems.length()) {
                val source = sourceItems.optJSONObject(index) ?: continue
                val id = source.optString("_id")
                if (id.isBlank()) continue
                val draftItem = draftItemsById[id]
                val acceptedItem = acceptedItemsById[id]
                val sourceInput = source.optJSONObject("input")
                val draftInput = draftItem?.optJSONObject("input")
                val acceptedInput = acceptedItem?.optJSONObject("input")
                val cate = source.optInt(
                    "cate",
                    draftItem?.optInt("cate", 0) ?: 0
                )

                val files = linkedMapOf<String, CheckInFile>()
                parseFiles(draftInput, cate).forEach { files[it.objectKey] = it }
                parseFiles(acceptedInput, cate).forEach { files.putIfAbsent(it.objectKey, it) }
                parseFiles(sourceInput, cate).forEach { files.putIfAbsent(it.objectKey, it) }

                val content = stringOrNull(draftInput, "content")
                    ?: stringOrNull(acceptedInput, "content")
                    ?: stringOrNull(sourceInput, "content")

                add(
                    CheckInItem(
                        id = id,
                        title = source.optString(
                            "title",
                            draftItem?.optString("title", "未命名任务")
                                ?: "未命名任务"
                        ),
                        cate = cate,
                        seq = source.optInt("seq", draftItem?.optInt("seq", index) ?: index),
                        required = source.optBoolean(
                            "required",
                            draftItem?.optBoolean("required", false) ?: false
                        ),
                        hasWaterMark = source.optBoolean(
                            "hasWaterMark",
                            draftItem?.optBoolean("hasWaterMark", false) ?: false
                        ),
                        hasSplicing = source.optBoolean(
                            "hasSplicing",
                            draftItem?.optBoolean("hasSplicing", false) ?: false
                        ),
                        limitMaxSelect = source.optInt(
                            "limitMaxSelect",
                            draftItem?.optInt("limitMaxSelect", 0) ?: 0
                        ),
                        savedFiles = files.values.toList(),
                        content = content
                    )
                )
            }
        }.sortedBy { it.seq }

        return CheckInDetail(
            notifyId = notify.optString("_id", data.optString("_id")),
            investId = invest?.optString("_id").orEmpty(),
            title = notify.optString("title", "打卡任务"),
            subject = notify.optString("subject"),
            textContent = notify.optString("text_content"),
            endDay = notify.optString("end_day"),
            cid = notify.optString("cls").ifBlank { fallbackCid },
            dakaDay = notify.optString("daka_day"),
            isPrivate = invest?.optBoolean("is_private", false) ?: false,
            isFeedback = notify.optBoolean("is_feedback", false) ||
                accepted?.optBoolean("is_feedback", false) == true,
            needFeedback = notify.optBoolean("need_feedback", true),
            items = items,
            feedbackText = draft?.optString("feedback_text")
                ?.takeIf { it.isNotBlank() }
                ?: accepted?.optString("feedback_text").orEmpty(),
            hasDraft = draft != null,
            submittedAt = accepted?.optString("friendly_create_at")
                ?.takeIf { it.isNotBlank() }
                ?: accepted?.optString("create_at")?.takeIf { it.isNotBlank() },
            media = TaskSetApi.extractSubmissionMedia(notify)
        )
    }

    private fun parseFiles(input: JSONObject?, itemCate: Int): List<CheckInFile> {
        val fileArray = input?.optJSONArray("file") ?: return emptyList()
        return buildList {
            for (index in 0 until fileArray.length()) {
                val file = when (val raw = fileArray.opt(index)) {
                    is JSONObject -> raw
                    is String -> JSONObject().put("id", raw)
                    else -> continue
                }
                val objectKey = stringOrNull(file, "id")
                    ?: stringOrNull(file, "new_name")
                    ?: stringOrNull(file, "file")?.takeIf {
                        it.startsWith("_oss/") || it.startsWith("_cos/")
                    }
                    ?: continue
                val originalName = stringOrNull(file, "name")
                    ?: stringOrNull(file, "origin_name")
                    ?: stringOrNull(file, "file")?.substringAfterLast('/')
                    ?: objectKey.substringAfterLast('/')
                add(
                    CheckInFile(
                        objectKey = objectKey,
                        name = originalName,
                        size = file.optLong("size", 0).coerceAtLeast(0),
                        durationSeconds = file.optInt("duration", 0).coerceAtLeast(0),
                        category = stringOrNull(file, "cate")
                            ?: categoryForItemCate(itemCate)
                    )
                )
            }
        }
    }

    private fun buildFeedbackBody(
        memberId: String,
        detail: CheckInDetail,
        filesByItem: Map<String, List<CheckInFile>>,
        feedbackText: String,
        submitType: CheckInSubmitType
    ): JSONObject {
        val allFiles = detail.items
            .flatMap { filesByItem[it.id].orEmpty() }
            .distinctBy { it.objectKey }

        val topLevelFiles = JSONArray().apply {
            allFiles.forEach { file ->
                put(JSONObject().apply {
                    put("origin_name", file.name)
                    put("cate", file.category)
                    put("size", file.size)
                    put("new_name", file.objectKey)
                    put("file", file.objectKey)
                    put("duration", file.durationSeconds)
                    put("name", file.name)
                })
            }
        }

        val subject = JSONArray().apply {
            detail.items.forEach { item ->
                val itemFiles = filesByItem[item.id].orEmpty()
                val input = if (itemFiles.isEmpty() && item.content.isNullOrBlank()) {
                    JSONObject.NULL
                } else {
                    JSONObject().apply {
                        put("file", JSONArray().apply {
                            itemFiles.forEach { file ->
                                put(JSONObject().apply {
                                    put("id", file.objectKey)
                                    put("file", file.name)
                                    put("duration", 0)
                                    put("name", file.name)
                                })
                            }
                        })
                        put("content", item.content ?: JSONObject.NULL)
                    }
                }

                put(JSONObject().apply {
                    put("input", input)
                    put("seq", item.seq)
                    put("cate", item.cate)
                    put("item_details", JSONArray())
                    put("_id", item.id)
                    put("required", item.required)
                    put("hasWaterMark", item.hasWaterMark)
                    put("hasSplicing", item.hasSplicing)
                    put("limitMaxSelect", item.limitMaxSelect)
                    put("title", item.title)
                })
            }
        }

        return JSONObject().apply {
            put("id", detail.notifyId)
            put("member_id", memberId)
            put("feedback_text", feedbackText)
            put("file_type", "any")
            put("files", topLevelFiles)
            put("form_id", "")
            put("networkType", "WiFi")
            put("daka_day", detail.dakaDay.ifBlank { todayString() })
            put("examdetail", "")
            put("sub_info", JSONArray())
            put("op", "add")
            put("submit_type", submitType.value)
            put("invest", JSONObject().apply {
                put("is_tmp", false)
                put("is_private", detail.isPrivate)
                put("_id", detail.investId)
                put("subject", subject)
                put("book", JSONObject.NULL)
                put("create_at", JSONObject.NULL)
            })
            put("childInfo", JSONObject().put("openid", ""))
            put("subByTeacher", false)
            put("is_offset", false)
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

    private fun readVideoDuration(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            val descriptor = resolver.openAssetFileDescriptor(uri, "r")
                ?: return 0L
            descriptor.use {
                retriever.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read video duration", e)
            0L
        } finally {
            retriever.release()
        }
    }

    private fun httpError(
        statusCode: Int,
        body: String,
        prefix: String = "请求失败"
    ): Exception {
        val preview = body.replace(Regex("\\s+"), " ").take(240)
        return Exception(
            if (preview.isBlank()) "$prefix（HTTP $statusCode）"
            else "$prefix（HTTP $statusCode）：$preview"
        )
    }

    private fun stringOrNull(json: JSONObject?, name: String): String? {
        if (json == null || !json.has(name) || json.isNull(name)) return null
        return json.optString(name).takeIf { it.isNotBlank() }
    }

    private fun categoryForItemCate(cate: Int): String = when (cate) {
        5 -> "img"
        7 -> "video"
        else -> "any"
    }

    internal fun buildMediaObjectKey(
        fileName: String,
        memberId: String,
        uploadType: CheckInUploadType,
        timestamp: Long = nextObjectTimestamp()
    ): String {
        val prefix = MessageDigest.getInstance("MD5")
            .digest(fileName.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(8)
        val extension = when (uploadType) {
            CheckInUploadType.VIDEO -> ".mp4"
            CheckInUploadType.IMAGE -> imageExtension(fileName)
        }
        return "${uploadType.cloudName}/$memberId/${uploadType.directory}/" +
            "${prefix}_$timestamp$extension"
    }

    private fun imageExtension(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
            .lowercase(Locale.US)
            .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
        return if (extension != null) ".$extension" else ".jpg"
    }

    private fun nextObjectTimestamp(): Long {
        val now = System.currentTimeMillis()
        return lastObjectTimestamp.updateAndGet { previous ->
            if (now > previous) now else previous + 1
        }
    }

    private fun todayString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())
    }

    private fun String.toMediaTypeOrNullSafe(): MediaType? {
        return runCatching { toMediaType() }.getOrNull()
    }

    private class ContentUriRequestBody(
        private val context: Context,
        private val uri: Uri,
        private val mediaType: MediaType?,
        private val declaredLength: Long,
        private val onProgress: (UploadProgress) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType

        override fun contentLength(): Long = declaredLength.takeIf { it >= 0 } ?: -1L

        override fun writeTo(sink: BufferedSink) {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            input.use {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var uploaded = 0L
                val startedAtNanos = System.nanoTime()
                var lastCallbackAtNanos = 0L
                val totalBytes = declaredLength.coerceAtLeast(0L)

                fun emitProgress(force: Boolean) {
                    val now = System.nanoTime()
                    if (!force &&
                        now - lastCallbackAtNanos < 100_000_000L
                    ) {
                        return
                    }
                    lastCallbackAtNanos = now
                    val elapsedSeconds = (
                        (now - startedAtNanos).coerceAtLeast(1L) /
                            1_000_000_000.0
                        )
                    val speed = (uploaded / elapsedSeconds)
                        .toLong()
                        .coerceAtLeast(0L)
                    val remainingMillis = if (
                        totalBytes > uploaded &&
                        speed > 0
                    ) {
                        (totalBytes - uploaded) * 1000L / speed
                    } else {
                        null
                    }
                    onProgress(
                        UploadProgress(
                            uploadedBytes = uploaded,
                            totalBytes = totalBytes,
                            bytesPerSecond = speed,
                            remainingMillis = remainingMillis
                        )
                    )
                }

                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    uploaded += read
                    emitProgress(force = false)
                }
                emitProgress(force = true)
            }
        }
    }
}
