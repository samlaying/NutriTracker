package com.example.nutritracker.data.repository

import androidx.room.withTransaction
import com.example.nutritracker.data.AppDatabase
import com.example.nutritracker.data.dao.TrainingExerciseDao
import com.example.nutritracker.data.dao.TrainingPlanDao
import com.example.nutritracker.data.dao.TrainingSessionDao
import com.example.nutritracker.data.entity.PlanStatus
import com.example.nutritracker.data.entity.SessionStatus
import com.example.nutritracker.data.entity.TrainingExercise
import com.example.nutritracker.data.entity.TrainingPlan
import com.example.nutritracker.data.entity.TrainingSession
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** 计划里一次训练的完整定义（Agent 生成计划的入参形态） */
data class PlannedSession(
    val date: LocalDate,
    val title: String,
    val focus: String? = null,
    val exercises: List<PlannedExercise> = emptyList()
)

data class PlannedExercise(
    val name: String,
    val targetSets: String,
    val targetReps: String,
    val targetWeightKg: Double? = null,
    val restSeconds: Int? = null,
    val rpeTarget: String? = null,
    val notes: String? = null
)

@Singleton
class TrainingRepository @Inject constructor(
    private val db: AppDatabase,
    private val planDao: TrainingPlanDao,
    private val sessionDao: TrainingSessionDao,
    private val exerciseDao: TrainingExerciseDao
) {
    // ── 计划 ──

    fun getActivePlanFlow(): Flow<TrainingPlan?> = planDao.getByStatusFlow(PlanStatus.ACTIVE)

    suspend fun getActivePlan(): TrainingPlan? = planDao.getByStatus(PlanStatus.ACTIVE)

    suspend fun getPlanById(id: Long): TrainingPlan? = planDao.getById(id)

    suspend fun getAllPlans(): List<TrainingPlan> = planDao.getAll()

    /** 事务化创建计划：归档旧 ACTIVE 计划 → 建新计划 → 写入全部会话与动作 */
    suspend fun createPlan(
        name: String,
        goal: String,
        weeksTotal: Int,
        startDate: LocalDate,
        sessions: List<PlannedSession>,
        notes: String? = null
    ): TrainingPlan = db.withTransaction {
        planDao.getByStatus(PlanStatus.ACTIVE)?.let { active ->
            planDao.update(active.copy(status = PlanStatus.ARCHIVED))
        }
        val plan = TrainingPlan(
            name = name, goal = goal, weeksTotal = weeksTotal,
            startDate = startDate, notes = notes
        )
        val planId = planDao.upsert(plan)
        planDao.archiveAllExcept(planId, PlanStatus.ARCHIVED) // 防御重复 ACTIVE
        sessions.forEach { s ->
            val sessionId = sessionDao.upsert(
                TrainingSession(
                    planId = planId, scheduledDate = s.date,
                    title = s.title, focus = s.focus
                )
            )
            s.exercises.forEachIndexed { index, e ->
                exerciseDao.upsert(
                    TrainingExercise(
                        sessionId = sessionId, orderIndex = index,
                        name = e.name, targetSets = e.targetSets, targetReps = e.targetReps,
                        targetWeightKg = e.targetWeightKg, restSeconds = e.restSeconds,
                        rpeTarget = e.rpeTarget, notes = e.notes
                    )
                )
            }
        }
        plan.copy(id = planId)
    }

    suspend fun deletePlan(id: Long) = planDao.deleteById(id)

    // ── 导入（备份恢复用，绕过归档逻辑直接落库） ──

    suspend fun createPlanDirect(plan: TrainingPlan): Long = planDao.upsert(plan.copy(id = 0))

    suspend fun importSession(session: TrainingSession): Long = sessionDao.upsert(session.copy(id = 0))

    suspend fun importExercise(exercise: TrainingExercise): Long = exerciseDao.upsert(exercise.copy(id = 0))

    // ── 会话 ──

    fun getSessionsFlow(planId: Long): Flow<List<TrainingSession>> = sessionDao.getByPlanFlow(planId)

    suspend fun getSessions(planId: Long): List<TrainingSession> = sessionDao.getByPlan(planId)

    suspend fun getSession(id: Long): TrainingSession? = sessionDao.getById(id)

    fun getSessionFlow(id: Long): Flow<TrainingSession?> = sessionDao.getByIdFlow(id)

    suspend fun getNextPlannedSession(planId: Long): TrainingSession? =
        sessionDao.getFirstByStatus(planId, SessionStatus.PLANNED)

    suspend fun getSessionsInRange(start: LocalDate, end: LocalDate): List<TrainingSession> =
        sessionDao.getByDateRange(start, end)

    /** 完成训练日：回读校验后落库（写后回源验证） */
    suspend fun completeSession(sessionId: Long, notes: String? = null): TrainingSession? {
        val session = sessionDao.getById(sessionId) ?: return null
        val updated = session.copy(
            status = SessionStatus.COMPLETED,
            completedAt = LocalDateTime.now(),
            notes = notes ?: session.notes
        )
        sessionDao.update(updated)
        return sessionDao.getById(sessionId)
    }

    suspend fun skipSession(sessionId: Long, notes: String? = null): TrainingSession? {
        val session = sessionDao.getById(sessionId) ?: return null
        val updated = session.copy(status = SessionStatus.SKIPPED, notes = notes ?: session.notes)
        sessionDao.update(updated)
        return sessionDao.getById(sessionId)
    }

    suspend fun replanSession(sessionId: Long): TrainingSession? {
        val session = sessionDao.getById(sessionId) ?: return null
        val updated = session.copy(status = SessionStatus.PLANNED, completedAt = null)
        sessionDao.update(updated)
        return sessionDao.getById(sessionId)
    }

    // ── 动作 ──

    fun getExercisesFlow(sessionId: Long): Flow<List<TrainingExercise>> =
        exerciseDao.getBySessionFlow(sessionId)

    suspend fun getExercises(sessionId: Long): List<TrainingExercise> =
        exerciseDao.getBySession(sessionId)

    suspend fun updateActual(
        exerciseId: Long,
        actualSets: String?,
        actualReps: String?,
        actualWeightKg: Double?,
        completed: Boolean?
    ): TrainingExercise? {
        val e = exerciseDao.getById(exerciseId) ?: return null
        val updated = e.copy(
            actualSets = actualSets ?: e.actualSets,
            actualReps = actualReps ?: e.actualReps,
            actualWeightKg = actualWeightKg ?: e.actualWeightKg,
            completed = completed ?: e.completed
        )
        exerciseDao.update(updated)
        return exerciseDao.getById(exerciseId)
    }

    /** 汇总当前计划的执行进度（用于上下文注入与复盘） */
    suspend fun planProgress(planId: Long): PlanProgress {
        val sessions = sessionDao.getByPlan(planId)
        return PlanProgress(
            total = sessions.size,
            completed = sessions.count { it.status == SessionStatus.COMPLETED },
            skipped = sessions.count { it.status == SessionStatus.SKIPPED },
            planned = sessions.count { it.status == SessionStatus.PLANNED }
        )
    }
}

data class PlanProgress(val total: Int, val completed: Int, val skipped: Int, val planned: Int)
