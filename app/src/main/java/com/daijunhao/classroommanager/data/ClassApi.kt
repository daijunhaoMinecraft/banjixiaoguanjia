package com.daijunhao.classroommanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// 班级实体类
data class ClassInfo(
    val id: String,
    val className: String,
    val uniqueCode: String,
    val grade: String,
    val studentCount: Int,
    val allCount: Int,
    val schoolTitle: String,
    val schoolAddress: String
)

data class MemberInfo(
    val id: String,
    val name: String,
    val phone: String?, // 手机号可能为空
    val avatar: String,
    val wxName: String
)

data class AccountProfile(
    val memberId: String,
    val name: String,
    val wxName: String,
    val phone: String?,
    val avatar: String,
    val classNames: List<String>
)

object ClassApi {
    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun getClassList(token: String, memberId: String): Result<List<ClassInfo>> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("member_ids", JSONArray().put(memberId)).toString().toRequestBody(jsonMedia)

            // 构造请求（还原 APP 端常见的 Headers，Token 放在 Authorization 里）
            val request = Request.Builder()
                .url("https://b.welife001.com/applet/getClassByMemberId")
                .post(body)
                .header("User-Agent", "Dart/3.11 (dart:io)")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("App-info", "1/3.11.7/1072")
                .header("Authorization", token) // 传递截取到的 Token
                .header("Other-info", "${System.currentTimeMillis()}/${UUID.randomUUID().toString().replace("-", "")}")
                .build()

            client.newCall(request).execute().use { response ->
                val resBody = response.body?.string().orEmpty()
                val json = JSONObject(resBody)

                if (json.optInt("code") == 0) {
                    val dataArr = json.optJSONArray("data") ?: JSONArray()
                    val list = mutableListOf<ClassInfo>()
                    for (i in 0 until dataArr.length()) {
                        val item = dataArr.getJSONObject(i)
                        val school = item.optJSONObject("school_location")
                        list.add(
                            ClassInfo(
                                id = item.optString("_id", ""),
                                className = item.optString("class_name", "未知班级"),
                                uniqueCode = item.optString("unique_code", "无"),
                                grade = item.optString("grade", "未知"),
                                studentCount = item.optInt("number", 0),
                                allCount = item.optInt("all_count", 0),
                                schoolTitle = school?.optString("title", "未知学校") ?: "未知学校",
                                schoolAddress = school?.optString("address", "未知地址") ?: "未知地址"
                            )
                        )
                    }
                    Result.success(list)
                } else {
                    Result.failure(Exception(json.optString("msg", "获取班级失败")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMembersByCid(token: String, cid: String): Result<List<MemberInfo>> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("cid", cid)
                put("updateclass", 1)
                put("all_status", true)
            }.toString().toRequestBody(jsonMedia)

            val request = Request.Builder()
                .url("https://b.welife001.com/applet/getMembersByCid")
                .post(body)
                .header("User-Agent", "Dart/3.11 (dart:io)")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", token)
                .build()

            client.newCall(request).execute().use { response ->
                val resBody = response.body?.string().orEmpty()
                val json = JSONObject(resBody)

                if (json.optInt("code") == 0) {
                    val membersArr = json.getJSONObject("data").optJSONArray("members") ?: JSONArray()
                    val list = mutableListOf<MemberInfo>()
                    for (i in 0 until membersArr.length()) {
                        val item = membersArr.getJSONObject(i)
                        list.add(
                            MemberInfo(
                                id = item.optString("_id", ""),
                                name = item.optString("name", "未知姓名"),
                                phone = if (item.has("phone") && item.getString("phone").isNotBlank()) item.getString("phone") else null,
                                avatar = item.optString("wx_avatar", ""),
                                wxName = item.optString("wx_name", "未知微信昵称")
                            )
                        )
                    }
                    Result.success(list)
                } else {
                    Result.failure(Exception(json.optString("msg", "获取成员失败")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCurrentProfile(
        token: String,
        memberId: String
    ): Result<AccountProfile> = withContext(Dispatchers.IO) {
        try {
            val classes = getClassList(token, memberId).getOrElse {
                return@withContext Result.failure(it)
            }
            var profile: MemberInfo? = null
            for (clazz in classes) {
                val members = getMembersByCid(token, clazz.id).getOrNull() ?: continue
                profile = members.firstOrNull { it.id == memberId }
                if (profile != null) break
            }
            val member = profile
                ?: return@withContext Result.failure(
                    Exception("未在班级成员中找到当前账号")
                )
            Result.success(
                AccountProfile(
                    memberId = member.id.ifBlank { memberId },
                    name = member.name,
                    wxName = member.wxName,
                    phone = member.phone,
                    avatar = member.avatar,
                    classNames = classes.map { it.className }.distinct()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
