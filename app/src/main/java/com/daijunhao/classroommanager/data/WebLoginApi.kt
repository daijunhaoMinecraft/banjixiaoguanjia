package com.daijunhao.classroommanager.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl.Companion.toHttpUrl

// 定义数据模型
data class InitLoginData(val random: String, val qrUrl: String)
data class RoleItem(val cid: String, val member: String, val value: String, val type: String, val nameRole: String)
data class RoleListData(val imprint: String, val roles: List<RoleItem>)
data class LoginSuccessData(val token: String, val memberId: String)


object WebLoginApi {
    private const val DOMAIN_E = "https://e.welife001.com"
    private const val DOMAIN_A = "https://a.welife001.com"

    // AES 加密固定密钥
    private const val AES_KEY = "1E390CMD585LLS4S"
    private const val AES_IV = "1111000988761654"

    // 内存 CookieJar，用于保持 Session 状态
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
    private val cookies = mutableListOf<Cookie>()

    // 获取根域名，用于强行子域共享
    private fun getRootDomain(host: String): String {
        if (host.contains("welife001.com")) return "welife001.com"
        if (host.contains("banjixiaoguanjia.com")) return "banjixiaoguanjia.com"
        if (host.contains("bjxgj.com")) return "bjxgj.com"
        return host
    }

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, newCookies: List<Cookie>) {
            // 核心修改：将所有收到的 Cookie 强行“提权”
            // 赋予根域名(实现跨子域共享) 和 根路径(防止路径限制)
            val broadCookies = newCookies.map { cookie ->
                val rootDomain = getRootDomain(cookie.domain)
                Cookie.Builder()
                    .name(cookie.name)
                    .value(cookie.value)
                    .domain(rootDomain) // 强制赋予根域名，关闭 hostOnly 限制
                    .path("/")          // 强制赋为根路径
                    .expiresAt(cookie.expiresAt)
                    .apply {
                        if (cookie.secure) secure()
                        if (cookie.httpOnly) httpOnly()
                    }
                    .build()
            }

            // 移除同名的旧 Cookie
            val iterator = cookies.iterator()
            while (iterator.hasNext()) {
                val existing = iterator.next()
                if (broadCookies.any { it.name == existing.name && it.domain == existing.domain }) {
                    iterator.remove()
                }
            }
            cookies.addAll(broadCookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val validCookies = mutableListOf<Cookie>()
            val iterator = cookies.iterator()
            while (iterator.hasNext()) {
                val cookie = iterator.next()
                if (cookie.expiresAt < System.currentTimeMillis()) {
                    iterator.remove() // 顺手清理过期 Cookie
                } else if (cookie.matches(url)) { // matches 会自动对 a.welife001 匹配 welife001
                    validCookies.add(cookie)
                }
            }
            return validCookies
        }
    }

    // 允许自动重定向的 OkHttpClient
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // 工具：手动向 CookieJar 注入 Cookie (选完角色后需要)
    private fun injectCookie(name: String, value: String, domain: String = "welife001.com") {
        val cleanDomain = if (domain.startsWith(".")) domain.substring(1) else domain
        val cookie = Cookie.Builder()
            .domain(cleanDomain)
            .name(name)
            .value(value)
            .path("/")
            .build()

        cookies.removeAll { it.name == name && it.domain == cleanDomain }
        cookies.add(cookie)
    }
    // AES 密码加密
    private fun encryptPassword(password: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding") // Java/Kotlin 中 PKCS5 等同于 PKCS7
        val keySpec = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    // 1. 初始化，获取 random 和 二维码
    suspend fun initLogin(): Result<InitLoginData> = withContext(Dispatchers.IO) {
        try {
            cookieStore.clear() // 每次初始化清空之前的状态
            val request = Request.Builder().url("$DOMAIN_E/web/login").build()
            client.newCall(request).execute().use { response ->
                val html = response.body?.string().orEmpty()
                val doc = Jsoup.parse(html)
                val random = doc.select("input#random").`val`()
                val qrSrc = doc.select("img.qrcode").attr("src")
                val qrUrl = if (qrSrc.startsWith("http")) qrSrc else "$DOMAIN_E$qrSrc"

                Result.success(InitLoginData(random, qrUrl))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 2A. 账号密码登录
    suspend fun loginWithPassword(phone: String, pwd: String, random: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encryptedPwd = encryptPassword(pwd)

            // 使用 toHttpUrl() 替代 HttpUrl.parse()
            val url = "$DOMAIN_E/web/loginForm".toHttpUrl().newBuilder()
                .addQueryParameter("phone", phone)
                .addQueryParameter("password", encryptedPwd)
                .addQueryParameter("random", random)
                .build()

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                if (json.optString("status") == "ok") {
                    Result.success(json.getString("unionid"))
                } else {
                    Result.failure(Exception(json.optString("reason", "账号或密码错误")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 2B. 轮询二维码扫码状态
    suspend fun checkQrStatus(random: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val url = "$DOMAIN_E/web/checkLogin?random=$random&ts=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                if (json.optString("status") == "ok") {
                    if (json.optBoolean("login")) {
                        Result.success(json.getJSONObject("data").getString("unionid")) // 扫码成功
                    } else {
                        Result.success(null) // 还没扫码或还没确认
                    }
                } else {
                    Result.failure(Exception("二维码过期"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 3. 获取角色列表
    suspend fun getRoleList(random: String, unionId: String, type: String): Result<RoleListData> = withContext(Dispatchers.IO) {
        try {
            val url = "$DOMAIN_E/back/authOA?random=$random&unionid=$unionId&type=$type"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val html = response.body?.string().orEmpty()

                // 正则匹配 imprint
                val imprintMatch = Regex("var imprint = \"(.*?)\";").find(html)
                val imprint = imprintMatch?.groupValues?.get(1) ?: throw Exception("未能获取 imprint")

                val doc = Jsoup.parse(html)
                val options = doc.select("select#role option")
                val roles = options.mapNotNull { opt ->
                    val value = opt.attr("value")
                    if (value.isBlank()) null else {
                        RoleItem(
                            cid = opt.attr("cid"),
                            member = opt.attr("member"),
                            value = value,
                            type = opt.attr("type"),
                            nameRole = opt.text()
                        )
                    }
                }
                Result.success(RoleListData(imprint, roles))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 4. 选择角色，触发重定向，最终抓取 Token
    suspend fun selectRoleAndGetToken(role: RoleItem, imprint: String): Result<LoginSuccessData> = withContext(Dispatchers.IO) {
        try {
            // 写入对应 Cookie 逻辑 (根据网页版 JS)
            injectCookie("imprint", imprint, "welife001.com")

            // 这里判断身份 (老师、授权、家长等)，由于大部分是孩子/家长身份，我们按照 JS 逻辑写死家长身份
            if (role.type == "child") {
                injectCookie("role", "parent", ".welife001.com")
                injectCookie("member_id", role.member, ".welife001.com")
                injectCookie("cid", role.cid, ".welife001.com")
            } else {
                injectCookie("role", "teacher", ".welife001.com")
            }

            // 发起最终请求，OkHttp 会自动跟随所有的 302 重定向
            val request = Request.Builder().url("$DOMAIN_A/web/appMainPage").build()
            client.newCall(request).execute().use { response ->
                // response.request.url 会是最终被重定向到的那个 URL
                val finalUrl = response.request.url
                val token = finalUrl.queryParameter("token")

                if (token != null) {
                    Result.success(LoginSuccessData(token, role.member))
                } else {
                    Result.failure(Exception("未能截取到 Token，最终 URL: $finalUrl"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}