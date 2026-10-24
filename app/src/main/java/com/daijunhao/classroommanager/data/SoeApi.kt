package com.daijunhao.classroommanager.data

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object SoeApi {
    private const val TAG = "SoeApi"
    private const val BASE_URL = "https://openspeech.bytedance.com"
    private const val ENDPOINT = "$BASE_URL/api/v1/mdd"
    private const val APP_ID = "7915409851"
    private const val ACCESS_TOKEN = "mGFVN4w4PpbWZNYl0LBF26otLpOZq_Cn"
    private const val AUTHORIZATION = "Bearer; $ACCESS_TOKEN"
    private const val TARGET_SAMPLE_RATE = 16_000
    private const val MAX_AUDIO_BYTES = 8 * 1024 * 1024

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun evaluate(
        recording: LocalAudioRecording,
        referenceText: String
    ): Result<SoeScore> = withContext(Dispatchers.IO) {
        try {
            require(referenceText.isNotBlank()) { "朗读文本为空，无法评测" }
            val language = detectLanguage(referenceText)
            val wavBytes = transcodeToWav(recording.file)
            if (wavBytes.size > MAX_AUDIO_BYTES) {
                return@withContext Result.failure(
                    Exception("录音过长，请控制在约 90 秒以内")
                )
            }
            val payload = JSONObject().apply {
                put(
                    "app",
                    JSONObject().apply {
                        put("appid", APP_ID)
                        put("token", ACCESS_TOKEN)
                        put(
                            "cluster",
                            if (language == SoeLanguage.ENGLISH) {
                                "volc_en_k12"
                            } else {
                                "volc_cn_common"
                            }
                        )
                    }
                )
                put("user", JSONObject().put("uid", "app_user"))
                put(
                    "audio",
                    JSONObject().apply {
                        put("format", "wav")
                        put("codec", "raw")
                        put("rate", TARGET_SAMPLE_RATE)
                        put(
                            "data",
                            Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                        )
                    }
                )
                put(
                    "request",
                    JSONObject().apply {
                        put("reqid", UUID.randomUUID().toString())
                        put("sequence", -1)
                        put(
                            "core_type",
                            if (language == SoeLanguage.ENGLISH) {
                                "en.sent.score"
                            } else {
                                "cn.sent.raw"
                            }
                        )
                        put("ref_text", referenceText)
                        put("difficulty", 3)
                        put("response_mode", "once")
                    }
                )
            }
            val request = Request.Builder()
                .url(ENDPOINT)
                .post(payload.toString().toRequestBody(jsonMedia))
                .header("Authorization", AUTHORIZATION)
                .header("User-Agent", "ClassroomManager/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception(
                            "评测请求失败（HTTP ${response.code}）：" +
                                body.replace(Regex("\\s+"), " ").take(240)
                        )
                    )
                }
                val root = JSONObject(body)
                val code = root.optInt("code", -1)
                if (code != 1000) {
                    val message = root.optString("message")
                        .ifBlank { root.optString("msg", "音频评测失败") }
                    return@withContext Result.failure(
                        Exception("$message（code=$code）")
                    )
                }
                Result.success(parseScore(root, language))
            }
        } catch (e: Exception) {
            Log.e(TAG, "evaluate failed", e)
            Result.failure(e)
        }
    }

    private fun parseScore(root: JSONObject, language: SoeLanguage): SoeScore {
        val scores = root.optJSONObject("scores") ?: JSONObject()
        val details = root.optJSONArray("accuracy_details")
        val words = buildList {
            if (details != null) {
                for (index in 0 until details.length()) {
                    val detail = details.optJSONObject(index) ?: continue
                    val raw = if (language == SoeLanguage.CHINESE) {
                        detail.optDouble("pinyin_score", detail.optDouble("score"))
                    } else {
                        detail.optDouble("score")
                    }
                    val normalized = normalizeSoeDetailScore(raw) ?: continue
                    add(
                        SoeWordScore(
                            word = detail.optString("ref_word").ifBlank {
                                detail.optString("word")
                            },
                            score = normalized
                        )
                    )
                }
            }
        }
        val detailAverage = words.map { it.score }.takeIf { it.isNotEmpty() }
            ?.average()
        val accuracy = scores.optDouble("accuracy")
            .takeIf { it.isFinite() }
            ?.coerceIn(0.0, 100.0)
            ?: detailAverage
            ?: throw Exception("未识别到有效朗读内容，请重试")
        val fluency = scores.optDouble("fluency")
            .takeIf { it.isFinite() }
            ?.coerceIn(0.0, 100.0)
        val integrity = scores.optDouble("integrity")
            .takeIf { it.isFinite() }
            ?.coerceIn(0.0, 100.0)
        val overall = calculateSoeOverall(accuracy, fluency, integrity)
            ?: throw Exception("评测结果缺少分数")
        val addition = root.optJSONObject("addition")
        return SoeScore(
            overall = overall,
            accuracy = accuracy,
            fluency = fluency,
            integrity = integrity,
            words = words,
            requestId = root.optString("reqid").takeIf { it.isNotBlank() },
            audioUrl = addition?.optString("audio_url")?.takeIf { it.isNotBlank() }
        )
    }

    private fun detectLanguage(text: String): SoeLanguage {
        return if (text.any { it in '\u4E00'..'\u9FFF' }) {
            SoeLanguage.CHINESE
        } else {
            SoeLanguage.ENGLISH
        }
    }

    private fun transcodeToWav(file: File): ByteArray {
        require(file.exists() && file.length() > 0) { "录音文件不存在" }
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw Exception("录音中没有音频轨道")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw Exception("无法识别录音格式")
            var sampleRate = inputFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channelCount = inputFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw Exception("读取录音失败")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        sampleRate = outputFormat.optInt(
                            MediaFormat.KEY_SAMPLE_RATE,
                            sampleRate
                        )
                        channelCount = outputFormat.optInt(
                            MediaFormat.KEY_CHANNEL_COUNT,
                            channelCount
                        )
                        if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outputFormat.getInteger(
                                MediaFormat.KEY_PCM_ENCODING
                            )
                        }
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && info.size > 0) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            pcm.write(
                                convertPcmChunk(
                                    outputBuffer,
                                    pcmEncoding
                                )
                            )
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            return buildWav(
                pcm.toByteArray(),
                sampleRate.coerceAtLeast(1),
                channelCount.coerceAtLeast(1)
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun convertPcmChunk(buffer: ByteBuffer, encoding: Int): ByteArray {
        val bytes = ByteArray(buffer.remaining())
        val source = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        if (encoding != AudioFormat.ENCODING_PCM_FLOAT) {
            source.get(bytes)
            return bytes
        }
        val floats = FloatArray(bytes.size / 4)
        source.asFloatBuffer().get(floats)
        return ByteBuffer.allocate(floats.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                floats.forEach { value ->
                    putShort(
                        (value.coerceIn(-1f, 1f) * Short.MAX_VALUE)
                            .roundToInt()
                            .toShort()
                    )
                }
            }
            .array()
    }

    private fun buildWav(
        pcmBytes: ByteArray,
        sourceRate: Int,
        channelCount: Int
    ): ByteArray {
        val sourceSamples = ByteBuffer.wrap(pcmBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val sourceFrames = sourceSamples.remaining() / channelCount
        require(sourceFrames > 0) { "录音为空，请重试" }
        val mono = ShortArray(sourceFrames) { frame ->
            var sum = 0
            repeat(channelCount) {
                sum += sourceSamples.get(frame * channelCount + it).toInt()
            }
            (sum / channelCount).toShort()
        }
        val targetFrames = (
            sourceFrames.toLong() * TARGET_SAMPLE_RATE / sourceRate
            ).toInt().coerceAtLeast(1)
        val resampled = ShortArray(targetFrames) { index ->
            if (sourceRate == TARGET_SAMPLE_RATE) {
                mono[index.coerceAtMost(mono.lastIndex)]
            } else {
                val sourcePosition = index.toDouble() * sourceRate /
                    TARGET_SAMPLE_RATE
                val left = sourcePosition.toInt().coerceAtMost(mono.lastIndex)
                val right = (left + 1).coerceAtMost(mono.lastIndex)
                val fraction = sourcePosition - left
                (
                    mono[left] + (mono[right] - mono[left]) * fraction
                    ).roundToInt().toShort()
            }
        }
        val dataSize = resampled.size * 2
        return ByteBuffer.allocate(44 + dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray())
                putInt(36 + dataSize)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(TARGET_SAMPLE_RATE)
                putInt(TARGET_SAMPLE_RATE * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(dataSize)
                resampled.forEach(::putShort)
            }
            .array()
    }

    private fun MediaFormat.optInt(key: String, fallback: Int): Int {
        return if (containsKey(key)) runCatching { getInteger(key) }
            .getOrDefault(fallback) else fallback
    }
}
