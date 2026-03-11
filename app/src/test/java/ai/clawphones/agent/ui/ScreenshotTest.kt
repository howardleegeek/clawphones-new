package ai.clawphones.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import android.graphics.Bitmap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.clawphones.agent.ui.theme.ClawPhonesColors
import ai.clawphones.agent.ui.theme.ClawPhonesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun saveScreenshot(name: String) {
        val outputDir = File("/tmp/clawphones-screenshots").apply { mkdirs() }
        composeTestRule.waitForIdle()
        val activity = composeTestRule.activity
        val rootView = activity.window.decorView.rootView
        rootView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
        )
        rootView.layout(0, 0, 1200, 2400)
        val bitmap = rootView.drawToBitmap()
        FileOutputStream(File(outputDir, "$name.png")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        println("Screenshot saved: /tmp/clawphones-screenshots/$name.png")
    }

    @Test
    fun screenshotDashboard() {
        composeTestRule.setContent {
            ClawPhonesTheme {
                DashboardPreview()
            }
        }
        saveScreenshot("01-dashboard")
    }

    @Test
    fun screenshotColorPalette() {
        composeTestRule.setContent {
            ClawPhonesTheme {
                ColorPalettePreview()
            }
        }
        saveScreenshot("02-colors")
    }

    @Test
    fun screenshotConsole() {
        composeTestRule.setContent {
            ClawPhonesTheme {
                ConsolePreview()
            }
        }
        saveScreenshot("03-console")
    }

    @Test
    fun screenshotSettings() {
        composeTestRule.setContent {
            ClawPhonesTheme {
                SettingsPreview()
            }
        }
        saveScreenshot("04-settings")
    }
}

// ============================================================================
// Preview Composables — standalone, no runtime dependencies
// ============================================================================

@Composable
fun DashboardPreview() {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawPhonesColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        // Logo
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = ClawPhonesColors.Primary, fontWeight = FontWeight.ExtraBold)) {
                    append("Claw")
                }
                withStyle(SpanStyle(color = ClawPhonesColors.TextPrimary, fontWeight = FontWeight.ExtraBold)) {
                    append("Phones")
                }
            },
            fontSize = 28.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text("AgentOS", fontSize = 14.sp, color = ClawPhonesColors.TextDim)

        Spacer(Modifier.height(24.dp))

        // Status card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ClawPhonesColors.Surface, shape)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(ClawPhonesColors.Accent))
                Spacer(Modifier.width(12.dp))
                Text("Online", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ClawPhonesColors.TextPrimary)
                Spacer(Modifier.weight(1f))
                Text("System >", fontSize = 12.sp, color = ClawPhonesColors.TextDim)
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = ClawPhonesColors.TextDim.copy(alpha = 0.2f))
            Spacer(Modifier.height(20.dp))

            Text("Uptime", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = ClawPhonesColors.TextDim, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            Text("02h 47m 13s", fontFamily = FontFamily.Monospace, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = ClawPhonesColors.TextPrimary)

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatMini("TODAY", "12")
                StatMini("TOTAL", "847")
                StatMini("LAST", "2:34 PM")
            }
        }

        Spacer(Modifier.height(28.dp))

        // Uplinks
        Text("Uplinks", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = ClawPhonesColors.TextDim, letterSpacing = 1.sp)
        Spacer(Modifier.height(10.dp))

        UplinkCard("//TG", "Telegram", "Message relay", ClawPhonesColors.Accent, shape)
        Spacer(Modifier.height(8.dp))
        UplinkCard("//GW", "Gateway", "OpenClaw engine", ClawPhonesColors.Accent, shape)
        Spacer(Modifier.height(8.dp))
        UplinkCard("//AI", "AI Model", "Claude Sonnet 4", ClawPhonesColors.Accent, shape)

        Spacer(Modifier.height(28.dp))

        // Deploy button
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = ClawPhonesColors.Primary, contentColor = Color.White),
        ) {
            Text("Stop Agent", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
fun ColorPalettePreview() {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawPhonesColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("ClawPhones Design System", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ClawPhonesColors.TextPrimary)
        Spacer(Modifier.height(20.dp))

        // Color swatches
        Text("COLORS", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = ClawPhonesColors.TextDim, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))

        ColorSwatch("Primary (Gold)", ClawPhonesColors.Primary)
        ColorSwatch("Background", ClawPhonesColors.Background)
        ColorSwatch("Surface", ClawPhonesColors.Surface)
        ColorSwatch("Text Primary (Cream)", ClawPhonesColors.TextPrimary)
        ColorSwatch("Accent (Green)", ClawPhonesColors.Accent)
        ColorSwatch("Error (Red)", ClawPhonesColors.Error)
        ColorSwatch("Warning (Yellow)", ClawPhonesColors.Warning)
        ColorSwatch("Action Primary", ClawPhonesColors.ActionPrimary)

        Spacer(Modifier.height(24.dp))
        Text("BUTTONS", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = ClawPhonesColors.TextDim, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {}, modifier = Modifier.fillMaxWidth().height(48.dp), shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = ClawPhonesColors.ActionPrimary),
        ) { Text("Deploy Agent", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {}, modifier = Modifier.fillMaxWidth().height(48.dp), shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = ClawPhonesColors.Primary),
        ) { Text("Stop Agent", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {}, modifier = Modifier.fillMaxWidth().height(48.dp), shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = ClawPhonesColors.ActionDanger),
        ) { Text("Reset Config", fontWeight = FontWeight.Bold, color = ClawPhonesColors.ActionDangerText) }
    }
}

@Composable
fun ConsolePreview() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawPhonesColors.Background)
            .padding(20.dp),
    ) {
        // Search bar
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Terminal, contentDescription = null, tint = ClawPhonesColors.Primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Console", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ClawPhonesColors.TextPrimary)
        }
        Spacer(Modifier.height(16.dp))

        // Filter chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("DEBUG", ClawPhonesColors.LogDebug)
            FilterChip("INFO", ClawPhonesColors.LogInfo)
            FilterChip("WARN", ClawPhonesColors.Warning)
            FilterChip("ERROR", ClawPhonesColors.Error)
        }

        Spacer(Modifier.height(16.dp))

        // Log lines
        val logs = listOf(
            Triple("08:12:01", "INFO", "Agent started successfully"),
            Triple("08:12:02", "INFO", "Telegram bot connected (@clawphones_bot)"),
            Triple("08:12:03", "DEBUG", "Loaded 35 skills, 56 tools"),
            Triple("08:12:05", "INFO", "Solana wallet connected: 7xKQ...3mFp"),
            Triple("08:12:10", "WARN", "Rate limit warning: 45/50 requests"),
            Triple("08:15:22", "INFO", "Message from @howard: 'check SOL price'"),
            Triple("08:15:23", "DEBUG", "Tool call: crypto_price(SOL)"),
            Triple("08:15:24", "INFO", "Response sent: SOL = \$142.50"),
            Triple("08:20:01", "ERROR", "API timeout after 30s — retrying"),
            Triple("08:20:03", "INFO", "Retry successful"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            logs.forEach { (time, level, msg) ->
                val color = when (level) {
                    "DEBUG" -> ClawPhonesColors.LogDebug
                    "INFO" -> ClawPhonesColors.LogInfo
                    "WARN" -> ClawPhonesColors.Warning
                    "ERROR" -> ClawPhonesColors.Error
                    else -> ClawPhonesColors.TextDim
                }
                Text(
                    text = "$time [$level] $msg",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = color,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
fun SettingsPreview() {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawPhonesColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ClawPhonesColors.TextPrimary)
        Spacer(Modifier.height(24.dp))

        // Config section
        Text("CONFIGURATION", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = ClawPhonesColors.TextDim, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))

        SettingsRow("API Key", "sk-ant-...7x4Q", shape)
        Spacer(Modifier.height(8.dp))
        SettingsRow("Bot Token", "7284...masked", shape)
        Spacer(Modifier.height(8.dp))
        SettingsRow("Model", "Claude Sonnet 4", shape)
        Spacer(Modifier.height(8.dp))
        SettingsRow("Agent Name", "ClawPhones", shape)

        Spacer(Modifier.height(24.dp))
        Text("PREFERENCES", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = ClawPhonesColors.TextDim, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))

        ToggleRow("Auto-start on boot", true, shape)
        Spacer(Modifier.height(8.dp))
        ToggleRow("Keep screen on", false, shape)

        Spacer(Modifier.height(24.dp))
        Text("DANGER ZONE", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = ClawPhonesColors.Error, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {}, modifier = Modifier.fillMaxWidth().height(48.dp), shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = ClawPhonesColors.ActionDanger),
        ) { Text("Reset Config", fontWeight = FontWeight.Bold, color = ClawPhonesColors.ActionDangerText) }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {}, modifier = Modifier.fillMaxWidth().height(48.dp), shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = ClawPhonesColors.ActionDanger),
        ) { Text("Wipe Memory", fontWeight = FontWeight.Bold, color = ClawPhonesColors.ActionDangerText) }
    }
}

// ============================================================================
// Helper Composables
// ============================================================================

@Composable
private fun StatMini(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ClawPhonesColors.TextPrimary)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = ClawPhonesColors.TextDim, letterSpacing = 1.sp)
    }
}

@Composable
private fun UplinkCard(icon: String, name: String, subtitle: String, dotColor: Color, shape: RoundedCornerShape) {
    Row(
        modifier = Modifier.fillMaxWidth().background(ClawPhonesColors.Surface, shape).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ClawPhonesColors.Primary, modifier = Modifier.width(44.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ClawPhonesColors.TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = ClawPhonesColors.TextDim)
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
    }
}

@Composable
private fun ColorSwatch(name: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(color))
        Spacer(Modifier.width(12.dp))
        Text(name, fontSize = 13.sp, color = ClawPhonesColors.TextPrimary)
        Spacer(Modifier.weight(1f))
        Text("#${Integer.toHexString(color.toArgb()).uppercase().drop(2)}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ClawPhonesColors.TextDim)
    }
}

@Composable
private fun FilterChip(label: String, color: Color) {
    Box(
        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun SettingsRow(label: String, value: String, shape: RoundedCornerShape) {
    Row(
        modifier = Modifier.fillMaxWidth().background(ClawPhonesColors.Surface, shape).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = ClawPhonesColors.TextPrimary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = ClawPhonesColors.TextDim)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, shape: RoundedCornerShape) {
    Row(
        modifier = Modifier.fillMaxWidth().background(ClawPhonesColors.Surface, shape).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = ClawPhonesColors.TextPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ClawPhonesColors.Primary,
                checkedTrackColor = ClawPhonesColors.Primary.copy(alpha = 0.3f),
                uncheckedThumbColor = ClawPhonesColors.TextDim,
                uncheckedTrackColor = ClawPhonesColors.BorderSubtle,
            ),
        )
    }
}
