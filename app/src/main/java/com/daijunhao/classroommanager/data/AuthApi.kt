package com.daijunhao.classroommanager.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SendSmsResult(
    val success: Boolean,
    val message: String
)

data class LoginResult(
    val success: Boolean,
    val token: String?,
    val message: String
)

object AuthApi {
    private const val TAG = "AuthApi"
    private const val BASE_URL = "https://b.welife001.com"

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun deviceInfoJson(): String {
        val deviceId = UUID.randomUUID().toString().replace("-", "").take(16)
        return JSONObject().apply {
            put("os_name", "android")
            put("os_version", android.os.Build.VERSION.RELEASE ?: "unknown")
            put("brand", android.os.Build.BRAND ?: "unknown")
            put("model", android.os.Build.MODEL ?: "unknown")
            put("screen_height", 0)
            put("screen_width", 0)
            put("device_id", deviceId)
            put("network_type", "WiFi")
            put("oaid", "123456")
        }.toString()
    }

    private fun baseBody(phone: String): JSONObject {
        return JSONObject().apply {
            put("phone", phone)
            put("channel", "mi")
            put("platform", "app")
            put("app_info", JSONObject().apply {
                put("app_vcode", "1072")
                put("app_vname", "3.11.7")
            })
            put("device_info", JSONObject(deviceInfoJson()))
        }
    }

    private fun commonHeaders(builder: Request.Builder) {
        builder.header("User-Agent", "Dart/3.11 (dart:io)")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("App-info", "1/3.11.7/1072")
            .header("Imprint", "oWRkU0em6bqAHVyAdmb8bsBApNRQ")
            .header("Authorization", "")
            .header("Other-info", "${System.currentTimeMillis()}/${UUID.randomUUID().toString().replace("-", "")}")
    }

    private fun parseEnvelope(body: String): Triple<Int, String, Any?> {
        val json = JSONObject(body)
        val code = json.optInt("code", -1)
        val msg = json.optString("msg", "未知错误")
        return Triple(code, msg, json.opt("data"))
    }

    suspend fun sendSms(phone: String): SendSmsResult = withContext(Dispatchers.IO) {
        try {
            val body = baseBody(phone).toString().toRequestBody(jsonMedia)
            val request = Request.Builder()
                .url("$BASE_URL/sendSMS")
                .post(body)
                .apply { commonHeaders(this) }
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                Log.d(TAG, "sendSMS code=${response.code} body=$text")
                val (code, msg, _) = parseEnvelope(text)
                if (response.isSuccessful && code == 0) {
                    SendSmsResult(true, "验证码已发送")
                } else {
                    SendSmsResult(false, msg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendSMS failed", e)
            SendSmsResult(false, "网络请求失败：${e.message}")
        }
    }

    suspend fun verifySmsAndLogin(phone: String, smsCode: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            val body = baseBody(phone).apply {
                put("code", smsCode)
            }.toString().toRequestBody(jsonMedia)
            val request = Request.Builder()
                .url("$BASE_URL/verifySmsAndLogin")
                .post(body)
                .apply { commonHeaders(this) }
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                Log.d(TAG, "verifySmsAndLogin code=${response.code} body=$text")
                val (code, msg, data) = parseEnvelope(text)
                if (response.isSuccessful && code == 0 && data is String && data.isNotBlank()) {
                    LoginResult(true, data, "登录成功")
                } else {
                    LoginResult(false, null, msg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifySmsAndLogin failed", e)
            LoginResult(false, null, "网络请求失败：${e.message}")
        }
    }
}
