package com.example.nutritracker.harness.tools

import com.example.nutritracker.data.entity.SessionStatus
import com.example.nutritracker.harness.ToolResult
import com.example.nutritracker.feature.sources.sourceEntries
import com.google.gson.JsonObject
import java.time.LocalDate
import kotlin.math.roundToInt

// ── get_today_summary ────────────────────────────────────────────────────────

class GetTodaySummaryTool : AgentTool {
    override val name = "get_today_summary"
    override val description =
        "获取某天的完整状态汇总：热量目标/已摄入/运动消耗/剩余、三大宏量营养素、饮水、各餐明细。回答'今天还能吃多少''现在状态怎么样'等问题的第一手数据。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "date_offset": {"type": "integer", "description": "0=今天(默认)，1=昨天，2=前天"}
        }
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val offsetDays = args.intOrNull("date_offset") ?: 0
        val date = ctx.today.minusDays(offsetDays.toLong().coerceIn(0, 30))
        val summary = TodaySummaryBuilder(ctx).build(date)
        return ToolResult.ok(summary, cardType = "today_summary", payloadJson = summary)
    }
}

// ── get_recent_records ───────────────────────────────────────────────────────

class GetRecentRecordsTool : AgentTool {
    override val name = "get_recent_records"
    override val description = "获取最近 N 天（默认 7，最多 60）每天的摄入、运动、饮水、体重概要，用于趋势判断和复盘。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "days": {"type": "integer", "description": "回看天数，默认 7"}
        }
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val days = (args.intOrNull("days") ?: 7).coerceIn(1, 60)
        val summaryBuilder = TodaySummaryBuilder(ctx)
        val sb = StringBuilder()
        for (i in (days - 1) downTo 0) {
            val date = ctx.today.minusDays(i.toLong())
            val n = summaryBuilder.numbers(date)
            val intakeCount = ctx.intakeRepo.getByLogicalDay(date, ctx.dayBoundaryOffset).size
            val activityCount = ctx.activityRepo.getByLogicalDay(date, ctx.dayBoundaryOffset).size
            val weight = ctx.weightRepo.getByRange(date, date).firstOrNull()?.weightKg
            sb.appendLine(
                "$date: 摄入${n.caloriesSupplied.roundToInt()}/${n.calorieGoal.roundToInt()}kcal(${intakeCount}条)，" +
                    "运动${n.caloriesBurned.roundToInt()}kcal(${activityCount}条)，饮水${n.waterMl}ml" +
                    (weight?.let { "，体重${it.f1()}kg" } ?: "")
            )
        }
        return ToolResult.ok(sb.toString().trimEnd())
    }
}

// ── get_weight_trend ─────────────────────────────────────────────────────────

class GetWeightTrendTool : AgentTool {
    override val name = "get_weight_trend"
    override val description = "获取体重记录时间序列与区间变化，用于趋势/平台期判断。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "days": {"type": "integer", "description": "回看天数，默认 30"}
        }
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val days = (args.intOrNull("days") ?: 30).coerceIn(1, 365)
        val start = ctx.today.minusDays(days.toLong() - 1)
        val logs = ctx.weightRepo.getByRange(start, ctx.today)
        if (logs.isEmpty()) return ToolResult.ok("最近 $days 天没有体重记录。")
        val first = logs.first()
        val last = logs.last()
        val delta = last.weightKg - first.weightKg
        val text = buildString {
            appendLine("区间 $start ~ ${ctx.today}，共 ${logs.size} 条记录")
            appendLine("起点 ${first.date} ${first.weightKg.f1()}kg → 终点 ${last.date} ${last.weightKg.f1()}kg，变化 ${delta.f1()}kg")
            logs.takeLast(15).forEach { appendLine("${it.date}: ${it.weightKg.f1()}kg") }
        }.trimEnd()
        return ToolResult.ok(text)
    }
}

// ── get_training_plan ────────────────────────────────────────────────────────

class GetTrainingPlanTool : AgentTool {
    override val name = "get_training_plan"
    override val description = "获取当前训练计划：名称/目标/周期、执行进度、全部训练日状态、下一次训练的动作明细。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val plan = ctx.trainingRepo.getActivePlan()
            ?: return ToolResult.ok("当前没有生效的训练计划。可以让我帮你制定一份。")
        val progress = ctx.trainingRepo.planProgress(plan.id)
        val sessions = ctx.trainingRepo.getSessions(plan.id)
        val sb = buildString {
            appendLine("计划: ${plan.name}（目标 ${plan.goal}，${plan.weeksTotal} 周，${plan.startDate} 开始，状态 ${plan.status}）")
            appendLine("进度: 完成 ${progress.completed}/${progress.total}，跳过 ${progress.skipped}，待练 ${progress.planned}")
            sessions.forEach { s ->
                appendLine("- ${s.scheduledDate} ${s.title}${s.focus?.let { "（$it）" } ?: ""} [${s.status}] id=${s.id}")
            }
            val next = sessions.firstOrNull { it.status == SessionStatus.PLANNED }
            if (next != null) {
                appendLine("下一次训练 ${next.scheduledDate} ${next.title}:")
                ctx.trainingRepo.getExercises(next.id).forEach { e ->
                    appendLine("  - ${e.name}: ${e.targetSets}组×${e.targetReps}次${e.targetWeightKg?.let { " @${it.f1()}kg" } ?: ""}${e.rpeTarget?.let { " RPE$it" } ?: ""}${e.restSeconds?.let { " 休息${it}s" } ?: ""}")
                }
            }
        }.trimEnd()
        return ToolResult.ok(sb.toString(), cardType = "plan_summary", payloadJson = null)
    }
}

// ── search_sources ───────────────────────────────────────────────────────────

class SearchSourcesTool : AgentTool {
    override val name = "search_sources"
    override val description =
        "检索 App 内置的科学文献目录（TDEE/营养分配/MET/BMI 等计算依据）。回答'为什么这样算''依据是什么'时引用，输出可点击的文献卡。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "关键词，如 'TDEE' '蛋白质' 'MET'"}
        }
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args.str("query")?.replace(" ", "") ?: ""
        val hits = sourceEntries.filter { entry ->
            entry.title.replace(" ", "").contains(query, ignoreCase = true) ||
                entry.description.replace(" ", "").contains(query, ignoreCase = true) ||
                query.isBlank()
        }
        if (hits.isEmpty()) {
            return ToolResult.ok("内置文献目录中没有匹配\"$query\"的条目。内置主题：${sourceEntries.joinToString("、") { it.title }}。")
        }
        val text = hits.joinToString("\n\n") { entry ->
            buildString {
                appendLine("【${entry.title}】${entry.description}")
                entry.links.forEach { appendLine("引用: ${it.citation}  链接: ${it.url}") }
            }
        }
        val payload = com.google.gson.Gson().toJson(
            hits.map { h ->
                mapOf(
                    "title" to h.title,
                    "description" to h.description,
                    "url" to (h.links.firstOrNull()?.url ?: ""),
                    "citation" to (h.links.firstOrNull()?.citation ?: "")
                )
            }
        )
        return ToolResult.ok(text, cardType = "sources_cited", payloadJson = payload)
    }
}

// ── load_skill（渐进式披露第二跳） ───────────────────────────────────────────

class LoadSkillTool : AgentTool {
    override val name = "load_skill"
    override val description = "加载指定技能的完整说明（先看技能列表描述，命中再加载）。加载后遵循其中的流程。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "name": {"type": "string", "description": "技能名，见系统提示词的可用技能列表"}
        },
        "required": ["name"]
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val skillName = args.str("name") ?: return ToolResult.failure("缺少 name 参数")
        val skill = ctx.skillRegistry.byName(skillName)
            ?: return ToolResult.failure("技能 $skillName 不存在。可用：${ctx.skillRegistry.names()}")
        return ToolResult.ok(skill.body)
    }
}

fun com.example.nutritracker.harness.SkillRegistry.names(): List<String> =
    descriptionsBlock().lines().mapNotNull { line ->
        if (line.startsWith("- ")) line.substringAfter("- ").substringBefore(":").trim() else null
    }

// ── date 帮助 ────────────────────────────────────────────────────────────────

internal fun parseFlexibleDate(text: String?, today: LocalDate): LocalDate? {
    if (text.isNullOrBlank()) return null
    val normalized = text.trim()
    return when {
        normalized == "今天" -> today
        normalized == "昨天" -> today.minusDays(1)
        normalized == "前天" -> today.minusDays(2)
        normalized == "明天" -> today.plusDays(1)
        else -> runCatching { LocalDate.parse(normalized) }.getOrNull()
    }
}
