package com.daijunhao.classroommanager.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.daijunhao.classroommanager.data.CheckInApi
import com.daijunhao.classroommanager.data.CheckInDetail
import com.daijunhao.classroommanager.data.CheckInFile
import com.daijunhao.classroommanager.data.CheckInItem
import com.daijunhao.classroommanager.data.CheckInSubmitType
import com.daijunhao.classroommanager.data.CheckInUploadType
import com.daijunhao.classroommanager.data.LocalMedia
import com.daijunhao.classroommanager.data.SubmissionMedia
import com.daijunhao.classroommanager.data.SubmissionMediaKind
import com.daijunhao.classroommanager.data.TaskSetApi
import com.daijunhao.classroommanager.data.TokenStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID

private data class PendingFile(
    val localId: String,
    val media: LocalMedia,
    val progress: Float? = null,
    val error: String? = null
)

private data class ItemEditorState(
    val savedFiles: List<CheckInFile> = emptyList(),
    val pendingFiles: List<PendingFile> = emptyList()
)

private data class PendingUploadEntry(
    val itemId: String,
    val pending: PendingFile
)

private data class UploadFailure(
    val fileName: String,
    val message: String
)

private data class MediaPickerRequest(
    val itemId: String,
    val uploadType: CheckInUploadType
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInDetailScreen(
    notifyId: String,
    cid: String,
    targetMemberId: String? = null,
    targetMemberName: String = "",
    onBack: () -> Unit,
    onOpenClassAnalysis: () -> Unit = {},
    onCheckInSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localMemberId = remember { TokenStore.readMemberId(context).orEmpty() }
    val viewMemberId = targetMemberId?.takeIf { it.isNotBlank() }
        ?: localMemberId
    val isReadOnly = targetMemberId?.isNotBlank() == true
    val itemStates = remember(notifyId) { mutableStateMapOf<String, ItemEditorState>() }

    var detail by remember(notifyId) { mutableStateOf<CheckInDetail?>(null) }
    var feedbackText by remember(notifyId) { mutableStateOf("") }
    var loading by remember(notifyId) { mutableStateOf(true) }
    var loadError by remember(notifyId) { mutableStateOf<String?>(null) }
    var reloadVersion by remember(notifyId) { mutableIntStateOf(0) }
    var pickerRequest by remember(notifyId) { mutableStateOf<MediaPickerRequest?>(null) }
    var isBusy by remember(notifyId) { mutableStateOf(false) }
    var busyText by remember(notifyId) { mutableStateOf("") }
    var selectedMedia by remember(notifyId) { mutableStateOf<SubmissionMedia?>(null) }
    var cameraTargetItemId by remember(notifyId) { mutableStateOf<String?>(null) }
    var cameraMode by remember(notifyId) {
        mutableStateOf(CameraCaptureMode.VIDEO)
    }

    fun editorOf(item: CheckInItem): ItemEditorState {
        return itemStates[item.id] ?: ItemEditorState(savedFiles = item.savedFiles)
    }

    fun updatePendingFile(
        itemId: String,
        localId: String,
        transform: (PendingFile) -> PendingFile?
    ) {
        val current = itemStates[itemId] ?: return
        itemStates[itemId] = current.copy(
            pendingFiles = current.pendingFiles.mapNotNull { pending ->
                if (pending.localId == localId) transform(pending) else pending
            }
        )
    }

    fun addPickedMedia(
        itemId: String,
        uploadType: CheckInUploadType,
        uris: List<Uri>
    ) {
        if (uris.isEmpty() || isBusy) return
        val item = detail?.items?.firstOrNull { it.id == itemId } ?: return
        val current = editorOf(item)
        val currentCount = current.savedFiles.size + current.pendingFiles.size
        val remaining = if (item.limitMaxSelect > 0) {
            (item.limitMaxSelect - currentCount).coerceAtLeast(0)
        } else {
            uris.size
        }
        val accepted = uris.take(remaining)
        if (accepted.isEmpty()) {
            Toast.makeText(context, "已达到该题目的可选数量上限", Toast.LENGTH_SHORT).show()
            return
        }
        if (accepted.size < uris.size) {
            Toast.makeText(
                context,
                "最多还能再选择 ${accepted.size} 个文件",
                Toast.LENGTH_SHORT
            ).show()
        }

        accepted.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }

        isBusy = true
        busyText = "正在读取所选文件..."
        scope.launch {
            try {
                val results = coroutineScope {
                    accepted.map { uri ->
                        async { CheckInApi.readLocalMedia(context, uri, uploadType) }
                    }.awaitAll()
                }
                val pendingFiles = results.mapNotNull { result ->
                    result.getOrNull()?.let { media ->
                        PendingFile(
                            localId = UUID.randomUUID().toString(),
                            media = media
                        )
                    }
                }
                val failures = results.mapNotNull { it.exceptionOrNull() }

                if (pendingFiles.isNotEmpty()) {
                    val latest = editorOf(item)
                    itemStates[item.id] = latest.copy(
                        pendingFiles = latest.pendingFiles + pendingFiles
                    )
                }
                if (failures.isNotEmpty()) {
                    Toast.makeText(
                        context,
                        "读取文件失败：${failures.first().message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                isBusy = false
                busyText = ""
            }
        }
    }

    fun addCapturedFile(
        itemId: String,
        uploadType: CheckInUploadType,
        file: File
    ) {
        if (isBusy) return
        val item = detail?.items?.firstOrNull { it.id == itemId } ?: return
        val current = editorOf(item)
        if (item.limitMaxSelect > 0 &&
            current.savedFiles.size + current.pendingFiles.size >= item.limitMaxSelect
        ) {
            Toast.makeText(context, "已达到该题目的数量上限", Toast.LENGTH_SHORT).show()
            file.delete()
            return
        }
        isBusy = true
        busyText = "正在读取拍摄文件..."
        scope.launch {
            try {
                CheckInApi.readLocalMedia(context, Uri.fromFile(file), uploadType)
                    .onSuccess { media ->
                        val latest = editorOf(item)
                        itemStates[item.id] = latest.copy(
                            pendingFiles = latest.pendingFiles + PendingFile(
                                localId = UUID.randomUUID().toString(),
                                media = media
                            )
                        )
                    }
                    .onFailure {
                        file.delete()
                        Toast.makeText(
                            context,
                            "读取拍摄文件失败：${it.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            } finally {
                isBusy = false
                busyText = ""
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val request = pickerRequest
        pickerRequest = null
        if (request != null) {
            addPickedMedia(request.itemId, CheckInUploadType.VIDEO, uris)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val request = pickerRequest
        pickerRequest = null
        if (request != null) {
            addPickedMedia(request.itemId, CheckInUploadType.IMAGE, uris)
        }
    }

    LaunchedEffect(notifyId, cid, viewMemberId, reloadVersion) {
        val token = TokenStore.readToken(context)
        if (token.isNullOrBlank() || viewMemberId.isBlank()) {
            loadError = "登录凭证失效，请重新登录"
            loading = false
            return@LaunchedEffect
        }

        if (detail == null) loading = true
        CheckInApi.getCheckInDetail(
            token = token,
            memberId = viewMemberId,
            notifyId = notifyId,
            cid = cid
        ).onSuccess { loaded ->
            detail = loaded
            feedbackText = loaded.feedbackText
            itemStates.clear()
            loaded.items.forEach { item ->
                itemStates[item.id] = ItemEditorState(savedFiles = item.savedFiles)
            }
            loadError = null
        }.onFailure { error ->
            if (detail == null) {
                loadError = error.message ?: "加载打卡详情失败"
            } else {
                Toast.makeText(
                    context,
                    "刷新打卡详情失败：${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        loading = false
    }

    fun persist(submit: Boolean) {
        val currentDetail = detail ?: return
        if (isBusy) return

        val token = TokenStore.readToken(context)
        if (token.isNullOrBlank() || viewMemberId.isBlank()) {
            Toast.makeText(context, "登录凭证失效，请重新登录", Toast.LENGTH_SHORT).show()
            return
        }

        if (submit) {
            val missingItems = currentDetail.items.filter { item ->
                val editor = editorOf(item)
                item.required && editor.savedFiles.isEmpty() && editor.pendingFiles.isEmpty()
            }
            if (missingItems.isNotEmpty()) {
                val names = missingItems.take(3).joinToString("、") { it.title }
                Toast.makeText(context, "请先完成必填项：$names", Toast.LENGTH_LONG).show()
                return
            }
        }

        val pendingUploads = currentDetail.items.flatMap { item ->
            editorOf(item).pendingFiles.map { pending ->
                PendingUploadEntry(item.id, pending)
            }
        }

        isBusy = true
        busyText = if (submit) "正在准备提交..." else "正在准备保存..."
        scope.launch {
            var closeAfterSuccess = false
            var reloadAfterSuccess = false

            try {
                if (pendingUploads.isNotEmpty()) {
                    busyText = "正在并行上传 ${pendingUploads.size} 个文件..."
                    pendingUploads.forEach { entry ->
                        updatePendingFile(entry.itemId, entry.pending.localId) {
                            it.copy(progress = 0f, error = null)
                        }
                    }

                    val uploadTypes = pendingUploads
                        .map { it.pending.media.uploadType }
                        .distinct()
                    val credentialResults = coroutineScope {
                        uploadTypes.map { uploadType ->
                            async {
                                uploadType to CheckInApi.getUploadCredentials(token, uploadType)
                            }
                        }.awaitAll()
                    }
                    val credentialsByType = credentialResults.mapNotNull { (type, result) ->
                        result.getOrNull()?.let { type to it }
                    }.toMap()
                    val credentialErrors = credentialResults.mapNotNull { (type, result) ->
                        result.exceptionOrNull()?.let { type to (it.message ?: "获取上传凭据失败") }
                    }.toMap()

                    val failures = coroutineScope {
                        pendingUploads.map { entry ->
                            async {
                                val uploadType = entry.pending.media.uploadType
                                val credentials = credentialsByType[uploadType]
                                val result = if (credentials == null) {
                                    Result.failure<CheckInFile>(
                                        Exception(
                                            credentialErrors[uploadType]
                                                ?: "获取上传凭据失败"
                                        )
                                    )
                                } else {
                                    CheckInApi.uploadMedia(
                                        context = context,
                                        credentials = credentials,
                                        memberId = viewMemberId,
                                        media = entry.pending.media,
                                        onProgress = { progress ->
                                            scope.launch {
                                                updatePendingFile(
                                                    entry.itemId,
                                                    entry.pending.localId
                                                ) {
                                                    it.copy(progress = progress)
                                                }
                                            }
                                        }
                                    )
                                }

                                if (result.isSuccess) {
                                    val uploaded = result.getOrThrow()
                                    val current = itemStates[entry.itemId]
                                        ?: ItemEditorState()
                                    itemStates[entry.itemId] = current.copy(
                                        savedFiles = (current.savedFiles + uploaded)
                                            .distinctBy { it.objectKey },
                                        pendingFiles = current.pendingFiles.filterNot {
                                            it.localId == entry.pending.localId
                                        }
                                    )
                                    null
                                } else {
                                    val message = result.exceptionOrNull()?.message
                                        ?: "上传失败"
                                    updatePendingFile(
                                        entry.itemId,
                                        entry.pending.localId
                                    ) {
                                        it.copy(progress = null, error = message)
                                    }
                                    UploadFailure(entry.pending.media.name, message)
                                }
                            }
                        }.awaitAll()
                    }.filterNotNull()

                    if (failures.isNotEmpty()) {
                        val first = failures.first()
                        Toast.makeText(
                            context,
                            "${failures.size} 个文件上传失败：${first.fileName} ${first.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                }

                busyText = if (submit) "正在提交打卡..." else "正在保存草稿..."
                val filesByItem = currentDetail.items.associate { item ->
                    item.id to editorOf(item).savedFiles
                }
                val submitResult = CheckInApi.submitFeedback(
                    token = token,
                    memberId = viewMemberId,
                    detail = currentDetail,
                    filesByItem = filesByItem,
                    feedbackText = feedbackText,
                    submitType = if (submit) {
                        CheckInSubmitType.SUBMIT
                    } else {
                        CheckInSubmitType.DRAFT
                    }
                )

                if (submitResult.isFailure) {
                    Toast.makeText(
                        context,
                        "${if (submit) "提交" else "保存"}失败：" +
                            submitResult.exceptionOrNull()?.message,
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                onCheckInSaved()
                Toast.makeText(
                    context,
                    if (submit) "打卡已提交" else "草稿已保存",
                    Toast.LENGTH_SHORT
                ).show()
                closeAfterSuccess = submit
                reloadAfterSuccess = !submit
            } finally {
                isBusy = false
                busyText = ""
            }

            if (closeAfterSuccess) {
                onBack()
            } else if (reloadAfterSuccess) {
                reloadVersion++
            }
        }
    }

    fun openMedia(media: SubmissionMedia) {
        selectedMedia = media
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("打卡详情") },
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
            if (detail != null && !isReadOnly) {
                Surface(shadowElevation = 8.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        if (isBusy) {
                            Text(
                                text = busyText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { persist(submit = false) },
                                enabled = !isBusy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("保存草稿")
                            }
                            Button(
                                onClick = { persist(submit = true) },
                                enabled = !isBusy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (detail?.isFeedback == true) "更新提交" else "提交打卡")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        when {
            loading && detail == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            loadError != null && detail == null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "加载失败：$loadError",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        loadError = null
                        reloadVersion++
                    }) {
                        Text("重试")
                    }
                }
            }

            detail != null -> {
                val currentDetail = detail!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        CheckInHeaderCard(
                            detail = currentDetail,
                            onOpenMedia = ::openMedia
                        )
                    }

                    if (isReadOnly) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "正在查看 ${targetMemberName.ifBlank { "该学生" }} 的打卡内容（只读）。",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    } else {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "全班完成分析",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "查看全班提交、错误/需订正和未提交名单。",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    OutlinedButton(onClick = onOpenClassAnalysis) {
                                        Text("查看全班情况")
                                    }
                                }
                            }
                        }
                    }

                    items(currentDetail.items, key = { it.id }) { item ->
                        val editor = editorOf(item)
                        CheckInItemCard(
                            item = item,
                            editor = editor,
                            enabled = !isBusy,
                            readOnly = isReadOnly,
                            onPickVideo = {
                                pickerRequest = MediaPickerRequest(
                                    item.id,
                                    CheckInUploadType.VIDEO
                                )
                                videoPicker.launch(arrayOf("video/*"))
                            },
                            onPickImage = {
                                pickerRequest = MediaPickerRequest(
                                    item.id,
                                    CheckInUploadType.IMAGE
                                )
                                imagePicker.launch(arrayOf("image/*"))
                            },
                            onRecordVideo = {
                                cameraTargetItemId = item.id
                                cameraMode = CameraCaptureMode.VIDEO
                            },
                            onRecordPhoto = {
                                cameraTargetItemId = item.id
                                cameraMode = CameraCaptureMode.PHOTO
                            },
                            onRemoveSavedFile = { file ->
                                val current = itemStates[item.id] ?: editor
                                itemStates[item.id] = current.copy(
                                    savedFiles = current.savedFiles.filterNot {
                                        it.objectKey == file.objectKey
                                    }
                                )
                            },
                            onRemovePendingFile = { pending ->
                                val current = itemStates[item.id] ?: editor
                                itemStates[item.id] = current.copy(
                                    pendingFiles = current.pendingFiles.filterNot {
                                        it.localId == pending.localId
                                    }
                                )
                            },
                            onOpenMedia = ::openMedia
                        )
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "补充说明",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = feedbackText,
                                    onValueChange = { feedbackText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("选填，可补充打卡说明") },
                                    minLines = 3,
                                    maxLines = 6,
                                    enabled = !isBusy,
                                    readOnly = isReadOnly
                                )
                            }
                        }
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

    cameraTargetItemId?.let { itemId ->
        CameraCaptureDialog(
            mode = cameraMode,
            onDismiss = { cameraTargetItemId = null },
            onCaptured = { file ->
                cameraTargetItemId = null
                addCapturedFile(
                    itemId = itemId,
                    uploadType = if (cameraMode == CameraCaptureMode.VIDEO) {
                        CheckInUploadType.VIDEO
                    } else {
                        CheckInUploadType.IMAGE
                    },
                    file = file
                )
            }
        )
    }
}

@Composable
private fun CheckInHeaderCard(
    detail: CheckInDetail,
    onOpenMedia: (SubmissionMedia) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (detail.subject.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = detail.subject,
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Surface(
                    color = when {
                        detail.isFeedback -> MaterialTheme.colorScheme.primary
                        detail.hasDraft -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.secondary
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = when {
                            detail.isFeedback -> "已提交"
                            detail.hasDraft -> "草稿已保存"
                            else -> "待完成"
                        },
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = detail.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (detail.textContent.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = detail.textContent,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            if (detail.endDay.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "截止时间：${detail.endDay}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            detail.submittedAt?.let { submittedAt ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "提交时间：$submittedAt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            detail.media
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
private fun CheckInItemCard(
    item: CheckInItem,
    editor: ItemEditorState,
    enabled: Boolean,
    readOnly: Boolean,
    onPickVideo: () -> Unit,
    onPickImage: () -> Unit,
    onRecordVideo: () -> Unit,
    onRecordPhoto: () -> Unit,
    onRemoveSavedFile: (CheckInFile) -> Unit,
    onRemovePendingFile: (PendingFile) -> Unit,
    onOpenMedia: (SubmissionMedia) -> Unit
) {
    val uploadType = uploadTypeForCate(item.cate)
    val selectedCount = editor.savedFiles.size + editor.pendingFiles.size
    val reachedLimit = item.limitMaxSelect > 0 && selectedCount >= item.limitMaxSelect

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (item.required) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "必填",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = when (uploadType) {
                    CheckInUploadType.VIDEO -> "视频题"
                    CheckInUploadType.IMAGE -> "图片题"
                    null -> "任务项"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!item.content.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(item.content, style = MaterialTheme.typography.bodyMedium)
            }

            editor.savedFiles.forEach { file ->
                Spacer(Modifier.height(10.dp))
                SavedFileRow(
                    file = file,
                    enabled = enabled && !readOnly,
                    onDelete = { onRemoveSavedFile(file) },
                    onOpen = onOpenMedia
                )
            }

            editor.pendingFiles.forEach { pending ->
                Spacer(Modifier.height(10.dp))
                PendingFileRow(
                    pending = pending,
                    enabled = enabled && !readOnly,
                    onDelete = { onRemovePendingFile(pending) }
                )
            }

            if (!readOnly) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(10.dp))

                when (uploadType) {
                CheckInUploadType.VIDEO -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRecordVideo,
                            enabled = enabled && !reachedLimit,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("录制视频")
                        }
                        OutlinedButton(
                            onClick = onPickVideo,
                            enabled = enabled && !reachedLimit,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                when {
                                    reachedLimit -> "已达到上限"
                                    editor.savedFiles.isNotEmpty() ||
                                        editor.pendingFiles.isNotEmpty() -> "从文件添加"
                                    else -> "选择视频"
                                }
                            )
                        }
                    }
                }

                CheckInUploadType.IMAGE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRecordPhoto,
                            enabled = enabled && !reachedLimit,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("拍照")
                        }
                        OutlinedButton(
                            onClick = onPickImage,
                            enabled = enabled && !reachedLimit,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                when {
                                    reachedLimit -> "已达到上限"
                                    editor.savedFiles.isNotEmpty() ||
                                        editor.pendingFiles.isNotEmpty() -> "从文件添加"
                                    else -> "选择图片"
                                }
                            )
                        }
                    }
                }

                null -> {
                    Text(
                        text = "当前版本暂不支持编辑该题型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun SavedFileRow(
    file: CheckInFile,
    enabled: Boolean,
    onDelete: () -> Unit,
    onOpen: (SubmissionMedia) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val media = TaskSetApi.resolveSubmissionMedia(file.objectKey)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("已保存 · ")
                            append(fileLabel(file.category))
                            if (file.durationSeconds > 0) append(" · ${file.durationSeconds}s")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (media != null && media.kind != SubmissionMediaKind.IMAGE) {
                    TextButton(onClick = { onOpen(media) }) {
                        Text(
                            if (
                                media.kind == SubmissionMediaKind.AUDIO ||
                                media.kind == SubmissionMediaKind.VIDEO
                            ) {
                                "播放"
                            } else {
                                "查看"
                            }
                        )
                    }
                }
                if (media != null && canOpenInBrowser(media.url)) {
                    TextButton(onClick = {
                        openInBrowser(context, media.url)
                    }) {
                        Text("浏览器")
                    }
                }
                TextButton(onClick = onDelete, enabled = enabled) {
                    Text("删除")
                }
            }

            if (media?.kind == SubmissionMediaKind.IMAGE) {
                AsyncImage(
                    model = media.url,
                    contentDescription = "已提交图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .padding(end = 12.dp, top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpen(media) }
                )
            }
        }
    }
}

@Composable
private fun PendingFileRow(
    pending: PendingFile,
    enabled: Boolean,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pending.media.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "待上传 · ${formatBytes(pending.media.size)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                TextButton(onClick = onDelete, enabled = enabled) {
                    Text("删除")
                }
            }

            pending.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 12.dp, top = 6.dp)
                )
            }

            pending.error?.let { error ->
                Text(
                    text = "上传失败：$error",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp, end = 12.dp)
                )
            }
        }
    }
}

private fun uploadTypeForCate(cate: Int): CheckInUploadType? = when (cate) {
    7 -> CheckInUploadType.VIDEO
    5 -> CheckInUploadType.IMAGE
    else -> null
}

private fun fileLabel(category: String): String = when (category) {
    "video" -> "视频"
    "img" -> "图片"
    else -> "文件"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "大小未知"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
