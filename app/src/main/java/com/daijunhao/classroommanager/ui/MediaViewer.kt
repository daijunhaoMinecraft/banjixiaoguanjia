package com.daijunhao.classroommanager.ui

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.daijunhao.classroommanager.data.SubmissionMedia
import com.daijunhao.classroommanager.data.SubmissionMediaKind
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun MediaViewer(
    media: SubmissionMedia?,
    onDismiss: () -> Unit
) {
    if (media == null) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        when (media.kind) {
            SubmissionMediaKind.IMAGE -> ImageViewer(media, onDismiss)
            SubmissionMediaKind.VIDEO -> VideoViewer(media, onDismiss)
            SubmissionMediaKind.AUDIO -> AudioViewer(media, onDismiss)
            SubmissionMediaKind.FILE -> UnsupportedViewer(media, onDismiss)
        }
    }
}

@Composable
private fun ImageViewer(
    media: SubmissionMedia,
    onDismiss: () -> Unit
) {
    var scale by remember(media.url) { mutableFloatStateOf(1f) }
    var offset by remember(media.url) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AsyncImage(
            model = media.url,
            contentDescription = "图片预览",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
                .pointerInput(media.url) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                }
        )
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd)
        )
        BrowserOpenButton(
            media = media,
            modifier = Modifier.align(Alignment.TopStart)
        )
        Text(
            text = "双指缩放 · ${media.name}",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
        )
    }
}

@Composable
private fun VideoViewer(
    media: SubmissionMedia,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val videoView = remember(media.url) { VideoView(context) }

    DisposableEffect(media.url) {
        val controller = MediaController(context).apply {
            setAnchorView(videoView)
        }
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener { player ->
            player.isLooping = false
            videoView.start()
        }
        videoView.setOnErrorListener { _, what, extra ->
            Toast.makeText(
                context,
                "视频播放失败（$what/$extra）",
                Toast.LENGTH_SHORT
            ).show()
            true
        }
        videoView.setVideoURI(media.url.toUri())
        videoView.requestFocus()
        videoView.start()

        onDispose {
            videoView.stopPlayback()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { videoView },
            modifier = Modifier.fillMaxSize()
        )
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd)
        )
        BrowserOpenButton(
            media = media,
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}

@Composable
private fun AudioViewer(
    media: SubmissionMedia,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val player = remember(media.url) { MediaPlayer() }
    var prepared by remember(media.url) { mutableStateOf(false) }
    var playing by remember(media.url) { mutableStateOf(false) }
    var durationMs by remember(media.url) { mutableIntStateOf(0) }
    var positionMs by remember(media.url) { mutableIntStateOf(0) }
    var sliderPosition by remember(media.url) { mutableIntStateOf(0) }
    var seeking by remember(media.url) { mutableStateOf(false) }
    var error by remember(media.url) { mutableStateOf<String?>(null) }

    DisposableEffect(media.url) {
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(media.url)
            player.setOnPreparedListener {
                prepared = true
                durationMs = it.duration.coerceAtLeast(0)
                it.start()
                playing = true
            }
            player.setOnCompletionListener {
                playing = false
                positionMs = durationMs
                sliderPosition = durationMs
            }
            player.setOnErrorListener { _, what, extra ->
                error = "播放失败（$what/$extra）"
                playing = false
                true
            }
            player.prepareAsync()
        }.onFailure {
            error = it.message ?: "无法加载音频"
        }

        onDispose {
            runCatching { player.release() }
        }
    }

    LaunchedEffect(playing, prepared) {
        while (playing && prepared) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            if (!seeking) sliderPosition = positionMs
            delay(300)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = media.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(18.dp))

                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onDismiss) {
                        Text("关闭")
                    }
                } else if (!prepared) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("正在加载音频...", style = MaterialTheme.typography.bodySmall)
                } else {
                    Slider(
                        value = sliderPosition
                            .toFloat()
                            .coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
                        onValueChange = {
                            seeking = true
                            sliderPosition = it.toInt()
                        },
                        onValueChangeFinished = {
                            player.seekTo(sliderPosition)
                            positionMs = sliderPosition
                            seeking = false
                        },
                        valueRange = 0f..durationMs.coerceAtLeast(1).toFloat()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(sliderPosition.toLong()),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = formatDuration(durationMs.toLong()),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                        }) {
                            Text("-10s")
                        }
                        Button(onClick = {
                            if (playing) {
                                player.pause()
                                playing = false
                            } else {
                                player.start()
                                playing = true
                            }
                        }) {
                            Icon(
                                imageVector = if (playing) {
                                    Icons.Default.Pause
                                } else {
                                    Icons.Default.PlayArrow
                                },
                                contentDescription = null
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (playing) "暂停" else "播放")
                        }
                        TextButton(onClick = {
                            player.seekTo(
                                (player.currentPosition + 10_000)
                                    .coerceAtMost(durationMs)
                            )
                        }) {
                            Text("+10s")
                        }
                    }
                }
                if (canOpenInBrowser(media.url)) {
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = {
                        openInBrowser(context, media.url)
                    }) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("在浏览器中打开")
                    }
                }
            }
        }
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

@Composable
private fun UnsupportedViewer(
    media: SubmissionMedia,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("暂不支持预览该文件")
                Spacer(Modifier.height(6.dp))
                Text(media.name, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDismiss) {
                    Text("关闭")
                }
                if (canOpenInBrowser(media.url)) {
                    TextButton(onClick = {
                        openInBrowser(context, media.url)
                    }) {
                        Text("在浏览器中打开")
                    }
                }
            }
        }
    }
}

@Composable
private fun CloseButton(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = CircleShape,
        modifier = modifier.statusBarsPadding().padding(16.dp)
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭",
                tint = Color.White
            )
        }
    }
}

@Composable
fun SubmissionMediaPreview(
    media: SubmissionMedia,
    onOpen: (SubmissionMedia) -> Unit,
    modifier: Modifier = Modifier,
    imageHeight: Dp = 180.dp
) {
    val context = LocalContext.current
    when (media.kind) {
        SubmissionMediaKind.IMAGE -> {
            Column(modifier = modifier) {
                AsyncImage(
                    model = media.url,
                    contentDescription = "图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(imageHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpen(media) }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = media.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (canOpenInBrowser(media.url)) {
                        TextButton(onClick = {
                            openInBrowser(context, media.url)
                        }) {
                            Text("浏览器打开")
                        }
                    }
                }
            }
        }

        SubmissionMediaKind.VIDEO -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onOpen(media) },
                    modifier = modifier.weight(1f)
                ) {
                    Text("播放视频：${media.name}")
                }
                if (canOpenInBrowser(media.url)) {
                    TextButton(onClick = {
                        openInBrowser(context, media.url)
                    }) {
                        Text("浏览器打开")
                    }
                }
            }
        }

        SubmissionMediaKind.AUDIO -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onOpen(media) },
                    modifier = modifier.weight(1f)
                ) {
                    Text("播放录音：${media.name}")
                }
                if (canOpenInBrowser(media.url)) {
                    TextButton(onClick = {
                        openInBrowser(context, media.url)
                    }) {
                        Text("浏览器打开")
                    }
                }
            }
        }

        SubmissionMediaKind.FILE -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onOpen(media) },
                    modifier = modifier.weight(1f)
                ) {
                    Text("打开文件：${media.name}")
                }
                if (canOpenInBrowser(media.url)) {
                    TextButton(onClick = {
                        openInBrowser(context, media.url)
                    }) {
                        Text("浏览器打开")
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserOpenButton(
    media: SubmissionMedia,
    modifier: Modifier = Modifier
) {
    if (!canOpenInBrowser(media.url)) return
    val context = LocalContext.current
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = CircleShape,
        modifier = modifier.statusBarsPadding().padding(16.dp)
    ) {
        IconButton(onClick = { openInBrowser(context, media.url) }) {
            Icon(
                Icons.Default.OpenInBrowser,
                contentDescription = "在浏览器中打开",
                tint = Color.White
            )
        }
    }
}

internal fun canOpenInBrowser(url: String): Boolean {
    return url.startsWith("http://") || url.startsWith("https://")
}

internal fun openInBrowser(context: Context, url: String) {
    if (!canOpenInBrowser(url)) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        )
    }.onFailure {
        Toast.makeText(
            context,
            "没有可用的浏览器：${it.message}",
            Toast.LENGTH_SHORT
        ).show()
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
