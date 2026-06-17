package com.example.nutritracker.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Main : Screen("main")
    data object Home : Screen("home")
    data object Diary : Screen("diary")
    data object Profile : Screen("profile")
    data object Settings : Screen("settings")
    data object AddMeal : Screen("add_meal/{intakeTypeId}?date={date}") {
        fun createRoute(intakeTypeId: Int, dateEpochDay: Long = java.time.LocalDate.now().toEpochDay()) =
            "add_meal/$intakeTypeId?date=$dateEpochDay"
    }
    data object CameraCapture : Screen("camera_capture?intakeTypeId={intakeTypeId}&date={date}") {
        fun createRoute(intakeTypeId: Int = 0, dateEpochDay: Long = java.time.LocalDate.now().toEpochDay()) =
            "camera_capture?intakeTypeId=$intakeTypeId&date=$dateEpochDay"
    }
    data object MealEdit : Screen("meal_edit?mealId={mealId}&intakeTypeId={intakeTypeId}&date={date}") {
        fun createRoute(mealId: Long = -1, intakeTypeId: Int = 0, dateEpochDay: Long = java.time.LocalDate.now().toEpochDay()) =
            "meal_edit?mealId=$mealId&intakeTypeId=$intakeTypeId&date=$dateEpochDay"
    }
    data object AddActivity : Screen("add_activity")
    data object WeightHistory : Screen("weight_history")
    data object Sources : Screen("sources")
}

data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val route: String
)

val bottomNavItems = listOf(
    BottomNavItem("首页", Icons.Filled.Home, Screen.Home.route),
    BottomNavItem("日记", Icons.Filled.CalendarMonth, Screen.Diary.route),
    BottomNavItem("我的", Icons.Filled.Person, Screen.Profile.route),
    BottomNavItem("设置", Icons.Filled.Settings, Screen.Settings.route)
)
