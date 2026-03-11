package ai.clawphones.agent.config

import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles importing configuration from custom URI schemes (universalclaw://)
 * and setup URLs (universalclaw.xyz/setup?config=...).
 */
object ConfigClaimImporter {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses a setup URI or URL and returns a partial AgentConfig.
     * Supported formats:
     * - universalclaw://config/<base64_json>
     * - https://universalclaw.xyz/setup?config=<base64_json>
     */
    fun parseImportUri(uri: Uri): AgentConfig? {
        if (!uri.scheme.equals("universalclaw", ignoreCase = true) && 
            !uri.host.equals("universalclaw.xyz", ignoreCase = true)) return null

        val payload = when {
            uri.scheme.equals("universalclaw", ignoreCase = true) -> {
                if (uri.host == "config") uri.path?.removePrefix("/") else null
            }
            uri.host.equals("universalclaw.xyz", ignoreCase = true) -> {
                uri.getQueryParameter("config")
            }
            else -> null
        } ?: return null

        return try {
            val decoded = String(Base64.decode(payload, Base64.URL_SAFE))
            val jsonObj = json.parseToJsonElement(decoded).jsonObject
            
            AgentConfig(
                anthropicKey = jsonObj["anthropic_api_key"]?.jsonPrimitive?.content ?: "",
                telegramToken = jsonObj["telegram_bot_token"]?.jsonPrimitive?.content ?: "",
                telegramOwnerId = jsonObj["telegram_owner_id"]?.jsonPrimitive?.content ?: "",
                model = jsonObj["model"]?.jsonPrimitive?.content ?: "claude-sonnet-4-5",
                agentName = jsonObj["agent_name"]?.jsonPrimitive?.content?.trim()?.ifBlank { "UniversalClaw" }
                    ?: "UniversalClaw",
                autoStart = true
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetches a config payload from a setup token (short-lived claim).
     * Used for "Login with Claude" or secure browser-to-app handoff.
     */
    suspend fun fetchConfigByToken(token: String): AgentConfig? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = URL("https://api.universalclaw.xyz/v1/setup/claim/$token")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "UniversalClaw/Android")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val jsonObj = json.parseToJsonElement(body).jsonObject
                    
                    AgentConfig(
                        anthropicKey = jsonObj["anthropic_api_key"]?.jsonPrimitive?.content ?: "",
                        telegramToken = jsonObj["telegram_bot_token"]?.jsonPrimitive?.content ?: "",
                        telegramOwnerId = jsonObj["telegram_owner_id"]?.jsonPrimitive?.content ?: "",
                        model = jsonObj["model"]?.jsonPrimitive?.content ?: "claude-sonnet-4-5",
                        agentName = jsonObj["agent_name"]?.jsonPrimitive?.content?.trim()?.ifBlank { "UniversalClaw" }
                            ?: "UniversalClaw"
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }
}
