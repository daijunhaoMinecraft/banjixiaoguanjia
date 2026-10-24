package com.daijunhao.classroommanager.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.daijunhao.classroommanager.data.ClassApi
import com.daijunhao.classroommanager.data.MemberInfo
import com.daijunhao.classroommanager.data.SavedScoreExport
import com.daijunhao.classroommanager.data.ScoreApi
import com.daijunhao.classroommanager.data.ScoreExportStore
import com.daijunhao.classroommanager.data.ScoreResultData
import com.daijunhao.classroommanager.data.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreDetailScreen(
    scoreId: String,
    cid: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nameInput by remember(scoreId) { mutableStateOf("") }
    var members by remember(cid) { mutableStateOf<List<MemberInfo>>(emptyList()) }
    var membersLoading by remember(cid) { mutableStateOf(false) }
    var membersError by remember(cid) { mutableStateOf<String?>(null) }
    var suggestionsOpen by remember(cid) { mutableStateOf(false) }
    var scoreData by remember(scoreId) { mutableStateOf<ScoreResultData?>(null) }
    var querying by remember(scoreId) { mutableStateOf(false) }
    var queryError by remember(scoreId) { mutableStateOf<String?>(null) }
    var exportRunning by remember(scoreId) { mutableStateOf(false) }
    var exportCompleted by remember(scoreId) { mutableIntStateOf(0) }
    var exportTotal by remember(scoreId) { mutableIntStateOf(0) }
    var exportError by remember(scoreId) { mutableStateOf<String?>(null) }
    var savedExport by remember(scoreId) {
        mutableStateOf<SavedScoreExport?>(null)
    }
    var exportedSuccessCount by remember(scoreId) { mutableIntStateOf(0) }
    var exportedRowCount by remember(scoreId) { mutableIntStateOf(0) }

    fun queryScore(name: String) {
        if (querying) return
        val token = TokenStore.readToken(context)
        if (token.isNullOrBlank()) {
            queryError = "登录凭证失效，请重新登录"
            return
        }
        scope.launch {
            querying = true
            queryError = null
            ScoreApi.getScoreDetail(token, scoreId, name)
                .onSuccess {
                    scoreData = it
                    nameInput = it.studentName
                }
                .onFailure {
                    queryError = it.message ?: "查询成绩失败"
                }
            querying = false
        }
    }

    fun exportAllScores() {
        if (exportRunning) return
        val token = TokenStore.readToken(context)
        if (token.isNullOrBlank()) {
            exportError = "登录凭证失效，请重新登录"
            return
        }
        if (cid.isBlank()) {
            exportError = "缺少班级 ID，无法导出全班成绩"
            return
        }
        scope.launch {
            exportRunning = true
            exportError = null
            savedExport = null
            exportCompleted = 0
            exportTotal = 0
            try {
                val export = ScoreApi.getAllStudentScores(
                    token = token,
                    scoreId = scoreId,
                    cid = cid,
                    onProgress = { completed, total ->
                        withContext(Dispatchers.Main) {
                            exportCompleted = completed
                            exportTotal = total
                        }
                    }
                ).getOrElse { throw it }
                val fileName = ScoreExportStore.fileName(export)
                val workbook = ScoreExportStore.buildWorkbook(export)
                val saved = ScoreExportStore.save(
                    context = context,
                    displayName = fileName,
                    bytes = workbook
                ).getOrElse { throw it }
                savedExport = saved
                exportedSuccessCount = export.rows.count { it.status == "成功" }
                exportedRowCount = export.rows.size
                Toast.makeText(
                    context,
                    "已导出 ${export.rows.size} 名学生的成绩",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                exportError = e.message ?: "导出失败"
            } finally {
                exportRunning = false
            }
        }
    }

    fun share(saved: SavedScoreExport) {
        val uri = saved.uri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享班级成绩"))
    }

    LaunchedEffect(scoreId) {
        queryScore("")
    }

    LaunchedEffect(cid) {
        val token = TokenStore.readToken(context)
        if (token.isNullOrBlank() || cid.isBlank()) return@LaunchedEffect
        membersLoading = true
        ClassApi.getMembersByCid(token, cid)
            .onSuccess {
                members = it.sortedWith(
                    compareBy<MemberInfo> { it.studentNumber }
                        .thenBy { member -> member.name }
                )
                membersError = null
            }
            .onFailure {
                membersError = it.message ?: "无法加载班级名单"
            }
        membersLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成绩详情与分析") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "按姓名查询",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "姓名需与班级名单一致。可用于查看同班同学成绩并分析自己的位置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    val filteredMembers = remember(nameInput, members) {
                        members.filter { member ->
                            nameInput.isBlank() ||
                                    member.name.contains(nameInput, ignoreCase = true)
                        } // 移除了 .take(15)，这样名单过长时可以在下拉菜单中完整滚动查看
                    }
                    Box {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = {
                                nameInput = it
                                suggestionsOpen = members.isNotEmpty()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged {
                                    if (it.isFocused && members.isNotEmpty()) {
                                        suggestionsOpen = true
                                    }
                                },
                            label = { Text("学生姓名") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null
                                )
                            },
                            trailingIcon = {
                                if (membersLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    IconButton(
                                        onClick = {
                                            suggestionsOpen = !suggestionsOpen
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowDropDown,
                                            contentDescription = "展开班级名单"
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            enabled = !querying && !exportRunning
                        )
                        DropdownMenu(
                            expanded = suggestionsOpen &&
                                    filteredMembers.isNotEmpty(),
                            onDismissRequest = { suggestionsOpen = false },
                            properties = PopupProperties(focusable = false), // 核心修复：防止夺走焦点导致输入法弹开
                            modifier = Modifier.heightIn(max = 280.dp) // 辅助修复：限制最大高度，保证软键盘开启时也可以滚动
                        ) {
                            filteredMembers.forEach { member ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = member.name,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            val details = buildList {
                                                if (member.studentNumber != Int.MAX_VALUE) {
                                                    add("学号 ${member.studentNumber}")
                                                }
                                                if (member.wxName.isNotBlank()) {
                                                    add(member.wxName)
                                                }
                                            }.joinToString(" · ")
                                            if (details.isNotBlank()) {
                                                Text(
                                                    text = details,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        nameInput = member.name
                                        suggestionsOpen = false
                                        queryScore(member.name)
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        text = when {
                            membersLoading -> "正在加载班级名单…"
                            membersError != null -> "名单加载失败，可直接输入姓名：$membersError"
                            members.isNotEmpty() -> "可从下拉列表选择，或输入姓名自动筛选（${members.size} 人）"
                            else -> "输入学生姓名后查询"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (membersError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { queryScore(nameInput.trim()) },
                            enabled = !querying && !exportRunning,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (querying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(if (querying) "查询中" else "查询成绩")
                        }
                        OutlinedButton(
                            onClick = {
                                nameInput = ""
                                queryScore("")
                            },
                            enabled = !querying && !exportRunning
                        ) {
                            Text("我的成绩")
                        }
                    }
                    queryError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            when {
                querying && scoreData == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                scoreData != null -> {
                    ScoreResultCard(scoreData!!)
                }

                queryError != null -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = "成绩加载失败：$queryError",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "全班成绩分析",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "读取本班成员名单，按姓名逐位查询成绩，生成包含全部科目、排名和查询状态的 XLSX 文件。10 分钟内重复分析会复用本地缓存。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = ::exportAllScores,
                        enabled = !exportRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (exportRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在查询全班")
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("分析并导出全班成绩")
                        }
                    }
                    if (exportRunning) {
                        Spacer(Modifier.height(10.dp))
                        val progress = if (exportTotal > 0) {
                            exportCompleted.toFloat() / exportTotal
                        } else {
                            0f
                        }
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "已查询 $exportCompleted / $exportTotal 人",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    exportError?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "导出失败：$it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    savedExport?.let { saved ->
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "导出完成",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "成功 $exportedSuccessCount / $exportedRowCount 人",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = saved.displayPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (saved.uri != null) {
                                    TextButton(onClick = { share(saved) }) {
                                        Icon(
                                            Icons.Default.Share,
                                            contentDescription = null
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("分享 XLSX")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreResultCard(data: ScoreResultData) {
    val scoreMap = remember(data.scores) {
        data.scores.associate { it.subject to it.score }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = data.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "考试时间：${data.examTime}",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = data.studentName.ifBlank { "当前学生" },
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            val highlights = listOf(
                "总分折后" to "总分",
                "班排" to "班级排名",
                "段排" to "段排名"
            ).mapNotNull { (subject, label) ->
                scoreMap[subject]?.let { label to it }
            }
            if (highlights.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    highlights.forEach { (label, value) ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "各科成绩",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            data.scores.forEachIndexed { index, score ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = score.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = score.score,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (index < data.scores.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(
                            alpha = 0.45f
                        )
                    )
                }
            }
        }
    }
}