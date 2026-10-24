package com.daijunhao.classroommanager.ui

import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.daijunhao.classroommanager.data.AnswerQuestion
import com.daijunhao.classroommanager.data.AnswerResponse
import com.daijunhao.classroommanager.data.AnswerSheetApi
import com.daijunhao.classroommanager.data.AnswerSheetData
import com.daijunhao.classroommanager.data.AppSettings
import com.daijunhao.classroommanager.data.LocalAudioRecording
import com.daijunhao.classroommanager.data.SoeApi
import com.daijunhao.classroommanager.data.SoeScore
import com.daijunhao.classroommanager.data.SubmissionMedia
import com.daijunhao.classroommanager.data.SubmissionMediaKind
import com.daijunhao.classroommanager.data.TaskSetApi
import com.daijunhao.classroommanager.data.TokenStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private data class AudioAttemptUi(
    val recording: LocalAudioRecording,
    val score: SoeScore? = null,
    val evaluating: Boolean = false,
    val error: String? = null
)

private data class QuestionUiState(
    val selectedIds: List<String> = emptyList(),
    val attempts: List<AudioAttemptUi> = emptyList()
) {
    val bestAttempt: AudioAttemptUi?
        get() = attempts
            .filter { it.score != null }
            .maxByOrNull { requireNotNull(it.score).overall }

    val scoredAttempts: List<AudioAttemptUi>
        get() = attempts.filter { it.score != null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnswerSheetScreen(
    mainId: String,
    notifyId: String,
    cid: String,
    taskId: String,
    feedbackType: Int,
    sheetId: String,
    onBack: () -> Unit,
    onSubmitted: () -> Unit
) {
    val context = LocalContext.current
    val debugOptions = remember {
        AppSettings.readDebugOptions(context)
    }
    val scope = rememberCoroutineScope()
    val questionStates = remember(sheetId) {
        mutableStateMapOf<String, QuestionUiState>()
    }
    val uploadedAudioKeys = remember(sheetId) { mutableStateMapOf<String, String>() }

    var sheet by remember(sheetId) { mutableStateOf<AnswerSheetData?>(null) }
    var loading by remember(sheetId) { mutableStateOf(true) }
    var error by remember(sheetId) { mutableStateOf<String?>(null) }
    var submitting by remember(sheetId) { mutableStateOf(false) }
    var submitProgress by remember(sheetId) { mutableStateOf<Float?>(null) }
    var submitLabel by remember(sheetId) { mutableStateOf("") }
    var submitError by remember(sheetId) { mutableStateOf<String?>(null) }
    var recorderQuestionId by remember(sheetId) { mutableStateOf<String?>(null) }
    var showCamera by remember(sheetId) { mutableStateOf(false) }
    var photoFile by remember(sheetId) { mutableStateOf<File?>(null) }
    var uploadedPhotoKey by remember(sheetId) { mutableStateOf<String?>(null) }
    var mediaViewer by remember(sheetId) { mutableStateOf<SubmissionMedia?>(null) }
    val startedAt = remember(sheetId) { SystemClock.elapsedRealtime() }

    fun updateQuestion(questionId: String, transform: (QuestionUiState) -> QuestionUiState) {
        questionStates[questionId] = transform(
            questionStates[questionId] ?: QuestionUiState()
        )
    }

    fun requiredReadCount(question: AnswerQuestion): Int {
        return if (debugOptions.singleRead) 1 else question.needReadCount
    }

    fun evaluateRecording(question: AnswerQuestion, recording: LocalAudioRecording) {
        val attemptPath = recording.file.absolutePath
        updateQuestion(question.id) { current ->
            current.copy(
                attempts = current.attempts + AudioAttemptUi(
                    recording = recording,
                    evaluating = true
                )
            )
        }
        uploadedAudioKeys.remove(question.id)
        scope.launch {
            val result = SoeApi.evaluate(recording, question.stemText)
            val current = questionStates[question.id] ?: return@launch
            questionStates[question.id] = current.copy(
                attempts = current.attempts.map { attempt ->
                    if (attempt.recording.file.absolutePath != attemptPath) {
                        attempt
                    } else {
                        result.fold(
                            onSuccess = {
                                attempt.copy(
                                    score = it,
                                    evaluating = false,
                                    error = null
                                )
                            },
                            onFailure = {
                                attempt.copy(
                                    score = null,
                                    evaluating = false,
                                    error = it.message ?: "评测失败"
                                )
                            }
                        )
                    }
                }
            )
        }
    }

    LaunchedEffect(sheetId) {
        val token = TokenStore.readToken(context)
        if (token.isNullOrBlank()) {
            error = "登录凭证失效，请重新登录"
            loading = false
            return@LaunchedEffect
        }
        AnswerSheetApi.getAnswerSheet(token, sheetId)
            .onSuccess { loaded ->
                sheet = loaded
                loaded.questions.forEach { question ->
                    questionStates[question.id] = QuestionUiState()
                }
                error = null
            }
            .onFailure { error = it.message ?: "加载答题卷失败" }
        loading = false
    }

    fun isQuestionComplete(question: AnswerQuestion): Boolean {
        if (debugOptions.forceSubmit) return true
        val state = questionStates[question.id] ?: QuestionUiState()
        return when (question.type) {
            6, 11 -> {
                val best = state.bestAttempt
                best != null &&
                    best.score?.passed == true &&
                    state.scoredAttempts.size >= requiredReadCount(question)
            }
            1 -> state.selectedIds.size == 1
            4 -> state.selectedIds.isNotEmpty()
            15 -> state.selectedIds.size == question.blanks.size
            else -> true
        }
    }

    fun submit() {
        val currentSheet = sheet ?: return
        if (submitting) return
        val token = TokenStore.readToken(context)
        val memberId = TokenStore.readMemberId(context)
        if (token.isNullOrBlank() || memberId.isNullOrBlank()) {
            submitError = "登录凭证失效，请重新登录"
            return
        }

        val incomplete = currentSheet.questions.filterNot(::isQuestionComplete)
        if (incomplete.isNotEmpty()) {
            submitError = "还有 ${incomplete.size} 道题未完成"
            return
        }
        if (!debugOptions.forceSubmit && feedbackType == 14 && photoFile == null) {
            submitError = "请先拍照上传本次打卡凭证"
            return
        }

        submitting = true
        submitError = null
        submitLabel = "准备上传..."
        scope.launch {
            try {
                val bestAudioAttempts = buildMap {
                    currentSheet.questions
                        .filter { it.needsRecording }
                        .forEach { question ->
                            val best = questionStates[question.id]?.bestAttempt
                            if (best != null) {
                                put(question.id, best)
                            } else if (!debugOptions.forceSubmit) {
                                throw Exception("第 ${question.index} 题缺少有效录音")
                            }
                        }
                    }
                val recordingResults = coroutineScope {
                    bestAudioAttempts.map { (questionId, attempt) ->
                        async {
                            val existing = uploadedAudioKeys[questionId]
                            if (existing != null) {
                                questionId to Result.success(existing)
                            } else {
                                questionId to AnswerSheetApi.uploadRecording(
                                    token = token,
                                    memberId = memberId,
                                    recording = attempt.recording
                                )
                            }
                        }
                    }.awaitAll()
                }
                recordingResults.firstOrNull { it.second.isFailure }?.let { (_, result) ->
                    throw result.exceptionOrNull() ?: Exception("录音上传失败")
                }
                recordingResults.forEach { (questionId, result) ->
                    result.getOrNull()?.let { uploadedAudioKeys[questionId] = it }
                }

                submitLabel = "上传图片凭证..."
                val photoKey = if (feedbackType == 14 && photoFile != null) {
                    uploadedPhotoKey ?: AnswerSheetApi.uploadPhoto(
                        context = context,
                        token = token,
                        memberId = memberId,
                        file = requireNotNull(photoFile)
                    ).getOrElse { throw it }.also {
                        uploadedPhotoKey = it
                    }
                } else {
                    null
                }

                val completionBefore = TaskSetApi.getTaskCompletionState(
                    token = token,
                    memberId = memberId,
                    cid = cid,
                    mainId = mainId,
                    notifyId = notifyId,
                    taskId = taskId
                ).getOrNull()

                val responses = currentSheet.questions.map { question ->
                    val state = questionStates[question.id] ?: QuestionUiState()
                    when (question.type) {
                        6, 11 -> {
                            val best = state.bestAttempt
                            AnswerResponse(
                                questionId = question.id,
                                type = question.type,
                                audioKey = uploadedAudioKeys[question.id],
                                score = best?.score?.overall,
                                right = true
                            )
                        }

                        1, 4, 15 -> {
                            val answer = state.selectedIds.joinToString("|")
                            AnswerResponse(
                                questionId = question.id,
                                type = question.type,
                                selectedAnswer = answer,
                                right = true
                            )
                        }

                        else -> AnswerResponse(
                            questionId = question.id,
                            type = question.type,
                            right = true
                        )
                    }
                }

                submitLabel = "提交答题卷..."
                submitProgress = null
                val duration = (
                    (SystemClock.elapsedRealtime() - startedAt) / 1000
                    ).toInt().coerceAtLeast(1)
                AnswerSheetApi.submitAnswerSheet(
                    token = token,
                    memberId = memberId,
                    cid = cid,
                    mainId = mainId,
                    notifyId = notifyId,
                    taskId = taskId,
                    sheetId = sheetId,
                    acceptId = completionBefore?.acceptId,
                    durationSeconds = duration,
                    responses = responses,
                    subPhotoKey = photoKey
                ).getOrElse { throw it }

                submitLabel = "确认任务状态..."
                var completion = completionBefore
                for (attempt in 0 until 3) {
                    completion = TaskSetApi.getTaskCompletionState(
                        token = token,
                        memberId = memberId,
                        cid = cid,
                        mainId = mainId,
                        notifyId = notifyId,
                        taskId = taskId
                    ).getOrNull() ?: completion
                    if (completion?.allSheetsSubmitted == true) break
                    delay(700)
                }
                if (
                    completion?.allSheetsSubmitted == true ||
                    debugOptions.forceSubmit
                ) {
                    submitLabel = "完成整个任务..."
                    AnswerSheetApi.submitTask(
                        token = token,
                        memberId = memberId,
                        cid = cid,
                        mainId = mainId,
                        notifyId = notifyId,
                        taskId = taskId,
                        acceptId = completion?.acceptId
                    ).getOrElse { throw it }
                }

                Toast.makeText(context, "答题已提交", Toast.LENGTH_SHORT).show()
                onSubmitted()
            } catch (e: Exception) {
                submitError = e.message ?: "提交失败"
            } finally {
                submitting = false
                submitProgress = null
                submitLabel = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sheet?.title ?: "答题") },
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
        },
        bottomBar = {
            if (sheet != null) {
                Surface(shadowElevation = 8.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        if (submitting) {
                            Text(
                                text = submitLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            submitProgress?.let {
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        submitError?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        if (debugOptions.forceSubmit) {
                            Text(
                                text = "开发调试：强制提交已开启，将跳过未完成校验",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Button(
                            onClick = ::submit,
                            enabled = !submitting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("提交本卷")
                        }
                    }
                }
            }
        }
    ) { padding ->
        when {
            loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "加载失败：$error",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            sheet != null -> {
                val currentSheet = sheet!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        AnswerSheetHeader(
                            sheet = currentSheet,
                            feedbackType = feedbackType,
                            photoFile = photoFile,
                            onTakePhoto = { showCamera = true },
                            onClearPhoto = {
                                photoFile?.delete()
                                photoFile = null
                                uploadedPhotoKey = null
                            }
                        )
                    }

                    items(currentSheet.questions, key = { it.id }) { question ->
                        val questionState = questionStates[question.id] ?: QuestionUiState()
                        AnswerQuestionCard(
                            question = question,
                            state = questionState,
                            feedbackType = feedbackType,
                            requiredReadCount = requiredReadCount(question),
                            revealHiddenText = debugOptions.showHiddenAnswers,
                            enabled = !submitting,
                            onSelectOption = { optionId ->
                                updateQuestion(question.id) { state ->
                                    when (question.type) {
                                        1 -> state.copy(selectedIds = listOf(optionId))
                                        4 -> state.copy(
                                            selectedIds = if (optionId in state.selectedIds) {
                                                state.selectedIds - optionId
                                            } else {
                                                state.selectedIds + optionId
                                            }
                                        )

                                        15 -> state.copy(
                                            selectedIds = if (state.selectedIds.size < question.blanks.size) {
                                                state.selectedIds + optionId
                                            } else {
                                                state.selectedIds
                                            }
                                        )

                                        else -> state
                                    }
                                }
                            },
                            onClearAnswer = {
                                updateQuestion(question.id) {
                                    it.copy(selectedIds = emptyList())
                                }
                            },
                            onRecord = {
                                recorderQuestionId = question.id
                            },
                            onPlayMedia = { mediaViewer = it },
                            onRemoveAttempt = { attempt ->
                                attempt.recording.file.delete()
                                updateQuestion(question.id) {
                                    it.copy(
                                        attempts = it.attempts.filterNot { candidate ->
                                            candidate.recording.file.absolutePath ==
                                                attempt.recording.file.absolutePath
                                        }
                                    )
                                }
                                uploadedAudioKeys.remove(question.id)
                            }
                        )
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    recorderQuestionId?.let { questionId ->
        val question = sheet?.questions?.firstOrNull { it.id == questionId }
        val attemptNumber = (
            questionStates[questionId]?.attempts?.size ?: 0
            ) + 1
        val hideRecitationPrompt = question != null &&
            feedbackType == 13 &&
            question.type == 11 &&
            !debugOptions.showHiddenAnswers
        AudioRecorderDialog(
            promptText = if (hideRecitationPrompt) {
                ""
            } else {
                question?.stemText.orEmpty()
            },
            attemptNumber = attemptNumber,
            onDismiss = { recorderQuestionId = null },
            onRecorded = { recording ->
                val targetQuestion = question
                if (targetQuestion == null) {
                    recording.file.delete()
                } else {
                    evaluateRecording(targetQuestion, recording)
                }
                recorderQuestionId = null
            }
        )
    }

    if (showCamera) {
        CameraCaptureDialog(
            mode = CameraCaptureMode.PHOTO,
            onDismiss = { showCamera = false },
            onCaptured = { file ->
                photoFile?.delete()
                photoFile = file
                uploadedPhotoKey = null
                showCamera = false
            }
        )
    }

    MediaViewer(
        media = mediaViewer,
        onDismiss = { mediaViewer = null }
    )
}

@Composable
private fun AnswerSheetHeader(
    sheet: AnswerSheetData,
    feedbackType: Int,
    photoFile: File?,
    onTakePhoto: () -> Unit,
    onClearPhoto: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = sheet.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (feedbackType) {
                    8 -> "单词跟读 · 共 ${sheet.questions.size} 个"
                    9 -> "句子跟读 · 共 ${sheet.questions.size} 句"
                    13 -> "句子背诵 · 共 ${sheet.questions.size} 句"
                    14 -> "听写 · 共 ${sheet.questions.size} 个 · 上传作答图片"
                    6 -> "词汇训练 · 共 ${sheet.questions.size} 题"
                    else -> "共 ${sheet.questions.size} 题"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            if (feedbackType == 14) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "听写作答照片",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                if (photoFile == null) {
                    OutlinedButton(onClick = onTakePhoto) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("拍照")
                    }
                } else {
                    AsyncImage(
                        model = Uri.fromFile(photoFile),
                        contentDescription = "打卡图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        OutlinedButton(onClick = onTakePhoto) {
                            Text("重新拍照")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onClearPhoto) {
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnswerQuestionCard(
    question: AnswerQuestion,
    state: QuestionUiState,
    feedbackType: Int,
    requiredReadCount: Int,
    revealHiddenText: Boolean,
    enabled: Boolean,
    onSelectOption: (String) -> Unit,
    onClearAnswer: () -> Unit,
    onRecord: () -> Unit,
    onPlayMedia: (SubmissionMedia) -> Unit,
    onRemoveAttempt: (AudioAttemptUi) -> Unit
) {
    val isDictation = feedbackType == 14
    val isRecitation = feedbackType == 13 && question.type == 11
    val hideAnswerContent = !revealHiddenText && (isDictation || isRecitation)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "第 ${question.index} 题",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = questionTypeLabel(question.type, feedbackType),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (hideAnswerContent) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isDictation) {
                            "听写内容已隐藏，请播放音频后在纸上作答并拍照。"
                        } else {
                            "背诵内容已隐藏，请尝试背诵后录音。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else if (question.stemText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = question.stemText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!hideAnswerContent) {
                question.stemPictures.forEach { picture ->
                    Spacer(Modifier.height(8.dp))
                    AsyncImage(
                        model = picture,
                        contentDescription = "题目图片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
            }

            if (question.stemAudio.isNotBlank() && (!hideAnswerContent || isDictation)) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    onPlayMedia(
                        SubmissionMedia(
                            path = question.stemAudio,
                            url = question.stemAudio,
                            kind = SubmissionMediaKind.AUDIO,
                            name = if (isDictation) "听写音频" else "示范音频"
                        )
                    )
                }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isDictation) "播放听写内容" else "播放示范")
                }
            }

            if (question.explanation.isNotBlank() && !hideAnswerContent) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = question.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (revealHiddenText && question.answer.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "调试答案：${question.answer}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(12.dp))
            when (question.type) {
                1, 4 -> {
                    if (question.type == 4) {
                        Text(
                            text = "可多选，已选 ${state.selectedIds.size} 项",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    question.options.forEach { option ->
                        AnswerOptionCard(
                            option = option,
                            selected = option.id in state.selectedIds,
                            enabled = enabled,
                            onClick = { onSelectOption(option.id) }
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }

                15 -> {
                    Text(
                        text = "按顺序选择拼写片段：${state.selectedIds.size}/${question.blanks.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    val selectedValues = state.selectedIds.mapNotNull { id ->
                        question.options.firstOrNull { it.id == id }?.value
                    }
                    Text(
                        text = selectedValues.joinToString(""),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        question.options.forEach { option ->
                            OutlinedButton(
                                onClick = { onSelectOption(option.id) },
                                enabled = enabled &&
                                    state.selectedIds.size < question.blanks.size
                            ) {
                                Text(option.value)
                            }
                        }
                    }
                    if (state.selectedIds.isNotEmpty()) {
                        TextButton(onClick = onClearAnswer, enabled = enabled) {
                            Text("清空")
                        }
                    }
                }

                6, 11 -> {
                    val bestPath = state.bestAttempt
                        ?.recording?.file?.absolutePath
                    Text(
                        text = if (isRecitation) {
                            "老师要求背诵 $requiredReadCount 遍 · 最终成绩取最高分"
                        } else {
                            "老师要求朗读 $requiredReadCount 遍 · " +
                                "最终成绩取最高分"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    if (requiredReadCount < question.needReadCount) {
                        Text(
                            text = "开发调试：已从 ${question.needReadCount} 遍降为 1 遍",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            (
                                state.scoredAttempts.size.toFloat() /
                                    requiredReadCount
                                ).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "已评测 ${state.scoredAttempts.size}/" +
                            "$requiredReadCount 遍",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.attempts.forEachIndexed { attemptIndex, attempt ->
                        Spacer(Modifier.height(10.dp))
                        AudioAttemptCard(
                            attempt = attempt,
                            attemptNumber = attemptIndex + 1,
                            isBest = attempt.recording.file.absolutePath == bestPath,
                            enabled = enabled,
                            onPlay = {
                                onPlayMedia(
                                    SubmissionMedia(
                                        path = attempt.recording.file.absolutePath,
                                        url = Uri.fromFile(
                                            attempt.recording.file
                                        ).toString(),
                                        kind = SubmissionMediaKind.AUDIO,
                                        name = "第 ${attemptIndex + 1} 遍录音"
                                    )
                                )
                            },
                            onDelete = { onRemoveAttempt(attempt) }
                        )
                    }
                    state.bestAttempt?.score?.takeIf { !it.passed }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "最高分 ${formatScore(it.overall)}，低于 60 分，" +
                                "请继续重读",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onRecord,
                        enabled = enabled && state.attempts.none { it.evaluating },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.attempts.isEmpty()) {
                                "开始录音"
                            } else if (isRecitation) {
                                "再背一遍（第 ${state.attempts.size + 1} 遍）"
                            } else {
                                "再读一遍（第 ${state.attempts.size + 1} 遍）"
                            }
                        )
                    }
                    if (state.scoredAttempts.size > requiredReadCount) {
                        Text(
                            text = "已完成要求，可继续刷新最高分",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                12 -> {
                    Text(
                        text = if (isDictation) {
                            "请先播放音频，在纸上完成听写后，使用上方“听写作答照片”拍照上传。"
                        } else {
                            "认读内容，无需额外作答"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDictation) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                else -> {
                    Text(
                        text = "暂不支持该题型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioAttemptCard(
    attempt: AudioAttemptUi,
    attemptNumber: Int,
    isBest: Boolean,
    enabled: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = if (isBest) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "第 $attemptNumber 遍",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatRecordingDuration(attempt.recording.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isBest) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "最高分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.weight(1f))
                attempt.score?.let {
                    Text(
                        text = formatScore(it.overall),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (it.passed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Text(
                        text = "分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when {
                attempt.evaluating -> {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "AI 正在评分，请稍候…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                attempt.error != null -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "评测失败：${attempt.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                attempt.score != null -> {
                    val score = attempt.score
                    Spacer(Modifier.height(10.dp))
                    SoeMetricRow("准确度", score.accuracy)
                    score.fluency?.let {
                        Spacer(Modifier.height(6.dp))
                        SoeMetricRow("流利度", it)
                    }
                    score.integrity?.let {
                        Spacer(Modifier.height(6.dp))
                        SoeMetricRow("完整度", it)
                    }
                    val weakWords = score.words
                        .filter { it.score < 80.0 }
                        .sortedBy { it.score }
                        .take(6)
                    if (weakWords.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "建议重读：" + weakWords.joinToString(" · ") {
                                "${it.word} ${it.score.roundToInt()}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onPlay, enabled = enabled) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("播放")
                }
                TextButton(onClick = onDelete, enabled = enabled) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("删除")
                }
            }
        }
    }
}

@Composable
private fun SoeMetricRow(label: String, value: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp)
        )
        LinearProgressIndicator(
            progress = { (value / 100.0).toFloat().coerceIn(0f, 1f) },
            color = if (value >= 60.0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatScore(value),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun AnswerOptionCard(
    option: com.daijunhao.classroommanager.data.AnswerOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (option.type == "img") {
                AsyncImage(
                    model = option.value,
                    contentDescription = "选项图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else {
                Text(
                    text = option.value,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

private fun questionTypeLabel(type: Int, feedbackType: Int): String = when {
    feedbackType == 14 -> "听写"
    feedbackType == 13 && type == 11 -> "背诵"
    feedbackType in listOf(8, 9) && type in listOf(6, 11) -> "跟读"
    type == 1 -> "选择题"
    type == 4 -> "多选题"
    type in listOf(6, 11) -> "跟读题"
    type == 12 -> "认读"
    type == 15 -> "拼写题"
    else -> "题目"
}

private fun formatRecordingDuration(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}

private fun formatScore(value: Double): String =
    String.format(Locale.US, "%.1f", value)
