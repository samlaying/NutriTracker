package com.example.nutritracker.data.entity

import androidx.room.*
import java.time.LocalDate
import java.time.LocalDateTime

// ── 训练计划 ─────────────────────────────────────────────────────────────────

enum class PlanStatus { ACTIVE, ARCHIVED }

@Entity(tableName = "training_plans")
data class TrainingPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val goal: String,
    val weeksTotal: Int,
    val startDate: LocalDate,
    val status: PlanStatus = PlanStatus.ACTIVE,
    val notes: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

// ── 训练日 ───────────────────────────────────────────────────────────────────

enum class SessionStatus { PLANNED, COMPLETED, SKIPPED }

@Entity(
    tableName = "training_sessions",
    foreignKeys = [ForeignKey(
        entity = TrainingPlan::class,
        parentColumns = ["id"],
        childColumns = ["planId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("planId")]
)
data class TrainingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val scheduledDate: LocalDate,
    val title: String,
    val focus: String? = null,
    val status: SessionStatus = SessionStatus.PLANNED,
    val completedAt: LocalDateTime? = null,
    val notes: String? = null
)

// ── 训练动作条目 ─────────────────────────────────────────────────────────────

@Entity(
    tableName = "training_exercises",
    foreignKeys = [ForeignKey(
        entity = TrainingSession::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class TrainingExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val orderIndex: Int,
    val name: String,
    val targetSets: String,
    val targetReps: String,
    val targetWeightKg: Double? = null,
    val restSeconds: Int? = null,
    val rpeTarget: String? = null,
    val actualSets: String? = null,
    val actualReps: String? = null,
    val actualWeightKg: Double? = null,
    val completed: Boolean = false,
    val notes: String? = null
)
