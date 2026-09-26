package com.example.nutritracker.di

import android.content.Context
import androidx.room.Room
import com.example.nutritracker.data.AppDatabase
import com.example.nutritracker.application.media.MealPhotoAnalyzer
import com.example.nutritracker.feature.camera.AiAnalysisManager
import com.example.nutritracker.data.dao.*
import com.example.nutritracker.util.DayBoundaryCalc
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMealPhotoAnalyzer(implementation: AiAnalysisManager): MealPhotoAnalyzer = implementation

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "nutritracker.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideUserDao(db: AppDatabase): UserDao = db.userDao()
    @Provides fun provideMealDao(db: AppDatabase): MealDao = db.mealDao()
    @Provides fun provideIntakeDao(db: AppDatabase): IntakeDao = db.intakeDao()
    @Provides fun provideTrackedDayDao(db: AppDatabase): TrackedDayDao = db.trackedDayDao()
    @Provides fun provideActivityDao(db: AppDatabase): ActivityDao = db.activityDao()
    @Provides fun provideWeightLogDao(db: AppDatabase): WeightLogDao = db.weightLogDao()
    @Provides fun provideWaterIntakeDao(db: AppDatabase): WaterIntakeDao = db.waterIntakeDao()
    @Provides fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideChatMessageDao(db: AppDatabase): ChatMessageDao = db.chatMessageDao()
    @Provides fun provideUserMemoryDao(db: AppDatabase): UserMemoryDao = db.userMemoryDao()
    @Provides fun provideAgentTaskDao(db: AppDatabase): AgentTaskDao = db.agentTaskDao()
    @Provides fun provideTrainingPlanDao(db: AppDatabase): TrainingPlanDao = db.trainingPlanDao()
    @Provides fun provideTrainingSessionDao(db: AppDatabase): TrainingSessionDao = db.trainingSessionDao()
    @Provides fun provideTrainingExerciseDao(db: AppDatabase): TrainingExerciseDao = db.trainingExerciseDao()

    @Provides
    @Singleton
    fun provideDayBoundaryCalc(): DayBoundaryCalc = DayBoundaryCalc()
}
