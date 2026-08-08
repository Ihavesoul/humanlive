package dreamteam.app
import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource

/**
 * M8-D ([DRE-90](/DRE/issues/DRE-90)): the app's bottom navigation bar. This is
 * the single biggest "doesn't look like an MD-viewer" win — the M5–M7 home
 * surface reached Plan / History / Evidence-sources via a vertical stack of six
 * `OutlinedButton`s glued to the bottom of a scroll, which read as a markdown
 * link list. A bottom [NavigationBar] is the production-app nav pattern: the
 * four read/main destinations sit one tap away, always visible, never scrolled
 * off. Icons are ZEN thin line icons (Lucide v1.30, ISC) traced to vector XML in
 * `res/drawable` ([DRE-237](/DRE/issues/DRE-237), ZEN v3 §4) — **no new dependency**.
 *
 * Logging actions (progress / symptom) are NOT here: they stay contextual to the
 * Today screen (compact buttons in its header), because they are *writes*, not
 * destinations. Navigation only re-points the existing `Screen` enum — no new
 * screens, no new state, the gate/plan/logic is untouched.
 */

/** The nav destinations shown in the bottom bar (read/main screens only). */
private data class NavDestination(val screen: Screen, val label: String, @DrawableRes val icon: Int)

private val NAV_DESTINATIONS = listOf(
    NavDestination(Screen.Today, UiStrings.NAV_TODAY, R.drawable.zen_ic_today),
    NavDestination(Screen.Plan, UiStrings.NAV_PLAN, R.drawable.zen_ic_plan),
    NavDestination(Screen.History, UiStrings.NAV_HISTORY, R.drawable.zen_ic_history),
    NavDestination(Screen.EvidenceSources, UiStrings.NAV_SOURCES, R.drawable.zen_ic_sources),
    NavDestination(Screen.Settings, UiStrings.NAV_SETTINGS, R.drawable.zen_ic_settings),
)

/**
 * Render the bottom bar. [current] is the active destination (highlighted);
 * [onNavigate] re-points the app's `Screen`. A non-nav screen (Symptoms /
 * Progress / Onboarding) renders nothing here — those flows own the full screen.
 */
@Composable
internal fun AppNavigationBar(current: Screen, onNavigate: (Screen) -> Unit) {
    NavigationBar {
        NAV_DESTINATIONS.forEach { dest ->
            NavigationBarItem(
                selected = current == dest.screen,
                onClick = { onNavigate(dest.screen) },
                icon = { Icon(painterResource(dest.icon), contentDescription = dest.label) },
                label = { Text(dest.label) },
            )
        }
    }
}

/**
 * M8-D: the authored chrome strings the app shell renders (nav labels + the
 * brand wordmark + section headers). Gathered as one list ([all]) so a JVM test
 * can snapshot them against the banned medical-claim phrase list, mirroring
 * [TodayStrings] / [CoachStrings] / [ReferencesCardStrings]. Support framing
 * only: plain navigation nouns — no diagnosis, no treatment claim.
 */
internal object UiStrings {
    const val APP_NAME = "DreamTeam"
    const val NAV_TODAY = "Сегодня"
    const val NAV_PLAN = "План"
    // DRE-193: aligned to the screen it opens — HistoryScreen is titled
    // «История и тренд» (HistoryStrings.TITLE); the prior «Журнал» label
    // mismatched the screen title it navigated to.
    const val NAV_HISTORY = "История"
    const val NAV_SOURCES = "Источники"
    const val NAV_SETTINGS = "Настройки"

    val all: List<String> = listOf(APP_NAME, NAV_TODAY, NAV_PLAN, NAV_HISTORY, NAV_SOURCES, NAV_SETTINGS)
}
