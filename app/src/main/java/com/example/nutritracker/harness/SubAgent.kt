package com.example.nutritracker.harness

import com.example.nutritracker.harness.tools.ToolContext
import com.example.nutritracker.harness.tools.ToolRegistry
import com.google.gson.JsonParser

/**
 * 子 Agent（教程 Multi-Agent：主 Agent 是协调者不是执行者，子 Agent 上下文完全隔离）。
 * 配置化定义（≈教程 YAML + loader）：新增一个子 Agent = 加一个 SubAgentConfig。
 */
data class SubAgentConfig(
    val name: String,
    val title: String,
    val description: String,
    val systemPrompt: String,
    val allowedTools: List<String> = emptyList()
)

/** 默认子 Agent 群（按业务域拆分） */
object SubAgents {
    val ALL = listOf(
        SubAgentConfig(
            name = "nutrition_analyst",
            title = "营养分析师",
            description = "分析饮食记录、营养结构、热量趋势等数据密集任务",
            systemPrompt = "你是营养数据分析专家。基于交接给你的任务与数据完成分析，输出结论与依据。数据不足时明确说明缺什么，不要编造。数字必须来自交接的数据或工具返回，禁止臆造。",
            allowedTools = listOf("get_today_summary", "get_recent_records", "get_weight_trend")
        ),
        SubAgentConfig(
            name = "training_planner",
            title = "训练计划师",
            description = "生成或调整周期化训练计划（周结构、动作编排、进阶规则）",
            systemPrompt = "你是训练计划设计专家。基于交接的目标、经验、频率、器械与限制设计训练计划。动作选择匹配器械条件；必须包含热身、最低任务版本与进阶/降级规则；重量不臆造，给选择逻辑。",
            allowedTools = listOf("get_training_plan", "get_recent_records")
        ),
        SubAgentConfig(
            name = "review_analyst",
            title = "复盘分析师",
            description = "按'事实→判断→行动→下一次验证'生成周期复盘",
            systemPrompt = "你是训练营养复盘专家。按'事实→判断→行动→下一次验证'组织输出；缺失日期、混合单位或记录不足时明确标记未知，不制造趋势或平台期结论。",
            allowedTools = listOf("get_today_summary", "get_recent_records", "get_weight_trend", "get_training_plan")
        )
    )

    fun byName(name: String): SubAgentConfig? = ALL.firstOrNull { it.name == name }
}

/**
 * 子 Agent 执行器：独立 messages（上下文隔离），受限工具集，非流式。
 * 教程 AsyncSubAgent 的同步侧；异步长任务由 AsyncTaskRunner 包装同一执行器。
 */
class SubAgentRunner(
    private val modelClient: ModelClient,
    private val registry: ToolRegistry
) {
    /**
     * handoff 5 要素（教程委派约定）：任务目标 / 用户偏好 / 需求正文 / 输出要求 / 重要提醒
     */
    suspend fun run(
        config: HarnessConfig,
        agent: SubAgentConfig,
        handoff: Map<String, String>,
        ctx: ToolContext,
        maxModelCalls: Int = 8
    ): String {
        var activeConfig = config
        val messages = mutableListOf(
            HarnessMessage.system(agent.systemPrompt + "\n\n" + SafetyGate.RULES),
            HarnessMessage.user(formatHandoff(handoff))
        )
        val allowed = agent.allowedTools.toSet()
        val tools = registry.definitions().filter { it.name in allowed }

        var modelCalls = 0
        var usedFallback = false
        while (modelCalls < maxModelCalls) {
            modelCalls++
            val reply = try {
                modelClient.chat(activeConfig, messages, tools, temperature = 0.4, maxTokens = 3000)
            } catch (e: Exception) {
                if (!usedFallback && !activeConfig.fallbackModel.isNullOrBlank()) {
                    usedFallback = true
                    activeConfig = activeConfig.copy(model = activeConfig.fallbackModel!!)
                    continue
                }
                return "子任务执行失败：${e.message ?: "未知错误"}"
            }

            val toolCalls = reply.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                return reply.content ?: "子任务没有产出内容"
            }
            messages += reply
            for (call in toolCalls) {
                val tool = registry.byName(call.name)
                val result = when {
                    tool == null -> ToolResult.failure("未知工具 ${call.name}")
                    tool.requiresConfirmation -> ToolResult.failure(
                        "该工具需要用户在主对话中确认，子任务里不可用。请把拟执行的操作与参数写在结论里返回。"
                    )
                    else -> try {
                        tool.execute(parseArgs(call.argumentsJson), ctx)
                    } catch (e: Exception) {
                        ToolResult.failure("工具执行失败：${e.message}")
                    }
                }
                messages += HarnessMessage.tool(call.id, call.name, result.summary.take(4000))
            }
        }
        val lastTool = messages.lastOrNull { it.role == "tool" }?.content ?: ""
        return "子任务达到调用上限，阶段性结果：$lastTool"
    }

    private fun formatHandoff(handoff: Map<String, String>): String = buildString {
        append("【任务目标】").appendLine(handoff["goal"] ?: "（未指定）")
        append("【用户偏好】").appendLine(handoff["preferences"] ?: "（无特别偏好）")
        append("【需求正文】").appendLine(handoff["requirement"] ?: "")
        append("【输出要求】").appendLine(handoff["output_requirements"] ?: "中文，结构化，给出可执行结论")
        append("【重要提醒】").appendLine(handoff["reminders"] ?: "无")
    }.trim()

    private fun parseArgs(json: String) = try {
        JsonParser.parseString(json.ifBlank { "{}" }).asJsonObject
    } catch (_: Exception) {
        com.google.gson.JsonObject()
    }
}
