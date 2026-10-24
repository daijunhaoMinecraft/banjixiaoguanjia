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
import androidx.compose.foundation.lazy.items
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
import com.daijunhao.classroommanager.data.ClassTaskAnalysis
import com.daijunhao.classroommanager.data.ClassTaskAnalysisApi
import com.daijunhao.classroommanager.data.StudentTaskState
import com.daijunhao.classroommanager.data.StudentTaskStatus
import com.daijunhao.classroommanager.data.TaskClassBreakdown
import com.daijunhao.classroommanager.data.TokenStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassTaskAnalysisScreen(
    mainId: String,
    notifyId: String,
    cid: String,
    onBack: () -> Unit,
    onOpenMember: (memberId: String, memberName: String) -> Unit
) {
    val context = LocalContext.current
    var analysis by remember(mainId) {
        mutableStateOf<ClassTaskAnalysis?>(null)
    }
    var loading by remember(mainId) { mutableStateOf(true) }
    var error by remember(mainId) { mutableStateOf<String?>(null) }
    var reloadVersion by remember(mainId) { mutableIntStateOf(0) }

    LaunchedEffect(mainId, cid, reloadVersion) {
        val token = TokenStore.readToken(context)
        val memberId = TokenStore.readMemberId(context)
        if (token.isNullOrBlank() || memberId.isNullOrBlank()) {
            error = "登录凭证失效，请重新登录"
            loading = false
            return@LaunchedEffect
        }
        loading = true
        ClassTaskAnalysisApi.load(
            token = token,
            memberId = memberId,
            cid = cid,
            mainId = mainId
        ).onSuccess {
            analysis = it
            error = null
        }.onFailure {
            error = it.message ?: "加载全班任务情况失败"
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
                                    text = "班级共 ${current.members.size} 人 · " +
                                        "错误状态依据任务反馈状态识别",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "点击学生姓名可进入该学生的任务视图，查看具体模块和提交记录。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    items(current.tasks, key = { it.taskId }) { task ->
                        TaskAnalysisCard(
                            task = task,
                            onOpenMember = onOpenMember
                        )
                    }

                    item {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = "“错误/需订正”来自服务端反馈状态（-3）；“未提交”表示该任务没有对应反馈记录。",
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
private fun TaskAnalysisCard(
    task: TaskClassBreakdown,
    onOpenMember: (String, String) -> Unit
) {
    val wrong = task.students.filter { it.state == StudentTaskState.WRONG }
    val missing = task.students.filter {
        it.state == StudentTaskState.NOT_SUBMITTED
    }
    val completed = task.students.filter {
        it.state == StudentTaskState.COMPLETED
    }
    val other = task.students.filter { it.state == StudentTaskState.OTHER }
    var showMissing by remember(task.taskId) { mutableStateOf(false) }
    var showCompleted by remember(task.taskId) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountBadge("正确 ${task.completedCount}", true)
                CountBadge("错误 ${task.wrongCount}", false)
                CountBadge("未提交 ${task.notSubmittedCount}", false)
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
                    text = "错误/需订正",
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
                StudentNameList(wrong, onOpenMember)
            }

            if (missing.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                TextButton(onClick = { showMissing = !showMissing }) {
                    Text(
                        if (showMissing) {
                            "收起未提交名单"
                        } else {
                            "查看未提交名单（${missing.size}）"
                        }
                    )
                }
                if (showMissing) {
                    StudentNameList(missing, onOpenMember)
                }
            }

            if (completed.isNotEmpty()) {
                TextButton(onClick = { showCompleted = !showCompleted }) {
                    Text(
                        if (showCompleted) {
                            "收起正确名单"
                        } else {
                            "查看正确名单（${completed.size}）"
                        }
                    )
                }
                if (showCompleted) {
                    StudentNameList(completed, onOpenMember)
                }
            }

            if (other.isNotEmpty()) {
                Text(
                    text = "其他状态：${other.joinToString("、") { it.member.name }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CountBadge(text: String, completed: Boolean) {
    Surface(
        color = if (completed) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
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
private fun StudentNameList(
    students: List<StudentTaskStatus>,
    onOpenMember: (String, String) -> Unit
) {
    Column {
        students.forEach { student ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onOpenMember(
                            student.member.id,
                            student.member.name
                        )
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (student.state == StudentTaskState.WRONG) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = student.member.name.ifBlank { "未命名学生" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (student.member.studentNumber != Int.MAX_VALUE) {
                    Text(
                        text = "学号 ${student.member.studentNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "查看任务",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
