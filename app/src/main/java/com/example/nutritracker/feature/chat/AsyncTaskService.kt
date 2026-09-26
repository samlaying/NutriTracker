package com.example.nutritracker.feature.chat

import com.example.nutritracker.data.entity.AgentTask
import com.example.nutritracker.data.entity.AgentTaskStatus
import com.example.nutritracker.data.repository.AgentTaskRepository
import com.example.nutritracker.data.repository.ConversationRepository
import com.example.nutritracker.data.repository.SettingsRepository
import com.example.nutritracker.harness.HarnessConfig
import com.example.nutritracker.harness.SubAgents
import com.example.nutritracker.harness.SubAgentRunner
import com.example.nutritracker.harness.tools.AsyncTaskRunner
import com.example.nutritracker.harness.tools.ToolContext
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 异步子 Agent 任务执行器（教程 AsyncSubAgent）：
 * start 即返回 task id，长任务后台跑；完成后自动把结果作为教练消息回传对话。
 */
@Singleton
class AsyncTaskService @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val modelClient: com.example.nutritracker.harness.ModelClient,
    private val subAgentRunner: dagger.Lazy<SubAgentRunner>,
    private val toolContextFactory: dagger.Lazy<ToolContextFactory>,
    private val taskRepo: AgentTaskRepository,
    private val conversationRepo: ConversationRepository,
    private val settingsRepo: SettingsRepository
) : AsyncTaskRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _taskFinished = MutableSharedFlow<AgentTask>(extraBufferCapacity = 16)
    val taskFinished: SharedFlow<AgentTask> = _taskFinished

    override suspend fun start(
        conversationId: Long?,
        kind: String,
        title: String,
        prompt: String
    ): Long {
        val task = taskRepo.create(conversationId, kind, title, prompt)
        scope.launch { runTask(task.id) }
        return task.id
    }

    private suspend fun runTask(taskId: Long) {
        val task = taskRepo.getById(taskId) ?: return
        taskRepo.markRunning(taskId)

        val agent = when (task.kind) {
            "weekly_report", "monthly_report" -> SubAgents.byName("review_analyst")
            else -> SubAgents.byName("nutrition_analyst")
        } ?: SubAgents.ALL.first()

        val result = try {
            val config = buildConfig()
            val ctx = toolContextFactory.get().create(
                conversationId = task.conversationId ?: 0L,
                navigator = { false },
                startAsync = { _, _, _, _ -> -1L },   // 异步任务内禁止再开异步任务
                delegate = { _, _ -> "异步任务内不支持再次委派" }
            )
            val handoff = mapOf(
                "goal" to task.title,
                "preferences" to ctx.memoryRepo.formatForContext(),
                "requirement" to task.prompt,
                "output_requirements" to "中文，结构化，按'事实→判断→行动→下一次验证'组织；写入 /reports/ 文件并给出路径",
                "reminders" to "不编造数据；缺失数据标记未知；安全红线优先"
            )
            subAgentRunner.get().run(config, agent, handoff, ctx, maxModelCalls = 10)
        } catch (e: Exception) {
            taskRepo.finish(taskId, AgentTaskStatus.FAILED, e.message)
            _taskFinished.tryEmit(task.copy(status = AgentTaskStatus.FAILED, result = e.message))
            return
        }

        taskRepo.finish(taskId, AgentTaskStatus.DONE, result)

        // 结果自动回传对话（教程要求：不能让用户轮询刷新）
        task.conversationId?.let { cid ->
            if (cid > 0) {
                conversationRepo.appendMessage(
                    conversationId = cid,
                    role = com.example.nutritracker.data.entity.ChatRole.ASSISTANT,
                    content = "后台任务「${task.title}」已完成：\n\n$result",
                    cardType = "task_done",
                    payloadJson = com.google.gson.Gson().toJson(
                        mapOf("task_id" to taskId, "title" to task.title, "status" to "done")
                    )
                )
            }
        }
        _taskFinished.tryEmit(task.copy(status = AgentTaskStatus.DONE, result = result))
    }

    private suspend fun buildConfig(): HarnessConfig {
        val key = settingsRepo.aiApiKey.first()
        val baseUrl = settingsRepo.aiBaseUrl.first().ifBlank { "https://api.deepseek.com" }
        val model = settingsRepo.aiChatModel.first().ifBlank {
            settingsRepo.aiModel.first().ifBlank { "deepseek-chat" }
        }
        val fallback = settingsRepo.aiFallbackModel.first()
        val summary = settingsRepo.aiSummaryModel.first()
        return HarnessConfig(
            apiKey = key, baseUrl = baseUrl, model = model,
            fallbackModel = fallback.ifBlank { null },
            summaryModel = summary.ifBlank { null }
        )
    }
}
