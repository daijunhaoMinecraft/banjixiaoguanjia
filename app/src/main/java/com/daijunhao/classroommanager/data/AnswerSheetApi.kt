package com.daijunhao.classroommanager.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AnswerOption(
    val id: String,
    val type: String,
    val value: String
)

data class AnswerQuestion(
    val id: String,
    val type: Int,
    val index: Int,
    val stemText: String,
    val stemAudio: String,
    val stemPictures: List<String>,
    val options: List<AnswerOption>,
    val answer: String,
    val explanation: String,
    val blanks: List<String>,
    val needReadCount: Int
) {
    val needsRecording: Boolean
        get() = type == 6 || type == 11
}

data class AnswerSheetData(
    val id: String,
    val type: Int,
    val title: String,
    val mainId: String,
    val questions: List<AnswerQuestion>
)

data class AnswerResponse(
    val questionId: String,
    val type: Int,
    val selectedAnswer: String? = null,
    val audioKey: String? = null,
    val score: Double? = null,
    val right: Boolean = true
)

data class LocalAudioRecording(
    val file: File,
    val durationMs: Long
)

object AnswerSheetApi {
    private const val TAG = "AnswerSheetApi"
    private const val BASE_URL = "https://b.welife001.com"
    private const val TOS_UPLOAD_URL = "https://xgjrecord.tos-cn-beijing.volces.com/"

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val audioMedia = "audio/mp4".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    suspend fun getAnswerSheet(
        token: String,
        sheetId: String
    ): Result<AnswerSheetData> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/applet/content/getAnswerSheet".toHttpUrl().newBuilder()
                .addQueryParameter("id", sheetId)
                .build()
            val request = Request.Builder()
                .url(url)
                .get()
                .apply { addCommonHeaders(token) }
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(httpError(response.code, body))
                }
                val envelope = JSONObject(body)
                if (envelope.optInt("code", -1) != 0) {
                    return@withContext Result.failure(
                        Exception(envelope.optString("msg", "获取答题卷失败"))
                    )
                }
                val data = envelope.optJSONObject("data")
                    ?: return@withContext Result.failure(Exception("答题卷数据为空"))
                Result.success(parseAnswerSheet(data))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAnswerSheet failed", e)
            Result.failure(e)
        }
    }

    suspend fun uploadRecording(
        token: String,
        memberId: String,
        recording: LocalAudioRecording
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val openId = readOpenId(token).ifBlank { memberId }
            val timestamp = System.currentTimeMillis()
            val hash = md5File(recording.file)
            val key = "_tos/${openId}_app_record/${hash}_$timestamp.m4a"
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("key", key)
                .addFormDataPart(
                    "file",
                    "audio.m4a",
                    recording.file.asRequestBody(audioMedia)
                )
                .build()
            val request = Request.Builder()
                .url(TOS_UPLOAD_URL)
                .post(multipart)
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        httpError(response.code, body, "录音上传失败")
                    )
                }
            }
            Result.success(key)
        } catch (e: Exception) {
            Log.e(TAG, "uploadRecording failed", e)
            Result.failure(e)
        }
    }

    suspend fun uploadPhoto(
        context: Context,
        token: String,
        memberId: String,
        file: File
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val credentials = CheckInApi.getUploadCredentials(
                token = token,
                uploadType = CheckInUploadType.IMAGE
            ).getOrElse { return@withContext Result.failure(it) }
            val media = LocalMedia(
                uri = Uri.fromFile(file),
                name = file.name,
                size = file.length(),
                mimeType = "image/jpeg",
                durationSeconds = 0,
                uploadType = CheckInUploadType.IMAGE
            )
            CheckInApi.uploadMedia(
                context = context,
                credentials = credentials,
                memberId = memberId,
                media = media
            ).map { it.objectKey }
        } catch (e: Exception) {
            Log.e(TAG, "uploadPhoto failed", e)
            Result.failure(e)
        }
    }

    suspend fun submitAnswerSheet(
        token: String,
        memberId: String,
        cid: String,
        mainId: String,
        notifyId: String,
        taskId: String,
        sheetId: String,
        acceptId: String?,
        durationSeconds: Int,
        responses: List<AnswerResponse>,
        subPhotoKey: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val questions = JSONArray().apply {
                responses.forEach { response ->
                    put(JSONObject().apply {
                        put("q_id", response.questionId)
                        put("type", response.type)
                        put("right", response.right)
                        put("answer_status", if (response.right) "right" else "wrong")
                        response.selectedAnswer?.let { put("stuAnswer", it) }
                        response.audioKey?.let { put("stuAnswer", it) }
                        response.score?.let { put("score", roundSoeScore(it)) }
                    })
                }
            }
            val payload = JSONObject().apply {
                put("duration", durationSeconds.coerceAtLeast(1))
                put("sheet_id", sheetId)
                put("task_id", taskId)
                put("questions", questions)
                put("notify", notifyId)
                put("notify_id", notifyId)
                put("main_id", mainId)
                put("member_id", memberId)
                put("cls", cid)
                if (!acceptId.isNullOrBlank()) put("accept_id", acceptId)
                if (!subPhotoKey.isNullOrBlank()) put("sub_photo", subPhotoKey)
            }

            var lastError: Exception? = null
            repeat(3) { attempt ->
                val request = Request.Builder()
                    .url("$BASE_URL/applet/task/submit/content/feedbacksAnswerSheet")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .apply { addCommonHeaders(token) }
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        lastError = httpError(response.code, body)
                        return@use
                    }
                    val envelope = runCatching { JSONObject(body) }.getOrNull()
                    if (envelope == null) {
                        return@withContext Result.success(Unit)
                    }
                    val code = envelope.optInt("code", -1)
                    if (code == 0) {
                        return@withContext Result.success(Unit)
                    }
                    lastError = Exception(envelope.optString("msg", "提交答题卷失败"))
                    if (code == 429 && attempt < 2) {
                        Thread.sleep(2_000)
                    }
                }
                if (lastError != null && attempt < 2) {
                    delay(500)
                }
            }
            Result.failure(lastError ?: Exception("提交答题卷失败"))
        } catch (e: Exception) {
            Log.e(TAG, "submitAnswerSheet failed", e)
            Result.failure(e)
        }
    }

    suspend fun submitTask(
        token: String,
        memberId: String,
        cid: String,
        mainId: String,
        notifyId: String,
        taskId: String,
        acceptId: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("cls", cid)
                put("main_id", mainId)
                put("member_id", memberId)
                put("notify_id", notifyId)
                put("task_id", taskId)
                put("sub_notify", true)
                if (!acceptId.isNullOrBlank()) put("accept_id", acceptId)
            }
            val request = Request.Builder()
                .url("$BASE_URL/applet/task/submit/content/feedbacks")
                .post(payload.toString().toRequestBody(jsonMedia))
                .apply { addCommonHeaders(token) }
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(httpError(response.code, body))
                }
                val envelope = runCatching { JSONObject(body) }.getOrNull()
                if (envelope == null || envelope.optInt("code", -1) == 0) {
                    return@withContext Result.success(Unit)
                }
                Result.failure(
                    Exception(envelope.optString("msg", "确认提交任务失败"))
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "submitTask failed", e)
            Result.failure(e)
        }
    }

    private fun parseAnswerSheet(data: JSONObject): AnswerSheetData {
        val questionArray = data.optJSONArray("questions") ?: JSONArray()
        val questions = buildList {
            for (position in 0 until questionArray.length()) {
                val question = questionArray.optJSONObject(position) ?: continue
                val stem = question.optJSONObject("stem") ?: JSONObject()
                val optionArray = question.optJSONArray("options") ?: JSONArray()
                val options = buildList {
                    for (optionIndex in 0 until optionArray.length()) {
                        val option = optionArray.optJSONObject(optionIndex) ?: continue
                        add(
                            AnswerOption(
                                id = option.optString("id"),
                                type = option.optString("type", "text"),
                                value = option.optString("val")
                            )
                        )
                    }
                }
                val pictureArray = stem.optJSONArray("picture") ?: JSONArray()
                val pictures = buildList {
                    for (pictureIndex in 0 until pictureArray.length()) {
                        pictureArray.optString(pictureIndex)
                            .takeIf { it.isNotBlank() }
                            ?.let(::add)
                    }
                }
                val blankArray = stem.optJSONArray("blank") ?: JSONArray()
                val blanks = buildList {
                    for (blankIndex in 0 until blankArray.length()) {
                        blankArray.optString(blankIndex).let(::add)
                    }
                }
                add(
                    AnswerQuestion(
                        id = question.optString("_id"),
                        type = question.optInt("type", -1),
                        index = question.optInt("index", position + 1),
                        stemText = stem.optString("text"),
                        stemAudio = stem.optString("audio"),
                        stemPictures = pictures,
                        options = options,
                        answer = question.optString("answer"),
                        explanation = question.optJSONObject("explain")
                            ?.optString("text")
                            .orEmpty(),
                        blanks = blanks,
                        needReadCount = question.optInt("needReadCount", 1)
                            .coerceAtLeast(1)
                    )
                )
            }
        }
        return AnswerSheetData(
            id = data.optString("_id"),
            type = data.optString("type").toIntOrNull() ?: -1,
            title = data.optString("title", "答题"),
            mainId = data.optString("main_id"),
            questions = questions
        )
    }

    private fun readOpenId(token: String): String {
        return runCatching {
            val payload = token.split('.').getOrNull(1) ?: return@runCatching ""
            val decoded = Base64.decode(
                payload,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            JSONObject(String(decoded, Charsets.UTF_8)).optString("openid")
        }.getOrDefault("")
    }

    private fun md5File(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte)
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
}
