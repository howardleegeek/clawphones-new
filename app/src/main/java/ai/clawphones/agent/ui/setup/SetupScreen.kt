package ai.clawphones.agent.ui.setup

import android.Manifest
import android.app.Activity
import android.content.Intent
import ai.clawphones.agent.util.LogCollector
import ai.clawphones.agent.util.LogLevel
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import ai.clawphones.agent.ui.theme.RethinkSans
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ai.clawphones.agent.R
import ai.clawphones.agent.config.AppConfig
import ai.clawphones.agent.config.ConfigClaimImporter
import ai.clawphones.agent.config.ConfigManager
import ai.clawphones.agent.config.availableModels
import ai.clawphones.agent.config.freeModels
import ai.clawphones.agent.config.paidModels
import ai.clawphones.agent.config.ModelTier
import ai.clawphones.agent.qr.QrScannerActivity
import ai.clawphones.agent.service.OpenClawService
import ai.clawphones.agent.util.Analytics
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import ai.clawphones.agent.ui.components.SetupStepIndicator
import ai.clawphones.agent.ui.components.dotMatrix
import ai.clawphones.agent.ui.theme.ClawPhonesColors

private const val PLATFORM_PROXY_URL = "https://api.clawphones.ai"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onSetupComplete: () -> Unit) {
    val context = LocalContext.current

    // Pre-fill from existing config (for "Run Setup Again" flow)
    val existingConfig = remember { ConfigManager.loadConfig(context) }

    var apiKey by remember { mutableStateOf(existingConfig?.activeCredential ?: "") }
    var authType by remember { mutableStateOf(existingConfig?.authType ?: "api_key") }
    var botToken by remember { mutableStateOf(existingConfig?.telegramBotToken ?: "") }
    var ownerId by remember { mutableStateOf(existingConfig?.telegramOwnerId ?: "") }
    var selectedModel by remember {
        mutableStateOf(
            existingConfig?.model?.takeIf { model ->
                availableModels.any { it.id == model }
            } ?: availableModels[0].id
        )
    }
    var agentName by remember { mutableStateOf(existingConfig?.agentName ?: "SeekerClaw") }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var apiKeyError by remember { mutableStateOf<String?>(null) }
    var botTokenError by remember { mutableStateOf<String?>(null) }

    var currentStep by remember { mutableIntStateOf(0) }
    var usePlatformMode by remember { mutableStateOf(existingConfig?.isPlatformMode ?: true) }
    var isRegistering by remember { mutableStateOf(false) }
    var proxyToken by remember { mutableStateOf(existingConfig?.proxyToken ?: "") }
    var proxyUrl by remember { mutableStateOf(existingConfig?.proxyUrl?.ifBlank { PLATFORM_PROXY_URL } ?: PLATFORM_PROXY_URL) }
    var isQrImporting by remember { mutableStateOf(false) }
    var qrError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val qrScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val scanError = result.data?.getStringExtra(QrScannerActivity.EXTRA_ERROR)
        if (!scanError.isNullOrBlank()) {
            qrError = scanError
            return@rememberLauncherForActivityResult
        }
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult

        val qrText = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_TEXT)
        if (qrText.isNullOrBlank()) {
            qrError = "No QR data received"
            return@rememberLauncherForActivityResult
        }

        isQrImporting = true
        qrError = null
        scope.launch {
            ConfigClaimImporter.fetchFromQr(qrText)
                .onSuccess { imported ->
                    val cfg = imported.config
                    if (cfg.authType == "setup_token") {
                        authType = "setup_token"
                        apiKey = cfg.setupToken
                    } else {
                        authType = "api_key"
                        apiKey = cfg.anthropicApiKey
                    }
                    botToken = cfg.telegramBotToken
                    ownerId = cfg.telegramOwnerId
                    selectedModel = cfg.model.takeIf { m ->
                        availableModels.any { it.id == m }
                    } ?: availableModels[0].id
                    agentName = cfg.agentName
                    isQrImporting = false
                    errorMessage = null
                    currentStep = 3 // Jump to Options for review
                }
                .onFailure { err ->
                    isQrImporting = false
                    qrError = err.message ?: "Config import failed"
                }
        }
    }

    fun skipSetup() {
        ConfigManager.markSetupSkipped(context)
        onSetupComplete()
    }

    var hasNotificationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var showNotificationDialog by remember { mutableStateOf(!hasNotificationPermission) }
    var isStarting by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
        showNotificationDialog = false
    }

    fun saveAndStart() {
        if (isStarting) return
        // Platform mode skips API key validation
        if (!usePlatformMode) {
            if (apiKey.isBlank()) {
                apiKeyError = "Required"
                errorMessage = "Claude API key is required"
                currentStep = 1
                return
            }
            val credentialError = ConfigManager.validateCredential(apiKey.trim(), authType)
            if (credentialError != null) {
                apiKeyError = credentialError
                errorMessage = credentialError
                currentStep = 1
                return
            }
        }
        if (botToken.isBlank()) {
            botTokenError = "Required"
            errorMessage = "Telegram bot token is required"
            currentStep = 2
            return
        }

        errorMessage = null
        isStarting = true
        try {
            val trimmedKey = apiKey.trim()
            val config = if (usePlatformMode) {
                AppConfig(
                    anthropicApiKey = "",
                    authType = "platform",
                    proxyUrl = proxyUrl,
                    proxyToken = proxyToken,
                    telegramBotToken = botToken.trim(),
                    telegramOwnerId = ownerId.trim(),
                    model = selectedModel,
                    agentName = agentName.trim().ifBlank { "SeekerClaw" },
                )
            } else {
                AppConfig(
                    anthropicApiKey = if (authType == "api_key") trimmedKey else "",
                    setupToken = if (authType == "setup_token") trimmedKey else "",
                    authType = authType,
                    telegramBotToken = botToken.trim(),
                    telegramOwnerId = ownerId.trim(),
                    model = selectedModel,
                    agentName = agentName.trim().ifBlank { "SeekerClaw" },
                )
            }
            ConfigManager.saveConfig(context, config)
            ConfigManager.seedWorkspace(context)
            OpenClawService.start(context)
            currentStep = 4
        } catch (e: Exception) {
            LogCollector.append("[Setup] Failed to start agent: ${e.message}", LogLevel.ERROR)
            isStarting = false
            errorMessage = e.message ?: "Failed to start agent"
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ClawPhonesColors.Primary,
        unfocusedBorderColor = ClawPhonesColors.TextDim.copy(alpha = 0.3f),
        focusedTextColor = ClawPhonesColors.TextPrimary,
        unfocusedTextColor = ClawPhonesColors.TextPrimary,
        cursorColor = ClawPhonesColors.Primary,
        focusedLabelColor = ClawPhonesColors.Primary,
        unfocusedLabelColor = ClawPhonesColors.TextSecondary,
        focusedContainerColor = ClawPhonesColors.Surface,
        unfocusedContainerColor = ClawPhonesColors.Surface,
    )

    val scrollState = rememberScrollState()
    val shape = RoundedCornerShape(ClawPhonesColors.CornerRadius)

    val bgModifier = if (ClawPhonesColors.UseDotMatrix) {
        Modifier
            .fillMaxSize()
            .background(ClawPhonesColors.Background)
            .dotMatrix(
                dotColor = ClawPhonesColors.DotMatrix,
                dotSpacing = 6.dp,
                dotRadius = 1.dp,
            )
    } else {
        Modifier
            .fillMaxSize()
            .background(ClawPhonesColors.Background)
    }

    Column(
        modifier = bgModifier
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (currentStep < 4) {
            // Header row: logo left, skip right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_clawphones_logo_horizontal),
                    contentDescription = "ClawPhones logo",
                    modifier = Modifier.height(36.dp),
                )
                Text(
                    text = "Skip",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = ClawPhonesColors.TextDim,
                    modifier = Modifier
                        .clickable { skipSetup() }
                        .padding(4.dp),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Your personal AI agent, running on your phone",
                fontSize = 13.sp,
                color = ClawPhonesColors.TextDim,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Step indicator — platform mode skips "Claude" step
            SetupStepIndicator(
                currentStep = if (usePlatformMode) when (currentStep) {
                    0 -> 0; 2 -> 1; 3 -> 2; else -> currentStep
                } else currentStep,
                labels = if (usePlatformMode) listOf("Welcome", "Telegram", "Options")
                         else listOf("Welcome", "Claude", "Telegram", "Options"),
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Error message
        if (errorMessage != null && currentStep < 4) {
            Text(
                text = errorMessage!!,
                fontFamily = FontFamily.Monospace,
                color = ClawPhonesColors.Error,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ClawPhonesColors.Error.copy(alpha = 0.1f), shape)
                    .padding(14.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        when (currentStep) {
            0 -> WelcomeStep(
                onPlatformMode = {
                    usePlatformMode = true
                    authType = "platform"
                    isRegistering = true
                    errorMessage = null
                    scope.launch {
                        val deviceId = Settings.Secure.getString(
                            context.contentResolver, Settings.Secure.ANDROID_ID
                        ) ?: "unknown"
                        val result = registerForPlatform(deviceId, proxyUrl)
                        isRegistering = false
                        result.onSuccess { (token, _) ->
                            proxyToken = token
                            selectedModel = freeModels.firstOrNull()?.id ?: availableModels[0].id
                            currentStep = 2 // Skip API key, go to Telegram
                        }.onFailure { err ->
                            errorMessage = "Registration failed: ${err.message}"
                        }
                    }
                },
                onByokMode = {
                    usePlatformMode = false
                    authType = "api_key"
                    currentStep = 1
                },
                onScanQr = {
                    Analytics.featureUsed("qr_scan_setup")
                    qrScanLauncher.launch(Intent(context, QrScannerActivity::class.java))
                },
                isRegistering = isRegistering,
                isQrImporting = isQrImporting,
                qrError = qrError,
            )
            1 -> ClaudeApiStep(
                apiKey = apiKey,
                onApiKeyChange = { newValue ->
                    apiKey = newValue
                    apiKeyError = null
                    errorMessage = null
                    if (newValue.length > 20) {
                        authType = ConfigManager.detectAuthType(newValue)
                    }
                },
                authType = authType,
                onAuthTypeChange = { authType = it },
                apiKeyError = apiKeyError,
                fieldColors = fieldColors,
                onNext = { currentStep = 2 },
                onBack = { currentStep = 0 },
            )
            2 -> TelegramStep(
                botToken = botToken,
                onBotTokenChange = { botToken = it; botTokenError = null; errorMessage = null },
                ownerId = ownerId,
                onOwnerIdChange = { ownerId = it; errorMessage = null },
                botTokenError = botTokenError,
                fieldColors = fieldColors,
                onNext = { currentStep = 3 },
                onBack = { currentStep = 1 },
            )
            3 -> OptionsStep(
                selectedModel = selectedModel,
                onModelChange = { selectedModel = it },
                modelDropdownExpanded = modelDropdownExpanded,
                onModelDropdownExpandedChange = { modelDropdownExpanded = it },
                agentName = agentName,
                onAgentNameChange = { agentName = it },
                fieldColors = fieldColors,
                isStarting = isStarting,
                onStartAgent = ::saveAndStart,
                onBack = { currentStep = 2 },
            )
            4 -> SetupSuccessStep(
                agentName = agentName.ifBlank { "SeekerClaw" },
                onContinue = onSetupComplete,
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    // Notification permission explanation dialog
    if (showNotificationDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationDialog = false },
            title = {
                Text(
                    "Enable Notifications",
                    fontFamily = RethinkSans,
                    fontWeight = FontWeight.Bold,
                    color = ClawPhonesColors.TextPrimary,
                )
            },
            text = {
                Text(
                    "SeekerClaw runs your AI agent in the background. " +
                        "Notifications let you know when the agent starts, stops, " +
                        "or needs attention \u2014 even when the app isn\u2019t open.",
                    fontFamily = RethinkSans,
                    fontSize = 13.sp,
                    color = ClawPhonesColors.TextSecondary,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) {
                    Text(
                        "Enable",
                        fontFamily = RethinkSans,
                        fontWeight = FontWeight.Bold,
                        color = ClawPhonesColors.Primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationDialog = false }) {
                    Text(
                        "Not Now",
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

@Composable
private fun WelcomeStep(
    onPlatformMode: () -> Unit,
    onByokMode: () -> Unit,
    onScanQr: () -> Unit = {},
    isRegistering: Boolean = false,
    isQrImporting: Boolean = false,
    qrError: String? = null,
) {
    val shape = RoundedCornerShape(ClawPhonesColors.CornerRadius)
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "SeekerClaw turns your phone into a 24/7 personal AI agent. " +
                   "Choose how to get started:",
            fontSize = 14.sp,
            color = ClawPhonesColors.TextPrimary,
            lineHeight = 22.sp,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Platform mode card — recommended
        SetupCard {
            Text(
                text = "RECOMMENDED",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = ClawPhonesColors.Accent,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .background(
                        ClawPhonesColors.Accent.copy(alpha = 0.12f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            RequirementRow(
                icon = Icons.Default.PlayArrow,
                title = "Get Started Free",
                subtitle = "Free AI models included, no API key needed",
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Upgrade to premium models anytime with credits.",
                fontSize = 12.sp,
                color = ClawPhonesColors.TextDim,
                modifier = Modifier.padding(start = 36.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Get Started Free button
        Button(
            onClick = onPlatformMode,
            enabled = !isRegistering && !isQrImporting,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = ClawPhonesColors.ActionPrimary,
                contentColor = Color.White,
                disabledContainerColor = ClawPhonesColors.ActionPrimary.copy(alpha = 0.6f),
                disabledContentColor = Color.White.copy(alpha = 0.7f),
            ),
        ) {
            if (isRegistering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Setting up\u2026",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Text(
                    "Get Started Free",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(color = ClawPhonesColors.TextDim.copy(alpha = 0.2f))

        Spacer(modifier = Modifier.height(16.dp))

        // BYOK / QR options
        Text(
            text = "Already have an API key?",
            fontSize = 13.sp,
            color = ClawPhonesColors.TextDim,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // BYOK manual entry
            Button(
                onClick = onByokMode,
                enabled = !isRegistering && !isQrImporting,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ClawPhonesColors.Surface,
                    contentColor = ClawPhonesColors.TextPrimary,
                ),
            ) {
                Icon(
                    Icons.Default.Key,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Enter API Key",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // QR scan
            Button(
                onClick = onScanQr,
                enabled = !isRegistering && !isQrImporting,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ClawPhonesColors.Surface,
                    contentColor = ClawPhonesColors.TextPrimary,
                ),
            ) {
                if (isQrImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = ClawPhonesColors.TextPrimary,
                    )
                } else {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = "QR code",
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Scan QR",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (qrError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = qrError,
                fontFamily = FontFamily.Monospace,
                color = ClawPhonesColors.Error,
                fontSize = 12.sp,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = { uriHandler.openUri("https://seekerclaw.xyz/setup") },
        ) {
            Icon(
                @Suppress("DEPRECATION") Icons.Default.HelpOutline,
                contentDescription = "Help",
                tint = ClawPhonesColors.TextDim,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "Need help? Quick setup guide",
                fontSize = 13.sp,
                color = ClawPhonesColors.TextDim,
            )
        }
    }
}

@Composable
private fun ClaudeApiStep(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    authType: String,
    onAuthTypeChange: (String) -> Unit,
    apiKeyError: String?,
    fieldColors: androidx.compose.material3.TextFieldColors,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val shape = RoundedCornerShape(ClawPhonesColors.CornerRadius)
    val isToken = authType == "setup_token"
    val uriHandler = LocalUriHandler.current
    val isValid = apiKey.trim().isNotBlank() &&
        ConfigManager.validateCredential(apiKey.trim(), authType) == null &&
        apiKeyError == null

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("Authentication")

        Spacer(modifier = Modifier.height(10.dp))

        SetupCard {
            // Auth type toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("api_key" to "API Key", "setup_token" to "Pro/Max Token").forEach { (type, label) ->
                    val isSelected = authType == type
                    Button(
                        onClick = { onAuthTypeChange(type) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = shape,
                        border = if (!isSelected) BorderStroke(1.dp, ClawPhonesColors.CardBorder) else null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) ClawPhonesColors.Primary.copy(alpha = 0.15f)
                                else ClawPhonesColors.Background,
                            contentColor = if (isSelected) ClawPhonesColors.Primary
                                else ClawPhonesColors.TextDim,
                        ),
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Instructions with clickable link
            if (isToken) {
                Text(
                    text = "Run in your terminal:",
                    fontSize = 13.sp,
                    color = ClawPhonesColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "claude setup-token",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = ClawPhonesColors.Primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Requires Claude Pro or Max subscription.",
                    fontSize = 12.sp,
                    color = ClawPhonesColors.TextDim,
                )
            } else {
                Row {
                    Text(
                        text = "Get your API key from ",
                        fontSize = 13.sp,
                        color = ClawPhonesColors.TextSecondary,
                    )
                    Text(
                        text = "console.anthropic.com",
                        fontSize = 13.sp,
                        color = ClawPhonesColors.Primary,
                        modifier = Modifier.clickable {
                            uriHandler.openUri("https://console.anthropic.com/settings/keys")
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = {
                    Text(
                        if (isToken) "Setup Token" else "API Key",
                        fontSize = 12.sp,
                    )
                },
                placeholder = {
                    Text(
                        if (isToken) "sk-ant-oat01-\u2026" else "sk-ant-api03-\u2026",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = ClawPhonesColors.TextDim,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = apiKeyError != null,
                trailingIcon = if (isValid) {
                    {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Valid",
                            tint = ClawPhonesColors.Accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else null,
                supportingText = apiKeyError?.let { err ->
                    { Text(err, fontSize = 12.sp) }
                },
                colors = fieldColors,
                shape = shape,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        NavButtons(
            onBack = onBack,
            onNext = onNext,
            nextEnabled = apiKey.isNotBlank(),
        )
    }
}

@Composable
private fun TelegramStep(
    botToken: String,
    onBotTokenChange: (String) -> Unit,
    ownerId: String,
    onOwnerIdChange: (String) -> Unit,
    botTokenError: String?,
    fieldColors: androidx.compose.material3.TextFieldColors,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val shape = RoundedCornerShape(ClawPhonesColors.CornerRadius)

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("Telegram Connection")

        Spacer(modifier = Modifier.height(10.dp))

        SetupCard {
            // Bot token
            Text(
                text = "Bot Token",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = ClawPhonesColors.TextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Open Telegram \u2192 @BotFather \u2192 /newbot \u2192 copy the token.",
                fontSize = 12.sp,
                color = ClawPhonesColors.TextDim,
                lineHeight = 18.sp,
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = botToken,
                onValueChange = onBotTokenChange,
                label = { Text("Bot Token", fontSize = 12.sp) },
                placeholder = {
                    Text(
                        "123456789:ABC\u2026",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = ClawPhonesColors.TextDim,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = botTokenError != null,
                supportingText = botTokenError?.let { err ->
                    { Text(err, fontSize = 12.sp) }
                },
                trailingIcon = if (
                    botToken.trim().matches(Regex("^\\d+:[A-Za-z0-9_-]+$")) &&
                    botTokenError == null
                ) {
                    {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Valid format",
                            tint = ClawPhonesColors.Accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    null
                },
                colors = fieldColors,
                shape = shape,
            )

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider(color = ClawPhonesColors.CardBorder)

            Spacer(modifier = Modifier.height(16.dp))

            // User ID with auto-detect badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "User ID",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = ClawPhonesColors.TextPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "(optional)",
                    fontSize = 12.sp,
                    color = ClawPhonesColors.TextDim,
                )
                if (ownerId.isBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AUTO-DETECT",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = ClawPhonesColors.Accent,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier
                            .background(
                                ClawPhonesColors.Accent.copy(alpha = 0.12f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Leave empty \u2014 the first person to message your bot becomes the owner.",
                fontSize = 12.sp,
                color = ClawPhonesColors.TextDim,
                lineHeight = 18.sp,
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = ownerId,
                onValueChange = onOwnerIdChange,
                label = { Text("User ID", fontSize = 12.sp) },
                placeholder = {
                    Text(
                        "auto-detect",
                        fontSize = 14.sp,
                        color = ClawPhonesColors.TextDim,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors,
                shape = shape,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        NavButtons(
            onBack = onBack,
            onNext = onNext,
            nextEnabled = botToken.isNotBlank(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsStep(
    selectedModel: String,
    onModelChange: (String) -> Unit,
    modelDropdownExpanded: Boolean,
    onModelDropdownExpandedChange: (Boolean) -> Unit,
    agentName: String,
    onAgentNameChange: (String) -> Unit,
    fieldColors: androidx.compose.material3.TextFieldColors,
    isStarting: Boolean,
    onStartAgent: () -> Unit,
    onBack: () -> Unit,
) {
    val shape = RoundedCornerShape(ClawPhonesColors.CornerRadius)

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("Configuration")

        Spacer(modifier = Modifier.height(10.dp))

        SetupCard {
            // Model
            Text(
                text = "AI Model",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = ClawPhonesColors.TextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose the AI model that powers your agent.",
                fontSize = 12.sp,
                color = ClawPhonesColors.TextDim,
            )

            Spacer(modifier = Modifier.height(12.dp))

            ExposedDropdownMenuBox(
                expanded = modelDropdownExpanded,
                onExpandedChange = onModelDropdownExpandedChange,
            ) {
                val currentModel = availableModels.firstOrNull { it.id == selectedModel }
                OutlinedTextField(
                    value = currentModel?.let { "${it.displayName} (${it.description})" } ?: selectedModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model", fontSize = 12.sp) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    colors = fieldColors,
                    shape = shape,
                )
                ExposedDropdownMenu(
                    expanded = modelDropdownExpanded,
                    onDismissRequest = { onModelDropdownExpandedChange(false) },
                ) {
                    // Free models header
                    if (freeModels.isNotEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "FREE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ClawPhonesColors.Accent,
                                    letterSpacing = 1.sp,
                                )
                            },
                            onClick = {},
                            enabled = false,
                        )
                        freeModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${model.displayName} (${model.description})",
                                        color = ClawPhonesColors.TextPrimary,
                                    )
                                },
                                onClick = {
                                    onModelChange(model.id)
                                    onModelDropdownExpandedChange(false)
                                },
                            )
                        }
                    }
                    // Paid models header
                    if (paidModels.isNotEmpty()) {
                        HorizontalDivider(
                            color = ClawPhonesColors.CardBorder,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "PREMIUM (credits required)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ClawPhonesColors.Primary,
                                    letterSpacing = 1.sp,
                                )
                            },
                            onClick = {},
                            enabled = false,
                        )
                        paidModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${model.displayName} (${model.description})",
                                        color = ClawPhonesColors.TextPrimary,
                                    )
                                },
                                onClick = {
                                    onModelChange(model.id)
                                    onModelDropdownExpandedChange(false)
                                },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider(color = ClawPhonesColors.CardBorder)

            Spacer(modifier = Modifier.height(16.dp))

            // Agent name
            Text(
                text = "Agent Name",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = ClawPhonesColors.TextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Give your agent a name. You can change this later.",
                fontSize = 12.sp,
                color = ClawPhonesColors.TextDim,
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = agentName,
                onValueChange = onAgentNameChange,
                label = { Text("Agent Name", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors,
                shape = shape,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(
                    text = "Back",
                    fontSize = 14.sp,
                    color = ClawPhonesColors.TextDim,
                )
            }

            Button(
                onClick = onStartAgent,
                enabled = !isStarting,
                modifier = Modifier.height(56.dp),
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ClawPhonesColors.ActionPrimary,
                    contentColor = Color.White,
                    disabledContainerColor = ClawPhonesColors.ActionPrimary.copy(alpha = 0.6f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f),
                ),
            ) {
                if (isStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Starting\u2026",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Start",
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Initialize Agent",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupSuccessStep(
    agentName: String,
    onContinue: () -> Unit,
) {
    val shape = RoundedCornerShape(ClawPhonesColors.CornerRadius)

    // Auto-navigate after 2 seconds
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2000)
        onContinue()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Checkmark circle
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(ClawPhonesColors.ActionPrimary.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Success",
                tint = ClawPhonesColors.ActionPrimary,
                modifier = Modifier.size(40.dp),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "You're all set!",
            fontFamily = RethinkSans,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = ClawPhonesColors.TextPrimary,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "$agentName is starting up. Opening dashboard\u2026",
            fontFamily = RethinkSans,
            fontSize = 14.sp,
            color = ClawPhonesColors.TextDim,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Manual continue button (in case user doesn't want to wait)
        TextButton(onClick = onContinue) {
            Text(
                text = "Go to Dashboard",
                fontFamily = RethinkSans,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = ClawPhonesColors.Primary,
            )
        }
    }
}

@Composable
private fun NavButtons(
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean,
) {
    val shape = RoundedCornerShape(ClawPhonesColors.CornerRadius)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            Text(
                text = "Back",
                fontSize = 14.sp,
                color = ClawPhonesColors.TextDim,
            )
        }

        Button(
            onClick = onNext,
            enabled = nextEnabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = ClawPhonesColors.ActionPrimary,
                contentColor = Color.White,
                disabledContainerColor = ClawPhonesColors.Surface,
                disabledContentColor = ClawPhonesColors.TextDim,
            ),
        ) {
            Text(
                text = "Next",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ============================================================================
// SHARED COMPOSABLES — Card wrapper, requirement row, section label
// ============================================================================

@Composable
private fun SetupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(ClawPhonesColors.CornerRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ClawPhonesColors.Surface, shape)
            .border(1.dp, ClawPhonesColors.CardBorder, shape)
            .padding(20.dp),
        content = content,
    )
}

@Composable
private fun RequirementRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ClawPhonesColors.Primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = ClawPhonesColors.TextPrimary,
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = ClawPhonesColors.TextDim,
            )
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = ClawPhonesColors.TextDim,
        letterSpacing = 1.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Register device with the billing proxy and get an API token + free credits.
 * Returns Pair(api_token, user_id) on success.
 */
private suspend fun registerForPlatform(
    deviceId: String,
    proxyUrl: String,
): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
    runCatching {
        val url = URL("$proxyUrl/v1/auth/register")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.doOutput = true

        val body = """{"device_id":"$deviceId"}"""
        conn.outputStream.use { it.write(body.toByteArray()) }

        val status = conn.responseCode
        if (status !in 200..299) {
            val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            error("HTTP $status: $errBody")
        }

        val respBody = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = JSONObject(respBody)
        val apiToken = json.getString("api_token")
        val userId = json.getString("user_id")
        Pair(apiToken, userId)
    }
}

