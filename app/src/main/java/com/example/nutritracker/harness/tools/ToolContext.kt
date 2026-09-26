package com.example.nutritracker.harness.tools

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
import java.time.LocalDate

/** 报告/文件后端：路径白名单即 Harness 的执行边界 */
interface HarnessFileBackend {
    suspend fun write(path: String, content: String): String
    suspend fun read(path: String): String
    suspend fun list(prefix: String): List<String>
}

interface AsyncTaskRunner {
    suspend fun start(conversationId: Long?, kind: String, title: String, prompt: String): Long
}

/**
 * 工具执行上下文：一次 turn 的全部环境。
 * 导航/委派/异步任务以 lambda 注入，避免与 UI、任务服务的 DI 环。
 */
class ToolContext(
    val conversationId: Long,
    val today: LocalDate,
    val dayBoundaryOffset: Int,
    val userRepo: UserRepository,
    val mealRepo: MealRepository,
    val intakeRepo: IntakeRepository,
    val trackedDayRepo: TrackedDayRepository,
    val activityRepo: ActivityRepository,
    val weightRepo: WeightLogRepository,
    val waterRepo: WaterIntakeRepository,
    val trainingRepo: TrainingRepository,
    val memoryRepo: MemoryRepository,
    val taskRepo: AgentTaskRepository,
    val conversationRepo: ConversationRepository,
    val settingsRepo: SettingsRepository,
    val skillRegistry: SkillRegistry,
    val fileBackend: HarnessFileBackend,
    /** 打开原生页面，返回是否成功 */
    val openScreen: (route: String) -> Boolean,
    /** 启动异步长任务，返回 task id */
    val startAsyncTask: suspend (conversationId: Long?, kind: String, title: String, prompt: String) -> Long,
    /** 同步委派子 Agent，返回结果文本 */
    val delegate: suspend (agentName: String, handoff: Map<String, String>) -> String
)
