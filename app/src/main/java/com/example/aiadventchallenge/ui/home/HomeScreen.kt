package com.example.aiadventchallenge.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    var apiKey by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsState()
    val hasKey by viewModel.hasKey.collectAsState()

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
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.prompt_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Button(
                    onClick = { viewModel.send(prompt) },
                    enabled = uiState !is UiState.Loading,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.send))
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