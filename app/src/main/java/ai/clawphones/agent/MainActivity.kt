package ai.clawphones.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ai.clawphones.agent.ui.navigation.ClawPhonesNavHost
import ai.clawphones.agent.ui.theme.ClawPhonesTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClawPhonesTheme {
                ClawPhonesNavHost()
            }
        }
    }
}
