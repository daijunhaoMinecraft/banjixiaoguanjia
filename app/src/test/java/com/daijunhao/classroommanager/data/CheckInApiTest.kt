package com.daijunhao.classroommanager.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CheckInApiTest {
    @Test
    fun buildsOssObjectKeyUsingFileNameHashAndTimestamp() {
        val key = CheckInApi.buildMediaObjectKey(
            fileName = "VID_20261023_231022.mp4",
            memberId = "66ea446285290d897384175c",
            uploadType = CheckInUploadType.VIDEO,
            timestamp = 1792837585046
        )

        assertEquals(
            "_oss/66ea446285290d897384175c/app_video/95c5f51b_1792837585046.mp4",
            key
        )
    }

    @Test
    fun buildsCosObjectKeyForImage() {
        val key = CheckInApi.buildMediaObjectKey(
            fileName = "IMG_20261024_120000.jpg",
            memberId = "66ea446285290d897384175c",
            uploadType = CheckInUploadType.IMAGE,
            timestamp = 1792841154214
        )

        assertEquals(
            "_cos/66ea446285290d897384175c/app_image/b14e2a51_1792841154214.jpg",
            key
        )
    }
}
