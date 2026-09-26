package com.example.nutritracker.harness.tools

import com.example.nutritracker.data.entity.ActivityLevel
import com.example.nutritracker.data.entity.WeightGoal
import com.example.nutritracker.harness.SubAgents
import com.example.nutritracker.harness.ToolResult
import com.google.gson.Gson
import com.google.gson.JsonObject

// ── update_profile ───────────────────────────────────────────────────────────

class UpdateProfileTool : AgentTool {
    override val name = "update_profile"
    override val description =
        "更新用户资料字段（目标体重、周目标、活动水平、减重/增重目标等）。只填要改的字段；影响热量目标的修改需要用户确认。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "target_weight_kg": {"type": "number"},
            "weekly_goal_kg": {"type": "number", "description": "每周体重变化目标（正=增，负=减）"},
            "weight_goal": {"type": "string", "enum": ["lose", "maintain", "gain"]},
            "activity_level": {"type": "string", "enum": ["sedentary", "low_active", "active", "very_active"]}
        }
    }"""
    override val requiresConfirmation = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val user = ctx.userRepo.getUser()
            ?: return ToolResult.failure("用户尚未完成引导，无资料可更新")
        val targetWeight = args.doubleOrNull("target_weight_kg")
        val weeklyGoal = args.doubleOrNull("weekly_goal_kg")
        val goalStr = args.str("weight_goal")
        val activityStr = args.str("activity_level")
        if (targetWeight == null && weeklyGoal == null && goalStr == null && activityStr == null) {
            return ToolResult.failure("未提供任何要修改的字段")
        }

        val updated = user.copy(
            targetWeightKg = targetWeight ?: user.targetWeightKg,
            weeklyWeightGoalKg = weeklyGoal ?: user.weeklyWeightGoalKg,
            weightGoal = goalStr?.let {
                when (it.lowercase()) {
                    "lose" -> WeightGoal.LOSE; "gain" -> WeightGoal.GAIN; else -> WeightGoal.MAINTAIN
                }
            } ?: user.weightGoal,
            activityLevel = activityStr?.let {
                when (it.lowercase()) {
                    "sedentary" -> ActivityLevel.SEDENTARY
                    "low_active" -> ActivityLevel.LOW_ACTIVE
                    "active" -> ActivityLevel.ACTIVE
                    "very_active" -> ActivityLevel.VERY_ACTIVE
                    else -> null
                } ?: return ToolResult.failure("未知 activity_level: $it")
            } ?: user.activityLevel
        )
        ctx.userRepo.upsert(updated)

        // 回源验证
        val verified = ctx.userRepo.getUser()
        val changes = mutableListOf<String>()
        if (targetWeight != null && verified?.targetWeightKg == targetWeight) changes.add("目标体重=${targetWeight.f1()}kg")
        if (weeklyGoal != null && verified?.weeklyWeightGoalKg == weeklyGoal) changes.add("周目标=${weeklyGoal.f1()}kg/周")
        if (goalStr != null) changes.add("目标方向=${verified?.weightGoal}")
        if (activityStr != null) changes.add("活动水平=${verified?.activityLevel}")

        val payload = Gson().toJson(
            mapOf(
                "changes" to changes,
                "target_weight_kg" to updated.targetWeightKg,
                "weekly_goal_kg" to updated.weeklyWeightGoalKg,
                "weight_goal" to updated.weightGoal.name,
                "activity_level" to updated.activityLevel.name
            )
        )
        return ToolResult.ok(
            "资料已更新并回读验证：${changes.joinToString("，")}。热量目标已按新资料重算。",
            cardType = "profile_updated",
            payloadJson = payload
        )
    }

    override fun describeCall(args: JsonObject): String {
        val labels = mapOf(
            "target_weight_kg" to "目标体重", "weekly_goal_kg" to "每周目标",
            "weight_goal" to "目标方向", "activity_level" to "活动水平"
        )
        return buildString {
            appendLine("拟修改以下资料字段（影响热量目标计算）：")
            args.entrySet().forEach { (k, v) ->
                appendLine("- ${labels[k] ?: k}: ${v.asString}")
            }
        }.trimEnd()
    }
}

// ── open_screen ──────────────────────────────────────────────────────────────

class OpenScreenTool : AgentTool {
    override val name = "open_screen"
    override val description =
        "引导用户跳转到原生页面（相机拍餐、添加餐食、训练计划、体重历史、文献、设置等）。用户问'在哪设置''帮我打开'时用。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "route": {"type": "string", "enum": ["camera", "add_meal", "add_activity", "training", "weight_history", "sources", "settings", "diary", "profile"]},
            "reason": {"type": "string", "description": "一句话说明为什么跳转"}
        },
        "required": ["route"]
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val route = args.str("route") ?: return ToolResult.failure("缺少 route")
        val ok = ctx.openScreen(route)
        return if (ok) {
            ToolResult.ok("已为用户打开页面：$route。请在回复中衔接上下文。", cardType = "screen_link", payloadJson = Gson().toJson(mapOf("route" to route)))
        } else {
            ToolResult.failure("无法打开页面 $route（当前界面上下文不支持），请改用文字指路。")
        }
    }
}

// ── 文件后端（/reports） ─────────────────────────────────────────────────────

class WriteFileTool : AgentTool {
    override val name = "write_file"
    override val description = "把报告/复盘等产物写入文件（仅限 /reports/ 目录），返回路径。给用户展示摘要并告知可查看完整文件。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "path": {"type": "string", "description": "以 /reports/ 开头的路径，如 /reports/周报-2026-W39.md"},
            "content": {"type": "string"}
        },
        "required": ["path", "content"]
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult.failure("缺少 path")
        val content = args.str("content") ?: return ToolResult.failure("缺少 content")
        val validationError = when {
            !path.startsWith("/reports/") -> "路径必须以 /reports/ 开头"
            path.contains("..") -> "路径不允许包含 .."
            else -> null
        }
        if (validationError != null) return ToolResult.failure(validationError)
        val abs = try {
            ctx.fileBackend.write(path, content)
        } catch (e: Exception) {
            return ToolResult.failure("写入失败：${e.message}")
        }
        return ToolResult.ok(
            "已写入 $abs（${content.length} 字符）。给用户的回复中只放摘要与路径，不重复全文。",
            cardType = "report_saved",
            payloadJson = Gson().toJson(mapOf("path" to abs, "chars" to content.length))
        )
    }
}

class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description = "读取 /reports/ 下已保存的文件内容（如复盘时先读旧报告做对比）。"
    override val parametersJson = """{
        "type": "object",
        "properties": {"path": {"type": "string"}},
        "required": ["path"]
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult.failure("缺少 path")
        if (!path.startsWith("/reports/") || path.contains("..")) return ToolResult.failure("路径必须以 /reports/ 开头且不含 ..")
        return try {
            ToolResult.ok(ctx.fileBackend.read(path))
        } catch (e: Exception) {
            ToolResult.failure("读取失败：${e.message}")
        }
    }
}

// ── delegate_task（同步子 Agent 委派） ───────────────────────────────────────

class DelegateTaskTool : AgentTool {
    override val name = "delegate_task"
    override val description =
        "把数据密集或专业分析类子任务委派给子 Agent（上下文隔离）。交接时必须传齐 5 要素：goal/preferences/requirement/output_requirements/reminders。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "agent": {"type": "string", "enum": ["nutrition_analyst", "training_planner", "review_analyst"]},
            "goal": {"type": "string"},
            "preferences": {"type": "string", "description": "从你的上下文提取的用户偏好（子 Agent 看不到你的上下文）"},
            "requirement": {"type": "string", "description": "需求正文与已知数据"},
            "output_requirements": {"type": "string"},
            "reminders": {"type": "string"}
        },
        "required": ["agent", "goal", "requirement"]
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val agentName = args.str("agent") ?: return ToolResult.failure("缺少 agent")
        val agent = SubAgents.byName(agentName)
            ?: return ToolResult.failure("子 Agent $agentName 不存在。可用：${SubAgents.ALL.joinToString { it.name }}")
        val handoff = mapOf(
            "goal" to (args.str("goal") ?: ""),
            "preferences" to (args.str("preferences") ?: ""),
            "requirement" to (args.str("requirement") ?: ""),
            "output_requirements" to (args.str("output_requirements") ?: ""),
            "reminders" to (args.str("reminders") ?: "")
        )
        val result = ctx.delegate(agentName, handoff)
        return ToolResult.ok(
            "子 Agent「${agent.title}」返回结果：\n$result",
            cardType = "delegation",
            payloadJson = Gson().toJson(mapOf("agent" to agent.title, "result" to result.take(4000)))
        )
    }
}

// ── 异步任务（教程 AsyncSubAgent） ───────────────────────────────────────────

class StartTaskTool : AgentTool {
    override val name = "start_task"
    override val description =
        "启动异步长任务（周报/月度复盘等预计超过 1 分钟的分析），立即返回任务 id；用户可继续对话，完成后结果自动回传。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "kind": {"type": "string", "enum": ["weekly_report", "monthly_report", "analysis"]},
            "title": {"type": "string"},
            "goal": {"type": "string"},
            "preferences": {"type": "string"},
            "requirement": {"type": "string"},
            "output_requirements": {"type": "string"},
            "reminders": {"type": "string"}
        },
        "required": ["kind", "title", "goal", "requirement"]
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val kind = args.str("kind") ?: "analysis"
        val title = args.str("title") ?: "长任务"
        val handoff = buildString {
            appendLine("【任务目标】${args.str("goal") ?: ""}")
            appendLine("【用户偏好】${args.str("preferences") ?: ""}")
            appendLine("【需求正文】${args.str("requirement") ?: ""}")
            appendLine("【输出要求】${args.str("output_requirements") ?: "中文，结构化，事实→判断→行动→下一次验证"}")
            appendLine("【重要提醒】${args.str("reminders") ?: "不编造数据；缺失数据标记未知"}")
        }
        val taskId = ctx.startAsyncTask(ctx.conversationId, kind, title, handoff)
        return ToolResult.ok(
            "异步任务已启动：id=$taskId，标题=$title。现在可以继续与用户对话；任务完成会自动回传结果。可用 query_task 查询进度。",
            cardType = "task_started",
            payloadJson = Gson().toJson(mapOf("task_id" to taskId, "title" to title, "kind" to kind))
        )
    }
}

class QueryTaskTool : AgentTool {
    override val name = "query_task"
    override val description = "查询异步任务状态与结果（不传 task_id 列出活跃任务）。"
    override val parametersJson = """{
        "type": "object",
        "properties": {"task_id": {"type": "integer"}}
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val taskId = args.intOrNull("task_id")?.toLong()
        return if (taskId != null) {
            val task = ctx.taskRepo.getById(taskId)
                ?: return ToolResult.failure("任务 $taskId 不存在")
            ToolResult.ok("任务 $taskId「${task.title}」状态=${task.status}${task.result?.let { "\n结果：${it.take(3000)}" } ?: ""}")
        } else {
            val active = ctx.taskRepo.getActive()
            if (active.isEmpty()) ToolResult.ok("当前没有进行中的异步任务。")
            else ToolResult.ok(active.joinToString("\n") { "任务 ${it.id}「${it.title}」状态=${it.status}" })
        }
    }
}

class CancelTaskTool : AgentTool {
    override val name = "cancel_task"
    override val description = "取消一个异步任务。"
    override val parametersJson = """{
        "type": "object",
        "properties": {"task_id": {"type": "integer"}},
        "required": ["task_id"]
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val taskId = args.intOrNull("task_id")?.toLong()
            ?: return ToolResult.failure("缺少 task_id")
        val task = ctx.taskRepo.getById(taskId)
            ?: return ToolResult.failure("任务 $taskId 不存在")
        if (task.status == com.example.nutritracker.data.entity.AgentTaskStatus.DONE ||
            task.status == com.example.nutritracker.data.entity.AgentTaskStatus.CANCELLED
        ) {
            return ToolResult.failure("任务 $taskId 已结束（${task.status}），无需取消")
        }
        ctx.taskRepo.finish(taskId, com.example.nutritracker.data.entity.AgentTaskStatus.CANCELLED, null)
        return ToolResult.ok("任务 $taskId「${task.title}」已取消。")
    }
}
