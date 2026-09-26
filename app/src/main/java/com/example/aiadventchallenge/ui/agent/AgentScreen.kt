package com.example.aiadventchallenge.ui.agent

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aiadventchallenge.R
import com.example.aiadventchallenge.data.ChatMessage
import com.example.aiadventchallenge.data.agent.ContextStrategy
import com.example.aiadventchallenge.data.agent.Invariant
import com.example.aiadventchallenge.data.agent.InvariantCategory
import com.example.aiadventchallenge.data.agent.LongTermCategory
import com.example.aiadventchallenge.data.agent.LongTermEntry
import com.example.aiadventchallenge.data.agent.TaskStage
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    onBack: () -> Unit,
    onOpenProfile: () -> Unit
) {
    // Общий с экраном профиля ViewModel (скоуп на Activity), чтобы профиль применялся к агенту.
    val viewModel: AgentViewModel =
        viewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity)
    val messages by viewModel.messages.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val canRetry by viewModel.canRetry.collectAsState()
    val historyTokens by viewModel.historyTokens.collectAsState()
    val hasSavedContext by viewModel.hasSavedContext.collectAsState()
    val branchNames by viewModel.branchNames.collectAsState()
    val activeBranch by viewModel.activeBranch.collectAsState()
    val longTerm by viewModel.longTerm.collectAsState()
    val taskState by viewModel.taskState.collectAsState()
    val taskPaused by viewModel.taskPaused.collectAsState()
    val verifying by viewModel.verifying.collectAsState()
    val invariants by viewModel.invariants.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showLongTerm by rememberSaveable { mutableStateOf(false) }
    var showSystemPrompt by rememberSaveable { mutableStateOf(false) }
    var showInvariants by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.agent_settings)
                        )
                    }
                    IconButton(onClick = viewModel::clearChat) {
                        Icon(
                            Icons.Outlined.DeleteSweep,
                            contentDescription = stringResource(R.string.agent_clear)
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (settings.strategy == ContextStrategy.BRANCHING) {
                    BranchControls(
                        branches = branchNames,
                        active = activeBranch,
                        onSwitch = viewModel::switchBranch,
                        onCheckpoint = viewModel::saveCheckpoint,
                        onFork = viewModel::forkFromCheckpoint
                    )
                }
                if (settings.taskState) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = buildString {
                                    append(stringResource(R.string.task_bar_stage, taskState.stage.name))
                                    append(stringResource(R.string.task_bar_step, taskState.step))
                                    taskState.stepLabel.takeIf { it.isNotBlank() }?.let {
                                        append(" (").append(it).append(")")
                                    }
                                    taskState.expectedAction.takeIf { it.isNotBlank() }?.let {
                                        append("\n").append(stringResource(R.string.task_bar_expected, it))
                                    }
                                    if (taskPaused) append("\n").append(stringResource(R.string.task_bar_paused))
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            StageDropdown(
                                current = taskState.stage,
                                onRequest = viewModel::requestStage
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (taskState.stage == TaskStage.VALIDATION) {
                                OutlinedButton(
                                    onClick = viewModel::verifyTaskResult,
                                    enabled = !verifying
                                ) {
                                    Text(
                                        if (verifying) {
                                            stringResource(R.string.task_verifying)
                                        } else {
                                            stringResource(R.string.task_verify)
                                        }
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = if (taskPaused) viewModel::resumeTask else viewModel::pauseTask
                            ) {
                                Text(stringResource(if (taskPaused) R.string.task_resume else R.string.task_pause))
                            }
                            OutlinedButton(onClick = viewModel::resetTask) {
                                Text(stringResource(R.string.task_reset))
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(
                        R.string.agent_stats,
                        stats.requests,
                        stats.totalTokens,
                        historyTokens,
                        "%.6f".format(stats.costUsd)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Text(
                    text = stringResource(
                        R.string.agent_stats_compact,
                        stats.compactions,
                        stats.savedTokens,
                        viewModel.summaryLength
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Text(
                    text = stringResource(
                        R.string.agent_stats_memory,
                        viewModel.historySize,
                        viewModel.workingChars,
                        viewModel.longTermCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.agent_input_hint)) },
                        maxLines = 3
                    )
                    Spacer(Modifier.width(8.dp))
                    if (canRetry) {
                        OutlinedButton(onClick = viewModel::retry) {
                            Text(stringResource(R.string.retry))
                        }
                    } else {
                        Button(
                            onClick = {
                                viewModel.send(input)
                                input = ""
                            },
                            enabled = !sending
                        ) {
                            Text(stringResource(R.string.send))
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(
                        if (hasSavedContext) R.string.agent_empty_context else R.string.agent_empty
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages.size) { i ->
                    MessageBubble(messages[i])
                }
                if (sending) {
                    item { TypingBubble() }
                }
            }
            LaunchedEffect(messages.size, sending) {
                val target = if (sending) messages.size else (messages.size - 1).coerceAtLeast(0)
                listState.animateScrollToItem(target)
            }
        }
    }

    if (showSettings) {
        AgentSettingsDialog(
            settings = settings,
            models = viewModel.availableModels,
            onDismiss = { showSettings = false },
            onChange = { newSettings -> viewModel.updateSettings { newSettings } },
            onOpenSystemPrompt = { showSettings = false; showSystemPrompt = true },
            onOpenLongTerm = { showSettings = false; showLongTerm = true },
            onOpenProfile = { showSettings = false; onOpenProfile() },
            onOpenInvariants = { showSettings = false; showInvariants = true }
        )
    }

    if (showSystemPrompt) {
        SystemPromptPreviewDialog(
            prompt = viewModel.effectiveSystemPrompt,
            onDismiss = { showSystemPrompt = false }
        )
    }

    if (showLongTerm) {
        LongTermMemoryDialog(
            entries = longTerm,
            onAdd = viewModel::addLongTerm,
            onRemove = viewModel::removeLongTerm,
            onClear = viewModel::clearLongTerm,
            onDismiss = { showLongTerm = false }
        )
    }

    if (showInvariants) {
        InvariantsDialog(
            invariants = invariants,
            onAdd = viewModel::addInvariant,
            onRemove = viewModel::removeInvariant,
            onClear = viewModel::clearInvariants,
            onDismiss = { showInvariants = false }
        )
    }
}

@Composable
private fun AgentSettingsDialog(
    settings: AgentSettings,
    models: List<String>,
    onDismiss: () -> Unit,
    onChange: (AgentSettings) -> Unit,
    onOpenSystemPrompt: () -> Unit,
    onOpenLongTerm: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenInvariants: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = settings.systemPrompt,
                        onValueChange = { onChange(settings.copy(systemPrompt = it)) },
                        label = { Text(stringResource(R.string.settings_system_prompt)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = settings.team,
                            onValueChange = { onChange(settings.copy(team = it)) },
                            label = { Text(stringResource(R.string.settings_team)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(R.string.settings_team_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.settings_temperature, settings.temperature),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = settings.temperature,
                            onValueChange = { onChange(settings.copy(temperature = it)) },
                            valueRange = 0f..2f
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.settings_model),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        ModelDropdown(
                            selected = settings.model,
                            models = models,
                            onSelect = { onChange(settings.copy(model = it)) }
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.settings_json),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = settings.jsonFormat,
                            onCheckedChange = { onChange(settings.copy(jsonFormat = it)) }
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.settings_longterm),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = settings.longTerm,
                                onCheckedChange = { onChange(settings.copy(longTerm = it)) }
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_longterm_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.settings_task),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = settings.taskState,
                                onCheckedChange = { onChange(settings.copy(taskState = it)) }
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_task_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.settings_mcp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = settings.mcp,
                                onCheckedChange = { onChange(settings.copy(mcp = it)) }
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_mcp_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = settings.mcpEndpoints,
                            onValueChange = { onChange(settings.copy(mcpEndpoints = it)) },
                            label = { Text(stringResource(R.string.settings_mcp_endpoints)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                        Text(
                            text = stringResource(R.string.settings_mcp_endpoints_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.settings_invariants),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = settings.invariants,
                                onCheckedChange = { onChange(settings.copy(invariants = it)) }
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_invariants_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.settings_invariants_guard),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = settings.invariantGuard,
                                onCheckedChange = { onChange(settings.copy(invariantGuard = it)) }
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_invariants_guard_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = onOpenInvariants,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.settings_manage_invariants))
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenSystemPrompt,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.settings_show_sysprompt))
                        }
                        OutlinedButton(
                            onClick = onOpenLongTerm,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.settings_manage_longterm))
                        }
                    }
                }

                item {
                    OutlinedButton(
                        onClick = onOpenProfile,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_profile))
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.settings_strategy),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        StrategyDropdown(
                            selected = settings.strategy,
                            onSelect = { onChange(settings.copy(strategy = it)) }
                        )
                    }
                }

                if (settings.strategy != ContextStrategy.BRANCHING) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.settings_window, settings.historyWindow),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = settings.historyWindow.toFloat(),
                                onValueChange = { onChange(settings.copy(historyWindow = it.roundToInt())) },
                                valueRange = 5f..30f
                            )
                            Text(
                                text = stringResource(R.string.settings_window_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_done))
            }
        }
    )
}

@Composable
private fun StrategyDropdown(
    selected: ContextStrategy,
    onSelect: (ContextStrategy) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected.label(), maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ContextStrategy.values().forEach { strategy ->
                DropdownMenuItem(
                    text = { Text(strategy.label()) },
                    onClick = {
                        onSelect(strategy)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun ContextStrategy.label(): String = when (this) {
    ContextStrategy.SLIDING_WINDOW -> "Скользящее окно"
    ContextStrategy.FACTS -> "Факты (ключ-значение)"
    ContextStrategy.BRANCHING -> "Ветки диалога"
    ContextStrategy.SUMMARY -> "Резюме (сжатие)"
}

@Composable
private fun SystemPromptPreviewDialog(
    prompt: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sysprompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.sysprompt_desc),
                    style = MaterialTheme.typography.bodySmall
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    Text(
                        text = prompt.ifBlank { stringResource(R.string.sysprompt_empty) },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_done))
            }
        }
    )
}

@Composable
private fun LongTermMemoryDialog(
    entries: List<LongTermEntry>,
    onAdd: (LongTermCategory, String) -> Unit,
    onRemove: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var category by remember { mutableStateOf(LongTermCategory.PROFILE) }
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.longterm_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.longterm_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.longterm_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        entries.forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "[${entry.category.name.lowercase()}] ${entry.content}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onRemove(entry.id) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.longterm_remove)
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LongTermCategoryDropdown(
                        selected = category,
                        onSelect = { category = it }
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text(stringResource(R.string.longterm_add_hint)) },
                        modifier = Modifier.weight(1f),
                        maxLines = 2
                    )
                }
                Button(
                    onClick = {
                        if (content.isNotBlank()) {
                            onAdd(category, content.trim())
                            content = ""
                        }
                    },
                    enabled = content.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.longterm_add))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.longterm_clear))
            }
        }
    )
}

@Composable
private fun LongTermCategoryDropdown(
    selected: LongTermCategory,
    onSelect: (LongTermCategory) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected.label(), maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LongTermCategory.values().forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.label()) },
                    onClick = {
                        onSelect(category)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun LongTermCategory.label(): String = when (this) {
    LongTermCategory.PROFILE -> "Профиль"
    LongTermCategory.DECISION -> "Решение"
    LongTermCategory.KNOWLEDGE -> "Знание"
}

@Composable
private fun InvariantsDialog(
    invariants: List<Invariant>,
    onAdd: (InvariantCategory, String) -> Unit,
    onRemove: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var category by remember { mutableStateOf(InvariantCategory.ARCHITECTURE) }
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.invariants_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.invariants_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                if (invariants.isEmpty()) {
                    Text(
                        text = stringResource(R.string.invariants_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        invariants.forEach { inv ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "[${inv.category.name.lowercase()}] ${inv.content}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onRemove(inv.id) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.invariants_remove)
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InvariantCategoryDropdown(
                        selected = category,
                        onSelect = { category = it }
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text(stringResource(R.string.invariants_add_hint)) },
                        modifier = Modifier.weight(1f),
                        maxLines = 2
                    )
                }
                Button(
                    onClick = {
                        if (content.isNotBlank()) {
                            onAdd(category, content.trim())
                            content = ""
                        }
                    },
                    enabled = content.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.invariants_add))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.invariants_clear))
            }
        }
    )
}

@Composable
private fun InvariantCategoryDropdown(
    selected: InvariantCategory,
    onSelect: (InvariantCategory) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected.label(), maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            InvariantCategory.values().forEach { cat ->
                DropdownMenuItem(
                    text = { Text(cat.label()) },
                    onClick = {
                        onSelect(cat)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun InvariantCategory.label(): String = when (this) {
    InvariantCategory.ARCHITECTURE -> "Архитектура"
    InvariantCategory.DECISIONS -> "Решения"
    InvariantCategory.STACK -> "Стек"
    InvariantCategory.BUSINESS -> "Бизнес"
}

@Composable
private fun StageDropdown(
    current: TaskStage,
    onRequest: (TaskStage) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.task_stage_switch, current.name), maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TaskStage.values().forEach { stage ->
                DropdownMenuItem(
                    text = { Text(stage.name, maxLines = 1) },
                    onClick = {
                        onRequest(stage)
                        expanded = false
                    }
                )
            }
        }
    }
}


@Composable
private fun ModelDropdown(
    selected: String,
    models: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model, maxLines = 1) },
                    onClick = {
                        onSelect(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun BranchControls(
    branches: List<String>,
    active: String,
    onSwitch: (String) -> Unit,
    onCheckpoint: () -> Unit,
    onFork: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text("ветка: $active", maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                branches.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name, maxLines = 1) },
                        onClick = {
                            onSwitch(name)
                            expanded = false
                        }
                    )
                }
            }
        }
        OutlinedButton(onClick = onCheckpoint) {
            Text(stringResource(R.string.branch_checkpoint))
        }
        OutlinedButton(onClick = onFork) {
            Text(stringResource(R.string.branch_fork))
        }
    }
}

@Composable
private fun TypingBubble() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            Text(
                text = stringResource(R.string.agent_typing),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    if (message.role == "system") {
        Text(
            text = message.content,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        return
    }
    val isUser = message.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium
                )
                message.outputTokens?.let { out ->
                    Text(
                        text = buildString {
                            append(stringResource(R.string.agent_bubble_tokens, message.inputTokens ?: 0, out))
                            message.model?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                            if (message.compacted) {
                                append(" · ").append(stringResource(R.string.agent_compacted))
                            }
                            if (message.truncated) {
                                append(" · ").append(stringResource(R.string.agent_truncated))
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}