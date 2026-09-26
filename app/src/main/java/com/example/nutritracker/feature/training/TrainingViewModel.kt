package com.example.nutritracker.feature.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nutritracker.data.entity.SessionStatus
import com.example.nutritracker.data.entity.TrainingExercise
import com.example.nutritracker.data.entity.TrainingPlan
import com.example.nutritracker.data.entity.TrainingSession
import com.example.nutritracker.data.repository.PlanProgress
import com.example.nutritracker.data.repository.TrainingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrainingUiState(
    val isLoading: Boolean = true,
    val plan: TrainingPlan? = null,
    val progress: PlanProgress? = null,
    val sessions: List<TrainingSession> = emptyList()
)

@HiltViewModel
class TrainingViewModel @Inject constructor(
    private val trainingRepo: TrainingRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TrainingUiState())
    val state: StateFlow<TrainingUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val plan = trainingRepo.getActivePlan()
            if (plan == null) {
                _state.update { it.copy(isLoading = false, plan = null, sessions = emptyList(), progress = null) }
                return@launch
            }
            trainingRepo.getSessionsFlow(plan.id).collect { sessions ->
                val progress = trainingRepo.planProgress(plan.id)
                _state.update {
                    it.copy(isLoading = false, plan = plan, sessions = sessions, progress = progress)
                }
            }
        }
    }

    fun setSessionStatus(sessionId: Long, status: SessionStatus) {
        viewModelScope.launch {
            when (status) {
                SessionStatus.COMPLETED -> trainingRepo.completeSession(sessionId)
                SessionStatus.SKIPPED -> trainingRepo.skipSession(sessionId)
                SessionStatus.PLANNED -> trainingRepo.replanSession(sessionId)
            }
        }
    }
}

data class SessionDetailUiState(
    val isLoading: Boolean = true,
    val session: TrainingSession? = null,
    val exercises: List<TrainingExercise> = emptyList()
)

@HiltViewModel
class TrainingSessionViewModel @Inject constructor(
    private val trainingRepo: TrainingRepository,
    savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<Long>("sessionId")
        ?: savedStateHandle.get<String>("sessionId")?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(SessionDetailUiState())
    val state: StateFlow<SessionDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            trainingRepo.getExercisesFlow(sessionId).collect { exercises ->
                val session = trainingRepo.getSession(sessionId)
                _state.update { it.copy(isLoading = false, session = session, exercises = exercises) }
            }
        }
    }

    fun updateActual(exerciseId: Long, sets: String?, reps: String?, weightKg: Double?, completed: Boolean?) {
        viewModelScope.launch {
            trainingRepo.updateActual(exerciseId, sets, reps, weightKg, completed)
        }
    }

    fun completeSession() {
        viewModelScope.launch {
            trainingRepo.completeSession(sessionId)
        }
    }

    fun skipSession() {
        viewModelScope.launch {
            trainingRepo.skipSession(sessionId)
        }
    }

    fun replanSession() {
        viewModelScope.launch {
            trainingRepo.replanSession(sessionId)
        }
    }
}
