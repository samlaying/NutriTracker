package com.example.nutritracker.harness.tools

import com.example.nutritracker.data.entity.IntakeType
import com.example.nutritracker.data.entity.User
import com.example.nutritracker.data.repository.IntakeRepository
import com.example.nutritracker.util.CalorieGoalCalc
import com.example.nutritracker.util.MacroCalc
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 今日状态汇总（模型上下文注入与 get_today_summary 工具共用；口径与首页一致） */
class TodaySummaryBuilder(private val ctx: ToolContext) {

    data class Numbers(
        val calorieGoal: Double,
        val caloriesSupplied: Double,
        val caloriesBurned: Double,
        val carbsGoal: Double, val carbsTracked: Double,
        val fatGoal: Double, val fatTracked: Double,
        val proteinGoal: Double, val proteinTracked: Double,
        val waterMl: Int, val waterGoalMl: Int,
        val user: User?
    )

    suspend fun numbers(date: LocalDate = ctx.today): Numbers {
        val user = ctx.userRepo.getUser()
        val offset = ctx.dayBoundaryOffset
        val kcalAdj = ctx.settingsRepo.kcalAdjustment.first()
        val carbPct = ctx.settingsRepo.carbPct.first()
        val fatPct = ctx.settingsRepo.fatPct.first()
        val proteinPct = ctx.settingsRepo.proteinPct.first()
        val waterGoal = ctx.settingsRepo.waterGoalMl.first()

        val activityBurn = ctx.activityRepo.getTotalBurnedByLogicalDay(date, offset)
        val calorieGoal = user?.let {
            CalorieGoalCalc.getTotalKcalGoal(user = it, userKcalAdjustment = kcalAdj, totalKcalActivities = activityBurn)
        } ?: 0.0

        val intakes = ctx.intakeRepo.getByLogicalDay(date, offset)
        val meals = intakes.map { it.mealId }.distinct()
            .mapNotNull { ctx.mealRepo.getById(it) }.associateBy { it.id }

        fun kcal(i: com.example.nutritracker.data.entity.Intake) =
            meals[i.mealId]?.let { it.energyKcal100 * i.amount / 100.0 } ?: 0.0
        fun carbs(i: com.example.nutritracker.data.entity.Intake) =
            meals[i.mealId]?.let { it.carbohydrates100 * i.amount / 100.0 } ?: 0.0
        fun fat(i: com.example.nutritracker.data.entity.Intake) =
            meals[i.mealId]?.let { it.fat100 * i.amount / 100.0 } ?: 0.0
        fun protein(i: com.example.nutritracker.data.entity.Intake) =
            meals[i.mealId]?.let { it.proteins100 * i.amount / 100.0 } ?: 0.0

        return Numbers(
            calorieGoal = calorieGoal,
            caloriesSupplied = intakes.sumOf { kcal(it) },
            caloriesBurned = activityBurn,
            carbsGoal = MacroCalc.getCarbsGoal(calorieGoal, carbPct),
            carbsTracked = intakes.sumOf { carbs(it) },
            fatGoal = MacroCalc.getFatGoal(calorieGoal, fatPct),
            fatTracked = intakes.sumOf { fat(it) },
            proteinGoal = MacroCalc.getProteinGoal(calorieGoal, proteinPct),
            proteinTracked = intakes.sumOf { protein(it) },
            waterMl = ctx.waterRepo.getTotalMlByLogicalDay(date, offset),
            waterGoalMl = waterGoal,
            user = user
        )
    }

    /** 给模型的紧凑文本（回源数字） */
    suspend fun build(date: LocalDate = ctx.today): String {
        val n = numbers(date)
        if (n.user == null) return "（用户尚未完成引导，无个人资料）"
        val intakes = ctx.intakeRepo.getByLogicalDay(date, ctx.dayBoundaryOffset)
        val meals = intakes.map { it.mealId }.distinct()
            .mapNotNull { ctx.mealRepo.getById(it) }.associateBy { it.id }
        val byType = IntakeType.entries.joinToString("\n") { type ->
            val items = intakes.filter { it.intakeType == type }
            val label = when (type) {
                IntakeType.BREAKFAST -> "早餐"; IntakeType.LUNCH -> "午餐"
                IntakeType.DINNER -> "晚餐"; IntakeType.SNACK -> "零食"
            }
            if (items.isEmpty()) "$label: 无"
            else "$label: " + items.joinToString("；") { i ->
                val m = meals[i.mealId]
                "${m?.name ?: "未知"} ${i.amount.roundToInt()}g ≈${(m?.energyKcal100 ?: 0.0) * i.amount / 100.0}kcal"
            }
        }
        return buildString {
            appendLine("日期: $date")
            appendLine("热量: 已摄入 ${n.caloriesSupplied.roundToInt()} / 目标 ${n.calorieGoal.roundToInt()} kcal，运动消耗 ${n.caloriesBurned.roundToInt()} kcal，剩余约 ${(n.calorieGoal + n.caloriesBurned - n.caloriesSupplied).roundToInt()} kcal")
            appendLine("碳水 ${n.carbsTracked.roundToInt()}/${n.carbsGoal.roundToInt()}g，脂肪 ${n.fatTracked.roundToInt()}/${n.fatGoal.roundToInt()}g，蛋白质 ${n.proteinTracked.roundToInt()}/${n.proteinGoal.roundToInt()}g")
            appendLine("饮水 ${n.waterMl}/${n.waterGoalMl} ml")
            appendLine(byType)
        }.trimEnd()
    }
}

internal fun Double.f1(): String = String.format("%.1f", this)

internal fun LocalDate.zh(): String = format(DateTimeFormatter.ofPattern("M月d日"))
