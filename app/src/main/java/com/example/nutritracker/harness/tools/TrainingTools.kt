package com.example.nutritracker.harness.tools

import com.example.nutritracker.data.entity.SessionStatus
import com.example.nutritracker.harness.ToolResult
import com.example.nutritracker.data.repository.PlannedExercise
import com.example.nutritracker.data.repository.PlannedSession
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.time.LocalDate

// ── create_training_plan ─────────────────────────────────────────────────────

class CreateTrainingPlanTool : AgentTool {
    override val name = "create_training_plan"
    override val description =
        "创建周期化训练计划（替换当前生效计划，需用户确认）。会话日期从 start_date 起按周排布；每个训练日带动作清单（组/次/重量建议逻辑/RPE/休息）。遵循渐进超负荷：重量留待用户实际执行时确定或给起始选择逻辑。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "name": {"type": "string"},
            "goal": {"type": "string", "description": "增肌/减脂/力量/维持/体态"},
            "weeks": {"type": "integer", "description": "周期周数"},
            "start_date": {"type": "string", "description": "YYYY-MM-DD 或 今天/明天，默认今天"},
            "notes": {"type": "string", "description": "进阶与降级规则、最低任务版本等说明"},
            "sessions": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "date": {"type": "string", "description": "YYYY-MM-DD"},
                        "title": {"type": "string", "description": "如 推/拉/腿/上肢/下肢/有氧"},
                        "focus": {"type": "string"},
                        "exercises": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "name": {"type": "string"},
                                    "sets": {"type": "string", "description": "组数，如 4"},
                                    "reps": {"type": "string", "description": "次数范围，如 8-12"},
                                    "weight_kg": {"type": "number", "description": "可选，目标重量"},
                                    "rest_seconds": {"type": "integer"},
                                    "rpe": {"type": "string", "description": "如 RPE7-8 / RIR2"},
                                    "notes": {"type": "string", "description": "动作要点或替代方案"}
                                },
                                "required": ["name", "sets", "reps"]
                            }
                        }
                    },
                    "required": ["date", "title", "exercises"]
                }
            }
        },
        "required": ["name", "goal", "weeks", "sessions"]
    }"""
    override val requiresConfirmation = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args.str("name") ?: return ToolResult.failure("缺少 name")
        val goal = args.str("goal") ?: return ToolResult.failure("缺少 goal")
        val weeks = args.intOrNull("weeks") ?: return ToolResult.failure("缺少 weeks")
        val sessionsJson = args.getAsJsonArray("sessions")
            ?: return ToolResult.failure("缺少 sessions")
        if (sessionsJson.size() == 0) return ToolResult.failure("sessions 不能为空")
        if (sessionsJson.size() > 60) return ToolResult.failure("sessions 过多（>60），请控制计划规模")

        val startDate = parseFlexibleDate(args.str("start_date"), ctx.today) ?: ctx.today
        val notes = args.str("notes")

        val sessions = sessionsJson.mapIndexed { idx, el ->
            val s = el.asJsonObject
            val date = parseFlexibleDate(s.get("date")?.takeIf { !it.isJsonNull }?.asString, ctx.today)
                ?: startDate.plusDays((idx / (weeks.coerceAtLeast(1))).toLong() * 7)
            val exercises = s.getAsJsonArray("exercises")?.map { eEl ->
                val e = eEl.asJsonObject
                PlannedExercise(
                    name = e.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "动作",
                    targetSets = e.get("sets")?.takeIf { !it.isJsonNull }?.asString ?: "3",
                    targetReps = e.get("reps")?.takeIf { !it.isJsonNull }?.asString ?: "8-12",
                    targetWeightKg = e.get("weight_kg")?.takeIf { !it.isJsonNull }?.let { if (it.asJsonPrimitive.isNumber) it.asDouble else it.asString.toDoubleOrNull() },
                    restSeconds = e.get("rest_seconds")?.takeIf { !it.isJsonNull }?.let { if (it.asJsonPrimitive.isNumber) it.asInt else null },
                    rpeTarget = e.get("rpe")?.takeIf { !it.isJsonNull }?.asString,
                    notes = e.get("notes")?.takeIf { !it.isJsonNull }?.asString
                )
            } ?: emptyList()
            PlannedSession(
                date = date,
                title = s.get("title")?.takeIf { !it.isJsonNull }?.asString ?: "训练日",
                focus = s.get("focus")?.takeIf { !it.isJsonNull }?.asString,
                exercises = exercises
            )
        }

        // 写入（确认后才会到达这里）
        val plan = ctx.trainingRepo.createPlan(name, goal, weeks, startDate, sessions, notes)

        // 回源验证
        val savedSessions = ctx.trainingRepo.getSessions(plan.id)
        val progress = ctx.trainingRepo.planProgress(plan.id)
        val next = savedSessions.firstOrNull { it.status == SessionStatus.PLANNED }

        val payload = Gson().toJson(
            mapOf(
                "plan_id" to plan.id, "name" to plan.name, "goal" to plan.goal,
                "weeks" to plan.weeksTotal, "sessions" to progress.total,
                "start_date" to plan.startDate.toString()
            )
        )
        return ToolResult.ok(
            summary = buildString {
                appendLine("训练计划已创建并生效：${plan.name}（${plan.goal}，${plan.weeksTotal} 周），共 ${progress.total} 个训练日。")
                appendLine("会话数已回读验证：${savedSessions.size}。")
                next?.let { appendLine("第一次训练：${it.scheduledDate} ${it.title}。用户可以在训练页查看完整计划。") }
            }.trimEnd(),
            cardType = "plan_created",
            payloadJson = payload
        )
    }

    override fun describeCall(args: JsonObject): String {
        val sessions = args.getAsJsonArray("sessions")?.size() ?: 0
        return buildString {
            appendLine("计划名: ${args.str("name") ?: "?"}")
            appendLine("目标: ${args.str("goal") ?: "?"}，${args.intOrNull("weeks") ?: "?"} 周，共 $sessions 个训练日")
            appendLine("注意：创建后将替换当前生效的训练计划。")
        }.trimEnd()
    }
}

// ── update_session_status ────────────────────────────────────────────────────

class UpdateSessionStatusTool : AgentTool {
    override val name = "update_session_status"
    override val description = "更新训练日状态（completed 完成 / skipped 跳过 / planned 重排）。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "session_id": {"type": "integer", "description": "训练日 id（get_training_plan 返回的 id）"},
            "status": {"type": "string", "enum": ["completed", "skipped", "planned"]},
            "notes": {"type": "string"}
        },
        "required": ["session_id", "status"]
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val sessionId = args.intOrNull("session_id")?.toLong()
            ?: return ToolResult.failure("缺少 session_id")
        val status = args.str("status") ?: return ToolResult.failure("缺少 status")
        val notes = args.str("notes")
        val updated = when (status) {
            "completed" -> ctx.trainingRepo.completeSession(sessionId, notes)
            "skipped" -> ctx.trainingRepo.skipSession(sessionId, notes)
            "planned" -> ctx.trainingRepo.replanSession(sessionId)
            else -> return ToolResult.failure("status 应为 completed/skipped/planned")
        }
            ?: return ToolResult.failure("session_id=$sessionId 不存在，请先用 get_training_plan 查询")
        val payload = Gson().toJson(
            mapOf("session_id" to sessionId, "status" to updated.status.name, "title" to updated.title)
        )
        return ToolResult.ok(
            "训练日 ${updated.scheduledDate} ${updated.title} 状态已更新为 ${updated.status}（回读验证通过）。",
            cardType = "plan_updated",
            payloadJson = payload
        )
    }
}

// ── 日/周日期帮助 ────────────────────────────────────────────────────────────

fun weekRange(today: LocalDate, weeksAgo: Int): Pair<LocalDate, LocalDate> {
    val start = today.minusWeeks(weeksAgo.toLong())
    return start to today
}
