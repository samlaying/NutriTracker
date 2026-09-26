package com.example.nutritracker.harness

import com.example.nutritracker.harness.tools.AgentTool
import com.example.nutritracker.harness.tools.ToolContext
import com.example.nutritracker.harness.tools.ToolRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Agent Harness 主循环（教程世界观：Agent = 模型 + Harness）。
 * 职责：上下文组装（含保底压缩）→ 流式模型调用 → 工具执行（限次/自愈/HITL）→ 回填循环 → 最终回复。
 * TodoList 独立于消息流；大输出卸载；安全门最高优先级。
 */
class AgentHarness(
    private val modelClient: ModelClient,
    private val toolRegistry: ToolRegistry,
    private val limits: HarnessLimits = HarnessLimits(),
    private val compressor: ContextCompressor? = null
) {

    private val gson = Gson()

    /** 内部工具定义（分发在循环内拦截处理，不经注册表） */
    private val internalDefinitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "write_todo",
            description = "创建或更新当前任务清单（用于复杂任务：生成训练计划、周复盘、多步分析等）。传入完整清单替换旧清单；执行过程中持续更新各项状态，让用户随时看到进度。简单任务不要用。",
            parametersJsonSchema = """{
                "type": "object",
                "properties": {
                    "items": {
                        "type": "array",
                        "description": "完整任务清单（覆盖式）",
                        "items": {
                            "type": "object",
                            "properties": {
                                "content": {"type": "string"},
                                "status": {"type": "string", "enum": ["pending", "in_progress", "complete"]}
                            },
                            "required": ["content", "status"]
                        }
                    }
                },
                "required": ["items"]
            }"""
        ),
        ToolDefinition(
            name = "summarize_conversation",
            description = "把当前完整对话历史压缩成持久化摘要（收到长篇子任务报告后、或上下文很长时主动调用；调用后基于摘要继续，不要要求用户重复背景信息）。",
            parametersJsonSchema = """{"type":"object","properties":{}}"""
        )
    )

    data class TurnInput(
        val config: HarnessConfig,
        val systemPrompt: String,
        val history: List<HarnessMessage>,
        val userText: String?,
        val pendingTool: PendingToolCall? = null,
        val resolution: ToolResolution? = null,
        val todoItems: List<TodoItem> = emptyList(),
        val context: ToolContext
    )

    data class TurnResult(
        val reply: String?,
        val todos: List<TodoItem>,
        val summaryText: String? = null,
        val pendingTool: PendingToolCall? = null
    )

    private fun allDefinitions(): List<ToolDefinition> = internalDefinitions + toolRegistry.definitions()

    suspend fun turn(input: TurnInput, emit: suspend (AgentEvent) -> Unit): TurnResult {
        var config = input.config
        var todos = input.todoItems
        var summaryText: String? = null
        val allDefs = allDefinitions()

        // ── 上下文组装：system + （超阈值时保底压缩的）历史 ──
        var history = input.history
        val historyChars = history.sumOf { msg ->
            (msg.content?.length ?: 0) + (msg.toolCalls?.sumOf { it.argumentsJson.length } ?: 0)
        }
        if (historyChars > limits.contextCompressThresholdChars && compressor != null) {
            runCatching { compressor?.summarize(input.config, null, history, null) }
                .getOrNull()?.let { summary ->
                    summaryText = summary
                    history = listOf(HarnessMessage.user("（此前对话已压缩为摘要）\n$summary"))
                }
        }

        // ── HITL 恢复准备：解析用户回答并合并进挂起调用 ──
        // 不依赖模型重发相同 callId（模型每次响应都会生成新 id）：确认/表单路径在下方
        // 直接执行挂起调用并把真实结果作为 tool 消息注入；自由文本只并入说明，
        // 由模型修订参数后重新走确认门；拒绝则以 tool 结果告知模型未执行。
        val resolution = input.resolution ?: ToolResolution.Rejected
        val resumeCall: ToolCall? = input.pendingTool?.let { pending ->
            val mergedArgs = when (resolution) {
                is ToolResolution.FormFilled -> mergeArgs(pending.argumentsJson, resolution.values)
                is ToolResolution.Approved -> pending.argumentsJson
                is ToolResolution.FreeText -> mergeArgs(pending.argumentsJson, mapOf("_user_note" to resolution.text))
                ToolResolution.Rejected -> pending.argumentsJson
            }
            ToolCall(pending.callId, pending.toolName, mergedArgs)
        }
        val resumeConfirmed =
            resumeCall != null && (resolution is ToolResolution.Approved || resolution is ToolResolution.FormFilled)
        val messages = mutableListOf<HarnessMessage>()
        messages += HarnessMessage.system(input.systemPrompt)
        messages += history

        if (resumeCall != null) {
            when {
                resumeConfirmed -> {
                    val (result, nextTodos) = executeCall(
                        resumeCall, input.context, confirmed = true, todos = todos, config = input.config, emit = emit
                    )
                    todos = nextTodos
                    messages += HarnessMessage.tool(resumeCall.id, resumeCall.name, offloadForModel(result.summary))
                }
                resolution is ToolResolution.FreeText -> {
                    // ── 安全门：自由文本同样是用户输入，调用模型前筛查红旗 ──
                    val verdict = SafetyGate.screen(resolution.text)
                    if (verdict.isRedFlag) {
                        emit(AgentEvent.TurnCompleted(verdict.reply))
                        return TurnResult(reply = verdict.reply, todos = todos)
                    }
                    messages += HarnessMessage.tool(
                        resumeCall.id, resumeCall.name,
                        "等待确认的调用未执行。用户补充说明：${resolution.text}。请按说明调整参数后再决定是否调用。"
                    )
                    messages += HarnessMessage.user("（用户补充说明）${resolution.text}")
                }
                else -> {
                    emit(AgentEvent.ToolFinished(resumeCall.id, resumeCall.name, ok = false, summary = "（用户已拒绝，未执行）"))
                    messages += HarnessMessage.tool(
                        resumeCall.id, resumeCall.name,
                        "用户拒绝了本次操作，未执行。请根据用户意图调整方案，或询问希望如何修改。"
                    )
                }
            }
        } else if (input.userText != null) {
            // ── 安全门：模型调用前快速筛查红旗 ──
            val verdict = SafetyGate.screen(input.userText)
            if (verdict.isRedFlag) {
                emit(AgentEvent.TurnCompleted(verdict.reply))
                return TurnResult(reply = verdict.reply, todos = todos)
            }
            messages += HarnessMessage.user(input.userText)
        }

        // ── 主循环 ──
        var modelCalls = 0
        var toolCallCount = 0
        var finalReply: String? = null
        var usedFallback = false

        while (modelCalls < limits.maxModelCalls) {
            modelCalls++

            val content = StringBuilder()
            val toolBuilders = sortedMapOf<Int, ToolCallAcc>()
            var streamError: Throwable? = null
            var completed = false

            try {
                modelClient.streamChat(config, messages, allDefs).collect { ev ->
                    when (ev) {
                        is ModelStreamEvent.ContentDelta -> {
                            content.append(ev.delta)
                            emit(AgentEvent.Token(ev.delta))
                        }
                        is ModelStreamEvent.ToolCallDelta -> {
                            val acc = toolBuilders.getOrPut(ev.index) { ToolCallAcc() }
                            ev.id?.let { acc.id = it }
                            ev.name?.let { acc.name = it }
                            ev.argsDelta?.let { acc.args.append(it) }
                        }
                        is ModelStreamEvent.Completed -> completed = true
                        is ModelStreamEvent.Failed -> streamError = ev.error
                    }
                }
            } catch (e: Exception) {
                streamError = e
            }

            if (streamError != null) {
                // 自愈：换备用模型重试一次
                if (!usedFallback && !config.fallbackModel.isNullOrBlank()) {
                    usedFallback = true
                    config = config.copy(model = config.fallbackModel!!)
                    continue
                }
                emit(AgentEvent.TurnFailed("模型调用失败：${streamError?.message ?: "未知错误"}"))
                return TurnResult(reply = null, todos = todos, summaryText = summaryText)
            }
            if (!completed && toolBuilders.isEmpty() && content.isEmpty()) {
                emit(AgentEvent.TurnFailed("模型返回为空"))
                return TurnResult(reply = null, todos = todos, summaryText = summaryText)
            }

            val toolCalls = toolBuilders.values.map { it.build() }
            emit(AgentEvent.AssistantTurnCompleted(content.toString(), toolCalls))

            if (toolCalls.isEmpty()) {
                finalReply = content.toString()
                break
            }

            messages += HarnessMessage(role = "assistant", content = content.toString(), toolCalls = toolCalls)

            var pending: PendingToolCall? = null
            toolCallLoop@ for ((callIdx, call) in toolCalls.withIndex()) {
                if (toolCallCount >= limits.maxToolCalls) {
                    val note = "本轮工具调用已达上限（${limits.maxToolCalls} 次），熔断保护已触发。请基于已有信息直接回答用户。"
                    emit(AgentEvent.ToolFinished(call.id, call.name, ok = false, summary = note))
                    messages += HarnessMessage.tool(call.id, call.name, note)
                    continue
                }
                toolCallCount++

                val (result, nextTodos) = executeCall(
                    call, input.context,
                    confirmed = !requiresConfirm(call.name),
                    todos = todos, config = config, emit = emit
                )
                todos = nextTodos

                if (result.interrupt != null) {
                    pending = PendingToolCall(
                        callId = call.id,
                        toolName = call.name,
                        argumentsJson = call.argumentsJson,
                        interaction = result.interrupt
                    )
                    emit(AgentEvent.Interrupt(result.interrupt))
                    // 协议完整性：同一 assistant 消息的其余调用补占位结果，
                    // 挂起恢复后模型如仍需要可重新发起
                    for (rest in toolCalls.drop(callIdx + 1)) {
                        val note = "（此调用因等待用户确认被挂起，未执行）"
                        emit(AgentEvent.ToolFinished(rest.id, rest.name, ok = false, summary = note))
                        messages += HarnessMessage.tool(rest.id, rest.name, note)
                    }
                    break@toolCallLoop
                }

                messages += HarnessMessage.tool(call.id, call.name, offloadForModel(result.summary))
            }

            if (pending != null) {
                return TurnResult(reply = null, todos = todos, summaryText = summaryText, pendingTool = pending)
            }
        }

        if (finalReply == null) {
            finalReply = "这个任务比我预期的复杂，已执行到调用上限，先停在这里。已完成的部分都在对话里；你可以对我说\"继续\"，或把任务拆小一点再试。"
        }

        emit(AgentEvent.TurnCompleted(finalReply))
        return TurnResult(reply = finalReply, todos = todos, summaryText = summaryText)
    }

    // ── 工具分发 ──

    private fun requiresConfirm(toolName: String): Boolean =
        toolRegistry.byName(toolName)?.requiresConfirmation ?: false

    private suspend fun dispatchRegistryTool(
        call: ToolCall,
        ctx: ToolContext,
        confirmed: Boolean
    ): ToolResult {
        val tool: AgentTool = toolRegistry.byName(call.name)
            ?: return ToolResult.failure(
                "未知工具 ${call.name}。可用工具：${(toolRegistry.definitions().map { it.name } + listOf("write_todo", "summarize_conversation")).joinToString()}"
            )
        val args = parseArgs(call.argumentsJson)
        if (tool.requiresConfirmation && !confirmed) {
            return ToolResult(
                summary = "等待用户确认",
                interrupt = PendingInteraction.ConfirmWrite(
                    title = "确认执行：${tool.description.take(60)}",
                    detail = tool.describeCall(args)
                )
            )
        }
        return try {
            tool.execute(args, ctx)
        } catch (e: Exception) {
            ToolResult.failure("工具执行失败：${e.message ?: e.javaClass.simpleName}。请根据错误修正参数重试，或改用其他工具。")
        }
    }

    /** 统一的单次工具执行：事件、内部分发、todos 传播。恢复路径与主循环共用。 */
    private suspend fun executeCall(
        call: ToolCall,
        ctx: ToolContext,
        confirmed: Boolean,
        todos: List<TodoItem>,
        config: HarnessConfig,
        emit: suspend (AgentEvent) -> Unit
    ): Pair<ToolResult, List<TodoItem>> {
        emit(
            AgentEvent.ToolStarted(
                callId = call.id,
                toolName = call.name,
                argsSummary = argsPreview(call.argumentsJson)
            )
        )
        var nextTodos = todos
        val result = when (call.name) {
            "write_todo" -> handleWriteTodo(call.argumentsJson, ctx, todos)
                .also { nextTodos = it.second }
                .first
            "summarize_conversation" -> handleSummarize(config, ctx)
            else -> dispatchRegistryTool(call, ctx, confirmed)
        }
        emit(
            AgentEvent.ToolFinished(
                callId = call.id,
                toolName = call.name,
                ok = result.success && result.interrupt == null,
                summary = result.summary,
                cardType = result.cardType,
                payloadJson = result.payloadJson
            )
        )
        return result to nextTodos
    }

    /** 卸载大输出：完整结果已随事件进 UI，模型只收摘要 */
    private fun offloadForModel(summary: String): String =
        if (summary.length > limits.toolOutputOffloadChars) {
            summary.take(2000) + "\n…（结果过长已截断，完整内容已展示给用户）"
        } else {
            summary
        }

    private suspend fun handleWriteTodo(
        argsJson: String,
        ctx: ToolContext,
        previous: List<TodoItem>
    ): Pair<ToolResult, List<TodoItem>> {
        val items = try {
            val obj = JsonParser.parseString(argsJson).asJsonObject
            val arr = obj.getAsJsonArray("items")
            arr?.mapIndexed { idx, el ->
                val o = el.asJsonObject
                TodoItem(
                    id = "t${idx + 1}",
                    content = o.get("content")?.takeIf { !it.isJsonNull }?.asString ?: "任务 ${idx + 1}",
                    status = (o.get("status")?.takeIf { !it.isJsonNull }?.asString ?: "pending").let {
                        if (it in setOf("pending", "in_progress", "complete")) it else "pending"
                    }
                )
            } ?: emptyList()
        } catch (_: Exception) {
            return Pair(ToolResult.failure("write_todo 参数解析失败：${argsJson.take(200)}"), previous)
        }
        ctx.conversationRepo.updateTodo(ctx.conversationId, TodoItem.toJsonList(items))
        return Pair(
            ToolResult.ok("任务清单已更新，共 ${items.size} 项。继续执行后续步骤，并在推进时更新状态。"),
            items
        )
    }

    private suspend fun handleSummarize(config: HarnessConfig, ctx: ToolContext): ToolResult {
        val compressor = this.compressor ?: return ToolResult.failure("摘要能力未配置")
        val allMessages = ctx.conversationRepo.getMessages(ctx.conversationId)
        val wire = allMessages.mapNotNull { msg ->
            when (msg.role) {
                com.example.nutritracker.data.entity.ChatRole.USER -> HarnessMessage.user(msg.content)
                com.example.nutritracker.data.entity.ChatRole.ASSISTANT -> HarnessMessage(role = "assistant", content = msg.content)
                com.example.nutritracker.data.entity.ChatRole.TOOL -> HarnessMessage.tool(
                    callId = msg.toolCallId ?: "unknown",
                    toolName = msg.toolName ?: "unknown",
                    text = msg.content
                )
            }
        }
        return try {
            val summary = compressor.summarize(config, null, wire, null)
            ctx.conversationRepo.updateSummary(ctx.conversationId, summary)
            ToolResult.ok("对话已压缩为摘要并保存。后续请基于摘要继续。")
        } catch (e: Exception) {
            ToolResult.failure("摘要生成失败：${e.message}")
        }
    }

    // ── 辅助 ──

    private class ToolCallAcc {
        var id: String = "call_${System.nanoTime()}"
        var name: String = ""
        val args = StringBuilder()

        fun build(): ToolCall = ToolCall(
            id = id.ifBlank { "call_${System.nanoTime()}" },
            name = name,
            argumentsJson = if (args.isBlank()) "{}" else args.toString()
        )
    }

    private fun parseArgs(json: String): JsonObject = try {
        JsonParser.parseString(json.ifBlank { "{}" }).asJsonObject
    } catch (_: Exception) {
        JsonObject()
    }

    private fun mergeArgs(originalJson: String, values: Map<String, String>): String {
        val obj = parseArgs(originalJson)
        values.forEach { (k, v) -> obj.addProperty(k, v) }
        return gson.toJson(obj)
    }

    private fun argsPreview(json: String): String = runCatching {
        parseArgs(json).entrySet().take(4).joinToString("，") { (k, v) -> "$k=${v.toString().take(40)}" }
    }.getOrDefault(json.take(80))
}

/** 上下文压缩器（主动摘要工具与保底压缩共用） */
interface ContextCompressor {
    suspend fun summarize(config: HarnessConfig, priorSummary: String?, messages: List<HarnessMessage>, userText: String?): String
}

/** 模型驱动的摘要实现（用摘要小模型，空则回落主模型） */
class ModelContextCompressor(
    private val modelClient: ModelClient
) : ContextCompressor {
    override suspend fun summarize(config: HarnessConfig, priorSummary: String?, messages: List<HarnessMessage>, userText: String?): String {
        val summaryConfig = config.copy(
            model = config.summaryModel?.takeIf { it.isNotBlank() } ?: config.model,
            maxTokens = 1200
        )
        val transcript = messages.joinToString("\n") { msg ->
            val roleLabel = when (msg.role) {
                "user" -> "用户"
                "assistant" -> "教练"
                else -> msg.role
            }
            "$roleLabel: ${msg.content ?: "(工具调用)"}"
        }.take(60_000)
        val prompt = buildString {
            append("你是健身营养教练 Agent 的上下文压缩器。把下面的对话历史压缩成一份摘要，供下一轮对话作为唯一背景。")
            if (!priorSummary.isNullOrBlank()) append("\n已有更早的摘要：\n$priorSummary")
            append("\n\n要求：\n1. 保留用户的关键事实（目标、伤病、偏好、已记录的数据、已做的决定、未完成的任务）\n2. 保留关键数字（体重、热量、组次重量）\n3. 删除寒暄与重复\n4. 中文，800 字以内，条目式\n\n对话历史：\n$transcript")
        }
        return modelClient.chat(
            config = summaryConfig,
            messages = listOf(HarnessMessage.user(prompt)),
            temperature = 0.2,
            maxTokens = 1200
        ).content ?: throw IllegalStateException("摘要返回为空")
    }
}
