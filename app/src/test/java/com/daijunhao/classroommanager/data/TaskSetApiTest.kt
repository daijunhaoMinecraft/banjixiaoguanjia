package com.daijunhao.classroommanager.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskSetApiTest {
    @Test
    fun resolvesCosImagePath() {
        val media = TaskSetApi.resolveSubmissionMedia(
            "_cos/oWRkU0em6bqAHVyAdmb8bsBApNRQ_app_image/example_1792841154214.jpg"
        )

        assertEquals(SubmissionMediaKind.IMAGE, media?.kind)
        assertEquals(
            "https://img-1302562365.cos.ap-beijing.myqcloud.com/" +
                "_cos/oWRkU0em6bqAHVyAdmb8bsBApNRQ_app_image/example_1792841154214.jpg",
            media?.url
        )
    }

    @Test
    fun resolvesOssVideoPath() {
        val media = TaskSetApi.resolveSubmissionMedia(
            "_oss/66ea446285290d897384175c/app_video/example_1792837585046.mp4"
        )

        assertEquals(SubmissionMediaKind.VIDEO, media?.kind)
        assertEquals(
            "https://campusvideo.oss-cn-beijing.aliyuncs.com/" +
                "_oss/66ea446285290d897384175c/app_video/example_1792837585046.mp4",
            media?.url
        )
    }

    @Test
    fun resolvesCosPathWithDirectorySuffix() {
        val media = TaskSetApi.resolveSubmissionMedia(
            "app_image_ai_task_tag_cos/example_1778337386572.jpg"
        )

        assertEquals(SubmissionMediaKind.IMAGE, media?.kind)
        assertEquals(
            "https://img-1302562365.cos.ap-beijing.myqcloud.com/" +
                "app_image_ai_task_tag_cos/example_1778337386572.jpg",
            media?.url
        )
    }

    @Test
    fun resolvesTosAudioWithPublicRecordDomain() {
        val media = TaskSetApi.resolveSubmissionMedia(
            "_tos/66ea446285290d897384175c_app_record/example_1786938160487.mp3"
        )

        assertEquals(SubmissionMediaKind.AUDIO, media?.kind)
        assertEquals(
            "https://tosrecord.banjixiaoguanjia.com/" +
                "_tos/66ea446285290d897384175c_app_record/example_1786938160487.mp3",
            media?.url
        )
    }

    @Test
    fun resolvesTosVideoWithPublicVideoDomain() {
        val media = TaskSetApi.resolveSubmissionMedia(
            "_tos/66ea446285290d897384175c/app_video/example_1792165125918.mp4"
        )

        assertEquals(SubmissionMediaKind.VIDEO, media?.kind)
        assertEquals(
            "https://tosvideo.banjixiaoguanjia.com/" +
                "_tos/66ea446285290d897384175c/app_video/example_1792165125918.mp4",
            media?.url
        )
    }
}
