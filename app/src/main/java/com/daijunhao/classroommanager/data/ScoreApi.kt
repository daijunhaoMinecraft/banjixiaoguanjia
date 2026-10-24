package com.daijunhao.classroommanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class SubjectScore(
    val subject: String,
    val score: String
)

data class ScoreResultData(
    val title: String,
    val examTime: String,
    val scores: List<SubjectScore>
)

object ScoreApi {
    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun getScoreDetail(token: String, scoreId: String, studentName: String = ""): Result<ScoreResultData> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("id", scoreId)
                put("name", studentName) // 学生姓名，若不强校验可留空
                put("score_phone_verified", false)
            }.toString().toRequestBody(jsonMedia)

            val request = Request.Builder()
                .url("https://b.welife001.com/getStudentScoreById")
                .post(body)
                .header("User-Agent", "Dart/3.11 (dart:io)")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", token)
                .build()

            client.newCall(request).execute().use { response ->
                val resBody = response.body?.string().orEmpty()
                val json = JSONObject(resBody)

                if (json.optInt("code") == 0) {
                    // 提取通知信息和考试时间
                    val notifyObj = json.optJSONObject("notify") ?: JSONObject()
                    val title = notifyObj.optString("title", "成绩详情")
                    val examTime = json.optString("exam_time", "未知日期")

                    // 提取各科成绩明细
                    val scoreDetailArr = json.optJSONObject("studentScore")?.optJSONArray("score_detail") ?: JSONArray()
                    val scoreList = mutableListOf<SubjectScore>()

                    for (i in 0 until scoreDetailArr.length()) {
                        val item = scoreDetailArr.getJSONObject(i)
                        scoreList.add(
                            SubjectScore(
                                subject = item.optString("subject", ""),
                                score = item.optString("score", "")
                            )
                        )
                    }
                    Result.success(ScoreResultData(title, examTime, scoreList))
                } else {
                    Result.failure(Exception(json.optString("msg", "获取成绩详情失败")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}