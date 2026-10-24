package com.daijunhao.classroommanager.data

import kotlin.math.max
import kotlin.math.round

enum class SoeLanguage {
    ENGLISH,
    CHINESE
}

data class SoeWordScore(
    val word: String,
    val score: Double
)

data class SoeScore(
    val overall: Double,
    val accuracy: Double,
    val fluency: Double?,
    val integrity: Double?,
    val words: List<SoeWordScore>,
    val requestId: String?,
    val audioUrl: String?
) {
    val passed: Boolean
        get() = overall >= 60.0
}

internal fun calculateSoeOverall(
    accuracy: Double?,
    fluency: Double?,
    integrity: Double?
): Double? {
    val validAccuracy = accuracy?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
    val validFluency = fluency?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
    val validIntegrity = integrity?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
    val weighted = if (validFluency != null && validIntegrity != null) {
        validFluency * 0.7 + validIntegrity * 0.3
    } else {
        null
    }
    return when {
        validAccuracy != null && weighted != null -> max(validAccuracy, weighted)
        validAccuracy != null -> validAccuracy
        weighted != null -> weighted
        else -> null
    }
}

internal fun normalizeSoeDetailScore(value: Double): Double? {
    if (!value.isFinite()) return null
    val normalized = if (value <= 1.001) value * 100.0 else value
    return normalized.coerceIn(0.0, 100.0)
}

internal fun roundSoeScore(value: Double): Double =
    round(value.coerceIn(0.0, 100.0) * 10.0) / 10.0
