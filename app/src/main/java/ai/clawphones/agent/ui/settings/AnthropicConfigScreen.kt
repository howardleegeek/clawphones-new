package ai.clawphones.agent.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.clawphones.agent.config.ConfigManager
import ai.clawphones.agent.config.availableModels
import ai.clawphones.agent.config.freeModels
import ai.clawphones.agent.config.paidModels
import ai.clawphones.agent.config.ModelTier
import ai.clawphones.agent.util.Analytics
import ai.clawphones.agent.ui.theme.RethinkSans
import ai.clawphones.agent.ui.theme.ClawPhonesColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnthropicConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(ConfigManager.loadConfig(context)) }

    var editField by remember { mutableStateOf<String?>(null) }
    var editLabel by remember { mutableStateOf("") }
    var editValue by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showAuthTypePicker by remember { mutableStateOf(false) }

    var testStatus by remember { mutableStateOf("Idle") } // Idle, Loading, Success, Error
    var testMessage by remember { mutableStateOf("") }

    val shape = RoundedCornerShape(ClawPhonesColors.CornerRadius)

    fun saveField(field: String, value: String) {
        ConfigManager.updateConfigField(context, field, value)
        config = ConfigManager.loadConfig(context)
    }

    val authTypeLabel = when (config?.authType) {
        "platform" -> "Platform (Free)"
        "setup_token" -> "Pro/Max Token"
        else -> "API Key"
    }
    val maskedApiKey = config?.anthropicApiKey?.let { key ->
        if (key.isBlank()) "Not set"
        else if (key.length > 12) "${key.take(8)}${"*".repeat(8)}${key.takeLast(4)}" else "*".repeat(key.length)
    } ?: "Not set"
    val maskedSetupToken = config?.setupToken?.let { token ->
        if (token.isBlank()) "Not set"
        else if (token.length > 12) "${token.take(8)}${"*".repeat(8)}${token.takeLast(4)}" else "*".repeat(token.length)
    } ?: "Not set"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AI Configuration",
                        fontFamily = RethinkSans,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ClawPhonesColors.TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = ClawPhonesColors.TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ClawPhonesColors.Background
                )
            )
        },
        containerColor = ClawPhonesColors.Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ClawPhonesColors.Surface, shape),
            ) {
                ProviderConfigField(
                    label = "Model",
                    value = availableModels.find { it.id == config?.model }
                        ?.let { "${it.displayName} (${it.description})" }
                        ?: config?.model?.ifBlank { "Not set" }
                        ?: "Not set",
                    onClick = { showModelPicker = true },
                    info = SettingsHelpTexts.MODEL,
                )
                ProviderConfigField(
                    label = "Auth Type",
                    value = authTypeLabel,
                    onClick = { showAuthTypePicker = true },
                    info = SettingsHelpTexts.AUTH_TYPE,
                )
                if (config?.authType == "platform") {
                    ProviderConfigField(
                        label = "Platform Mode",
                        value = "Active — free models included, buy credits for premium",
                        info = "Platform mode uses ClawPhones servers. No API key needed. Free models are always available. Purchase credits to unlock premium models like Claude and GPT-4o.",
                        showDivider = false,
                    )
                } else {
                    ProviderConfigField(
                        label = if (config?.authType == "api_key") "API Key (active)" else "API Key",
                        value = maskedApiKey,
                        onClick = {
                            editField = "anthropicApiKey"
                            editLabel = "API Key"
                            editValue = config?.anthropicApiKey ?: ""
                        },
                        info = SettingsHelpTexts.API_KEY,
                        isRequired = config?.authType == "api_key",
                    )
                    ProviderConfigField(
                        label = if (config?.authType == "setup_token") "Setup Token (active)" else "Setup Token",
                        value = maskedSetupToken,
                        onClick = {
                            editField = "setupToken"
                            editLabel = "Setup Token"
                            editValue = config?.setupToken ?: ""
                        },
                        info = SettingsHelpTexts.SETUP_TOKEN,
                        isRequired = config?.authType == "setup_token",
                        showDivider = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            ProviderSectionLabel("Connection Test")
            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ClawPhonesColors.Surface, shape)
                    .padding(16.dp),
            ) {
                Text(
                    text = if (config?.authType == "platform") "Verify platform connection and check credit balance."
                           else "Verify your credentials are valid and the API is reachable.",
                    fontFamily = RethinkSans,
                    fontSize = 13.sp,
                    color = ClawPhonesColors.TextDim,
                )
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (testStatus == "Loading") return@Button
                        testStatus = "Loading"
                        testMessage = ""
                        val activeCredential = config?.activeCredential ?: ""
                        val authType = config?.authType ?: "api_key"

                        if (authType == "platform") {
                            // Test proxy connection
                            val proxyUrl = config?.proxyUrl ?: ""
                            val proxyToken = config?.proxyToken ?: ""
                            if (proxyUrl.isBlank() || proxyToken.isBlank()) {
                                testStatus = "Error"
                                testMessage = "Platform credentials missing. Run setup again."
                                return@Button
                            }
                            scope.launch {
                                val result = testProxyConnection(proxyUrl, proxyToken)
                                if (result.isSuccess) {
                                    testStatus = "Success"
                                    testMessage = result.getOrDefault("Connection successful!")
                                } else {
                                    testStatus = "Error"
                                    testMessage = result.exceptionOrNull()?.message ?: "Connection failed"
                                }
                            }
                        } else {
                            if (activeCredential.isBlank()) {
                                testStatus = "Error"
                                testMessage = "Credential is empty."
                                return@Button
                            }
                            scope.launch {
                                val result = testAnthropicConnection(activeCredential, authType)
                                if (result.isSuccess) {
                                    testStatus = "Success"
                                    testMessage = "Connection successful!"
                                } else {
                                    testStatus = "Error"
                                    testMessage = result.exceptionOrNull()?.message ?: "Connection failed"
                                }
                            }
                        }
                    },
                    enabled = testStatus != "Loading",
                    modifier = Modifier.fillMaxWidth(),
                    shape = shape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClawPhonesColors.ActionPrimary,
                        contentColor = Color.White,
                    ),
                ) {
                    if (testStatus == "Loading") {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testing...", fontFamily = RethinkSans, fontSize = 14.sp)
                    } else {
                        Text("Test Connection", fontFamily = RethinkSans, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }

                if (testStatus == "Success" || testStatus == "Error") {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = testMessage,
                        fontFamily = RethinkSans,
                        fontSize = 13.sp,
                        color = if (testStatus == "Success") ClawPhonesColors.ActionPrimary else ClawPhonesColors.Error,
                    )
                }
            }
        }
    }

    if (editField != null) {
        ProviderEditDialog(
            editField = editField,
            editLabel = editLabel,
            editValue = editValue,
            onValueChange = { editValue = it },
            onSave = {
                val field = editField ?: return@ProviderEditDialog
                val trimmed = editValue.trim()
                if (field == "setupToken") {
                    saveField(field, trimmed)
                    if (trimmed.isNotEmpty()) {
                        saveField("authType", "setup_token")
                    }
                } else if (trimmed.isNotEmpty()) {
                    if (field == "anthropicApiKey") {
                        val detected = ConfigManager.detectAuthType(trimmed)
                        if (detected == "setup_token") {
                            saveField("setupToken", trimmed)
                            saveField("authType", "setup_token")
                            editField = null
                            return@ProviderEditDialog
                        }
                    }
                    saveField(field, trimmed)
                }
                editField = null
            },
            onDismiss = { editField = null }
        )
    }

    if (showModelPicker) {
        var selectedModel by remember { mutableStateOf(config?.model ?: availableModels[0].id) }
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = {
                Text(
                    "Select Model",
                    fontFamily = RethinkSans,
                    fontWeight = FontWeight.Bold,
                    color = ClawPhonesColors.TextPrimary,
                )
            },
            text = {
                Column {
                    // Free models section
                    if (freeModels.isNotEmpty()) {
                        Text(
                            text = "FREE",
                            fontFamily = RethinkSans,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ClawPhonesColors.Accent,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                        )
                        freeModels.forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedModel = model.id }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedModel == model.id,
                                    onClick = { selectedModel = model.id },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = ClawPhonesColors.Primary,
                                        unselectedColor = ClawPhonesColors.TextDim,
                                    ),
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(
                                        text = "${model.displayName} (${model.description})",
                                        fontFamily = RethinkSans,
                                        fontSize = 14.sp,
                                        color = ClawPhonesColors.TextPrimary,
                                    )
                                    Text(
                                        text = model.id,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = ClawPhonesColors.TextDim,
                                    )
                                }
                            }
                        }
                    }
                    // Paid models section
                    if (paidModels.isNotEmpty()) {
                        HorizontalDivider(
                            color = ClawPhonesColors.TextDim.copy(alpha = 0.15f),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        Text(
                            text = "PREMIUM",
                            fontFamily = RethinkSans,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ClawPhonesColors.Primary,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                        )
                        paidModels.forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedModel = model.id }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedModel == model.id,
                                    onClick = { selectedModel = model.id },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = ClawPhonesColors.Primary,
                                        unselectedColor = ClawPhonesColors.TextDim,
                                    ),
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(
                                        text = "${model.displayName} (${model.description})",
                                        fontFamily = RethinkSans,
                                        fontSize = 14.sp,
                                        color = ClawPhonesColors.TextPrimary,
                                    )
                                    Text(
                                        text = model.id,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = ClawPhonesColors.TextDim,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveField("model", selectedModel)
                        Analytics.modelSelected(selectedModel)
                        showModelPicker = false
                    },
                ) {
                    Text(
                        "Save",
                        fontFamily = RethinkSans,
                        fontWeight = FontWeight.Bold,
                        color = ClawPhonesColors.ActionPrimary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showModelPicker = false }) {
                    Text(
                        "Cancel",
                        fontFamily = RethinkSans,
                        color = ClawPhonesColors.TextDim,
                    )
                }
            },
            containerColor = ClawPhonesColors.Surface,
            shape = shape,
        )
    }

    if (showAuthTypePicker) {
        val authOptions = listOf(
            "platform" to "Platform (Free)",
            "api_key" to "API Key",
            "setup_token" to "Pro/Max Token",
        )
        var selectedAuth by remember { mutableStateOf(config?.authType ?: "api_key") }

        AlertDialog(
            onDismissRequest = { showAuthTypePicker = false },
            title = {
                Text(
                    "Auth Type",
                    fontFamily = RethinkSans,
                    fontWeight = FontWeight.Bold,
                    color = ClawPhonesColors.TextPrimary,
                )
            },
            text = {
                Column {
                    authOptions.forEach { (typeId, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAuth = typeId }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedAuth == typeId,
                                onClick = { selectedAuth = typeId },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = ClawPhonesColors.Primary,
                                    unselectedColor = ClawPhonesColors.TextDim,
                                ),
                            )
                            Text(
                                text = label,
                                fontFamily = RethinkSans,
                                fontSize = 14.sp,
                                color = ClawPhonesColors.TextPrimary,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Platform mode uses free models via ClawPhones servers. API Key and Pro/Max Token connect directly to Anthropic.",
                        fontFamily = RethinkSans,
                        fontSize = 12.sp,
                        color = ClawPhonesColors.TextDim,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveField("authType", selectedAuth)
                        Analytics.authTypeChanged(selectedAuth)
                        showAuthTypePicker = false
                    },
                ) {
                    Text(
                        "Save",
                        fontFamily = RethinkSans,
                        fontWeight = FontWeight.Bold,
                        color = ClawPhonesColors.ActionPrimary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showAuthTypePicker = false }) {
                    Text(
                        "Cancel",
                        fontFamily = RethinkSans,
                        color = ClawPhonesColors.TextDim,
                    )
                }
            },
            containerColor = ClawPhonesColors.Surface,
            shape = shape,
        )
    }
}

private suspend fun testProxyConnection(proxyUrl: String, proxyToken: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val url = URL("$proxyUrl/v1/billing/wallet")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $proxyToken")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            val status = conn.responseCode
            if (status in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val credits = json.optDouble("available_credits", 0.0)
                "Connected! Credits: ${credits.toInt()}"
            } else if (status == 401 || status == 403) {
                error("Invalid platform token")
            } else {
                error("HTTP $status")
            }
        } catch (e: java.io.IOException) {
            error("Network unreachable or timeout")
        } finally {
            conn.disconnect()
        }
    }
}

private suspend fun testAnthropicConnection(credential: String, authType: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val url = URL("https://api.anthropic.com/v1/models") // models endpoint requires auth
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        if (authType == "setup_token") {
            conn.setRequestProperty("Authorization", "Bearer $credential")
            conn.setRequestProperty("anthropic-beta", "prompt-caching-2024-07-31,oauth-2025-04-20")
        } else {
            conn.setRequestProperty("x-api-key", credential)
            conn.setRequestProperty("anthropic-beta", "prompt-caching-2024-07-31")
        }
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        try {
            val status = conn.responseCode
            if (status in 200..299) {
                return@runCatching
            } else {
                var errorMessage = "HTTP $status"
                if (status == 401 || status == 403) {
                    errorMessage = "Unauthorized / Invalid credential"
                } else if (status in 500..599) {
                    errorMessage = "Anthropic API unavailable"
                } else {
                    val errorStream = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    try {
                        val json = JSONObject(errorStream)
                        val err = json.optJSONObject("error")
                        if (err != null && err.has("message")) {
                            errorMessage += ": ${err.getString("message")}"
                        }
                    } catch (e: Exception) {
                        // Ignore JSON parsing errors
                    }
                }
                error("Connection failed ($errorMessage)")
            }
        } catch (e: java.io.IOException) {
            error("Network unreachable or timeout")
        } finally {
            conn.disconnect()
        }
    }
}

@Composable
fun ProviderSectionLabel(title: String) {
    Text(
        text = title,
        fontFamily = RethinkSans,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = ClawPhonesColors.TextSecondary,
        letterSpacing = 1.sp,
    )
}

@Composable
fun ProviderConfigField(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    info: String? = null,
    isRequired: Boolean = false,
) {
    var showInfo by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (isRequired) Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "$label, required"
                } else Modifier,
            ) {
                Text(
                    text = label,
                    fontFamily = RethinkSans,
                    fontSize = 12.sp,
                    color = ClawPhonesColors.TextDim,
                )
                if (isRequired) {
                    Text(
                        text = " *",
                        fontSize = 12.sp,
                        color = ClawPhonesColors.Error,
                    )
                }
                if (info != null) {
                    IconButton(
                        onClick = { showInfo = true },
                        modifier = Modifier.size(20.dp).padding(start = 4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "More info about $label",
                            tint = ClawPhonesColors.TextDim,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            if (onClick != null) {
                Text(
                    text = "Edit",
                    fontFamily = RethinkSans,
                    fontSize = 12.sp,
                    color = ClawPhonesColors.TextInteractive,
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontFamily = RethinkSans,
            fontSize = 14.sp,
            color = ClawPhonesColors.TextPrimary,
        )
    }
    if (showDivider) {
        HorizontalDivider(
            color = ClawPhonesColors.TextDim.copy(alpha = 0.1f),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    if (showInfo && info != null) {
        ProviderInfoDialog(title = label, message = info, onDismiss = { showInfo = false })
    }
}

@Composable
fun ProviderInfoDialog(title: String, message: String, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(ClawPhonesColors.CornerRadius)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontFamily = RethinkSans,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = ClawPhonesColors.TextPrimary,
            )
        },
        text = {
            Text(
                text = message,
                fontFamily = RethinkSans,
                fontSize = 13.sp,
                color = ClawPhonesColors.TextSecondary,
                lineHeight = 20.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "Got it",
                    fontFamily = RethinkSans,
                    fontWeight = FontWeight.Bold,
                    color = ClawPhonesColors.Primary,
                )
            }
        },
        containerColor = ClawPhonesColors.Surface,
        shape = shape,
    )
}

@Composable
fun ProviderEditDialog(
    editField: String?,
    editLabel: String,
    editValue: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val shape = RoundedCornerShape(ClawPhonesColors.CornerRadius)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Edit $editLabel",
                fontFamily = RethinkSans,
                fontWeight = FontWeight.Bold,
                color = ClawPhonesColors.TextPrimary,
            )
        },
        text = {
            Column {
                if (editField == "anthropicApiKey" || editField == "setupToken" || editField == "telegramBotToken") {
                    Text(
                        "Changing this requires an agent restart.",
                        fontFamily = RethinkSans,
                        fontSize = 12.sp,
                        color = ClawPhonesColors.Warning,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                OutlinedTextField(
                    value = editValue,
                    onValueChange = onValueChange,
                    label = { Text(editLabel, fontFamily = RethinkSans, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = editField != "anthropicApiKey" && editField != "setupToken",
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = ClawPhonesColors.TextPrimary,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ClawPhonesColors.Primary,
                        unfocusedBorderColor = ClawPhonesColors.TextDim.copy(alpha = 0.3f),
                        cursorColor = ClawPhonesColors.Primary,
                        focusedTextColor = ClawPhonesColors.TextPrimary,
                        unfocusedTextColor = ClawPhonesColors.TextPrimary
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
            ) {
                Text(
                    "Save",
                    fontFamily = RethinkSans,
                    fontWeight = FontWeight.Bold,
                    color = ClawPhonesColors.ActionPrimary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "Cancel",
                    fontFamily = RethinkSans,
                    color = ClawPhonesColors.TextDim,
                )
            }
        },
        containerColor = ClawPhonesColors.Surface,
        shape = shape,
    )
}
