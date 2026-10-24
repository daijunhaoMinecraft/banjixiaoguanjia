package com.daijunhao.classroommanager.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.daijunhao.classroommanager.data.RoleListData
import com.daijunhao.classroommanager.data.TokenStore
import com.daijunhao.classroommanager.data.WebLoginApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isPasswordMode by remember { mutableStateOf(true) }
    var randomVal by remember { mutableStateOf("") }
    var qrCodeUrl by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    // 角色选择相关状态
    var roleListData by remember { mutableStateOf<RoleListData?>(null) }
    var unionId by remember { mutableStateOf("") }
    var loginType by remember { mutableStateOf("") }

    // 初始化：拉取 random 和二维码
    LaunchedEffect(Unit) {
        loading = true
        WebLoginApi.initLogin().onSuccess { data ->
            randomVal = data.random
            qrCodeUrl = data.qrUrl
        }.onFailure {
            Toast.makeText(context, "网络初始化失败", Toast.LENGTH_SHORT).show()
        }
        loading = false
    }

    fun fetchRoles() {
        scope.launch {
            loading = true
            WebLoginApi.getRoleList(randomVal, unionId, loginType).onSuccess { data ->
                roleListData = data
            }.onFailure {
                Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
            }
            loading = false
        }
    }

    // 如果处于扫码模式，开启轮询
    LaunchedEffect(isPasswordMode, randomVal) {
        if (!isPasswordMode && randomVal.isNotEmpty()) {
            while (true) {
                delay(3000) // 网页是3秒一查
                WebLoginApi.checkQrStatus(randomVal).onSuccess { fetchedUnionId ->
                    if (fetchedUnionId != null) { // 扫码成功
                        unionId = fetchedUnionId
                        loginType = "qr"
                        fetchRoles()
                    }
                }.onFailure {
                    Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
                    break // 出错或超时跳出轮询
                }
            }
        }
    }

    // ------------- 角色选择界面 -------------
    if (roleListData != null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("请选择角色", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))

            LazyColumn {
                items(roleListData!!.roles) { role ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                scope.launch {
                                    loading = true
                                    WebLoginApi.selectRoleAndGetToken(role, roleListData!!.imprint)
                                        .onSuccess { data ->
                                            TokenStore.save(context, data.token, data.memberId)
                                            Toast.makeText(context, "登录成功", Toast.LENGTH_SHORT).show()
                                            onLoginSuccess()
                                        }.onFailure {
                                            Toast.makeText(context, "获取 Token 失败", Toast.LENGTH_SHORT).show()
                                        }
                                    loading = false
                                }
                            }
                    ) {
                        Text(
                            text = role.nameRole,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
            if (loading) CircularProgressIndicator(Modifier.padding(top = 16.dp))
        }
        return // 如果显示了角色选择，就不显示登录表单
    }

    // ------------- 登录主界面 -------------
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("登录班级小管家", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))

        if (isPasswordMode) {
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("手机号") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (phone.isEmpty() || password.isEmpty()) {
                        Toast.makeText(context, "请输入完整信息", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        loading = true
                        WebLoginApi.loginWithPassword(phone, password, randomVal).onSuccess { resUnionId ->
                            unionId = resUnionId
                            loginType = "form"
                            fetchRoles()
                        }.onFailure {
                            Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
                        }
                        loading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(24.dp)) else Text("账号密码登录")
            }
        } else {
            // 二维码模式
            if (qrCodeUrl.isNotEmpty()) {
                AsyncImage(
                    model = qrCodeUrl,
                    contentDescription = "扫码登录",
                    modifier = Modifier.size(200.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("请使用微信扫一扫", color = MaterialTheme.colorScheme.primary)
            } else if (loading) {
                CircularProgressIndicator()
            }
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { isPasswordMode = !isPasswordMode }) {
            Text(if (isPasswordMode) "切换到微信扫码登录" else "切换到账号密码登录")
        }
    }
}