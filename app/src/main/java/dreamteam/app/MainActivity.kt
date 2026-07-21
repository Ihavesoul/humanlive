package dreamteam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Native entry point. Jetpack Compose is the only UI surface (ADR 0002 / DRE-9).
 *
 * This skeleton deliberately renders a static, non-medical screen: it proves
 * the native toolchain assembles and the client structure is in place. Wiring
 * it to the shared deterministic safety/domain logic (`:core:domain`) and the
 * backend `/v1` surface lands with DRE-6 / DRE-7. No medical claims, no LLM
 * calls, no safety decisions are made here — those stay server-side and in
 * code, never delegated to the model (ADR 0002 invariant #1).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Home()
                }
            }
        }
    }
}

@Composable
private fun Home() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Сkeleton native client (ADR 0002).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Safety engine, plan generation, and evidence-linked " +
                "content are wired in next (DRE-6 / DRE-7).",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
