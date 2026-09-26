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
import com.example.nutritracker.application.media.MealPhotoAnalyzer
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
import com.example.nutritracker.harness.historyWithoutToolResultFor
import com.example.nutritracker.harness.tools.TodaySummaryBuilder
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
    val todaySummary: ChatTodaySummary? = null,
    val live: LiveTurn = LiveTurn()
)

data class ChatTodaySummary(
    val calorieGoal: Double,
    val caloriesSupplied: Double,
    val caloriesBurned: Double,
    val carbsGoal: Double,
    val carbsTracked: Double,
    val fatGoal: Double,
    val fatTracked: Double,
    val proteinGoal: Double,
    val proteinTracked: Double,
    val waterMl: Int,
    val waterGoalMl: Int
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
    private val photoAnalyzer: MealPhotoAnalyzer,
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

    val aiIsAnalyzing: StateFlow<Boolean> = photoAnalyzer.isAnalyzing

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
            refreshTodaySummary()

            // 拍照识别结果自动回写对话（等 conversationId 就绪后再挂，避免写入孤儿会话的竞态）
            launch {
                photoAnalyzer.analysisSuccess.collect { msg ->
                    conversationRepo.appendMessage(
                        conversationId = conversationId,
                        role = ChatRole.ASSISTANT,
                        content = "拍照识别完成：$msg\n\n你可以继续问我这一餐怎么搭配，或让我看看今天还剩多少额度。",
                        cardType = "photo_analysis"
                    )
                    refreshMessages()
                    refreshTodaySummary()
                }
            }
            launch {
                photoAnalyzer.analysisError.collect { err ->
                    if (err != null) {
                        conversationRepo.appendMessage(
                            conversationId = conversationId,
                            role = ChatRole.ASSISTANT,
                            content = "拍照识别失败了：$err。可以改用文字记录，比如「记录午餐：米饭200g加鸡腿一个」。",
                            cardType = null
                        )
                        photoAnalyzer.clearError()
                        refreshMessages()
                    }
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

    fun refreshTodaySummary() {
        viewModelScope.launch {
            if (conversationId == 0L) return@launch
            val context = toolContextFactory.create(
                conversationId = conversationId,
                navigator = { false },
                startAsync = { _, _, _, _ -> -1L },
                delegate = { _, _ -> "" }
            )
            val numbers = TodaySummaryBuilder(context).numbers()
            _state.update {
                it.copy(todaySummary = ChatTodaySummary(
                    calorieGoal = numbers.calorieGoal,
                    caloriesSupplied = numbers.caloriesSupplied,
                    caloriesBurned = numbers.caloriesBurned,
                    carbsGoal = numbers.carbsGoal,
                    carbsTracked = numbers.carbsTracked,
                    fatGoal = numbers.fatGoal,
                    fatTracked = numbers.fatTracked,
                    proteinGoal = numbers.proteinGoal,
                    proteinTracked = numbers.proteinTracked,
                    waterMl = numbers.waterMl,
                    waterGoalMl = numbers.waterGoalMl
                ))
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
            val outcome = turnMutex.withLock {
                runTurn(userText = text.trim(), pendingTool = null, resolution = null)
            }
            rememberAfterTurn(outcome)
        }
    }

    /** 回答挂起的人工介入（表单/确认/自由文本） */
    fun answerTool(resolution: ToolResolution) {
        if (_state.value.live.isRunning) return
        viewModelScope.launch {
            val outcome = turnMutex.withLock {
                val pending = _state.value.pendingTool ?: return@withLock TurnOutcome(null, null)
                runTurn(userText = null, pendingTool = pending, resolution = resolution)
            }
            rememberAfterTurn(outcome)
        }
    }

    /** 一轮对话的输出：记忆回写只关心用户输入与最终回复 */
    private data class TurnOutcome(val userText: String?, val reply: String?)

    /** 记忆回写在 turnMutex 外异步执行：不阻塞下一轮对话（失败静默，下一轮注入仍可用） */
    private fun rememberAfterTurn(outcome: TurnOutcome) {
        if (outcome.userText == null) return
        viewModelScope.launch {
            try {
                memoryWriter.afterTurn(buildConfig(), memoryRepo, outcome.userText, outcome.reply)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun runTurn(
        userText: String?,
        pendingTool: PendingToolCall?,
        resolution: ToolResolution?
    ): TurnOutcome {
        val conversation = conversationRepo.getById(conversationId) ?: return TurnOutcome(null, null)

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
            return TurnOutcome(userText, null)
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
        val storedMessages = conversationRepo.getMessages(conversationId)
        val historyMessages = historyForTurn(
            storedMessages,
            newlyAddedUserMessageId = newUserMessage?.id,
            summaryThroughMessageId = conversation.summaryThroughMessageId
        )
            .let { messages -> pendingTool?.let { historyWithoutToolResultFor(messages, it.callId) } ?: messages }
            .let(::latestToolResultsOnly)
        val historyThroughMessageId = listOfNotNull(
            conversation.summaryThroughMessageId,
            historyMessages.maxOfOrNull { it.id }
        ).maxOrNull()
        val history = historyMessages
            .map { it.toWire() }
            .let { messages ->
                conversation.summary
                    ?.takeIf { it.isNotBlank() }
                    ?.let { listOf(com.example.nutritracker.harness.HarnessMessage.user("（此前对话摘要）\n$it")) + messages }
                    ?: messages
            }

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
                context = toolCtx,
                historyThroughMessageId = historyThroughMessageId
            )
        ) { event -> handleEvent(event) }

        // 持久化 turn 终态
        conversationRepo.updateTodo(conversationId, TodoItem.toJsonList(result.todos))
        result.summaryText?.let {
            conversationRepo.updateSummary(conversationId, it, result.summaryThroughMessageId)
        }
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

        return TurnOutcome(userText, result.reply)
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
                if (event.cardType in setOf("meal_logged", "water_logged", "activity_logged", "weight_logged")) {
                    refreshTodaySummary()
                }
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
            // 全部图片都持久化：首张进 imagePath（兼容单图渲染），
            // 完整列表进 payloadJson.images，多图渲染与备份恢复都以此为准
            val storedPaths = chatImageStore.persistAll(uris)
            conversationRepo.appendMessage(
                conversationId = conversationId,
                role = ChatRole.USER,
                content = "（拍照记录${intakeTypeLabel(intakeType)}：${uris.size} 张图）",
                imagePath = storedPaths.firstOrNull(),
                payloadJson = if (storedPaths.size > 1) gson.toJson(mapOf("images" to storedPaths)) else null
            )
            refreshMessages()
        }
        photoAnalyzer.analyzeAndCreateMeals(context, uris, intakeType, notes, LocalDate.now())
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
            meal?.let { mealRepo.delete(it) }
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
