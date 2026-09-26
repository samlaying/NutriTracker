package com.example.nutritracker.harness

import com.example.nutritracker.data.entity.ActivityLevel
import com.example.nutritracker.data.entity.WeightGoal
import com.example.nutritracker.harness.tools.ToolContext
import com.example.nutritracker.harness.tools.TodaySummaryBuilder
import com.example.nutritracker.harness.tools.f1
import com.example.nutritracker.util.AgeCalc
import com.example.nutritracker.util.BmiCalc
import kotlin.math.roundToInt

/**
 * 每轮 before-agent 注入（教程 Context-Engineering Load 流程）：
 * 角色（短）+ AGENT.md 详细规则 + 安全红线 + 用户画像 + 今日状态 + 长期记忆 + 技能清单 + Todo 提示。
 * 短系统提示词进 properties 风格首段；长规则独立存放，便于维护与持久化。
 */
class SystemPromptBuilder(private val agentMd: String) {

    suspend fun build(ctx: ToolContext, skills: SkillRegistry, todoItems: List<TodoItem>): String {
        val user = ctx.userRepo.getUser()
        val todaySummary = TodaySummaryBuilder(ctx).build(ctx.today)
        val memoryBlock = ctx.memoryRepo.formatForContext()

        return buildString {
            // ── 角色短提示词 ──
            appendLine("你是 Fit 数字教练：一位专业、克制、以用户长期健康为先的私人健身营养教练。你是协调者，数据密集的分析委派给子 Agent；简单问题直接回答，不制造流程。")
            appendLine("今天是 ${ctx.today}。")

            // ── 详细规则（AGENT.md）──
            appendLine()
            appendLine(agentMd.trim())

            // ── 安全红线 ──
            appendLine()
            appendLine(SafetyGate.RULES)

            // ── 用户画像 ──
            if (user != null) {
                val age = AgeCalc.getAge(user.birthday)
                val bmi = BmiCalc.getBmi(user.weightKg, user.heightCm)
                appendLine()
                appendLine("## 用户画像")
                appendLine("- 年龄 $age 岁，身高 ${user.heightCm.roundToInt()}cm，体重 ${user.weightKg}kg，BMI ${bmi.f1()}")
                appendLine("- 活动水平 ${activityLabel(user.activityLevel)}，目标方向 ${goalLabel(user.weightGoal)}")
                user.targetWeightKg?.let { appendLine("- 目标体重 ${it}kg") }
                user.weeklyWeightGoalKg?.let { appendLine("- 每周目标变化 ${it}kg/周") }
            } else {
                appendLine()
                appendLine("## 用户画像")
                appendLine("-（未完成引导。涉及计划类请求时先引导完成基本资料，缺数据用表单确认，不臆造）")
            }

            // ── 今日实时状态 ──
            appendLine()
            appendLine("## 今日状态（实时，已回源）")
            appendLine(todaySummary)

            // ── 长期记忆 ──
            appendLine()
            appendLine("## 长期记忆（用户偏好，回复时遵循）")
            appendLine(memoryBlock.ifBlank { "（暂无记录；对话中出现的稳定偏好会在对话后自动沉淀）" })

            // ── 技能清单（渐进式披露：仅描述） ──
            if (!skills.isEmpty()) {
                appendLine()
                appendLine(skills.descriptionsBlock())
            }

            // ── Todo 提示 ──
            if (todoItems.isNotEmpty()) {
                appendLine()
                appendLine("## 当前任务清单（独立于对话历史，不会被摘要丢失）")
                todoItems.forEach { appendLine("- [${it.status}] ${it.content}") }
            }

            // ── 工具使用总则 ──
            appendLine()
            appendLine("## 工具使用总则")
            appendLine("- 数字必须来自工具返回（已回源验证），禁止编造；查不到就说查不到。")
            appendLine("- 记录类写入（log_*）直接执行；create_training_plan、update_profile 属高危写，会走用户确认。")
            appendLine("- 引用计算依据时调用 search_sources，给出可点击的文献。")
            appendLine("- 需要用户补充信息时，说明已知、缺失和建议值，让用户做选择题。")
        }.trimEnd()
    }

    private fun activityLabel(level: ActivityLevel): String = when (level) {
        ActivityLevel.SEDENTARY -> "久坐"
        ActivityLevel.LOW_ACTIVE -> "低活跃"
        ActivityLevel.ACTIVE -> "活跃"
        ActivityLevel.VERY_ACTIVE -> "非常活跃"
    }

    private fun goalLabel(goal: WeightGoal): String = when (goal) {
        WeightGoal.LOSE -> "减重"
        WeightGoal.MAINTAIN -> "维持"
        WeightGoal.GAIN -> "增重"
    }
}
