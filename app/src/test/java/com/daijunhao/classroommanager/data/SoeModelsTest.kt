package com.daijunhao.classroommanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoeModelsTest {
    @Test
    fun `uses accuracy when it is higher than weighted dimensions`() {
        assertEquals(
            98.5,
            calculateSoeOverall(
                accuracy = 98.5,
                fluency = 90.0,
                integrity = 91.0
            )
        )
    }

    @Test
    fun `uses weighted dimensions when they are higher`() {
        assertEquals(
            95.0,
            calculateSoeOverall(
                accuracy = 88.0,
                fluency = 98.0,
                integrity = 88.0
            )
        )
    }

    @Test
    fun `falls back to accuracy when dimensions are missing`() {
        assertEquals(
            76.25,
            calculateSoeOverall(
                accuracy = 76.25,
                fluency = null,
                integrity = null
            )
        )
    }

    @Test
    fun `returns null when no score is available`() {
        assertNull(calculateSoeOverall(null, null, null))
    }

    @Test
    fun `normalizes detail scores and submission rounding`() {
        assertEquals(98.0, normalizeSoeDetailScore(0.98))
        assertEquals(91.0, normalizeSoeDetailScore(91.0))
        assertEquals(91.3, roundSoeScore(91.26), 0.0001)
    }
}
