package com.daijunhao.classroommanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

// 首页列表数据实体
data class HomeItem(
    val id: String,
    val type: Int,
    val title: String,
    val subject: String,
    val textContent: String,
    val creatorName: String,
    val isFeedback: Boolean,
    val isRead: Boolean,
    val endDay: String,
    val createAt: String,
    val scoreId: String? // <- 新增这个字段，专门用于成绩查询
)

object HomeApi {
    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun getHomeList(token: String, memberId: String, page: Int, size: Int = 20): Result<List<HomeItem>> = withContext(Dispatchers.IO) {
        try {
            // URL 拼接查询参数
            val url = "https://b.welife001.com/info/getParent?members=$memberId&type=-1&date=-1&page=$page&size=$size&isRecent=false"

            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Dart/3.11 (dart:io)")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", token)
                .build()

            client.newCall(request).execute().use { response ->
                val resBody = response.body?.string().orEmpty()
                val json = JSONObject(resBody)

                if (json.optInt("code") == 0) {
                    val dataArr = json.optJSONArray("data") ?: JSONArray()
                    val list = mutableListOf<HomeItem>()

                    for (i in 0 until dataArr.length()) {
                        val item = dataArr.getJSONObject(i)
                        // 提取发布者名字（有的叫 creator_wx_name，有的叫 creator_name）
                        val creator = item.optString("creator_wx_name").takeIf { it.isNotBlank() }
                            ?: item.optString("creator_name", "未知发布者")

                        list.add(
                            HomeItem(
                                id = item.optString("_id", ""),
                                type = item.optInt("type", 0),
                                title = item.optString("title", "无标题"),
                                subject = item.optString("subject", ""),
                                textContent = item.optString("text_content", ""),
                                creatorName = creator,
                                isFeedback = item.optBoolean("is_feedback", false),
                                isRead = item.optBoolean("is_read", false),
                                endDay = item.optString("end_day", ""),
                                createAt = item.optString("create_at", ""),
                                scoreId = item.optString("score", null) // <- 提取 score 字段
                            )
                        )
                    }
                    Result.success(list)
                } else {
                    Result.failure(Exception(json.optString("msg", "获取首页数据失败")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}