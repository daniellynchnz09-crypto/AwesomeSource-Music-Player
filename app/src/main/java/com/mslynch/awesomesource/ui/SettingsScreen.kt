package com.mslynch.awesomesource.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Settings for the three values the organization pipeline needs - the Compose
 * equivalent of `legacy-expo-attempt/app/settings.tsx`. Each field auto-saves to
 * [MainViewModel] (backed by [com.mslynch.awesomesource.organize.settings.SecureSettings]'s
 * Keystore-encrypted storage) when it loses focus, same as the Expo version's
 * `onBlur` behavior, so partial edits to one field are never lost while editing
 * another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsField(
                label = "Gemini API key",
                hint = "Free tier, from Google AI Studio. Used for the LLM double-check pass on ambiguous MusicBrainz matches.",
                initialValue = viewModel.geminiApiKey,
                onSave = viewModel::updateGeminiApiKey,
                secure = true,
            )

            SettingsField(
                label = "AcoustID API key",
                hint = "Must be an application key from acoustid.org/new-applications, not your personal account key - lookups reject the latter.",
                initialValue = viewModel.acoustIdApiKey,
                onSave = viewModel::updateAcoustIdApiKey,
                secure = true,
            )

            SettingsField(
                label = "MusicBrainz contact",
                hint = "Optional but recommended - any string (email/URL) identifying this app's requests, per MusicBrainz's API etiquette.",
                initialValue = viewModel.musicBrainzContact,
                onSave = viewModel::updateMusicBrainzContact,
                secure = false,
            )

            Text(
                "These are stored only on this device (Android Keystore-backed encrypted storage) " +
                    "and are never written to any file in the project.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsField(
    label: String,
    hint: String,
    initialValue: String,
    onSave: (String) -> Unit,
    secure: Boolean,
) {
    var value by remember { mutableStateOf(initialValue) }
    var wasFocused by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (secure) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secure) KeyboardType.Password else KeyboardType.Text),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (wasFocused && !state.isFocused) onSave(value)
                    wasFocused = state.isFocused
                },
        )
        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
