package com.example.nutritracker.feature.meal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nutritracker.data.entity.*
import com.example.nutritracker.data.repository.*
import android.content.Context
import android.net.Uri
import com.example.nutritracker.feature.camera.AnalysisResult
import com.example.nutritracker.feature.camera.AiFoodAnalyzer
import com.example.nutritracker.application.media.MealPhotoAnalyzer
import com.example.nutritracker.util.DayBoundaryCalc
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class AddMealViewModel @Inject constructor(
    private val mealRepo: MealRepository,
    private val intakeRepo: IntakeRepository,
    private val trackedDayRepo: TrackedDayRepository,
    private val settingsRepo: SettingsRepository,
    private val dayBoundaryCalc: DayBoundaryCalc,
    private val aiAnalysisManager: MealPhotoAnalyzer
) : ViewModel() {

    data class RecentIntake(val meal: Meal, val amount: Double, val kcal: Double)

    private val _todayIntakes = MutableStateFlow<List<Intake>>(emptyList())
    val todayIntakes: StateFlow<List<Intake>> = _todayIntakes.asStateFlow()

    private val _mealsMap = MutableStateFlow<Map<Long, Meal>>(emptyMap())
    val mealsMap: StateFlow<Map<Long, Meal>> = _mealsMap.asStateFlow()

    private val _recentIntakes = MutableStateFlow<List<RecentIntake>>(emptyList())
    val recentIntakes: StateFlow<List<RecentIntake>> = _recentIntakes.asStateFlow()

    val isAnalyzing: StateFlow<Boolean> = aiAnalysisManager.isAnalyzing
    val analysisError: StateFlow<String?> = aiAnalysisManager.analysisError

    fun clearAnalysisError() {
        aiAnalysisManager.clearError()
    }

    fun analyzeAndCreateMeals(context: Context, uris: List<Uri>, intakeType: IntakeType, notes: String = "", date: LocalDate = LocalDate.now()) {
        aiAnalysisManager.analyzeAndCreateMeals(context, uris, intakeType, notes, date)
    }

    fun loadTodayIntakes(intakeType: IntakeType, date: LocalDate = LocalDate.now()) {
        viewModelScope.launch {
            val offset = settingsRepo.dayBoundaryMinutes.first()
            val intakes = intakeRepo.getByTypeAndLogicalDay(intakeType, date, offset)
            _todayIntakes.value = intakes

            val mealIds = intakes.map { it.mealId }.distinct()
            val meals = mealIds.mapNotNull { id -> mealRepo.getById(id) }.associateBy { it.id }
            _mealsMap.value = meals

            // 加载近期记录（排除今天已有的 meal）
            loadRecentIntakes(intakeType, date, offset)
        }
    }

    private suspend fun loadRecentIntakes(intakeType: IntakeType, today: java.time.LocalDate, offset: Int) {
        val todayMealIds = _todayIntakes.value.map { it.mealId }.toSet()
        val recent = intakeRepo.getRecentByType(intakeType, 20)
            .filter { it.mealId !in todayMealIds }
            .distinctBy { it.mealId }
            .take(6)
        val items = recent.mapNotNull { intake ->
            mealRepo.getById(intake.mealId)?.let { meal ->
                RecentIntake(meal = meal, amount = intake.amount, kcal = meal.energyKcal100 * intake.amount / 100.0)
            }
        }
        _recentIntakes.value = items
    }

    fun quickAddIntake(meal: Meal, amount: Double, intakeType: IntakeType, date: LocalDate = LocalDate.now()) {
        viewModelScope.launch {
            val dateTime = if (date == LocalDate.now()) LocalDateTime.now() else date.atTime(12, 0)

            val intakeId = intakeRepo.upsert(Intake(
                mealId = meal.id, intakeType = intakeType,
                amount = amount, unit = "g", dateTime = dateTime
            ))

            val factor = amount / 100.0
            trackedDayRepo.ensureDay(date, 0.0, 0.0, 0.0, 0.0)
            trackedDayRepo.addCalories(
                date,
                meal.energyKcal100 * factor,
                meal.carbohydrates100 * factor,
                meal.fat100 * factor,
                meal.proteins100 * factor
            )

            loadTodayIntakes(intakeType, date)
        }
    }

    /**
     * 从 AI 分析结果创建多个 Meal 并记录摄入
     */
    fun createMealsFromAnalysis(result: AnalysisResult, intakeType: IntakeType, date: LocalDate = LocalDate.now()) {
        viewModelScope.launch {
            val nutrition = result.nutritionResult
            val thumbnailPath = result.thumbnailPath

            // 为每个食物项创建 Meal 和 Intake
            nutrition.foodItems.forEach { item ->
                val kcalPer100g = if (item.weightG > 0) item.calories * 100.0 / item.weightG else 0.0
                val carbsPer100g = if (item.weightG > 0) item.carbs * 100.0 / item.weightG else 0.0
                val fatPer100g = if (item.weightG > 0) item.fat * 100.0 / item.weightG else 0.0
                val proteinPer100g = if (item.weightG > 0) item.protein * 100.0 / item.weightG else 0.0

                val mealId = mealRepo.upsert(
                    Meal(
                        name = item.name,
                        source = MealSource.AI_ANALYSIS,
                        energyKcal100 = kcalPer100g,
                        carbohydrates100 = carbsPer100g,
                        fat100 = fatPer100g,
                        proteins100 = proteinPer100g,
                        localImagePath = thumbnailPath
                    )
                )
                addIntake(mealId, item.weightG, intakeType, date)
            }

            // 如果没有 food_items 但有总计数据，创建一个汇总条目
            if (nutrition.foodItems.isEmpty() && nutrition.totalCalories > 0) {
                val mealId = mealRepo.upsert(
                    Meal(
                        name = "AI 识别食物",
                        source = MealSource.AI_ANALYSIS,
                        energyKcal100 = nutrition.totalCalories,
                        carbohydrates100 = nutrition.totalCarbs,
                        fat100 = nutrition.totalFat,
                        proteins100 = nutrition.totalProtein,
                        localImagePath = thumbnailPath
                    )
                )
                addIntake(mealId, 100.0, intakeType, date)
            }

            loadTodayIntakes(intakeType, date)
        }
    }

    /**
     * 手动创建食物并记录摄入
     */
    fun createManualMeal(
        name: String, kcal: Double, carbs: Double, fat: Double, protein: Double,
        weight: Double, intakeType: IntakeType, date: LocalDate = LocalDate.now()
    ) {
        viewModelScope.launch {
            val mealId = mealRepo.upsert(
                Meal(
                    name = name, source = MealSource.MANUAL,
                    energyKcal100 = kcal, carbohydrates100 = carbs,
                    fat100 = fat, proteins100 = protein
                )
            )
            addIntake(mealId, weight, intakeType, date)
            loadTodayIntakes(intakeType, date)
        }
    }

    /**
     * 删除摄入记录
     * 先从 TrackedDay 移除卡路里，再删除摄入记录
     */
    fun deleteIntake(intake: Intake, date: LocalDate = LocalDate.now()) {
        viewModelScope.launch {
            // 先获取 Meal 数据（删除后可能无法获取）
            val meal = mealRepo.getById(intake.mealId)
            // 先从 TrackedDay 移除卡路里
            if (meal != null) {
                val day = intake.dateTime.toLocalDate()
                trackedDayRepo.removeCalories(
                    day,
                    meal.energyKcal100 * intake.amount / 100.0,
                    meal.carbohydrates100 * intake.amount / 100.0,
                    meal.fat100 * intake.amount / 100.0,
                    meal.proteins100 * intake.amount / 100.0
                )
            }
            // 再删除摄入记录
            intakeRepo.delete(intake)
            loadTodayIntakes(intake.intakeType, date)
        }
    }

    /**
     * 更新摄入记录的份量
     * 先移除旧的卡路里，更新记录，再添加新的卡路里
     */
    fun updateIntakeAmount(intake: Intake, newAmount: Double, date: LocalDate = LocalDate.now()) {
        viewModelScope.launch {
            val meal = mealRepo.getById(intake.mealId) ?: return@launch
            val day = intake.dateTime.toLocalDate()

            // 移除旧的卡路里
            trackedDayRepo.removeCalories(
                day,
                meal.energyKcal100 * intake.amount / 100.0,
                meal.carbohydrates100 * intake.amount / 100.0,
                meal.fat100 * intake.amount / 100.0,
                meal.proteins100 * intake.amount / 100.0
            )

            // 更新摄入记录
            val updatedIntake = intake.copy(amount = newAmount)
            intakeRepo.upsert(updatedIntake)

            // 添加新的卡路里
            trackedDayRepo.addCalories(
                day,
                meal.energyKcal100 * newAmount / 100.0,
                meal.carbohydrates100 * newAmount / 100.0,
                meal.fat100 * newAmount / 100.0,
                meal.proteins100 * newAmount / 100.0
            )

            loadTodayIntakes(intake.intakeType, date)
        }
    }

    private suspend fun addIntake(mealId: Long, amount: Double, type: IntakeType, date: LocalDate = LocalDate.now()) {
        val dateTime = if (date == LocalDate.now()) LocalDateTime.now() else date.atTime(12, 0)
        intakeRepo.upsert(Intake(mealId = mealId, intakeType = type, amount = amount, dateTime = dateTime))
        val meal = mealRepo.getById(mealId) ?: return
        trackedDayRepo.ensureDay(date, 0.0, 0.0, 0.0, 0.0)
        trackedDayRepo.addCalories(
            date,
            meal.energyKcal100 * amount / 100.0,
            meal.carbohydrates100 * amount / 100.0,
            meal.fat100 * amount / 100.0,
            meal.proteins100 * amount / 100.0
        )
    }
}
