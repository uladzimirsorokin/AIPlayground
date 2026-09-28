package com.example.aiadventchallenge.ui.rag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aiadventchallenge.R

/**
 * RAG (День 21): экран индексации документов — путь к подпапке tools/...,
 * выбор стратегии чанкинга, «Проиндексировать» (эмбеддинги через локальный
 * ollama nomic-embed-text), статус индексов и поиск.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagScreen(
    onBack: () -> Unit,
    viewModel: RagViewModel = viewModel()
) {
    val endpoint by viewModel.endpoint.collectAsState()
    val source by viewModel.source.collectAsState()
    val strategy by viewModel.strategy.collectAsState()
    val chunkSize by viewModel.chunkSize.collectAsState()
    val overlap by viewModel.overlap.collectAsState()
    val query by viewModel.query.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val indexes by viewModel.indexes.collectAsState()
    val hits by viewModel.hits.collectAsState()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rag_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = viewModel::setEndpoint,
                    label = { Text(stringResource(R.string.rag_endpoint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = source,
                        onValueChange = viewModel::setSource,
                        label = { Text(stringResource(R.string.rag_source)) },
                        placeholder = { Text("docs") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text(
                        text = stringResource(R.string.rag_source_desc),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item {
                StrategyDropdown(
                    selected = strategy,
                    onSelect = viewModel::setStrategy
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = chunkSize,
                        onValueChange = viewModel::setChunkSize,
                        label = { Text(stringResource(R.string.rag_chunk_size)) },
                        placeholder = { Text("150") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = overlap,
                        onValueChange = viewModel::setOverlap,
                        label = { Text(stringResource(R.string.rag_overlap)) },
                        placeholder = { Text("30") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = viewModel::buildIndex,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.rag_build))
                        }
                    }
                    OutlinedButton(
                        onClick = viewModel::loadStatus,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.rag_status))
                    }
                }
            }
            if (message.isNotBlank()) {
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (indexes.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.rag_indexes),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                items(indexes) { entry ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = entry,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = viewModel::setQuery,
                            label = { Text(stringResource(R.string.rag_query)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedButton(
                            onClick = viewModel::search,
                            enabled = !busy && query.isNotBlank(),
                            modifier = Modifier.weight(0.4f)
                        ) {
                            Text(stringResource(R.string.rag_search))
                        }
                    }
                    Text(
                        text = stringResource(R.string.rag_search_desc, "ollama nomic-embed-text"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (hits.isNotEmpty()) {
                items(hits) { hit ->
                    HitRow(hit)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StrategyDropdown(
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.rag_strategy)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            listOf("fixed", "structure").forEach { s ->
                DropdownMenuItem(
                    text = { Text(s) },
                    onClick = {
                        onSelect(s)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun HitRow(hit: RagSearchHit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${"%.4f".format(hit.score)} · ${hit.source} :: ${hit.section}",
                style = MaterialTheme.typography.titleSmall
            )
            hit.snippet.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}