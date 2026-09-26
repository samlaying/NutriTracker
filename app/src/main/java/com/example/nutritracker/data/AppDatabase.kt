package com.example.nutritracker.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.nutritracker.data.converter.Converters
import com.example.nutritracker.data.dao.*
import com.example.nutritracker.data.entity.*

@Database(
    entities = [
        User::class, Meal::class, Intake::class,
        TrackedDay::class, UserActivityEntity::class,
        WeightLog::class, WaterIntake::class,
        Conversation::class, ChatMessage::class, UserMemory::class, AgentTask::class,
        TrainingPlan::class, TrainingSession::class, TrainingExercise::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun mealDao(): MealDao
    abstract fun intakeDao(): IntakeDao
    abstract fun trackedDayDao(): TrackedDayDao
    abstract fun activityDao(): ActivityDao
    abstract fun weightLogDao(): WeightLogDao
    abstract fun waterIntakeDao(): WaterIntakeDao
    abstract fun conversationDao(): ConversationDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun trainingPlanDao(): TrainingPlanDao
    abstract fun trainingSessionDao(): TrainingSessionDao
    abstract fun trainingExerciseDao(): TrainingExerciseDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `conversations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL, `summary` TEXT, `todoJson` TEXT, `pendingToolJson` TEXT,
                        `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL)"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `chat_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationId` INTEGER NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL,
                        `toolCallJson` TEXT, `toolCallId` TEXT, `toolName` TEXT, `cardType` TEXT,
                        `payloadJson` TEXT, `imagePath` TEXT, `createdAt` TEXT NOT NULL,
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_conversationId` ON `chat_messages` (`conversationId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `user_memory` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `category` TEXT NOT NULL, `content` TEXT NOT NULL, `source` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL)"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `agent_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationId` INTEGER, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `prompt` TEXT NOT NULL,
                        `status` TEXT NOT NULL, `result` TEXT, `createdAt` TEXT NOT NULL, `finishedAt` TEXT)"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `training_plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL, `goal` TEXT NOT NULL, `weeksTotal` INTEGER NOT NULL,
                        `startDate` TEXT NOT NULL, `status` TEXT NOT NULL, `notes` TEXT, `createdAt` TEXT NOT NULL)"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `training_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `planId` INTEGER NOT NULL, `scheduledDate` TEXT NOT NULL, `title` TEXT NOT NULL,
                        `focus` TEXT, `status` TEXT NOT NULL, `completedAt` TEXT, `notes` TEXT,
                        FOREIGN KEY(`planId`) REFERENCES `training_plans`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_training_sessions_planId` ON `training_sessions` (`planId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `training_exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL, `orderIndex` INTEGER NOT NULL, `name` TEXT NOT NULL,
                        `targetSets` TEXT NOT NULL, `targetReps` TEXT NOT NULL, `targetWeightKg` REAL,
                        `restSeconds` INTEGER, `rpeTarget` TEXT, `actualSets` TEXT, `actualReps` TEXT,
                        `actualWeightKg` REAL, `completed` INTEGER NOT NULL, `notes` TEXT,
                        FOREIGN KEY(`sessionId`) REFERENCES `training_sessions`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_training_exercises_sessionId` ON `training_exercises` (`sessionId`)")
            }
        }
    }
}
