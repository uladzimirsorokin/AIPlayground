package com.example.aiadventchallenge.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aiadventchallenge.R
import com.example.aiadventchallenge.ui.theme.AIAdventChallengeTheme
import kotlin.math.roundToInt

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    var apiKey by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var maxTokens by rememberSaveable { mutableIntStateOf(200) }
    var jsonFormat by rememberSaveable { mutableStateOf(true) }
    var compareExpanded by rememberSaveable { mutableStateOf(false) }
    var reasoningExpanded by rememberSaveable { mutableStateOf(false) }
    var temperatureExpanded by rememberSaveable { mutableStateOf(false) }
    var modelsExpanded by rememberSaveable { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsState()
    val hasKey by viewModel.hasKey.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()
    var systemPromptInput by rememberSaveable(systemPrompt) { mutableStateOf(systemPrompt) }
    val stopSequences by viewModel.stopSequences.collectAsState()
    var stopInput by rememberSaveable(stopSequences) {
        mutableStateOf(stopSequences.joinToString("|") { it.replace("\n", "\\n") })
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium
            )

            if (!hasKey) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.api_key_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Button(
                    onClick = {
                        viewModel.saveKey(apiKey)
                        apiKey = ""
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.save))
                }
            } else {
                OutlinedTextField(
                    value = systemPromptInput,
                    onValueChange = {
                        systemPromptInput = it
                        viewModel.saveSystemPrompt(it)
                    },
                    label = { Text(stringResource(R.string.system_prompt_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.prompt_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Button(
                    onClick = { viewModel.send(prompt) },
                    enabled = uiState !is UiState.Loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.send))
                }

                OutlinedButton(
                    onClick = { reasoningExpanded = !reasoningExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (reasoningExpanded) R.string.reasoning_hide else R.string.reasoning_show
                        )
                    )
                }

                if (reasoningExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.reasoning_description),
                            style = MaterialTheme.typography.bodySmall
                        )
                        MethodItem(
                            title = stringResource(R.string.r_direct_title),
                            description = stringResource(R.string.r_direct_description)
                        )
                        MethodItem(
                            title = stringResource(R.string.r_step_title),
                            description = stringResource(R.string.r_step_description)
                        )
                        MethodItem(
                            title = stringResource(R.string.r_prompt_title),
                            description = stringResource(R.string.r_prompt_description)
                        )
                        MethodItem(
                            title = stringResource(R.string.r_experts_title),
                            description = stringResource(R.string.r_experts_description)
                        )
                        Button(
                            onClick = { viewModel.reasoning(prompt) },
                            enabled = uiState !is UiState.Loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.reasoning_run))
                        }
                    }
                }

                OutlinedButton(
                    onClick = { temperatureExpanded = !temperatureExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (temperatureExpanded) R.string.temperature_hide else R.string.temperature_show
                        )
                    )
                }

                if (temperatureExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.temperature_description),
                            style = MaterialTheme.typography.bodySmall
                        )
                        MethodItem(
                            title = stringResource(R.string.t0_title),
                            description = stringResource(R.string.t0_description)
                        )
                        MethodItem(
                            title = stringResource(R.string.t07_title),
                            description = stringResource(R.string.t07_description)
                        )
                        MethodItem(
                            title = stringResource(R.string.t12_title),
                            description = stringResource(R.string.t12_description)
                        )
                        Button(
                            onClick = { viewModel.temperature(prompt) },
                            enabled = uiState !is UiState.Loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.temperature_run))
                        }
                    }
                }

                OutlinedButton(
                    onClick = { modelsExpanded = !modelsExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (modelsExpanded) R.string.models_hide else R.string.models_show
                        )
                    )
                }

                if (modelsExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.models_description),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = { viewModel.models(prompt) },
                            enabled = uiState !is UiState.Loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.models_run))
                        }
                    }
                }

                OutlinedButton(
                    onClick = { compareExpanded = !compareExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (compareExpanded) R.string.compare_hide else R.string.compare_show
                        )
                    )
                }

                if (compareExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.compare_description),
                            style = MaterialTheme.typography.bodySmall
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.max_tokens_label, maxTokens),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Slider(
                                value = maxTokens.toFloat(),
                                onValueChange = { maxTokens = it.roundToInt() },
                                valueRange = 50f..2000f
                            )
                            Text(
                                text = stringResource(R.string.max_tokens_description),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.json_format),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Switch(
                                    checked = jsonFormat,
                                    onCheckedChange = { jsonFormat = it }
                                )
                            }
                            Text(
                                text = stringResource(R.string.json_description),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        OutlinedTextField(
                            value = stopInput,
                            onValueChange = {
                                stopInput = it
                                viewModel.saveStopSequences(it.replace("\\n", "\n"))
                            },
                            label = { Text(stringResource(R.string.stop_label)) },
                            supportingText = {
                                Text(stringResource(R.string.stop_support))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Button(
                            onClick = { viewModel.compare(prompt, maxTokens, jsonFormat) },
                            enabled = uiState !is UiState.Loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.compare))
                        }
                    }
                }
            }

            when (val state = uiState) {
                is UiState.Idle -> Text(
                    text = stringResource(R.string.idle_hint),
                    style = MaterialTheme.typography.bodyMedium
                )

                is UiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                is UiState.Success -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CopyButton(state.response)
                    Text(
                        text = state.response,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                is UiState.CompareSuccess -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CopyButton(buildCompareText(state))
                    Text(
                        text = stringResource(R.string.compare_plain_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = state.plain,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.compare_constrained_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = state.constrained,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                is UiState.ReasoningSuccess -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CopyButton(buildReasoningText(state))
                    ReasoningSection(stringResource(R.string.r_direct_title), state.direct)
                    ReasoningSection(stringResource(R.string.r_step_title), state.stepByStep)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.r_prompt_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.r_prompt_composed_label),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = state.composedPrompt,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.r_prompt_answer_label),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = state.promptComposed,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        HorizontalDivider()
                    }
                    ReasoningSection(stringResource(R.string.r_experts_title), state.experts)
                    ReasoningSection(stringResource(R.string.r_best_title), state.best)
                }

                is UiState.TemperatureSuccess -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CopyButton(buildTemperatureText(state))
                    ReasoningSection(stringResource(R.string.t0_title), state.temp0)
                    ReasoningSection(stringResource(R.string.t07_title), state.temp07)
                    ReasoningSection(stringResource(R.string.t12_title), state.temp12)
                }

                is UiState.ModelSuccess -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CopyButton(buildModelsText(state))
                    ModelSection(state.weak)
                    ModelSection(state.medium)
                    ModelSection(state.strong)
                }

                is UiState.Error -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ReasoningSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium
        )
        HorizontalDivider()
    }
}

@Composable
private fun MethodItem(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun CopyButton(text: String) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LLM response", text))
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.copy))
    }
}

@Composable
private fun buildCompareText(state: UiState.CompareSuccess): String = buildString {
    append(stringResource(R.string.compare_plain_title)).append(":\n").append(state.plain)
    append("\n\n").append(stringResource(R.string.compare_constrained_title)).append(":\n").append(state.constrained)
}

@Composable
private fun buildReasoningText(state: UiState.ReasoningSuccess): String = buildString {
    append(stringResource(R.string.r_direct_title)).append(":\n").append(state.direct)
    append("\n\n").append(stringResource(R.string.r_step_title)).append(":\n").append(state.stepByStep)
    append("\n\n").append(stringResource(R.string.r_prompt_title)).append(":\n").append(state.composedPrompt)
    append("\n\n").append(stringResource(R.string.r_prompt_answer_label)).append(":\n").append(state.promptComposed)
    append("\n\n").append(stringResource(R.string.r_experts_title)).append(":\n").append(state.experts)
    append("\n\n").append(stringResource(R.string.r_best_title)).append(":\n").append(state.best)
}

@Composable
private fun buildTemperatureText(state: UiState.TemperatureSuccess): String = buildString {
    append(stringResource(R.string.t0_title)).append(":\n").append(state.temp0)
    append("\n\n").append(stringResource(R.string.t07_title)).append(":\n").append(state.temp07)
    append("\n\n").append(stringResource(R.string.t12_title)).append(":\n").append(state.temp12)
}

@Composable
private fun ModelSection(result: UiState.ModelResult) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "${result.label} — ${result.model}",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = "⏱ ${"%.1f".format(result.timeMs / 1000.0)} с · токены: ${result.totalTokens} · стоимость: \$${"%.6f".format(result.costUsd)}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = result.content,
            style = MaterialTheme.typography.bodyMedium
        )
        HorizontalDivider()
    }
}

@Composable
private fun buildModelsText(state: UiState.ModelSuccess): String = buildString {
    listOf(state.weak, state.medium, state.strong).forEach { r ->
        append(r.label).append(" — ").append(r.model).append(":\n")
        append("⏱ ").append("%.1f".format(r.timeMs / 1000.0)).append(" с, токены: ").append(r.totalTokens)
        append(", стоимость: $").append("%.6f".format(r.costUsd)).append("\n")
        append(r.content).append("\n\n")
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AIAdventChallengeTheme {
        HomeScreen()
    }
}