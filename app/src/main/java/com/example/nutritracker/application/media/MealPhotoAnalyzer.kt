package com.example.nutritracker.application.media

import android.content.Context
import android.net.Uri
import com.example.nutritracker.data.entity.IntakeType
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** App-facing contract for photo meal analysis, independent of its camera implementation. */
interface MealPhotoAnalyzer {
    val isAnalyzing: StateFlow<Boolean>
    val analysisError: StateFlow<String?>
    val analysisSuccess: SharedFlow<String>

    fun clearError()

    fun analyzeAndCreateMeals(
        context: Context,
        uris: List<Uri>,
        intakeType: IntakeType,
        notes: String = "",
        date: LocalDate = LocalDate.now()
    )
}
