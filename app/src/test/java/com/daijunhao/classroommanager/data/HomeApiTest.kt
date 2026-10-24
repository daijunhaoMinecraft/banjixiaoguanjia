package com.daijunhao.classroommanager.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeApiTest {
    @Test
    fun groupsRelatedCustomAndCheckInItemsTogether() {
        val custom = homeItem(
            id = "custom",
            type = 0,
            sortTime = "2026-10-23T12:00:00.000Z"
        )
        val checkIn = homeItem(
            id = "check-in",
            type = 23,
            relevantId = "custom",
            sortTime = "2026-10-23T12:00:01.000Z"
        )
        val score = homeItem(
            id = "score",
            type = 4,
            sortTime = "2026-10-11T03:32:31.000Z"
        )

        val grouped = HomeApi.groupHomeItems(listOf(custom, checkIn, score))

        assertEquals(listOf("custom", "check-in", "score"), grouped.map { it.id })
        assertEquals("custom", grouped[0].groupKey)
        assertEquals(2, grouped[0].groupSize)
        assertEquals("custom", grouped[1].groupKey)
        assertEquals(2, grouped[1].groupSize)
    }

    private fun homeItem(
        id: String,
        type: Int,
        relevantId: String? = null,
        sortTime: String
    ): HomeItem {
        return HomeItem(
            id = id,
            cid = "class",
            type = type,
            mainId = if (type == 23) "main" else null,
            relevantId = relevantId,
            title = "task",
            subject = "英语",
            textContent = "",
            creatorName = "teacher",
            isFeedback = false,
            isRead = false,
            endDay = "",
            createAt = sortTime,
            scoreId = null,
            sortTime = sortTime
        )
    }
}
