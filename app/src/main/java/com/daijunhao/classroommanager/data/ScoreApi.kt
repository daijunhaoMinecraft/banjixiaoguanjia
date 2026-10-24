package com.daijunhao.classroommanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SubjectScore(
    val subject: String,
    val score: String
)

data class ScoreResultData(
    val title: String,
    val examTime: String,
    val studentName: String,
    val scores: List<SubjectScore>
)

data class StudentScoreRow(
    val memberId: String,
    val name: String,
    val scores: Map<String, String>,
    val status: String
)

data class ClassScoreExport(
    val title: String,
    val examTime: String,
    val subjects: List<String>,
    val rows: List<StudentScoreRow>
)

object ScoreApi {
    private const val BASE_URL = "https://b.welife001.com"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun getScoreDetail(
        token: String,
        scoreId: String,
        studentName: String = ""
    ): Result<ScoreResultData> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("id", scoreId)
                put("name", studentName.trim())
                put("score_phone_verified", false)
            }.toString().toRequestBody(jsonMedia)
            val cacheIdentity = "$token|$scoreId|${studentName.trim()}"
            val cachedBody = ApiCache.read(
                namespace = "scores",
                identity = cacheIdentity,
                maxAgeMillis = 10 * 60_000L
            )
            val request = Request.Builder()
                .url("$BASE_URL/getStudentScoreById")
                .post(body)
                .header("User-Agent", "Dart/3.11 (dart:io)")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("App-info", "1/3.11.7/1072")
                .header("Authorization", token)
                .build()

            val responseBody = if (cachedBody != null) {
                cachedBody
            } else {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw httpError(response.code, body)
                    }
                    if (body.isNotBlank()) {
                        ApiCache.write("scores", cacheIdentity, body)
                    }
                    body
                }
            }
            run {
                val envelope = JSONObject(responseBody)
                if (envelope.optInt("code", -1) != 0) {
                    return@withContext Result.failure(
                        Exception(envelope.optString("msg", "获取成绩详情失败"))
                    )
                }
                val data = envelope.optJSONObject("data") ?: envelope
                val studentScore = data.optJSONObject("studentScore")
                    ?: return@withContext Result.failure(
                        Exception(
                            if (studentName.isBlank()) {
                                "未找到当前账号的成绩"
                            } else {
                                "未找到学生“$studentName”的成绩"
                            }
                        )
                    )
                val scoreArray = studentScore.optJSONArray("score_detail")
                    ?: JSONArray()
                val scores = buildList {
                    for (index in 0 until scoreArray.length()) {
                        val item = scoreArray.optJSONObject(index) ?: continue
                        val subject = item.optString("subject").trim()
                        if (subject.isBlank()) continue
                        add(
                            SubjectScore(
                                subject = subject,
                                score = item.optString("score").trim()
                            )
                        )
                    }
                }
                Result.success(
                    ScoreResultData(
                        title = data.optJSONObject("notify")
                            ?.optString("title")
                            .orEmpty()
                            .ifBlank { "成绩详情" },
                        examTime = data.optString("exam_time", "未知日期"),
                        studentName = data.optString("name").trim()
                            .ifBlank { studentName },
                        scores = scores
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllStudentScores(
        token: String,
        scoreId: String,
        cid: String,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<ClassScoreExport> = withContext(Dispatchers.IO) {
        try {
            val members = ClassApi.getMembersByCid(token, cid).getOrElse {
                return@withContext Result.failure(it)
            }
            val names = members
                .map { it.name.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (names.isEmpty()) {
                return@withContext Result.failure(Exception("班级中没有可查询的学生"))
            }

            val resultByName = linkedMapOf<String, Result<ScoreResultData>>()
            var completed = 0
            onProgress(0, names.size)
            val chunks = names.chunked(4)
            chunks.forEachIndexed { index, chunk ->
                val chunkResults = coroutineScope {
                    chunk.map { name ->
                        async(Dispatchers.IO) {
                            name to getScoreDetail(token, scoreId, name)
                        }
                    }.awaitAll()
                }
                chunkResults.forEach { (name, result) ->
                    resultByName[name] = result
                }
                completed += chunk.size
                onProgress(completed, names.size)
                if (index < chunks.lastIndex) delay(120)
            }

            val successResults = resultByName.values.mapNotNull {
                it.getOrNull()
            }
            if (successResults.isEmpty()) {
                val firstError = resultByName.values
                    .firstNotNullOfOrNull { it.exceptionOrNull() }
                return@withContext Result.failure(
                    firstError ?: Exception("没有查询到任何学生成绩")
                )
            }

            val subjects = linkedSetOf<String>()
            successResults.forEach { result ->
                result.scores.forEach { subjects.add(it.subject) }
            }
            val rows = members.map { member ->
                val name = member.name.trim()
                val result = resultByName[name]
                val data = result?.getOrNull()
                StudentScoreRow(
                    memberId = member.id,
                    name = name.ifBlank { "未命名学生" },
                    scores = data?.scores
                        ?.associate { it.subject to it.score }
                        .orEmpty(),
                    status = when {
                        name.isBlank() -> "姓名缺失"
                        data != null -> "成功"
                        else -> result?.exceptionOrNull()?.message ?: "未查询"
                    }
                )
            }.sortedWith(
                compareBy<StudentScoreRow> {
                    it.scores["班排"]?.toIntOrNull() ?: Int.MAX_VALUE
                }.thenByDescending {
                    it.scores["总分折后"]?.toDoubleOrNull()
                        ?: Double.NEGATIVE_INFINITY
                }.thenBy { it.name }
            )

            Result.success(
                ClassScoreExport(
                    title = successResults.first().title,
                    examTime = successResults.first().examTime,
                    subjects = subjects.toList(),
                    rows = rows
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun httpError(statusCode: Int, body: String): Exception {
        val preview = body.replace(Regex("\\s+"), " ").take(240)
        return Exception(
            if (preview.isBlank()) "成绩请求失败（HTTP $statusCode）"
            else "成绩请求失败（HTTP $statusCode）：$preview"
        )
    }
}
