package com.daijunhao.classroommanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daijunhao.classroommanager.data.CheckInApi
import com.daijunhao.classroommanager.data.CheckInClassAnalysis
import com.daijunhao.classroommanager.data.CheckInClassStudent
import com.daijunhao.classroommanager.data.CheckInStudentState
import com.daijunhao.classroommanager.data.TokenStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInClassAnalysisScreen(
    notifyId: String,
    cid: String,
    onBack: () -> Unit,
    onOpenMember: (memberId: String, memberName: String) -> Unit
) {
    val context = LocalContext.current
    var analysis by remember(notifyId) {
        mutableStateOf<CheckInClassAnalysis?>(null)
    }
    var loading by remember(notifyId) { mutableStateOf(true) }
    var error by remember(notifyId) { mutableStateOf<String?>(null) }
    var reloadVersion by remember(notifyId) { mutableIntStateOf(0) }

    LaunchedEffect(notifyId, cid, reloadVersion) {
        val token = TokenStore.readToken(context)
        val memberId = TokenStore.readMemberId(context)
        if (token.isNullOrBlank() || memberId.isNullOrBlank()) {
            error = "登录凭证失效，请重新登录"
            loading = false
            return@LaunchedEffect
        }
        loading = true
        CheckInApi.getClassAnalysis(
            token = token,
            memberId = memberId,
            notifyId = notifyId,
            cid = cid
        ).onSuccess {
            analysis = it
            error = null
        }.onFailure {
            error = it.message ?: "加载全班打卡情况失败"
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全班完成分析") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { reloadVersion++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        when {
            loading && analysis == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            error != null && analysis == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "加载失败：$error",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { reloadVersion++ }) {
                        Text("重试")
                    }
                }
            }

            analysis != null -> {
                val current = analysis!!
                val wrong = current.students.filter {
                    it.state == CheckInStudentState.WRONG
                }
                val missing = current.students.filter {
                    it.state == CheckInStudentState.NOT_SUBMITTED
                }
                val submitted = current.students.filter {
                    it.state == CheckInStudentState.SUBMITTED
                }
                var showMissing by remember(notifyId) { mutableStateOf(false) }
                var showSubmitted by remember(notifyId) { mutableStateOf(false) }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = current.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "班级共 ${current.students.size} 人",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "点击学生姓名进入其只读打卡详情，查看提交内容。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CheckInCountBadge("已提交 ${current.submittedCount}")
                                    CheckInCountBadge("错误 ${current.wrongCount}")
                                    CheckInCountBadge("未提交 ${current.notSubmittedCount}")
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "错误 / 需订正",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                if (wrong.isEmpty()) {
                                    Text(
                                        text = "暂无错误记录",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                } else {
                                    CheckInStudentList(wrong, onOpenMember)
                                }

                                if (missing.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(
                                            alpha = 0.5f
                                        )
                                    )
                                    TextButton(onClick = {
                                        showMissing = !showMissing
                                    }) {
                                        Text(
                                            if (showMissing) {
                                                "收起未提交名单"
                                            } else {
                                                "查看未提交名单（${missing.size}）"
                                            }
                                        )
                                    }
                                    if (showMissing) {
                                        CheckInStudentList(missing, onOpenMember)
                                    }
                                }

                                if (submitted.isNotEmpty()) {
                                    TextButton(onClick = {
                                        showSubmitted = !showSubmitted
                                    }) {
                                        Text(
                                            if (showSubmitted) {
                                                "收起已提交名单"
                                            } else {
                                                "查看已提交名单（${submitted.size}）"
                                            }
                                        )
                                    }
                                    if (showSubmitted) {
                                        CheckInStudentList(submitted, onOpenMember)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = "分析数据会优先使用短时缓存；刷新按钮会在缓存过期后重新拉取。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckInCountBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun CheckInStudentList(
    students: List<CheckInClassStudent>,
    onOpenMember: (String, String) -> Unit
) {
    Column {
        students.forEach { student ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onOpenMember(student.memberId, student.name)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (student.state == CheckInStudentState.WRONG) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = student.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (student.studentNumber != Int.MAX_VALUE) {
                    Text(
                        text = "学号 ${student.studentNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "查看",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
