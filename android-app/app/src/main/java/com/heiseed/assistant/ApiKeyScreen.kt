package com.heiseed.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ApiKeyScreen(
    apiKeyManager: ApiKeyManager,
    deviceAuthManager: DeviceAuthManager
) {
    var apiKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var hasSavedApiKey by remember {
        mutableStateOf(
            try {
                !apiKeyManager.getApiKey().isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        )
    }

    var deviceKey by remember {
        mutableStateOf(
            try {
                deviceAuthManager.getDeviceKey().orEmpty()
            } catch (_: Exception) {
                ""
            }
        )
    }
    var showDeviceKey by remember { mutableStateOf(false) }
    var showReplaceDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    var apiExpanded by remember { mutableStateOf(false) }
    var deviceExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsAccordionSection(
            title = "OpenAI API Settings",
            subtitle = if (hasSavedApiKey) "API key stored on this device" else "No API key stored",
            expanded = apiExpanded,
            onToggle = { apiExpanded = !apiExpanded }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {


                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            if (hasSavedApiKey && apiKey.isBlank()) {
                                "OpenAI API key (stored)"
                            } else {
                                "OpenAI API key"
                            }
                        )
                    },
                    placeholder = {
                        Text(
                            if (hasSavedApiKey && apiKey.isBlank()) {
                                "A key is already stored securely on this device"
                            } else {
                                "Enter your API key"
                            }
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )

                Button(
                    onClick = {
                        try {
                            apiKeyManager.saveApiKey(apiKey.trim())
                            val saved = apiKeyManager.getApiKey()
                            if (!saved.isNullOrBlank()) {
                                apiKey = ""
                                hasSavedApiKey = true
                                status = "API key saved successfully."
                            } else {
                                hasSavedApiKey = false
                                status = "Could not confirm that the API key was saved."
                            }
                        } catch (_: Exception) {
                            hasSavedApiKey = false
                            status = "Could not save the API key."
                        }
                    },
                    enabled = apiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save API key")
                }

                OutlinedButton(
                    onClick = {
                        try {
                            apiKeyManager.clearApiKey()
                            apiKey = ""
                            hasSavedApiKey = false
                            status = "API key removed."
                        } catch (_: Exception) {
                            status = "Could not remove the API key."
                        }
                    },
                    enabled = hasSavedApiKey,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Remove API key")
                }

            }
        }

        SettingsAccordionSection(
            title = "Hei Seed Device Security",
            subtitle = if (deviceKey.isNotBlank()) "Device key stored on this phone" else "No device key generated",
            expanded = deviceExpanded,
            onToggle = { deviceExpanded = !deviceExpanded }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (deviceKey.isNotBlank()) {
                        "A device security key is stored on this phone."
                    } else {
                        "No device security key has been generated yet."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (deviceKey.isNotBlank()) {
                    OutlinedTextField(
                        value = deviceKey,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Device security key") },
                        visualTransformation = if (showDeviceKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        singleLine = true
                    )

                    TextButton(onClick = { showDeviceKey = !showDeviceKey }) {
                        Text(if (showDeviceKey) "Hide key" else "Show key")
                    }

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText("Hei Seed device key", deviceKey)
                                    )
                                )
                                status = "Device key copied to clipboard. Treat it as a secret."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Copy device key")
                    }

                    OutlinedButton(
                        onClick = {
                            try {
                                deviceAuthManager.clearDeviceKey()
                                deviceKey = ""
                                showDeviceKey = false
                                status = "Device security key removed. The XIAO must be configured again before authenticated uploads can work."
                            } catch (_: Exception) {
                                status = "Could not remove the device security key."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Remove device key")
                    }
                }

                Button(
                    onClick = {
                        if (deviceKey.isNotBlank()) {
                            showReplaceDialog = true
                        } else {
                            try {
                                deviceKey = deviceAuthManager.generateDeviceKey()
                                showDeviceKey = true
                                status = "Device key generated and stored. Copy it and configure the XIAO with the same key."
                            } catch (_: Exception) {
                                status = "Could not generate or store the device key."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (deviceKey.isBlank()) "Generate device security key" else "Generate new device key")
                }

                Text(
                    text = "The device key is encrypted before being stored on this phone. Keep it secret. If you generate a new key, the XIAO must be updated with the same key. Copying the key places it on the clipboard, where other apps may potentially access it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "The OpenAI API key is encrypted before being stored on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (status.isNotBlank()) {
            Text(
                text = status,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }

    if (showReplaceDialog) {
        AlertDialog(
            onDismissRequest = { showReplaceDialog = false },
            title = { Text("Replace device security key?") },
            text = {
                Text("The current key will stop matching the XIAO configuration. You must update the XIAO with the new key before authenticated image uploads can work. Continue?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReplaceDialog = false
                        try {
                            deviceKey = deviceAuthManager.generateDeviceKey()
                            showDeviceKey = true
                            status = "New device key generated. Update the XIAO with this key."
                        } catch (_: Exception) {
                            status = "Could not generate or store the new device key."
                        }
                    }
                ) {
                    Text("Replace key")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
