package com.example.nutritracker.feature.training

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nutritracker.data.entity.SessionStatus
import com.example.nutritracker.ui.theme.Dimens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// ── 计划概览页 ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingPlanScreen(
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit,
    vm: TrainingViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("训练计划", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val plan = state.plan
        if (plan == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(Dimens.ContentPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.FitnessCenter, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("还没有训练计划", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "去「教练」页对我说：\n「帮我制定一份 4 周增肌训练计划」",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            return@Scaffold
        }

        val progress = state.progress
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(Dimens.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "plan_header") {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(plan.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "${plan.goal} · ${plan.weeksTotal} 周 · ${plan.startDate} 开始",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (progress != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LinearProgressIndicator(
                                    progress = { if (progress.total == 0) 0f else progress.completed.toFloat() / progress.total },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "${progress.completed}/${progress.total}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            if (plan.notes != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    plan.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            val grouped = state.sessions.groupBy { weekOf(it.scheduledDate, plan.startDate) }
            grouped.toSortedMap().forEach { (week, sessions) ->
                item(key = "week_$week") {
                    Text(
                        "第 $week 周",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(sessions, key = { it.id }) { session ->
                    SessionRow(session = session, onClick = { onOpenSession(session.id) })
                }
            }
        }
    }
}

private fun weekOf(date: LocalDate, startDate: LocalDate): Int =
    (java.time.temporal.ChronoUnit.DAYS.between(startDate, date) / 7).toInt() + 1

@Composable
private fun SessionRow(session: com.example.nutritracker.data.entity.TrainingSession, onClick: () -> Unit) {
    val (icon, tint) = when (session.status) {
        SessionStatus.COMPLETED -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        SessionStatus.SKIPPED -> Icons.Filled.Cancel to MaterialTheme.colorScheme.error
        SessionStatus.PLANNED -> if (session.scheduledDate == LocalDate.now()) {
            Icons.Filled.RadioButtonChecked to MaterialTheme.colorScheme.tertiary
        } else {
            Icons.Filled.RadioButtonUnchecked to MaterialTheme.colorScheme.outline
        }
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(session.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${session.scheduledDate.format(DateTimeFormatter.ofPattern("M月d日 EEEE"))}${session.focus?.let { " · $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

// ── 会话详情页 ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingSessionDetailScreen(
    onBack: () -> Unit,
    vm: TrainingSessionViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.session?.let { "${it.scheduledDate.format(DateTimeFormatter.ofPattern("M月d日"))} ${it.title}" }
                            ?: "训练日",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    val session = state.session
                    if (session != null && session.status == SessionStatus.PLANNED) {
                        TextButton(onClick = { vm.completeSession() }) { Text("完成") }
                        TextButton(onClick = { vm.skipSession() }) { Text("跳过") }
                    } else if (session != null && session.status != SessionStatus.PLANNED) {
                        TextButton(onClick = { vm.replanSession() }) { Text("重排") }
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(Dimens.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "status_banner") {
                val session = state.session
                val (text, color) = when (session?.status) {
                    SessionStatus.COMPLETED -> "已完成" to MaterialTheme.colorScheme.primary
                    SessionStatus.SKIPPED -> "已跳过" to MaterialTheme.colorScheme.error
                    else -> "待训练" to MaterialTheme.colorScheme.tertiary
                }
                Surface(
                    color = color.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "$text · ${state.exercises.count { it.completed }}/${state.exercises.size} 个动作完成",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            items(state.exercises, key = { it.id }) { exercise ->
                ExerciseRow(exercise = exercise, onUpdate = { sets, reps, weight, completed ->
                    vm.updateActual(exercise.id, sets, reps, weight, completed)
                })
            }
        }
    }
}

@Composable
private fun ExerciseRow(
    exercise: com.example.nutritracker.data.entity.TrainingExercise,
    onUpdate: (sets: String?, reps: String?, weightKg: Double?, completed: Boolean?) -> Unit
) {
    var showEdit by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showEdit = true },
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (exercise.completed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = exercise.completed,
                onCheckedChange = { checked ->
                    onUpdate(exercise.actualSets, exercise.actualReps, exercise.actualWeightKg, checked)
                }
            )
            Column(Modifier.weight(1f)) {
                Text(exercise.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append("${exercise.targetSets}组 × ${exercise.targetReps}次")
                        exercise.targetWeightKg?.let { append(" @${it.roundToInt()}kg") }
                        exercise.rpeTarget?.let { append(" · $it") }
                        exercise.restSeconds?.let { append(" · 休息${it}s") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (exercise.actualWeightKg != null || exercise.actualSets != null) {
                    Text(
                        buildString {
                            append("实际：${exercise.actualSets ?: exercise.targetSets}组×${exercise.actualReps ?: exercise.targetReps}次")
                            exercise.actualWeightKg?.let { append(" @${it.roundToInt()}kg") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                exercise.notes?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showEdit) {
        var setsText by remember { mutableStateOf(exercise.actualSets ?: exercise.targetSets) }
        var repsText by remember { mutableStateOf(exercise.actualReps ?: exercise.targetReps) }
        var weightText by remember { mutableStateOf(exercise.actualWeightKg?.roundToInt()?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text(exercise.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = setsText, onValueChange = { setsText = it }, label = { Text("实际组数") }, singleLine = true)
                    OutlinedTextField(value = repsText, onValueChange = { repsText = it }, label = { Text("实际次数") }, singleLine = true)
                    OutlinedTextField(value = weightText, onValueChange = { weightText = it }, label = { Text("实际重量(kg)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdate(
                        setsText.takeIf { it.isNotBlank() },
                        repsText.takeIf { it.isNotBlank() },
                        weightText.toDoubleOrNull(),
                        true
                    )
                    showEdit = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("取消") } }
        )
    }
}
