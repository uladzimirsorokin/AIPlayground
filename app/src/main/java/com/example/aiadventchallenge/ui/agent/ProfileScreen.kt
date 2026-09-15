package com.example.aiadventchallenge.ui.agent

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aiadventchallenge.R
import com.example.aiadventchallenge.data.agent.SavedProfile
import com.example.aiadventchallenge.data.agent.UserProfile

/**
 * Полноценный экран управления профилями пользователя (День 12):
 * библиотека профилей, выбор активного, создание/переименование/удаление,
 * ручное редактирование и сборка из диалога.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit
) {
    // Общий с экраном агента ViewModel (скоуп на Activity), чтобы профиль жил в одном месте.
    val viewModel: AgentViewModel =
        viewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity)
    val savedProfiles by viewModel.savedProfiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val buildingProfile by viewModel.buildingProfile.collectAsState()

    val active = savedProfiles.firstOrNull { it.id == activeProfileId }
    // Локальное состояние полей: источник правды для ввода, чтобы рекомпозиции не ломали фокус.
    // Переинициализируется при смене активного профиля и после сборки из диалога.
    var label by remember(activeProfileId, buildingProfile) { mutableStateOf(active?.label ?: "") }
    var newLabel by remember { mutableStateOf("") }
    var name by remember(activeProfileId, buildingProfile) { mutableStateOf(active?.profile?.name ?: "") }
    var style by remember(activeProfileId, buildingProfile) { mutableStateOf(active?.profile?.style ?: "") }
    var format by remember(activeProfileId, buildingProfile) { mutableStateOf(active?.profile?.format ?: "") }
    var constraints by remember(activeProfileId, buildingProfile) { mutableStateOf(active?.profile?.constraints ?: "") }
    var extra by remember(activeProfileId, buildingProfile) { mutableStateOf(active?.profile?.extra ?: "") }

    fun current(): UserProfile = UserProfile(name, style, format, constraints, extra)

    fun persist() {
        viewModel.updateProfile(current(), label)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = {
                    IconButton(onClick = { persist(); onBack() }) {
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.profile_hint),
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProfileSelectDropdown(
                    profiles = savedProfiles,
                    activeId = activeProfileId,
                    onSelect = viewModel::selectProfile
                )
                OutlinedButton(
                    onClick = {
                        if (newLabel.isNotBlank()) {
                            viewModel.createProfile(newLabel.trim())
                            newLabel = ""
                        }
                    },
                    enabled = newLabel.isNotBlank()
                ) {
                    Text(stringResource(R.string.profile_new))
                }
            }
            OutlinedTextField(
                value = newLabel,
                onValueChange = { newLabel = it },
                label = { Text(stringResource(R.string.profile_new_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = label,
                onValueChange = {
                    label = it
                    persist()
                },
                label = { Text(stringResource(R.string.profile_label)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    persist()
                },
                label = { Text(stringResource(R.string.profile_name)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = style,
                onValueChange = {
                    style = it
                    persist()
                },
                label = { Text(stringResource(R.string.profile_style)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = format,
                onValueChange = {
                    format = it
                    persist()
                },
                label = { Text(stringResource(R.string.profile_format)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = constraints,
                onValueChange = {
                    constraints = it
                    persist()
                },
                label = { Text(stringResource(R.string.profile_constraints)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = extra,
                onValueChange = {
                    extra = it
                    persist()
                },
                label = { Text(stringResource(R.string.profile_extra)) },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = viewModel::buildProfile,
                enabled = !buildingProfile,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (buildingProfile) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.profile_build))
                }
            }

            Text(
                text = stringResource(R.string.profile_preview),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                Text(
                    text = current().text.ifBlank { stringResource(R.string.profile_preview_empty) },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                )
            }

            activeProfileId?.let {
                OutlinedButton(
                    onClick = { viewModel.deleteProfile(it) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.profile_delete))
                }
            }
            OutlinedButton(
                onClick = {
                    name = ""; style = ""; format = ""; constraints = ""; extra = ""
                    viewModel.clearProfile()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.profile_clear))
            }
        }
    }
}

@Composable
private fun ProfileSelectDropdown(
    profiles: List<SavedProfile>,
    activeId: String?,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val activeLabel = profiles.firstOrNull { it.id == activeId }?.label
        ?: stringResource(R.string.profile_none)
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(activeLabel, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { sp ->
                DropdownMenuItem(
                    text = { Text(sp.label, maxLines = 1) },
                    onClick = {
                        onSelect(sp.id)
                        expanded = false
                    }
                )
            }
        }
    }
}