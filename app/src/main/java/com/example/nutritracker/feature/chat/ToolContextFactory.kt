package com.example.nutritracker.feature.chat

import com.example.nutritracker.data.repository.ActivityRepository
import com.example.nutritracker.data.repository.AgentTaskRepository
import com.example.nutritracker.data.repository.ConversationRepository
import com.example.nutritracker.data.repository.IntakeRepository
import com.example.nutritracker.data.repository.MealRepository
import com.example.nutritracker.data.repository.MemoryRepository
import com.example.nutritracker.data.repository.SettingsRepository
import com.example.nutritracker.data.repository.TrackedDayRepository
import com.example.nutritracker.data.repository.TrainingRepository
import com.example.nutritracker.data.repository.UserRepository
import com.example.nutritracker.data.repository.WaterIntakeRepository
import com.example.nutritracker.data.repository.WeightLogRepository
import com.example.nutritracker.harness.SkillRegistry
import com.example.nutritracker.harness.tools.HarnessFileBackend
import com.example.nutritracker.harness.tools.ToolContext
import com.example.nutritracker.util.DayBoundaryCalc
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** 组装一次 turn 的 ToolContext（导航/委派/异步任务以 lambda 注入） */
@Singleton
class ToolContextFactory @Inject constructor(
    private val userRepo: UserRepository,
    private val mealRepo: MealRepository,
    private val intakeRepo: IntakeRepository,
    private val trackedDayRepo: TrackedDayRepository,
    private val activityRepo: ActivityRepository,
    private val weightRepo: WeightLogRepository,
    private val waterRepo: WaterIntakeRepository,
    private val trainingRepo: TrainingRepository,
    private val memoryRepo: MemoryRepository,
    private val taskRepo: AgentTaskRepository,
    private val conversationRepo: ConversationRepository,
    private val settingsRepo: SettingsRepository,
    private val skillRegistry: SkillRegistry,
    private val fileBackend: HarnessFileBackend,
    private val dayBoundaryCalc: DayBoundaryCalc
) {
    suspend fun create(
        conversationId: Long,
        navigator: (route: String) -> Boolean,
        startAsync: suspend (conversationId: Long?, kind: String, title: String, prompt: String) -> Long,
        delegate: suspend (agentName: String, handoff: Map<String, String>) -> String
    ): ToolContext {
        val offset = settingsRepo.dayBoundaryMinutes.first()
        val today: LocalDate = dayBoundaryCalc.currentLogicalDay(offset)
        return ToolContext(
            conversationId = conversationId,
            today = today,
            dayBoundaryOffset = offset,
            userRepo = userRepo,
            mealRepo = mealRepo,
            intakeRepo = intakeRepo,
            trackedDayRepo = trackedDayRepo,
            activityRepo = activityRepo,
            weightRepo = weightRepo,
            waterRepo = waterRepo,
            trainingRepo = trainingRepo,
            memoryRepo = memoryRepo,
            taskRepo = taskRepo,
            conversationRepo = conversationRepo,
            settingsRepo = settingsRepo,
            skillRegistry = skillRegistry,
            fileBackend = fileBackend,
            openScreen = navigator,
            startAsyncTask = startAsync,
            delegate = delegate
        )
    }
}
