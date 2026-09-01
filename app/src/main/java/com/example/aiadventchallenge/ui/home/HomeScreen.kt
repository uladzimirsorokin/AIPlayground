package com.example.aiadventchallenge.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { viewModel.send(prompt) },
                        enabled = uiState !is UiState.Loading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.send))
                    }
                    Button(
                        onClick = { viewModel.compare(prompt, maxTokens, jsonFormat) },
                        enabled = uiState !is UiState.Loading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.compare))
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

                is UiState.Success -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = state.response,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                is UiState.CompareSuccess -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AIAdventChallengeTheme {
        HomeScreen()
    }
}