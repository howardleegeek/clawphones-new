package ai.clawphones.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.clawphones.agent.config.ConfigManager
import ai.clawphones.agent.service.OpenClawService
import ai.clawphones.agent.util.LogCollector

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        LogCollector.init(context)
        LogCollector.append("[Boot] Device boot completed")

        if (!ConfigManager.isSetupComplete(context)) {
            LogCollector.append("[Boot] Setup not complete, skipping auto-start")
            return
        }

        if (!ConfigManager.getAutoStartOnBoot(context)) {
            LogCollector.append("[Boot] Auto-start disabled, skipping")
            return
        }

        val config = ConfigManager.loadConfig(context)
        val validationError = ConfigManager.runtimeValidationError(config)
        if (validationError != null) {
            LogCollector.append("[Boot] Config invalid ($validationError), skipping auto-start")
            return
        }

        LogCollector.append("[Boot] Auto-starting OpenClaw service...")
        OpenClawService.start(context)
    }
}
