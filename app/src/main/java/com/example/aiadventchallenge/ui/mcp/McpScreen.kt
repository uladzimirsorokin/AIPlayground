package com.example.aiadventchallenge.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aiadventchallenge.R
import com.example.aiadventchallenge.data.mcp.McpTool

/**
 * Экран проверки MCP (День 16): поле эндпоинта, «Подключиться» и список
 * инструментов, полученных от сервера.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    onBack: () -> Unit,
    viewModel: McpViewModel = viewModel()
) {
    val endpoint by viewModel.endpoint.collectAsState()
    val connecting by viewModel.connecting.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val error by viewModel.error.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mcp_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = endpoint,
                onValueChange = viewModel::setEndpoint,
                label = { Text(stringResource(R.string.mcp_endpoint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.setEndpoint(McpViewModel.PUBLIC_ENDPOINT)
                        viewModel.connect()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.mcp_preset_public))
                }
                OutlinedButton(
                    onClick = {
                        viewModel.setEndpoint(McpViewModel.LOCAL_ENDPOINT)
                        viewModel.connect()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.mcp_preset_local))
                }
            }
            Button(
                onClick = viewModel::connect,
                enabled = !connecting && endpoint.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (connecting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.mcp_connect))
                }
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            connection?.let { conn ->
                Text(
                    text = stringResource(
                        R.string.mcp_connected,
                        conn.protocolVersion.ifBlank { "?" },
                        conn.tools.size
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(conn.tools, key = { it.name }) { tool ->
                        ToolRow(tool)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(tool: McpTool) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = tool.name, style = MaterialTheme.typography.titleSmall)
            tool.description.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
            tool.inputSchema?.let {
                Text(
                    text = stringResource(R.string.mcp_schema),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}