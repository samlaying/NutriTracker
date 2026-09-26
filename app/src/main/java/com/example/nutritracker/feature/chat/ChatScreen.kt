package com.example.nutritracker.feature.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.nutritracker.data.entity.ChatMessage
import com.example.nutritracker.data.entity.ChatRole
import com.example.nutritracker.harness.MissingField
import com.example.nutritracker.harness.PendingInteraction
import com.example.nutritracker.harness.TodoItem
import com.example.nutritracker.harness.ToolResolution
import com.example.nutritracker.ui.components.MacroProgressRow
import com.example.nutritracker.ui.theme.Dimens
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    rootNavController: NavController,
    vm: ChatViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val today = state.todaySummary
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var summaryExpanded by remember { mutableStateOf(false) }

    // 相机返回的图片
    val selectedUris = rootNavController.currentBackStackEntry?.savedStateHandle
        ?.getStateFlow<String?>("selected_image_uris", null)?.collectAsStateWithLifecycle()
    val selectedNotes = rootNavController.currentBackStackEntry?.savedStateHandle
        ?.getStateFlow<String?>("selected_image_notes", null)?.collectAsStateWithLifecycle()

    LaunchedEffect(selectedUris?.value) {
        val urisJson = selectedUris?.value
        if (!urisJson.isNullOrBlank()) {
            rootNavController.currentBackStackEntry?.savedStateHandle?.remove<String>("selected_image_uris")
            rootNavController.currentBackStackEntry?.savedStateHandle?.remove<String>("selected_image_notes")
            try {
                val listType = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                val uriStrings: List<String> = Gson().fromJson(urisJson, listType)
                vm.handlePhotoUris(context, uriStrings.map { Uri.parse(it) }, com.example.nutritracker.data.entity.IntakeType.SNACK, selectedNotes?.value ?: "")
            } catch (_: Exception) {
            }
        }
    }

    // Agent 的 open_screen 跳转
    val navigationTarget by vm.pendingNavigation.collectAsStateWithLifecycle()
    LaunchedEffect(navigationTarget) {
        navigationTarget?.let { route ->
            vm.consumeNavigation()
            rootNavController.navigate(route)
        }
    }

    // 新消息自动滚到底部
    val messageCount = state.messages.size + (if (state.live.isRunning) 1 else 0)
    LaunchedEffect(messageCount, state.live.streamText.length) {
        if (messageCount > 0) {
            listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶部：标题 + 可折叠今日摘要 ──
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.ContentPadding, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.SelfImprovement, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("教练", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { rootNavController.navigate(com.example.nutritracker.navigation.Screen.Training.route) }) {
                        Icon(Icons.Filled.FitnessCenter, contentDescription = "训练计划",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { rootNavController.navigate(com.example.nutritracker.navigation.Screen.Sources.route) }) {
                        Icon(Icons.Filled.MenuBook, contentDescription = "科学文献",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 折叠摘要条
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.ContentPadding)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { summaryExpanded = !summaryExpanded },
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.LocalFireDepartment, null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "今日 ${(today?.caloriesSupplied ?: 0.0).roundToInt()} / ${(today?.calorieGoal ?: 0.0).roundToInt()} kcal" +
                                    " · 剩余 ${((today?.calorieGoal ?: 0.0) + (today?.caloriesBurned ?: 0.0) - (today?.caloriesSupplied ?: 0.0)).roundToInt()}",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(
                                if (summaryExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                null, tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AnimatedVisibility(visible = summaryExpanded) {
                            Column(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MacroProgressRow(
                                    carbsCurrent = today?.carbsTracked ?: 0.0, carbsGoal = today?.carbsGoal ?: 0.0,
                                    fatCurrent = today?.fatTracked ?: 0.0, fatGoal = today?.fatGoal ?: 0.0,
                                    proteinCurrent = today?.proteinTracked ?: 0.0, proteinGoal = today?.proteinGoal ?: 0.0
                                )
                                Text(
                                    "饮水 ${today?.waterMl ?: 0}/${today?.waterGoalMl ?: 0} ml · 运动消耗 ${(today?.caloriesBurned ?: 0.0).roundToInt()} kcal · 完整仪表盘见「首页」",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // ── 消息列表 ──
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Dimens.ContentPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.todoItems.isNotEmpty()) {
                    item(key = "todo_card") {
                        TodoCard(items = state.todoItems)
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    MessageRow(message = message, vm = vm)
                }
                if (state.live.runningTools.isNotEmpty()) {
                    item(key = "running_tools") {
                        RunningToolsIndicator(tools = state.live.runningTools)
                    }
                }
                if (state.live.streamText.isNotEmpty() || (state.live.isRunning && state.live.runningTools.isEmpty() && state.live.streamText.isEmpty())) {
                    item(key = "live_stream") {
                        if (state.live.streamText.isNotEmpty()) {
                            AssistantBubble(text = state.live.streamText + " ▍")
                        } else {
                            ThinkingIndicator()
                        }
                    }
                }
            }
        }

        // ── 挂起的人工介入卡 ──
        state.pendingTool?.let { pending ->
            PendingInteractionCard(
                pending = pending.interaction,
                onFormSubmit = { values -> vm.answerTool(ToolResolution.FormFilled(values)) },
                onApprove = { vm.answerTool(ToolResolution.Approved) },
                onReject = { vm.answerTool(ToolResolution.Rejected) }
            )
        }

        // ── 快捷指令 ──
        if (state.messages.size <= 1 && !state.live.isRunning) {
            SuggestionChips(onSuggestion = { vm.send(it) })
        }

        // ── 输入栏 ──
        val locked = state.live.isRunning || state.pendingTool != null
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ContentPadding, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        rootNavController.navigate(
                            com.example.nutritracker.navigation.Screen.CameraCapture.createRoute(0)
                        )
                    },
                    enabled = !locked,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "拍照记录",
                        tint = if (locked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (locked) "教练正在处理…" else "跟教练聊聊，如：今天还能吃多少") },
                    maxLines = 4,
                    enabled = !locked,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                FilledIconButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            vm.send(input)
                            input = ""
                        }
                    },
                    enabled = !locked && input.isNotBlank(),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

// ── 消息行 ───────────────────────────────────────────────────────────────────

@Composable
private fun MessageRow(message: ChatMessage, vm: ChatViewModel) {
    when (message.role) {
        ChatRole.USER -> UserBubble(message)
        ChatRole.ASSISTANT -> {
            if (message.cardType == "task_done") {
                TaskDoneCard(message)
            } else if (message.content.isNotBlank()) {
                AssistantBubble(message.content)
            }
        }
        ChatRole.TOOL -> ToolMessageRow(message, vm)
    }
}

@Composable
private fun UserBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 300.dp)) {
            if (message.imagePath != null && java.io.File(message.imagePath.replace("file://", "")).exists()) {
                AsyncImage(
                    model = message.imagePath,
                    contentDescription = null,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Spacer(Modifier.height(4.dp))
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun AssistantBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("思考中…", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RunningToolsIndicator(tools: List<RunningTool>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tools.forEach { tool ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "调用 ${tool.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── 工具消息：无卡片 → 折叠 chip；有卡片 → 富卡 ─────────────────────────────

@Composable
private fun ToolMessageRow(message: ChatMessage, vm: ChatViewModel) {
    when (message.cardType) {
        "meal_logged" -> RecordCard(
            icon = Icons.Filled.Restaurant, title = "已记录饮食",
            body = message.summaryLine(), payload = message.payloadJson,
            onUndo = { vm.undoMealLogged(message.payloadJson) }
        )
        "water_logged" -> RecordCard(
            icon = Icons.Filled.WaterDrop, title = "已记录饮水",
            body = message.summaryLine(), payload = message.payloadJson,
            onUndo = { vm.undoWaterLogged(message.payloadJson) }
        )
        "weight_logged" -> RecordCard(
            icon = Icons.Filled.MonitorWeight, title = "已记录体重",
            body = message.summaryLine(), payload = message.payloadJson, onUndo = null
        )
        "activity_logged" -> RecordCard(
            icon = Icons.Filled.DirectionsRun, title = "已记录运动",
            body = message.summaryLine(), payload = message.payloadJson,
            onUndo = { vm.undoActivityLogged(message.payloadJson) }
        )
        "plan_created", "plan_updated", "plan_summary" -> RecordCard(
            icon = Icons.Filled.FitnessCenter, title = "训练计划",
            body = message.summaryLine(), payload = message.payloadJson,
            onUndo = null,
            onOpen = { vm.openTraining() }
        )
        "sources_cited" -> SourcesCard(message)
        "report_saved" -> RecordCard(
            icon = Icons.Filled.Description, title = "报告已保存",
            body = message.summaryLine(), payload = message.payloadJson, onUndo = null
        )
        "task_started" -> RecordCard(
            icon = Icons.Filled.HourglassTop, title = "后台任务已启动",
            body = message.summaryLine(), payload = message.payloadJson, onUndo = null
        )
        "profile_updated" -> RecordCard(
            icon = Icons.Filled.Person, title = "资料已更新",
            body = message.summaryLine(), payload = message.payloadJson, onUndo = null
        )
        else -> CollapsedToolChip(message)
    }
}

private fun ChatMessage.summaryLine(): String =
    content.lineSequence().firstOrNull { it.isNotBlank() } ?: content.take(80)

// ── 通用卡片 ─────────────────────────────────────────────────────────────────

@Composable
private fun RecordCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    payload: String?,
    onUndo: (() -> Unit)?,
    onOpen: (() -> Unit)? = null
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onUndo != null || onOpen != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        onUndo?.let {
                            TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("撤销", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        onOpen?.let {
                            TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("查看计划", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesCard(message: ChatMessage) {
    val context = LocalContext.current
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("科学依据", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            val payload = message.payloadJson?.let {
                runCatching { Gson().fromJson(it, List::class.java) }.getOrNull()
            }
            payload?.forEach { el ->
                val map = el as? Map<*, *> ?: return@forEach
                val title = map["title"] as? String ?: ""
                val citation = map["citation"] as? String ?: ""
                val url = map["url"] as? String ?: ""
                Column(Modifier.clickable {
                    if (url.isNotBlank()) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }) {
                    Text("· $title", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    if (citation.isNotBlank()) {
                        Text(citation, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskDoneCard(message: ChatMessage) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.TaskAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("后台任务完成", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text(message.content, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CollapsedToolChip(message: ChatMessage) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Build, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${message.toolName ?: "工具"}${if (expanded) " ▾" else " ▸"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(message.content, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── TodoList 卡（独立 state，永不随上下文摘要丢失） ──────────────────────────

@Composable
private fun TodoCard(items: List<TodoItem>) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Checklist, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("任务清单", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when (item.status) {
                        "complete" -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
                        "in_progress" -> Icons.Filled.Pending to MaterialTheme.colorScheme.tertiary
                        else -> Icons.Filled.RadioButtonUnchecked to MaterialTheme.colorScheme.outline
                    }
                    Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.content,
                        style = MaterialTheme.typography.bodySmall,
                        textDecoration = if (item.status == "complete") androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                    )
                }
            }
        }
    }
}

// ── HITL：表单补充 / 高危确认 ────────────────────────────────────────────────

@Composable
private fun PendingInteractionCard(
    pending: PendingInteraction,
    onFormSubmit: (Map<String, String>) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    when (pending) {
        is PendingInteraction.MissingData -> MissingDataForm(pending, onFormSubmit)
        is PendingInteraction.ConfirmWrite -> ConfirmWriteCard(pending, onApprove, onReject)
    }
}

@Composable
private fun MissingDataForm(
    data: PendingInteraction.MissingData,
    onSubmit: (Map<String, String>) -> Unit
) {
    val values = remember(data) { mutableStateMapOf<String, String>() }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ContentPadding),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(data.question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (data.collectedSummary.isNotBlank()) {
                Text("已知：${data.collectedSummary}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            data.fields.forEach { field ->
                Column {
                    Text(field.label, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        field.suggestions.forEach { suggestion ->
                            FilterChip(
                                selected = values[field.key] == suggestion,
                                onClick = { values[field.key] = suggestion },
                                label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    val missing = values[field.key] == null
                    if (missing && field.suggestions.isEmpty()) {
                        OutlinedTextField(
                            value = values[field.key] ?: "",
                            onValueChange = { values[field.key] = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall,
                            placeholder = { Text("输入${field.label}", style = MaterialTheme.typography.labelSmall) },
                            singleLine = true
                        )
                    }
                }
            }
            Button(
                onClick = { onSubmit(values.toMap()) },
                enabled = data.fields.all { !values[it.key].isNullOrBlank() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("补充完成，继续")
            }
        }
    }
}

@Composable
private fun ConfirmWriteCard(
    data: PendingInteraction.ConfirmWrite,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ContentPadding),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GppMaybe, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(6.dp))
                Text(data.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(data.detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove) { Text("同意执行") }
                OutlinedButton(onClick = onReject) { Text("取消") }
            }
        }
    }
}

// ── 快捷指令 ─────────────────────────────────────────────────────────────────

@Composable
private fun SuggestionChips(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        "今天还能吃多少？",
        "记录午餐：鸡胸肉沙拉一份",
        "这周吃得怎么样？",
        "帮我制定增肌训练计划"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dimens.ContentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        suggestions.forEach { s ->
            SuggestionChip(onClick = { onSuggestion(s) }, label = { Text(s, style = MaterialTheme.typography.labelSmall) })
        }
    }
}
