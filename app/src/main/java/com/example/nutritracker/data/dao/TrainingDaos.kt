package com.example.nutritracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.nutritracker.data.entity.PlanStatus
import com.example.nutritracker.data.entity.SessionStatus
import com.example.nutritracker.data.entity.TrainingExercise
import com.example.nutritracker.data.entity.TrainingPlan
import com.example.nutritracker.data.entity.TrainingSession
import kotlinx.coroutines.flow.Flow

@Dao
interface TrainingPlanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: TrainingPlan): Long

    @Update
    suspend fun update(plan: TrainingPlan)

    @Query("SELECT * FROM training_plans WHERE id = :id")
    suspend fun getById(id: Long): TrainingPlan?

    @Query("SELECT * FROM training_plans WHERE status = :status LIMIT 1")
    suspend fun getByStatus(status: PlanStatus): TrainingPlan?

    @Query("SELECT * FROM training_plans WHERE status = :status LIMIT 1")
    fun getByStatusFlow(status: PlanStatus): Flow<TrainingPlan?>

    @Query("SELECT * FROM training_plans ORDER BY createdAt DESC")
    suspend fun getAll(): List<TrainingPlan>

    @Query("UPDATE training_plans SET status = :status WHERE id != :exceptId")
    suspend fun archiveAllExcept(exceptId: Long, status: PlanStatus)

    @Query("DELETE FROM training_plans WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface TrainingSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: TrainingSession): Long

    @Update
    suspend fun update(session: TrainingSession)

    @Query("SELECT * FROM training_sessions WHERE id = :id")
    suspend fun getById(id: Long): TrainingSession?

    @Query("SELECT * FROM training_sessions WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<TrainingSession?>

    @Query("SELECT * FROM training_sessions WHERE planId = :planId ORDER BY scheduledDate ASC, id ASC")
    suspend fun getByPlan(planId: Long): List<TrainingSession>

    @Query("SELECT * FROM training_sessions WHERE planId = :planId ORDER BY scheduledDate ASC, id ASC")
    fun getByPlanFlow(planId: Long): Flow<List<TrainingSession>>

    @Query("SELECT * FROM training_sessions WHERE planId = :planId AND status = :status ORDER BY scheduledDate ASC LIMIT 1")
    suspend fun getFirstByStatus(planId: Long, status: SessionStatus): TrainingSession?

    @Query("SELECT * FROM training_sessions WHERE scheduledDate BETWEEN :start AND :end ORDER BY scheduledDate ASC")
    suspend fun getByDateRange(start: java.time.LocalDate, end: java.time.LocalDate): List<TrainingSession>

    @Query("SELECT COUNT(*) FROM training_sessions WHERE planId = :planId AND status = :status")
    suspend fun countByStatus(planId: Long, status: SessionStatus): Int

    @Query("DELETE FROM training_sessions WHERE planId = :planId")
    suspend fun deleteByPlan(planId: Long)
}

@Dao
interface TrainingExerciseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(exercise: TrainingExercise): Long

    @Update
    suspend fun update(exercise: TrainingExercise)

    @Query("SELECT * FROM training_exercises WHERE id = :id")
    suspend fun getById(id: Long): TrainingExercise?

    @Query("SELECT * FROM training_exercises WHERE sessionId = :sessionId ORDER BY orderIndex ASC")
    suspend fun getBySession(sessionId: Long): List<TrainingExercise>

    @Query("SELECT * FROM training_exercises WHERE sessionId = :sessionId ORDER BY orderIndex ASC")
    fun getBySessionFlow(sessionId: Long): Flow<List<TrainingExercise>>

    @Query("DELETE FROM training_exercises WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}
