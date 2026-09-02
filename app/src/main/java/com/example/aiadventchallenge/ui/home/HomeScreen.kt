package com.example.aiadventchallenge.ui.home

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

                is UiState.Success -> Text(
                    text = state.response,
                    style = MaterialTheme.typography.bodyLarge
                )

                is UiState.CompareSuccess -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AIAdventChallengeTheme {
        HomeScreen()
    }
}