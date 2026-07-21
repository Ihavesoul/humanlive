package dreamteam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.view.WindowCompat
import dreamteam.app.data.LocalDatabase

/**
 * Native entry point. Jetpack Compose is the only UI surface (ADR 0002 / DRE-9).
 * Hosts the offline-first app: onboarding -> plan -> log. Plan generation runs
 * the shared [:core:domain] safety gate locally — no server call, no LLM, no
 * safety decision the user can skip (ADR 0002 invariants #1, #5). No medical
 * claims are made here; the app frames itself as support, not treatment.
 */
class MainActivity : ComponentActivity() {
    private lateinit var db: LocalDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = LocalDatabase(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DreamTeamApp(db)
                }
            }
        }
    }
}
