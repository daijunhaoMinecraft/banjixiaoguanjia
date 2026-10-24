package com.daijunhao.classroommanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class StudentTaskState {
    COMPLETED,
    WRONG,
    NOT_SUBMITTED,
    OTHER
}

data class StudentTaskStatus(
    val member: MemberInfo,
    val statusCode: Int?,
    val acceptId: String?,
    val state: StudentTaskState
)

data class TaskClassBreakdown(
    val taskId: String,
    val title: String,
    val students: List<StudentTaskStatus>
) {
    val completedCount: Int
        get() = students.count { it.state == StudentTaskState.COMPLETED }

    val wrongCount: Int
        get() = students.count { it.state == StudentTaskState.WRONG }

    val notSubmittedCount: Int
        get() = students.count { it.state == StudentTaskState.NOT_SUBMITTED }

    val otherCount: Int
        get() = students.count { it.state == StudentTaskState.OTHER }
}

data class ClassTaskAnalysis(
    val title: String,
    val members: List<MemberInfo>,
    val tasks: List<TaskClassBreakdown>
)

object ClassTaskAnalysisApi {
    suspend fun load(
        token: String,
        memberId: String,
        cid: String,
        mainId: String
    ): Result<ClassTaskAnalysis> = withContext(Dispatchers.IO) {
        try {
            val members = ClassApi.getMembersByCid(token, cid)
                .getOrElse { return@withContext Result.failure(it) }
                .sortedWith(
                    compareBy<MemberInfo> { it.studentNumber }
                        .thenBy { it.name }
                )
            val taskSet = TaskSetApi.getTaskSet(
                token = token,
                memberId = memberId,
                cid = cid,
                mainId = mainId
            ).getOrElse { return@withContext Result.failure(it) }
            val tasks = taskSet.tasks.map { task ->
                TaskClassBreakdown(
                    taskId = task.id,
                    title = task.title,
                    students = members.map { member ->
                        val status = task.memberStatuses[member.id]
                        val state = when {
                            status == null -> StudentTaskState.NOT_SUBMITTED
                            status < 0 -> StudentTaskState.WRONG
                            status == 0 -> StudentTaskState.COMPLETED
                            else -> StudentTaskState.OTHER
                        }
                        StudentTaskStatus(
                            member = member,
                            statusCode = status,
                            acceptId = task.memberAcceptIds[member.id],
                            state = state
                        )
                    }
                )
            }
            Result.success(
                ClassTaskAnalysis(
                    title = taskSet.title,
                    members = members,
                    tasks = tasks
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
