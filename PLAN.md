# ClawPhones UI 改造 Spec — OpenCode 执行用

> **项目路径**: `/Users/howardli/Downloads/clawphones-new/`
> **目标**: SeekerClaw → ClawPhones 全面换皮 (架构/功能不动，只改品牌+UI)

---

## 前置: 删除嵌套目录

项目根目录有个嵌套的 `SeekerClaw/` 子目录 (复制时带入的)，先删掉:
```bash
rm -rf /Users/howardli/Downloads/clawphones-new/SeekerClaw/
```

---

## S1. 源码目录重命名 (包名 com.seekerclaw.app → ai.clawphones.agent)

### 1.1 移动源码目录
```bash
cd /Users/howardli/Downloads/clawphones-new/app/src/main/java
mkdir -p ai/clawphones/agent
cp -R com/seekerclaw/app/* ai/clawphones/agent/
rm -rf com/
```

### 1.2 全局替换 package/import 声明
在 `app/src/main/java/ai/clawphones/agent/` 下的**所有 .kt 文件**中:
- `com.seekerclaw.app` → `ai.clawphones.agent` (全部替换)

**涉及文件** (所有 .kt):
- MainActivity.kt
- SeekerClawApplication.kt (后面 S2 会改文件名)
- bridge/AndroidBridge.kt
- camera/CameraCaptureActivity.kt
- config/ConfigManager.kt, ConfigClaimImporter.kt, KeystoreHelper.kt, Models.kt
- qr/QrScannerActivity.kt, ScannerOverlay.kt
- receiver/BootReceiver.kt
- service/OpenClawService.kt, NodeBridge.kt, Watchdog.kt
- solana/SolanaAuthActivity.kt, SolanaWalletManager.kt, SolanaTransactionBuilder.kt
- ui/components/PixelComponents.kt
- ui/dashboard/DashboardScreen.kt
- ui/logs/LogsScreen.kt
- ui/navigation/NavGraph.kt
- ui/settings/SettingsScreen.kt, AnthropicConfigScreen.kt, TelegramConfigScreen.kt, SettingsHelpTexts.kt
- ui/setup/SetupScreen.kt
- ui/skills/SkillsScreen.kt, SkillDetailScreen.kt, SkillInfo.kt, SkillsRepository.kt
- ui/system/SystemScreen.kt
- ui/theme/Theme.kt
- util/Analytics.kt, DeviceInfoProvider.kt, LogCollector.kt, ServiceState.kt, StatsClient.kt

### 1.3 build.gradle.kts
文件: `app/build.gradle.kts`

```
applicationId = "com.seekerclaw.app"  →  applicationId = "ai.clawphones.agent"
```

namespace 已经是 `ai.clawphones`，改为:
```
namespace = "ai.clawphones"  →  namespace = "ai.clawphones.agent"
```

signing config 属性名改:
```
SEEKERCLAW_KEYSTORE_PATH   →  CLAWPHONES_KEYSTORE_PATH
SEEKERCLAW_STORE_PASSWORD  →  CLAWPHONES_STORE_PASSWORD
SEEKERCLAW_KEY_ALIAS       →  CLAWPHONES_KEY_ALIAS
SEEKERCLAW_KEY_PASSWORD    →  CLAWPHONES_KEY_PASSWORD
```
(共 8 处，signingProp 的两个参数都要改)

### 1.4 settings.gradle.kts
```
rootProject.name = "SeekerClaw"  →  rootProject.name = "ClawPhones"
```

### 1.5 AndroidManifest.xml
文件: `app/src/main/AndroidManifest.xml`

```xml
android:name=".SeekerClawApplication"  →  android:name=".ClawPhonesApplication"
android:theme="@style/Theme.SeekerClaw"  →  android:theme="@style/Theme.ClawPhones"
```
(theme 出现 3 次: application 标签 + MainActivity + CameraCaptureActivity)

### 1.6 native-lib.cpp
文件: `app/src/main/cpp/native-lib.cpp`

搜索 `com_seekerclaw_app` 或 `com/seekerclaw/app` 的 JNI 函数名，替换为 `ai_clawphones_agent` / `ai/clawphones/agent`

---

## S2. 品牌文字替换

### 2.1 类名重命名
| 旧文件名 | 新文件名 |
|----------|---------|
| `SeekerClawApplication.kt` | `ClawPhonesApplication.kt` |

文件内容:
```kotlin
class SeekerClawApplication  →  class ClawPhonesApplication
"SeekerClaw Service"         →  "ClawPhones Service"
"SeekerClaw Alerts"          →  "ClawPhones Alerts"
CHANNEL_ID = "seekerclaw_service"  →  "clawphones_service"
ERROR_CHANNEL_ID = "seekerclaw_errors"  →  "clawphones_errors"
```

### 2.2 Theme 类名
文件: `ui/theme/Theme.kt`
```kotlin
object SeekerClawColors  →  object ClawPhonesColors
fun SeekerClawTheme(...)  →  fun ClawPhonesTheme(...)
// 注释中的 "SeekerClaw" 也改为 "ClawPhones"
// "DARKOPS THEME — SeekerClaw's single theme"  →  "DARKOPS THEME — ClawPhones's single theme"
```

### 2.3 NavGraph 函数名
文件: `ui/navigation/NavGraph.kt`
```kotlin
fun SeekerClawNavHost()  →  fun ClawPhonesNavHost()
```

### 2.4 MainActivity 引用
文件: `MainActivity.kt`
```kotlin
import ...SeekerClawNavHost  →  import ...ClawPhonesNavHost
import ...SeekerClawTheme    →  import ...ClawPhonesTheme
SeekerClawTheme {            →  ClawPhonesTheme {
    SeekerClawNavHost()      →      ClawPhonesNavHost()
```

### 2.5 全局 SeekerClawColors 引用
**所有 .kt 文件**中的 `SeekerClawColors` → `ClawPhonesColors`
(出现在: NavGraph.kt, DashboardScreen.kt, LogsScreen.kt, SetupScreen.kt, SettingsScreen.kt, SkillsScreen.kt, SkillDetailScreen.kt, SystemScreen.kt, ScannerOverlay.kt, QrScannerActivity.kt, PixelComponents.kt, AnthropicConfigScreen.kt, TelegramConfigScreen.kt)

### 2.6 strings.xml
文件: `app/src/main/res/values/strings.xml`
```xml
<string name="app_name">SeekerClaw</string>  →  <string name="app_name">ClawPhones</string>
<string name="notification_channel_name">SeekerClaw Service</string>  →  <string name="notification_channel_name">ClawPhones Service</string>
```

### 2.7 Dashboard 品牌 Logo
文件: `ui/dashboard/DashboardScreen.kt`

找到渲染品牌名的地方 (buildAnnotatedString 中 "Seeker" + "Claw"):
```kotlin
// 旧: "Seeker" in white, "Claw" in red
// 新: "Claw" in gold, "Phones" in white
withStyle(SpanStyle(color = ClawPhonesColors.Primary)) { append("Claw") }
withStyle(SpanStyle(color = ClawPhonesColors.TextPrimary)) { append("Phones") }
```

### 2.8 nodejs-project 内的品牌引用
`assets/nodejs-project/` 里的 JS 文件中 "SeekerClaw" / "seekerclaw" 改为 "ClawPhones" / "clawphones"
(这些是 Node.js 端的引用，telegram bot 名称等需要统一)

**重要**: 只改品牌显示名称 (如 Telegram bot 回复中的 "SeekerClaw")，不改功能代码逻辑。
`database.js` 中如果有 `seekerclaw.db` → `clawphones.db`

---

## S3. 颜色主题

文件: `ui/theme/Theme.kt`

### DarkOpsThemeColors 替换值:

```kotlin
val DarkOpsThemeColors = ThemeColors(
    // 背景 — 从深海军蓝改为深灰
    background = Color(0xFF1A1A1A),        // was 0xFF0A0A0F
    surface = Color(0xFF2A2A2A),           // was 0xFF16161F
    surfaceHighlight = Color(0xFF353535),   // was 0xFF1E1E2E
    cardBorder = Color(0x40505050),         // was 0x40374151

    // 品牌主色 — 从红改为金色
    primary = Color(0xFFE8A853),           // was 0xFFE41F28 (Gold)
    primaryDim = Color(0xFFC08A3A),        // was 0xFFB81820 (Darker Gold)
    primaryGlow = Color(0x33E8A853),       // was 0x33E41F28

    // Error/Warning/Accent — 保留不变
    error = Color(0xFFF87171),
    errorDim = Color(0xFFCC3636),
    errorGlow = Color(0x33F87171),
    warning = Color(0xFFFBBF24),
    accent = Color(0xFF4ADE80),

    // Actions — 保留不变
    actionPrimary = Color(0xFF00C805),
    actionDanger = Color(0xFF8B0000),
    actionDangerText = Color(0xFFFF6B6B),

    // Logs — 保留不变
    logInfo = Color(0xFF60A5FA),
    logDebug = Color(0xFF6B7280),

    // 文字 — 从纯白改为暖白/奶油色
    textPrimary = Color(0xFFF5F0E6),       // was 0xF0FFFFFF (Cream)
    textSecondary = Color(0xFF9CA3AF),     // 保留
    textDim = Color(0xFF9CA3AF),           // 保留
    textInteractive = Color(0xB3F5F0E6),   // was 0xB3FFFFFF (70% cream)

    // Borders
    borderSubtle = Color(0xFF505050),      // was 0xFF374151 (warmer gray)

    // Effects — 保留不变
    scanline = Color(0x00000000),
    dotMatrix = Color(0x00000000),
    cornerRadius = 12.dp,
    useDotMatrix = false,
    useScanlines = false,
)
```

---

## S4. App Icon 颜色

### 4.1 ic_seekerclaw_symbol.xml → ic_clawphones_symbol.xml
文件重命名 + 内容改色:
```xml
android:fillColor="#E41F28"  →  android:fillColor="#E8A853"
```
(2 处 path 的 fillColor)

### 4.2 ic_launcher_foreground.xml
同样:
```xml
android:fillColor="#E41F28"  →  android:fillColor="#E8A853"
```
(2 处)

### 4.3 ic_launcher_background.xml
```xml
android:fillColor="#0D0D0D"  →  android:fillColor="#1A1A1A"
```

### 4.4 代码中引用更新
搜索 `R.drawable.ic_seekerclaw_symbol`，替换为 `R.drawable.ic_clawphones_symbol`

---

## S5. XML Theme

文件: `app/src/main/res/values/themes.xml`

```xml
<style name="Theme.SeekerClaw" parent="android:Theme.Material.NoActionBar">
→  <style name="Theme.ClawPhones" parent="android:Theme.Material.NoActionBar">

<item name="android:colorBackground">#0D0F14</item>  →  #1A1A1A
<item name="android:statusBarColor">#0D0F14</item>    →  #1A1A1A
<item name="android:navigationBarColor">#161A25</item> →  #2A2A2A
```

---

## S6. Mipmap Icons (可选)

`app/src/main/res/mipmap-*/` 下的 PNG 图标如果存在，需要用新颜色重新生成。
如果只有 `ic_launcher.xml` (adaptive icon 引用 drawable)，那 S4 的改动已经覆盖了。

检查: `ls app/src/main/res/mipmap-*/`，如果有 PNG 文件需要重新生成。

---

## 验证清单

完成后运行以下验证:

```bash
cd /Users/howardli/Downloads/clawphones-new

# 1. 确认没有残留的 SeekerClaw 引用 (排除 git/build 目录和 PLAN.md)
grep -r "SeekerClaw\|seekerclaw\|SEEKERCLAW" --include="*.kt" --include="*.xml" --include="*.kts" app/

# 2. 确认没有残留的旧包名
grep -r "com\.seekerclaw\.app\|com/seekerclaw/app" --include="*.kt" --include="*.xml" --include="*.kts" --include="*.cpp" app/

# 3. 确认没有旧目录
ls app/src/main/java/com/ 2>/dev/null && echo "ERROR: old com/ dir still exists" || echo "OK: com/ removed"

# 4. 确认新目录存在
ls app/src/main/java/ai/clawphones/agent/MainActivity.kt && echo "OK" || echo "ERROR"

# 5. Gradle sync (如果有 Android Studio 环境)
# ./gradlew assembleDebug
```

---

## 不动的文件/模块 (重要!)

- `app/src/main/assets/nodejs-project/` 里的功能代码逻辑
- OpenClawService / NodeBridge / Watchdog 的功能
- Solana 钱包集成代码
- Camera / QR 功能代码
- ConfigManager 加密逻辑
- AndroidBridge HTTP bridge
- `app/src/main/cpp/` (除了 JNI 函数签名)
- `libnode/` 目录
- Gradle dependencies
- 字体文件 (Rethink Sans)

---

## 执行顺序

1. 删除嵌套 `SeekerClaw/` 目录
2. S1 — 包名重命名 (目录移动 + 全局替换)
3. S2 — 品牌文字 (类名 + 字符串)
4. S3 — 颜色主题
5. S4 — 图标颜色
6. S5 — XML theme
7. 验证清单
