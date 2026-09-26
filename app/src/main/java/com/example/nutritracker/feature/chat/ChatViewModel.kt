package com.example.nutritracker.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nutritracker.data.ChatImageStore
import com.example.nutritracker.data.entity.ChatMessage
import com.example.nutritracker.data.entity.ChatRole
import com.example.nutritracker.data.entity.IntakeType
import com.example.nutritracker.data.entity.User
import com.example.nutritracker.data.repository.ConversationRepository
import com.example.nutritracker.data.repository.MemoryRepository
import com.example.nutritracker.data.repository.SettingsRepository
import com.example.nutritracker.data.repository.UserRepository
import com.example.nutritracker.feature.camera.AiAnalysisManager
import com.example.nutritracker.harness.AgentEvent
import com.example.nutritracker.harness.AgentHarness
import com.example.nutritracker.harness.HarnessConfig
import com.example.nutritracker.harness.MemoryWriter
import com.example.nutritracker.harness.PendingToolCall
import com.example.nutritracker.harness.SkillRegistry
import com.example.nutritracker.harness.SubAgentRunner
import com.example.nutritracker.harness.SubAgents
import com.example.nutritracker.harness.SystemPromptBuilder
import com.example.nutritracker.harness.ToolCall
import com.example.nutritracker.harness.TodoItem
import com.example.nutritracker.harness.ToolResolution
import com.example.nutritracker.navigation.Screen
import com.example.nutritracker.data.repository.ActivityRepository
import com.example.nutritracker.data.repository.IntakeRepository
import com.example.nutritracker.data.repository.MealRepository
import com.example.nutritracker.data.repository.TrackedDayRepository
import com.example.nutritracker.data.repository.WaterIntakeRepository
import com.example.nutritracker.util.DayBoundaryCalc
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject

data class RunningTool(val callId: String, val name: String, val argsSummary: String)

data class LiveTurn(
    val isRunning: Boolean = false,
    val streamText: String = "",
    val runningTools: List<RunningTool> = emptyList()
)

data class ChatUiState(
    val isLoading: Boolean = true,
    val messages: List<ChatMessage> = emptyList(),
    val conversationId: Long = 0,
    val todoItems: List<TodoItem> = emptyList(),
    val pendingTool: PendingToolCall? = null,
    val live: LiveTurn = LiveTurn()
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val settingsRepo: SettingsRepository,
    private val userRepo: UserRepository,
    private val memoryRepo: MemoryRepository,
    private val toolContextFactory: ToolContextFactory,
    private val harness: AgentHarness,
    private val promptBuilder: SystemPromptBuilder,
    private val memoryWriter: MemoryWriter,
    private val subAgentRunner: SubAgentRunner,
    private val skillRegistry: SkillRegistry,
    private val aiAnalysisManager: AiAnalysisManager,
    private val asyncTaskService: AsyncTaskService,
    private val intakeRepo: IntakeRepository,
    private val mealRepo: MealRepository,
    private val trackedDayRepo: TrackedDayRepository,
    private val waterRepo: WaterIntakeRepository,
    private val activityRepo: ActivityRepository,
    private val dayBoundaryCalc: DayBoundaryCalc,
    private val chatImageStore: ChatImageStore
) : ViewModel() {

    private val gson = Gson()
    private val turnMutex = Mutex()

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val aiIsAnalyzing: StateFlow<Boolean> = aiAnalysisManager.isAnalyzing

    private var conversationId: Long = 0

    init {
        viewModelScope.launch {
            val conversation = conversationRepo.ensureConversation()
            conversationId = conversation.id
            val messages = conversationRepo.getMessages(conversation.id)
            if (messages.isEmpty()) {
                conversationRepo.appendMessage(
                    conversationId = conversation.id,
                    role = ChatRole.ASSISTANT,
                    content = buildGreeting(userRepo.getUser())
                )
            }
            refreshMessages()
        }
        // 拍照识别结果自动回写对话
        viewModelScope.launch {
            aiAnalysisManager.analysisSuccess.collect { msg ->
                conversationRepo.appendMessage(
                    conversationId = conversationId,
                    role = ChatRole.ASSISTANT,
                    content = "拍照识别完成：$msg\n\n你可以继续问我这一餐怎么搭配，或让我看看今天还剩多少额度。",
                    cardType = "photo_analysis"
                )
                refreshMessages()
            }
        }
        viewModelScope.launch {
            aiAnalysisManager.analysisError.collect { err ->
                if (err != null) {
                    conversationRepo.appendMessage(
                        conversationId = conversationId,
                        role = ChatRole.ASSISTANT,
                        content = "拍照识别失败了：$err。可以改用文字记录，比如「记录午餐：米饭200g加鸡腿一个」。",
                        cardType = null
                    )
                    aiAnalysisManager.clearError()
                    refreshMessages()
                }
            }
        }
    }

    fun refreshMessages() {
        viewModelScope.launch {
            if (conversationId == 0L) return@launch
            val conversation = conversationRepo.getById(conversationId) ?: return@launch
            val messages = conversationRepo.getMessages(conversationId)
            _state.update {
                it.copy(
                    isLoading = false,
                    messages = messages,
                    todoItems = TodoItem.fromJsonList(conversation.todoJson),
                    pendingTool = pendingFromJson(conversation.pendingToolJson)
                )
            }
        }
    }

    private fun buildGreeting(user: User?): String = buildString {
        appendLine("你好，我是你的数字教练 💪")
        appendLine()
        if (user != null) {
            val direction = when (user.weightGoal.name) {
                "LOSE" -> "减重"; "GAIN" -> "增重"; else -> "维持"
            }
            appendLine("已读取你的资料（${direction}方向）。你可以直接用自然语言指挥我：")
        } else {
            appendLine("先到「我的」页完成基础资料会更准；不过现在就可以用自然语言指挥我：")
        }
        appendLine()
        appendLine("· 「今天还能吃多少？」")
        appendLine("· 「记录午餐：鸡胸肉沙拉一份」")
        appendLine("· 「帮我制定一份 4 周增肌训练计划」")
        appendLine("· 点输入框的相机按钮，拍照记录一餐")
        appendLine()
        appendLine("我会读写你的饮食与训练数据、引用内置科学文献；出现身体不适信号时，安全永远排在训练前面。")
    }.trimEnd()

    // ── 发送 ──

    fun send(text: String) {
        if (text.isBlank() || _state.value.live.isRunning) return
        viewModelScope.launch {
            turnMutex.withLock {
                runTurn(userText = text.trim(), pendingTool = null, resolution = null)
            }
        }
    }

    /** 回答挂起的人工介入（表单/确认/自由文本） */
    fun answerTool(resolution: ToolResolution) {
        if (_state.value.live.isRunning) return
        viewModelScope.launch {
            turnMutex.withLock {
                val pending = _state.value.pendingTool ?: return@withLock
                runTurn(userText = null, pendingTool = pending, resolution = resolution)
            }
        }
    }

    private suspend fun runTurn(
        userText: String?,
        pendingTool: PendingToolCall?,
        resolution: ToolResolution?
    ) {
        val conversation = conversationRepo.getById(conversationId) ?: return

        val newUserMessage = userText?.let {
            conversationRepo.appendMessage(conversationId, ChatRole.USER, it)
        }

        val config = buildConfig()
        if (config.apiKey.isBlank()) {
            conversationRepo.appendMessage(
                conversationId, ChatRole.ASSISTANT,
                "还没有配置 AI 服务。请到 设置 → 通用 AI 配置 填入 API Key（默认支持 DeepSeek 等 OpenAI 兼容接口），然后回来继续。"
            )
            refreshMessages()
            return
        }

        val todos = TodoItem.fromJsonList(conversation.todoJson)

        var ctxRef: com.example.nutritracker.harness.tools.ToolContext? = null
        val delegate: suspend (String, Map<String, String>) -> String = { agentName, handoff ->
            val ctx = ctxRef
            val agent = SubAgents.byName(agentName)
            when {
                ctx == null -> "内部错误：工具上下文未就绪"
                agent == null -> "子 Agent $agentName 不存在"
                else -> subAgentRunner.run(config, agent, handoff, ctx)
            }
        }
        val toolCtx = toolContextFactory.create(
            conversationId = conversationId,
            navigator = { route -> openRoute(route) },
            startAsync = { cid, kind, title, prompt -> asyncTaskService.start(cid, kind, title, prompt) },
            delegate = delegate
        )
        ctxRef = toolCtx

        val systemPrompt = promptBuilder.build(toolCtx, skillRegistry, todos)
        val history = historyForTurn(
            conversationRepo.getMessages(conversationId),
            newlyAddedUserMessageId = newUserMessage?.id
        ).map { it.toWire() }

        _state.update { it.copy(live = LiveTurn(isRunning = true), pendingTool = null) }
        conversationRepo.updatePendingTool(conversationId, null)

        val result = harness.turn(
            input = AgentHarness.TurnInput(
                config = config,
                systemPrompt = systemPrompt,
                history = history,
                userText = userText,
                pendingTool = pendingTool,
                resolution = resolution,
                todoItems = todos,
                context = toolCtx
            )
        ) { event -> handleEvent(event) }

        // 持久化 turn 终态
        conversationRepo.updateTodo(conversationId, TodoItem.toJsonList(result.todos))
        result.summaryText?.let { conversationRepo.updateSummary(conversationId, it) }
        if (result.pendingTool != null) {
            conversationRepo.updatePendingTool(conversationId, gson.toJson(result.pendingTool))
        }
        _state.update {
            it.copy(
                live = LiveTurn(),
                todoItems = result.todos,
                pendingTool = result.pendingTool
            )
        }
        refreshMessages()

        // 记忆回写中间件（对话后自动沉淀稳定偏好）
        try {
            memoryWriter.afterTurn(config, memoryRepo, userText, result.reply)
        } catch (_: Exception) {
        }
    }

    private suspend fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Token -> {
                _state.update { it.copy(live = it.live.copy(streamText = it.live.streamText + event.delta)) }
            }
            is AgentEvent.AssistantTurnCompleted -> {
                conversationRepo.appendMessage(
                    conversationId = conversationId,
                    role = ChatRole.ASSISTANT,
                    content = event.content,
                    toolCallJson = if (event.toolCalls.isNotEmpty()) gson.toJson(event.toolCalls) else null
                )
                _state.update { it.copy(live = it.live.copy(streamText = "")) }
                refreshMessages()
            }
            is AgentEvent.ToolStarted -> {
                _state.update {
                    it.copy(
                        live = it.live.copy(
                            runningTools = it.live.runningTools + RunningTool(event.callId, event.toolName, event.argsSummary)
                        )
                    )
                }
            }
            is AgentEvent.ToolFinished -> {
                conversationRepo.appendMessage(
                    conversationId = conversationId,
                    role = ChatRole.TOOL,
                    content = event.summary,
                    toolCallId = event.callId,
                    toolName = event.toolName,
                    cardType = event.cardType,
                    payloadJson = event.payloadJson
                )
                _state.update {
                    it.copy(
                        live = it.live.copy(
                            runningTools = it.live.runningTools.filterNot { t -> t.callId == event.callId }
                        )
                    )
                }
                refreshMessages()
            }
            is AgentEvent.TodoUpdated -> {
                _state.update { it.copy(todoItems = event.items) }
            }
            is AgentEvent.Interrupt -> Unit // 终态由 runTurn 持久化
            is AgentEvent.TurnCompleted -> {
                if (!event.reply.isNullOrBlank()) {
                    conversationRepo.appendMessage(conversationId, ChatRole.ASSISTANT, event.reply)
                }
            }
            is AgentEvent.TurnFailed -> {
                conversationRepo.appendMessage(
                    conversationId, ChatRole.ASSISTANT,
                    "（这一轮出了点问题：${event.message}。你可以重试，或到 设置 → 通用 AI 配置 检查服务状态。）"
                )
            }
        }
    }

    // ── 相机接回：拍照识别 → 结果自动回写对话 ──

    fun handlePhotoUris(
        context: android.content.Context,
        uris: List<android.net.Uri>,
        intakeType: IntakeType,
        notes: String
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val storedImagePath = chatImageStore.persist(uris.first())
            conversationRepo.appendMessage(
                conversationId = conversationId,
                role = ChatRole.USER,
                content = "（拍照记录${intakeTypeLabel(intakeType)}：${uris.size} 张图）",
                imagePath = storedImagePath
            )
            refreshMessages()
        }
        aiAnalysisManager.analyzeAndCreateMeals(context, uris, intakeType, notes, LocalDate.now())
    }

    // ── 卡片撤销（直接写入的可逆性保障） ──

    private fun payloadOf(json: String?): JsonObject? = runCatching {
        json?.let { JsonParser.parseString(it).asJsonObject }
    }.getOrNull()

    fun undoMealLogged(payloadJson: String?) {
        val mealId = payloadOf(payloadJson)?.get("meal_id")?.asLong ?: return
        viewModelScope.launch {
            val intake = intakeRepo.getByMealId(mealId) ?: return@launch
            val meal = mealRepo.getById(intake.mealId)
            if (meal != null) {
                val offset = settingsRepo.dayBoundaryMinutes.first()
                val day = dayBoundaryCalc.logicalDayOf(intake.dateTime, offset)
                trackedDayRepo.removeCalories(
                    day,
                    meal.energyKcal100 * intake.amount / 100.0,
                    meal.carbohydrates100 * intake.amount / 100.0,
                    meal.fat100 * intake.amount / 100.0,
                    meal.proteins100 * intake.amount / 100.0
                )
            }
            intakeRepo.delete(intake)
            mealRepo.delete(mealRepo.getById(mealId) ?: return@launch)
            conversationRepo.appendMessage(conversationId, ChatRole.TOOL, "（用户已撤销这条饮食记录）", toolName = "undo")
            refreshMessages()
        }
    }

    fun undoWaterLogged(payloadJson: String?) {
        val waterId = payloadOf(payloadJson)?.get("water_id")?.asLong ?: return
        viewModelScope.launch {
            waterRepo.deleteById(waterId)
            conversationRepo.appendMessage(conversationId, ChatRole.TOOL, "（用户已撤销这条饮水记录）", toolName = "undo")
            refreshMessages()
        }
    }

    fun undoActivityLogged(payloadJson: String?) {
        val activityId = payloadOf(payloadJson)?.get("activity_id")?.asLong ?: return
        viewModelScope.launch {
            activityRepo.deleteById(activityId)
            conversationRepo.appendMessage(conversationId, ChatRole.TOOL, "（用户已撤销这条运动记录）", toolName = "undo")
            refreshMessages()
        }
    }

    fun openTraining() {
        _pendingNavigation.value = Screen.Training.route
    }

    // ── 内部工具 ──

    private suspend fun buildConfig(): HarnessConfig {
        val key = settingsRepo.aiApiKey.first()
        val baseUrl = settingsRepo.aiBaseUrl.first().ifBlank { "https://api.deepseek.com" }
        val model = settingsRepo.aiChatModel.first().ifBlank {
            settingsRepo.aiModel.first().ifBlank { "deepseek-chat" }
        }
        return HarnessConfig(
            apiKey = key,
            baseUrl = baseUrl,
            model = model,
            fallbackModel = settingsRepo.aiFallbackModel.first().ifBlank { null },
            summaryModel = settingsRepo.aiSummaryModel.first().ifBlank { null }
        )
    }

    /** Agent 的 open_screen 路由 → 真实 Navigation 路由 */
    private fun openRoute(route: String): Boolean {
        val target = when (route) {
            "camera" -> Screen.CameraCapture.createRoute(0)
            "add_meal" -> Screen.AddMeal.createRoute(0)
            "add_activity" -> Screen.AddActivity.route
            "training" -> Screen.Training.route
            "weight_history" -> Screen.WeightHistory.route
            "sources" -> Screen.Sources.route
            "settings" -> Screen.Settings.route
            else -> return false
        }
        _pendingNavigation.value = target
        return true
    }

    private val _pendingNavigation = MutableStateFlow<String?>(null)
    val pendingNavigation: StateFlow<String?> = _pendingNavigation.asStateFlow()

    fun consumeNavigation() {
        _pendingNavigation.value = null
    }
}

private fun pendingFromJson(json: String?): PendingToolCall? {
    if (json.isNullOrBlank()) return null
    return try {
        Gson().fromJson(json, PendingToolCall::class.java)
    } catch (_: Exception) {
        null
    }
}

private fun ChatMessage.toWire(): com.example.nutritracker.harness.HarnessMessage = when (role) {
    ChatRole.USER -> com.example.nutritracker.harness.HarnessMessage.user(content)
    ChatRole.ASSISTANT -> {
        val calls: List<ToolCall>? = toolCallJson?.let {
            try {
                Gson().fromJson(it, Array<ToolCall>::class.java).toList()
            } catch (_: Exception) {
                null
            }
        }
        com.example.nutritracker.harness.HarnessMessage(
            role = "assistant",
            content = content,
            toolCalls = calls?.takeIf { it.isNotEmpty() }
        )
    }
    ChatRole.TOOL -> com.example.nutritracker.harness.HarnessMessage.tool(
        callId = toolCallId ?: "unknown",
        toolName = toolName ?: "unknown",
        text = content
    )
}

private fun intakeTypeLabel(type: IntakeType): String = when (type) {
    IntakeType.BREAKFAST -> "早餐"
    IntakeType.LUNCH -> "午餐"
    IntakeType.DINNER -> "晚餐"
    IntakeType.SNACK -> "零食"
}

private fun ageOf(user: User): Int = Period.between(user.birthday, LocalDate.now()).years
