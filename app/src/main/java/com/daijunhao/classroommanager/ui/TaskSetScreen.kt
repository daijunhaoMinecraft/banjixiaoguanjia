package com.daijunhao.classroommanager.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daijunhao.classroommanager.data.SubmissionMedia
import com.daijunhao.classroommanager.data.SubmissionMediaKind
import com.daijunhao.classroommanager.data.TaskFeedbackHistory
import com.daijunhao.classroommanager.data.TaskFeedbackSummary
import com.daijunhao.classroommanager.data.TaskHistory
import com.daijunhao.classroommanager.data.TaskSetApi
import com.daijunhao.classroommanager.data.TaskSetData
import com.daijunhao.classroommanager.data.TaskSetTask
import com.daijunhao.classroommanager.data.TokenStore
import kotlinx.coroutines.launch

private data class HistoryUiState(
    val loading: Boolean = false,
    val history: TaskHistory? = null,
    val error: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSetDetailScreen(
    mainId: String,
    notifyId: String,
    cid: String,
    relatedId: String,
    refreshVersion: Int = 0,
    onBack: () -> Unit,
    onOpenRelated: (String) -> Unit,
    onOpenAnswerSheet: (
        mainId: String,
        notifyId: String,
        cid: String,
        taskId: String,
        feedbackType: Int,
        sheetId: String
    ) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val historyStates = remember(mainId) {
        mutableStateMapOf<String, HistoryUiState>()
    }

    var taskSet by remember(mainId) { mutableStateOf<TaskSetData?>(null) }
    var loading by remember(mainId) { mutableStateOf(true) }
    var error by remember(mainId) { mutableStateOf<String?>(null) }
    var reloadVersion by remember(mainId) { mutableIntStateOf(0) }
    var selectedMedia by remember(mainId) { mutableStateOf<SubmissionMedia?>(null) }

    LaunchedEffect(mainId, cid, reloadVersion, refreshVersion) {
        val token = TokenStore.readToken(context)
        val memberId = TokenStore.readMemberId(context)
        if (token.isNullOrBlank() || memberId.isNullOrBlank()) {
            error = "登录凭证失效，请重新登录"
            loading = false
            return@LaunchedEffect
        }
        loading = true
        TaskSetApi.getTaskSet(
            token = token,
            memberId = memberId,
            cid = cid,
            mainId = mainId
        ).onSuccess {
            taskSet = it
            historyStates.clear()
            error = null
        }.onFailure {
            error = it.message ?: "加载任务合集失败"
        }
        loading = false
    }

    fun loadHistory(task: TaskSetTask) {
        if (historyStates[task.id]?.loading == true) return
        historyStates[task.id] = HistoryUiState(loading = true)
        scope.launch {
            val token = TokenStore.readToken(context)
            val memberId = TokenStore.readMemberId(context)
            val currentTaskSet = taskSet
            if (token.isNullOrBlank() || memberId.isNullOrBlank() || currentTaskSet == null) {
                historyStates[task.id] = HistoryUiState(error = "登录凭证失效")
                return@launch
            }

            TaskSetApi.getTaskHistory(
                token = token,
                memberId = memberId,
                cid = cid,
                mainId = mainId,
                notifyId = currentTaskSet.notifyId.ifBlank { notifyId },
                task = task
            ).onSuccess { history ->
                historyStates[task.id] = HistoryUiState(history = history)
            }.onFailure {
                historyStates[task.id] = HistoryUiState(
                    error = it.message ?: "加载提交记录失败"
                )
            }
        }
    }

    fun openMedia(media: SubmissionMedia) {
        selectedMedia = media
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("任务合集") },
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
        when {
            loading && taskSet == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            error != null && taskSet == null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("加载失败：$error", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { reloadVersion++ }) {
                        Text("重试")
                    }
                }
            }

            taskSet != null -> {
                val current = taskSet!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        TaskSetHeader(current, onOpenMedia = ::openMedia)
                    }

                    if (relatedId.isNotBlank() && relatedId != "-") {
                        item {
                            RelatedTaskCard(
                                onOpen = { onOpenRelated(relatedId) }
                            )
                        }
                    }

                    if (current.tasks.isEmpty()) {
                        item {
                            Text(
                                text = "该任务合集暂无子任务",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(current.tasks, key = { it.id }) { task ->
                        TaskSetTaskCard(
                            task = task,
                            historyState = historyStates[task.id],
                            onLoadHistory = { loadHistory(task) },
                            onOpenMedia = ::openMedia,
                            onOpenAnswerSheet = { feedback ->
                                onOpenAnswerSheet(
                                    mainId,
                                    current.notifyId.ifBlank { notifyId },
                                    cid,
                                    task.id,
                                    feedback.type,
                                    feedback.sheetId
                                )
                            }
                        )
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    MediaViewer(
        media = selectedMedia,
        onDismiss = { selectedMedia = null }
    )
}

@Composable
private fun TaskSetHeader(
    taskSet: TaskSetData,
    onOpenMedia: (SubmissionMedia) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (taskSet.subject.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = taskSet.subject,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = taskSet.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (taskSet.startDay.isNotBlank() || taskSet.endDay.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${taskSet.startDay} - ${taskSet.endDay}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "已完成 ${taskSet.completedTaskCount}/${taskSet.tasks.size} 个子任务",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            taskSet.media
                .filter {
                    it.kind == SubmissionMediaKind.IMAGE ||
                        it.kind == SubmissionMediaKind.VIDEO
                }
                .forEach { media ->
                    Spacer(Modifier.height(10.dp))
                    SubmissionMediaPreview(
                        media = media,
                        onOpen = onOpenMedia,
                        imageHeight = 160.dp
                    )
                }
        }
    }
}

@Composable
private fun RelatedTaskCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "关联自定义作业",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "该任务合集同时包含一个可提交图片或视频的自定义作业。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpen) {
                Text("打开关联作业")
            }
        }
    }
}

@Composable
private fun TaskSetTaskCard(
    task: TaskSetTask,
    historyState: HistoryUiState?,
    onLoadHistory: () -> Unit,
    onOpenMedia: (SubmissionMedia) -> Unit,
    onOpenAnswerSheet: (TaskFeedbackSummary) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(status = task.status)
            }

            Spacer(Modifier.height(6.dp))
            val dateRange = listOf(task.startLabel, task.endLabel)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
            if (dateRange.isNotBlank()) {
                Text(
                    text = dateRange,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))
            task.media
                .filter {
                    it.kind == SubmissionMediaKind.IMAGE ||
                        it.kind == SubmissionMediaKind.VIDEO
                }
                .forEach { media ->
                    SubmissionMediaPreview(
                        media = media,
                        onOpen = onOpenMedia,
                        imageHeight = 140.dp
                    )
                    Spacer(Modifier.height(8.dp))
                }

            task.feedbacks.forEach { feedback ->
                FeedbackSummaryRow(
                    feedback = feedback,
                    enabled = historyState?.loading != true,
                    onAnswer = { onOpenAnswerSheet(feedback) }
                )
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(10.dp))

            when {
                historyState == null -> {
                    OutlinedButton(
                        onClick = onLoadHistory,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("查看提交记录")
                    }
                }

                historyState.loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("正在加载提交记录...", style = MaterialTheme.typography.bodySmall)
                    }
                }

                historyState.error != null -> {
                    Text(
                        text = "加载失败：${historyState.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onLoadHistory) {
                        Text("重试")
                    }
                }

                historyState.history != null -> {
                    Text(
                        text = "提交记录",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    historyState.history.feedbacks.forEach { history ->
                        FeedbackHistoryCard(
                            history = history,
                            onOpenMedia = onOpenMedia
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    OutlinedButton(
                        onClick = onLoadHistory,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("刷新提交记录")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: Int) {
    val completed = status == 3
    Surface(
        color = if (completed) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = if (completed) "已完成" else "未完成",
            color = if (completed) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun FeedbackSummaryRow(
    feedback: TaskFeedbackSummary,
    enabled: Boolean,
    onAnswer: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = feedbackTypeLabel(feedback.type),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feedback.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (feedback.subTitle.isNotBlank()) {
                Text(
                    text = feedback.subTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = onAnswer, enabled = enabled) {
            Text("作答")
        }
    }
}

@Composable
private fun FeedbackHistoryCard(
    history: TaskFeedbackHistory,
    onOpenMedia: (SubmissionMedia) -> Unit
) {
    var showAudio by remember(history.feedbackId) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = history.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (history.submitted) "已提交" else "未提交",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (history.submitted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            if (history.submitted) {
                val details = buildList {
                    history.score?.let { add("得分 $it") }
                    history.durationSeconds?.let { add("用时 ${it}s") }
                }.joinToString(" · ")
                val visualMedia = history.media.filter {
                    it.kind == SubmissionMediaKind.IMAGE ||
                        it.kind == SubmissionMediaKind.VIDEO
                }
                val audioCount = history.media.count {
                    it.kind == SubmissionMediaKind.AUDIO
                }
                val audioMedia = history.media.filter {
                    it.kind == SubmissionMediaKind.AUDIO
                }
                val fileCount = history.media.count {
                    it.kind == SubmissionMediaKind.FILE
                }
                if (details.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                history.error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "提交内容加载不完整：$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (visualMedia.isEmpty() && history.error == null) {
                    Spacer(Modifier.height(6.dp))
                    val extraParts = buildList {
                        if (audioCount > 0) add("$audioCount 条录音")
                        if (fileCount > 0) add("$fileCount 个文件")
                    }
                    Text(
                        text = if (extraParts.isEmpty()) {
                            "本次提交没有可展示的图片或视频。"
                        } else {
                            "本次提交没有图片或视频，包含 ${extraParts.joinToString("、")}。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                visualMedia.forEach { media ->
                    Spacer(Modifier.height(8.dp))
                    SubmissionMediaPreview(
                        media = media,
                        onOpen = onOpenMedia,
                        imageHeight = 180.dp
                    )
                }

                if (visualMedia.isNotEmpty() && audioCount > 0) {
                    Spacer(Modifier.height(6.dp))
                }

                if (audioCount > 0) {
                    TextButton(onClick = { showAudio = !showAudio }) {
                        Text(
                            if (showAudio) {
                                "收起录音"
                            } else {
                                "播放录音（$audioCount 条）"
                            }
                        )
                    }
                    if (showAudio) {
                        audioMedia.forEach { media ->
                            SubmissionMediaPreview(
                                media = media,
                                onOpen = onOpenMedia
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun feedbackTypeLabel(type: Int): String = when (type) {
    6 -> "答题"
    8, 9 -> "跟读"
    13 -> "背诵"
    14 -> "听写"
    1, 15 -> "答题"
    else -> "作业"
}
