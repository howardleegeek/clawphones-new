<div align="center">
  <h1>UniversalClaw</h1>
  <p><em>AgentOS for Android</em></p>
  <p><strong>A 24/7 AI agent that lives on your phone</strong></p>

  <p>
    <img src="https://img.shields.io/badge/Android-14+-3DDC84?logo=android&logoColor=white" alt="Android 14+">
    <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
    <img src="https://img.shields.io/badge/Compose-Material3-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose">
    <img src="https://img.shields.io/badge/Claude-Powered-cc785c?logo=anthropic&logoColor=white" alt="Claude">
    <img src="https://img.shields.io/badge/Solana-Wallet-9945FF?logo=solana&logoColor=white" alt="Solana">
    <img src="https://img.shields.io/badge/Telegram-Bot-26A5E4?logo=telegram&logoColor=white" alt="Telegram">
  </p>
</div>

---

UniversalClaw embeds a Node.js AI agent inside an Android app, running 24/7 as a foreground service. You interact through Telegram — ask questions, control your phone, trade crypto, schedule tasks. **56 tools, 35 skills, Solana wallet**, all running locally on your device.

## Features

| | Feature | What it does |
|---|---|---|
| :robot: | **AI Engine** | Claude (Opus / Sonnet / Haiku) with multi-turn tool use |
| :speech_balloon: | **Telegram** | Full bot — reactions, file sharing, inline keyboards, 12 commands |
| :link: | **Solana Wallet** | Swaps, limit orders, DCA, transfers via Jupiter + MWA |
| :iphone: | **Device Control** | Battery, GPS, camera, SMS, calls, clipboard, TTS |
| :brain: | **Memory** | Persistent personality, daily notes, ranked keyword search |
| :alarm_clock: | **Scheduling** | Cron jobs with natural language ("remind me in 30 min") |
| :globe_with_meridians: | **Web Intel** | Search (Brave / DuckDuckGo / Perplexity), fetch, caching |
| :electric_plug: | **Extensible** | 35 skills + custom skills + MCP remote tools |

<details>
<summary><strong>Architecture</strong></summary>

<br>

```mermaid
graph LR
    You["You (Telegram)"] -->|messages| Agent["UniversalClaw Agent"]
    Agent -->|reasoning| Claude["Claude API"]
    Agent -->|swaps, balance| Solana["Solana / Jupiter"]
    Agent -->|device access| Bridge["Android Bridge"]
    Agent -->|search, fetch| Web["Web APIs"]
    Claude -->|tool calls| Agent
```

**On-device stack:**

```
Android App (Kotlin, Jetpack Compose)
 └─ Foreground Service
     └─ Node.js Runtime (nodejs-mobile)
         ├─ claude.js      — Claude API, system prompt, conversations
         ├─ tools.js       — 56 tool handlers + confirmations
         ├─ solana.js      — Jupiter swaps, DCA, limit orders
         ├─ telegram.js    — Bot, formatting, commands
         ├─ memory.js      — Persistent memory + ranked search
         ├─ skills.js      — Skill loading + semantic routing
         ├─ cron.js        — Job scheduling + natural language parsing
         ├─ mcp-client.js  — MCP Streamable HTTP client
         ├─ web.js         — Search + fetch + caching
         ├─ database.js    — SQL.js analytics
         ├─ security.js    — Prompt injection defense
         ├─ bridge.js      — Android Bridge HTTP client
         ├─ config.js      — Config loading + validation
         └─ main.js        — Orchestrator + heartbeat
```

</details>

## Quick Start

**Prerequisites:** Android Studio, JDK 17, Android SDK 35

```bash
git clone https://github.com/sepivip/UniversalClaw.git
cd UniversalClaw
./gradlew assembleDebug
adb install app/build/outputs/apk/dappStore/debug/app-dappStore-debug.apk
```

Open the app → scan QR or enter your [Anthropic API key](https://console.anthropic.com/) + [Telegram bot token](https://t.me/BotFather) → choose a model → name your agent → deploy.

## Design

| Element | Value |
|---------|-------|
| **Primary** | Gold `#E8A853` |
| **Background** | Dark `#1A1A1A` |
| **Surface** | `#2A2A2A` |
| **Text** | Cream `#F5F0E6` |
| **Font** | Rethink Sans |
| **Accent** | Green `#4ADE80` |

## Safety

UniversalClaw gives an AI agent real capabilities on your phone — including wallet transactions, messaging, and device control.

- **AI can make mistakes.** Always verify before trusting critical outputs.
- **Wallet transactions are irreversible.** The agent requires confirmation for financial actions.
- **Start with small amounts.** Don't connect a wallet with significant funds until you're comfortable.

## Tech Stack

- **UI:** Jetpack Compose + Material 3
- **Runtime:** nodejs-mobile (Node.js 18 LTS embedded)
- **AI:** Claude API (Anthropic)
- **Crypto:** Solana Mobile Wallet Adapter 2.0 + Jupiter
- **Messaging:** Telegram Bot API
- **Camera:** CameraX + ML Kit
- **Security:** Android Keystore AES-256-GCM

## License

MIT

---

<div align="center">
  <sub>Built by <a href="https://github.com/sepivip">UniversalClaw Contributors</a></sub>
</div>
