package com.daijunhao.classroommanager.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.daijunhao.classroommanager.data.LocalAudioRecording
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor

enum class CameraCaptureMode {
    PHOTO,
    VIDEO
}

@Composable
fun AudioRecorderDialog(
    promptText: String = "",
    attemptNumber: Int? = null,
    onDismiss: () -> Unit,
    onRecorded: (LocalAudioRecording) -> Unit
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (!granted) {
            Toast.makeText(context, "需要麦克风权限才能录音", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var outputFile by remember { mutableStateOf<File?>(null) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }

    fun stopAndRelease(delete: Boolean) {
        val active = recorder
        recorder = null
        if (active != null) {
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        if (delete) {
            outputFile?.delete()
        }
        outputFile = null
    }

    DisposableEffect(Unit) {
        onDispose {
            stopAndRelease(delete = true)
        }
    }

    LaunchedEffect(recorder) {
        while (recorder != null) {
            elapsedMs = SystemClock.elapsedRealtime() - recordingStartedAt
            delay(100)
        }
    }

    fun startRecording() {
        error = null
        try {
            val file = File(
                context.cacheDir,
                "recording_${System.currentTimeMillis()}.m4a"
            )
            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(16_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            outputFile = file
            recordingStartedAt = SystemClock.elapsedRealtime()
            elapsedMs = 0
            recorder = newRecorder
        } catch (e: Exception) {
            error = e.message ?: "无法开始录音"
            stopAndRelease(delete = true)
        }
    }

    fun finishRecording() {
        val file = outputFile
        val duration = (SystemClock.elapsedRealtime() - recordingStartedAt)
            .coerceAtLeast(1)
        val active = recorder
        recorder = null
        try {
            active?.stop()
            active?.release()
            if (file != null && file.exists() && file.length() > 0) {
                onRecorded(LocalAudioRecording(file, duration))
            } else {
                error = "录音文件为空，请重试"
            }
        } catch (e: Exception) {
            error = e.message ?: "结束录音失败"
            file?.delete()
        } finally {
            outputFile = null
        }
    }

    Dialog(
        onDismissRequest = {
            stopAndRelease(delete = true)
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (attemptNumber == null) {
                            "录制音频"
                        } else {
                            "录制音频 · 第 $attemptNumber 遍"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    if (promptText.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                        ) {
                            Text(
                                text = promptText,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(14.dp)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        text = if (recorder == null) "点击开始录音" else "正在录音，完成后点击停止",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = formatClock(elapsedMs),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(24.dp))

                    if (!permissionGranted) {
                        Button(onClick = {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }) {
                            Text("授予麦克风权限")
                        }
                    } else if (recorder == null) {
                        Button(onClick = ::startRecording) {
                            Icon(Icons.Default.FiberManualRecord, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("开始录音")
                        }
                    } else {
                        Button(
                            onClick = ::finishRecording,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("停止并保存")
                        }
                    }

                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(onClick = {
                        stopAndRelease(delete = true)
                        onDismiss()
                    }) {
                        Text("取消")
                    }
                }
            }
        }
    }
}

@Composable
fun CameraCaptureDialog(
    mode: CameraCaptureMode,
    onDismiss: () -> Unit,
    onCaptured: (File) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val requiredPermissions = remember(mode) {
        if (mode == CameraCaptureMode.VIDEO) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }
    }
    var permissionsGranted by remember(mode) {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) ==
                    PackageManager.PERMISSION_GRANTED
            }
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
        if (!permissionsGranted) {
            Toast.makeText(context, "需要相机/麦克风权限", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) {
        if (!permissionsGranted) permissionLauncher.launch(requiredPermissions)
    }

    var lensFacing by remember(mode) {
        mutableIntStateOf(
            if (mode == CameraCaptureMode.VIDEO) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
        )
    }
    var cameraReady by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var pendingVideoFile by remember { mutableStateOf<File?>(null) }

    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
        )
    }

    DisposableEffect(permissionsGranted, lensFacing, mode) {
        if (!permissionsGranted) {
            return@DisposableEffect onDispose {}
        }
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()
                provider.unbindAll()
                if (mode == CameraCaptureMode.PHOTO) {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        imageCapture
                    )
                } else {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        videoCapture
                    )
                }
                cameraReady = true
                error = null
            } catch (e: Exception) {
                error = e.message ?: "相机启动失败"
                cameraReady = false
            }
        }
        future.addListener(listener, mainExecutor)

        onDispose {
            activeRecording?.stop()
            activeRecording = null
            isRecording = false
            runCatching { future.get().unbindAll() }
        }
    }

    fun takePhoto() {
        val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            options,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onCaptured(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    error = exception.message ?: "拍照失败"
                    file.delete()
                }
            }
        )
    }

    fun toggleVideo() {
        if (isRecording) {
            activeRecording?.stop()
            activeRecording = null
            isRecording = false
            return
        }
        val file = File(context.cacheDir, "video_${System.currentTimeMillis()}.mp4")
        pendingVideoFile = file
        val options = FileOutputOptions.Builder(file).build()
        val pending = videoCapture.output.prepareRecording(context, options)
            .apply {
                if (permissionsGranted) withAudioEnabled()
            }
        activeRecording = pending.start(mainExecutor) { event ->
            if (event is VideoRecordEvent.Finalize) {
                isRecording = false
                activeRecording = null
                if (event.hasError()) {
                    error = "录像失败（${event.error}）"
                    file.delete()
                } else {
                    onCaptured(file)
                }
                pendingVideoFile = null
            }
        }
        isRecording = true
    }

    Dialog(
        onDismissRequest = {
            activeRecording?.stop()
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (permissionsGranted) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("需要相机权限", color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        permissionLauncher.launch(requiredPermissions)
                    }) {
                        Text("授权")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RoundOverlayButton(onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                }) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription = "切换摄像头",
                        tint = Color.White
                    )
                }
                RoundOverlayButton(onClick = {
                    activeRecording?.stop()
                    onDismiss()
                }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Color.White
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                error?.let {
                    Text(
                        text = it,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (!cameraReady) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = when {
                        mode == CameraCaptureMode.PHOTO -> "拍照"
                        isRecording -> "正在录像，点击停止"
                        else -> "录像"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (mode == CameraCaptureMode.PHOTO) takePhoto() else toggleVideo()
                    },
                    enabled = cameraReady && error == null,
                    shape = CircleShape,
                    modifier = Modifier.size(76.dp)
                ) {
                    Icon(
                        imageVector = if (isRecording) {
                            Icons.Default.Stop
                        } else {
                            Icons.Default.FiberManualRecord
                        },
                        contentDescription = null
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundOverlayButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        shape = CircleShape
    ) {
        IconButton(onClick = onClick) {
            content()
        }
    }
}

private fun formatClock(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
