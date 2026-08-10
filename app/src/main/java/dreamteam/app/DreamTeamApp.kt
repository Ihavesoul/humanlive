package dreamteam.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dreamteam.app.data.LocalDatabase
import dreamteam.app.ui.Spacing
import dreamteam.app.ui.Motion
import dreamteam.app.ui.PlayScene
import dreamteam.app.ui.BreathingScene
import kotlinx.coroutines.launch
import dreamteam.app.data.ExerciseNoteOutcome
import dreamteam.app.data.Profile
import dreamteam.app.data.ProgressRow
import dreamteam.app.data.SymptomEntry
import dreamteam.domain.RuleId
import dreamteam.domain.adaptation.AdaptationSignal
import dreamteam.domain.safety.MedicalSafety
import dreamteam.domain.safety.SafetyEvaluation
import dreamteam.domain.safety.SafetyGate
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.training.BaselineProgram
import dreamteam.domain.training.GeneratedPlan
import dreamteam.domain.training.DeterministicPlanGenerator
import dreamteam.domain.training.PlanWeek
import dreamteam.domain.training.TrainingPlan
import dreamteam.domain.nutrition.GeneratedNutritionPlan
import dreamteam.domain.nutrition.NutritionPlan
import java.time.LocalDate

/**
 * The minimal M2-A client surface (ADR 0002). Ugly-but-functional is the brief;
 * design polish is M3.
 *
 * Flow: onboarding (profile) → plan view (the surfaced deterministic baseline) →
 * log workout + symptom. The plan is produced by the SAME shared
 * [SafetyGuardedGateway] the backend uses (DRE-12 #3): the client runs the
 * deterministic path offline-first, no server round-trip. A reported red flag
 * blocks the plan and routes to assessment — the gate is structural, the user
 * cannot skip it.
 */
internal enum class Screen { Onboarding, Today, Plan, Symptoms, Progress, History, EvidenceSources, Settings, Play, Breathing }

/**
 * M8-D ([DRE-90](/DRE/issues/DRE-90)): the screens that show the bottom
 * [AppNavigationBar]. The four read/main destinations. Onboarding owns the
 * full screen (no nav until a profile exists); Symptoms/Progress are modal
 * write flows with their own Back button, so they hide the bottom bar.
 */
internal val NAV_DESTINATION_SCREENS: Set<Screen> =
    setOf(Screen.Today, Screen.Plan, Screen.History, Screen.EvidenceSources, Screen.Settings)

/**
 * Outcome of the local deterministic generation, mirroring the server's. [Ok.signal]
 * carries the week's adaptation so the UI can render it — de-load only, support-
 * framed, no diagnosis (M3-C [DRE-52](/DRE/issues/DRE-52)).
 */
private sealed interface PlanResult {
    data class Ok(
        val week: PlanWeek,
        // M4-C ([DRE-57](/DRE/issues/DRE-57)): the full surfaced NutritionPlan
        // (target + meal structure + evidence refs), or null when the nutrition
        // gate blocks — only a gate-Ok plan is rendered. Offline-first: produced
        // locally from the profile by [localNutritionPlan].
        val nutritionPlan: NutritionPlan?,
        val safety: SafetyEvaluation,
        val signal: AdaptationSignal,
    ) : PlanResult
    /**
     * A gate block. [reason] is the support-framed headline; [ruleIds] are the
     * triggering rules so the render layer can resolve their citations (M6-C,
     * [DRE-69](/DRE/issues/DRE-69)). Empty for the medical-safety red-flag path
     * (a different gate — no [dreamteam.domain.safety.SafetyRule]); the rule-
     * engine path carries the verdict's rule ids. Citations EXPLAIN the block;
     * nothing is surfaced as guidance either way.
     */
    data class Blocked(val reason: String, val ruleIds: List<RuleId> = emptyList()) : PlanResult
}

/**
 * Runs the SAME deterministic, safety-gated path as the backend — offline-first,
 * no server round-trip (ADR 0002). [symptoms] are the user's own logged rows,
 * turned into a de-load-only [AdaptationSignal] by [localAdaptationSignal]; the
 * signal flows through [DeterministicPlanGenerator.generate] **inside** the
 * already-approved gate bounds (it only de-loads working sets; it never selects,
 * unblocks, or bypasses [SafetyGuardedGateway]). A red-flag profile still blocks
 * here, before any signal is considered.
 */
private fun generateLocalPlan(
    profile: Profile,
    today: String,
    symptoms: List<SymptomEntry>,
    // M5-A (DRE-61): real logged body-weight rows now feed the RapidWeightLoss
    // trigger (was emptyList() — the DRE-52 deferral).
    progress: List<ProgressRow>,
): PlanResult {
    // M7-A ([DRE-72](/DRE/issues/DRE-72)): delegate to the shared regeneration
    // core so there is ONE gate setup — the on-screen plan and the exported plan
    // are produced by the same code path and can never drift. The mapping below
    // preserves the exact prior UI semantics (red-flag vs gateway headlines).
    return when (val o = regenerateLocalPlans(profile, today, symptoms, progress)) {
        is LocalPlanOutcome.Ok ->
            PlanResult.Ok(o.plans.training.weeks.first(), o.plans.nutrition, o.plans.safety, o.plans.signal)
        is LocalPlanOutcome.RedFlag -> PlanResult.Blocked(SafetyBlockStrings.REDFLAG_HEADLINE)
        is LocalPlanOutcome.GatewayBlocked -> PlanResult.Blocked(SafetyBlockStrings.GATEWAY_HEADLINE, o.ruleIds)
    }
}

/**
 * Outcome of regenerating the local deterministic plans through the gate.
 * [Ok] carries the full surfaced [TrainingPlan] (all weeks) + the gate-Ok
 * [NutritionPlan]; the two block paths mirror the two gates the UI distinguishes.
 */
internal sealed interface LocalPlanOutcome {
    data class Ok(val plans: LocalPlans) : LocalPlanOutcome
    /** Medical-safety red-flag gate blocked before generation (no SafetyRule → no ruleIds). */
    data object RedFlag : LocalPlanOutcome
    /** The assignment gateway blocked — nothing surfaced; [ruleIds] are the triggers. */
    data class GatewayBlocked(val ruleIds: List<RuleId>) : LocalPlanOutcome
}

/** The freshly regenerated deterministic plans + the gate/signal context the UI reuses. */
internal data class LocalPlans(
    val training: TrainingPlan,
    /** null when the nutrition gate blocks (training still surfaced). */
    val nutrition: NutritionPlan?,
    val safety: SafetyEvaluation,
    val signal: AdaptationSignal,
)

/**
 * The shared regeneration core: the SAME deterministic, safety-gated path the UI
 * ([generateLocalPlan]) and the data export (M7-A / [DRE-72](/DRE/issues/DRE-72))
 * use. Runs the medical-safety red-flag gate, then the assignment gateway, then
 * the nutrition gate — exactly the wiring the app has always used. Pure given
 * (profile, today, symptoms, progress): same inputs → same plans, offline-first,
 * no network. The export's `plan` section is [Ok.plans], so it is computed fresh,
 * never a stale cache (the plan-is-computed invariant in [LocalDatabase]).
 */
internal fun regenerateLocalPlans(
    profile: Profile,
    today: String,
    symptoms: List<SymptomEntry>,
    progress: List<ProgressRow>,
): LocalPlanOutcome {
    val medical = MedicalSafety(
        scoliosisReported = profile.scoliosisReported,
        redFlags = profile.redFlags,
        currentCurveDataAvailable = false,
        clinicianCurveSpecificPlanAvailable = false,
    )
    val safety = SafetyGate.evaluate(medical)
    // M6-C: the medical-safety gate has no SafetyRule → no ruleIds → no
    // citations; the headline routes to assessment. Scan-clean support framing.
    if (!safety.allowTrainingGeneration) return LocalPlanOutcome.RedFlag
    val context = ScreeningContext(
        allowedExerciseIds = BaselineProgram.exerciseIds,
        allowedEvidenceIds = BaselineProgram.evidenceIds,
        sideSpecificLockEngaged = !safety.allowSideSpecificContent,
        conditionFlags = if (profile.scoliosisReported) setOf("scoliosis_flagged") else emptySet(),
    )
    val gateway = SafetyGuardedGateway(context, CLIENT_SAFETY_RULES)
    val signal = localAdaptationSignal(symptoms, progress)
    return when (val g = DeterministicPlanGenerator(gateway).generate(userId = "local", createdAt = today, adaptation = signal)) {
        is GeneratedPlan.Ok -> {
            // M4-C: surface the FULL deterministic NutritionPlan behind its own
            // nutrition-appropriate gate. Only a gate-Ok plan is included; a
            // block yields null (training still surfaced). Same inputs → same plan.
            val nutritionPlan = when (val n = localNutritionPlan(profile, today)) {
                is GeneratedNutritionPlan.Ok -> n.plan
                is GeneratedNutritionPlan.Blocked -> null
            }
            LocalPlanOutcome.Ok(LocalPlans(g.plan, nutritionPlan, safety, signal))
        }
        // M6-C: carry the triggering rule ids so the block card can resolve their
        // citations. The gate's block behavior is unchanged — nothing is surfaced.
        is GeneratedPlan.Blocked -> LocalPlanOutcome.GatewayBlocked(g.ruleIds)
    }
}

/**
 * M6-C ([DRE-69](/DRE/issues/DRE-69)): the shared block card both [PlanScreen]
 * and [TodayScreen] render. Shows the support-framed headline + the BLOCKING
 * rule's citations ("Основание:" + a resolved citation or the blocked-until-
 * sourced placeholder) so a block is transparent, not opaque. Citations EXPLAIN
 * the block — they are not rendered guidance; the gate's `surfaced == []`
 * invariant is unchanged. Pure render via [safetyBlockExplanation] (no logic in
 * the tree), Android I/O only at the edge (the resolver loaded at the root).
 */
@Composable
private fun BlockCard(result: PlanResult.Blocked, resolver: EvidenceResolver) {
    val explanation = remember(result) { safetyBlockExplanation(result.reason, result.ruleIds, CLIENT_SAFETY_RULES, resolver) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.card), verticalArrangement = Arrangement.spacedBy(Spacing.tightGap)) {
            Text(explanation.reason, fontWeight = FontWeight.Medium)
            if (explanation.citations.isNotEmpty()) {
                Text(SafetyBlockStrings.CITATION_LABEL, fontWeight = FontWeight.Light)
                explanation.citations.forEach { c -> Text("• ${c.line}", fontWeight = FontWeight.Light) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DreamTeamApp(db: LocalDatabase) {
    var screen by remember { mutableStateOf(if (db.loadProfile() == null) Screen.Onboarding else Screen.Today) }
    var profile by remember { mutableStateOf(db.loadProfile()) }
    // DRE-175: the user's encrypted AI-coach credential store (Android Keystore
    // AES-GCM). Built once at the root and threaded into the coach call sites so
    // "Спросить у AI" / "Сообщить коучу" can call the LLM directly with the
    // user's own URL+token; absent creds ⇒ null ⇒ deterministic fallback (#4).
    val appContext = LocalContext.current
    val coachCredStore = remember { CoachCredentialStore(appContext) }
    // DRE-209: the AI-coach user toggle (default OFF). When off the two coach CTAs
    // ("Спросить у AI" / "Отправить коучу") are hidden and the app runs the
    // deterministic plan alone; when on the user can enter creds to light their LLM.
    var aiCoachEnabled by remember { mutableStateOf(coachCredStore.isEnabled()) }
    // M6-B ([DRE-68](/DRE/issues/DRE-68)): the offline-first evidence resolver,
    // decoded once from the bundled catalog asset (single Android-I/O point) so
    // the nutrition + training views render READABLE citations, not raw ids. No
    // network; pure render below ([resolveCitations] / [nutritionPlanView]).
    // [LocalContext.current] is read outside the remember lambda — it is a
    // @Composable read and cannot live inside it.
    val resolver = remember { loadEvidenceResolver(appContext.assets) }
    // M8-A ([DRE-80](/DRE/issues/DRE-80)): the offline-first exercise-library
    // resolver, decoded once from the bundled `exercises.json` asset (single
    // Android-I/O point, same `data/` srcDir as the evidence catalog) so each
    // exercise renders a tappable references card (video / how-to / images /
    // evidence) — no naked links. No network; pure render below
    // ([resolveExerciseReferences] / [ReferencesCard]).
    val exerciseLibrary = remember { loadExerciseLibrary(appContext.assets) }
    // Redesign v2 ([DRE-211](/DRE/issues/DRE-211)): the offline-first exercise-MEDIA
    // resolver (FE track, [DRE-210](/DRE/issues/DRE-210)), decoded once from the
    // bundled `exercise_media.json` asset (single Android-I/O point, same `data/`
    // srcDir) so each exercise card renders its license-clean image + readable RU
    // summary ([DRE-207](/DRE/issues/DRE-207) content). No network for resolution;
    // only the image bytes themselves are fetched (Coil, cached to disk).
    val exerciseMedia = remember { loadExerciseMedia(appContext.assets) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(UiStrings.APP_NAME) }) },
        bottomBar = {
            // M8-D ([DRE-90](/DRE/issues/DRE-90)): the bottom nav is shown only
            // on the four read/main destinations — Onboarding owns the full
            // screen (no nav until a profile exists), and Symptoms/Progress are
            // modal write flows that keep their own Back button. None of this
            // changes the gate/plan/logic — it only re-points the existing
            // `Screen` enum (the deterministic plan is recomputed per-screen
            // as before).
            if (screen in NAV_DESTINATION_SCREENS) {
                AppNavigationBar(current = screen, onNavigate = { screen = it })
            }
        },
    ) { padding ->
        // ZEN v3 §7 Motion ([DRE-237](/DRE/issues/DRE-237), spec §7): a soft 400ms
        // fade-through between screens so navigation never "snaps" — the calm-motion
        // rule ("ничто не щёлкает"). Uses the system's `Motion` tokens (mediumMs +
        // Emphasized) as the single source of screen-change motion. contentKey = the
        // Screen so a branch is identity-keyed; the `when` body keeps its own state.
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                fadeIn(tween(Motion.mediumMs, easing = Motion.Emphasized)) togetherWith
                    fadeOut(tween(Motion.mediumMs, easing = Motion.Emphasized))
            },
            contentKey = { it },
            label = "screen",
        ) { target ->
        when (target) {
            Screen.Onboarding -> OnboardingScreen(
                modifier = Modifier.padding(padding),
                onPlanReady = { p ->
                    db.saveProfile(p); profile = p; screen = Screen.Today
                },
            )
            // M5-B ([DRE-62](/DRE/issues/DRE-62)): Today is the landing screen —
            // the whole daily loop (today's session + nutrition + adaptation +
            // one-tap logging) on one screen. Plan stays reachable as the
            // full-week view.
            Screen.Today -> TodayScreen(
                modifier = Modifier.padding(padding),
                db = db,
                profile = profile,
                resolver = resolver,
                exerciseLibrary = exerciseLibrary,
                exerciseMedia = exerciseMedia,
                coachCredStore = coachCredStore,
                aiCoachEnabled = aiCoachEnabled,
                // Redesign v2 ([DRE-211](/DRE/issues/DRE-211)): the global Play CTA on
                // Today opens the dedicated Play session scene for today's workout.
                onPlay = { screen = Screen.Play },
                onSymptoms = { screen = Screen.Symptoms },
                onProgress = { screen = Screen.Progress },
            )
            // M5-C (DRE-63): the read-only history/trend screen — shows logged
            // progress + symptoms + the deterministic trend, no interpretation.
            Screen.History -> HistoryScreen(
                modifier = Modifier.padding(padding),
                db = db,
                onBack = { screen = Screen.Today },
            )
            // M6-D (stretch) ([DRE-66](/DRE/issues/DRE-66)): the read-only
            // evidence-sources screen — the full allowlisted catalog, each entry
            // as citation + evidenceLevel + keyFinding, no interpretation.
            Screen.EvidenceSources -> EvidenceSourcesScreen(
                modifier = Modifier.padding(padding),
                resolver = resolver,
                onBack = { screen = Screen.Today },
            )
            Screen.Plan -> PlanScreen(
                modifier = Modifier.padding(padding),
                db = db,
                profile = profile,
                resolver = resolver,
                exerciseLibrary = exerciseLibrary,
                exerciseMedia = exerciseMedia,
                coachCredStore = coachCredStore,
                aiCoachEnabled = aiCoachEnabled,
                onSymptoms = { screen = Screen.Symptoms },
                onProgress = { screen = Screen.Progress },
            )
            Screen.Symptoms -> SymptomsScreen(
                modifier = Modifier.padding(padding),
                db = db,
                onBack = { screen = Screen.Today },
            )
            Screen.Progress -> ProgressScreen(
                modifier = Modifier.padding(padding),
                db = db,
                onBack = { screen = Screen.Today },
            )
            // DRE-175: the user-facing AI-coach credential screen (URL + token +
            // model). Lets the user light up "Спросить у AI" with their own key
            // before the operator server key ([DRE-130](/DRE/issues/DRE-130)) is
            // provisioned. Creds are encrypted at rest (Android Keystore AES-GCM).
            Screen.Settings -> SettingsScreen(
                modifier = Modifier.padding(padding),
                coachCredStore = coachCredStore,
                aiCoachEnabled = aiCoachEnabled,
                onToggleAiCoach = { coachCredStore.setEnabled(it); aiCoachEnabled = it },
            )
            // Redesign v2 ([DRE-211](/DRE/issues/DRE-211), founder p.6): the dedicated
            // Play session scene. Today's session is picked from the SAME gated week
            // Today renders (one source of truth); a rest day / blocked plan lands on
            // the empty state. The session state machine itself is the FE track
            // ([DRE-210](/DRE/issues/DRE-210)); this ships the full scene UI now.
            Screen.Play -> {
                val playProfile = profile
                if (playProfile == null) {
                    Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("Профиль не найден.")
                    }
                } else {
                    val playSymptoms = db.recentSymptoms()
                    val playProgress = db.recentProgress()
                    val playToday = LocalDate.now()
                    val playResult = remember(playProfile, playSymptoms, playProgress) {
                        generateLocalPlan(playProfile, playToday.toString(), playSymptoms, playProgress)
                    }
                    val playSession = (playResult as? PlanResult.Ok)?.let { todaySession(it.week, playToday) }
                    if (playSession != null) {
                        PlayScene(
                            db = db,
                            session = playSession,
                            onBreathing = { screen = Screen.Breathing },
                            onDone = { screen = Screen.Today },
                            onBack = { screen = Screen.Today },
                            modifier = Modifier.padding(padding),
                        )
                    } else {
                        Column(
                            modifier = Modifier.padding(padding).fillMaxSize().padding(Spacing.screen),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(TodayStrings.REST_DAY, style = MaterialTheme.typography.titleMedium)
                            Button(onClick = { screen = Screen.Today }, modifier = Modifier.padding(top = Spacing.md)) {
                                Text("К сегодняшнему дню")
                            }
                        }
                    }
                }
            }
            // Redesign v2 ([DRE-211](/DRE/issues/DRE-211), founder p.7): the breathing
            // scene — a calm box-breathing pacer. Full-screen (no bottom bar).
            Screen.Breathing -> BreathingScene(modifier = Modifier.padding(padding), onBack = { screen = Screen.Today })
        }
        }
    }
}

@Composable
private fun OnboardingScreen(modifier: Modifier, onPlanReady: (Profile) -> Unit) {
    // Seed values from the PoC profile.json — pre-filled, editable.
    var sex by remember { mutableStateOf("male") }
    var age by remember { mutableStateOf("28") }
    var height by remember { mutableStateOf("188") }
    var weight by remember { mutableStateOf("83.2") }
    var bodyFat by remember { mutableStateOf("21.2") }
    var scoliosis by remember { mutableStateOf(true) }
    var redFlag by remember { mutableStateOf(false) }

    LazyColumn(modifier = modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.section)) {
        // Header: screen title + support disclaimer.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(OnboardingStrings.TITLE, style = MaterialTheme.typography.headlineMedium)
                Text(OnboardingStrings.SUBTITLE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Body fields.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(value = sex, onValueChange = { sex = it }, label = { Text("Пол для уравнений (male/female)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = age, onValueChange = { age = it }, label = { Text("Возраст") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = height, onValueChange = { height = it }, label = { Text("Рост, см") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = weight, onValueChange = { weight = it }, label = { Text("Вес, кг") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = bodyFat, onValueChange = { bodyFat = it }, label = { Text("Жир, % (BIA, необязательно)") }, modifier = Modifier.fillMaxWidth())
            }
        }
        // Checkboxes — 48dp touch targets with labeled toggle rows.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).clickable { scoliosis = !scoliosis },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = scoliosis, onCheckedChange = { scoliosis = it })
                    Text("Сколиоз (по самооценке)")
                }
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).clickable { redFlag = !redFlag },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = redFlag, onCheckedChange = { redFlag = it })
                    Text("Есть красный флаг (см. оценку врача)")
                }
            }
        }
        // Submit.
        item {
            Button(
                onClick = {
                    onPlanReady(
                        Profile(
                            sex = sex.trim(),
                            age = age.toIntOrNull() ?: 28,
                            height = height.toDoubleOrNull() ?: 188.0,
                            weight = weight.toDoubleOrNull() ?: 83.2,
                            bodyFat = bodyFat.toDoubleOrNull(),
                            scoliosisReported = scoliosis,
                            redFlags = if (redFlag) listOf("other") else emptyList(),
                            createdOn = LocalDate.now().toString(),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Создать план") }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PlanScreen(modifier: Modifier, db: LocalDatabase, profile: Profile?, resolver: EvidenceResolver, exerciseLibrary: ExerciseLibraryResolver, exerciseMedia: ExerciseMediaResolver, coachCredStore: CoachCredentialStore, aiCoachEnabled: Boolean, onSymptoms: () -> Unit, onProgress: () -> Unit) {
    val p = profile ?: run {
        Column(modifier.fillMaxSize().padding(Spacing.screen)) { Text("Профиль не найден."); Button(onClick = {}) {} }
        return
    }
    // Local, offline-first read; cheap SQLite query, no network. Keying the plan
    // on the symptom + progress snapshots means a newly logged symptom (escalation)
    // or weight point (rapid-loss trend) is reflected the next time this screen
    // composes — same inputs → same plan.
    val symptoms = db.recentSymptoms()
    val progress = db.recentProgress()
    val result = remember(p, symptoms, progress) { generateLocalPlan(p, LocalDate.now().toString(), symptoms, progress) }

    LazyColumn(modifier = modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.section)) {
        when (result) {
            is PlanResult.Blocked -> item { BlockCard(result, resolver) }
            is PlanResult.Ok -> {
                // Week title — the screen's dominant element.
                item {
                    Text("Неделя ${result.week.weekNumber} · ${result.week.phase}", style = MaterialTheme.typography.headlineMedium)
                }
                // Nutrition labeled section (M4-C).
                result.nutritionPlan?.let { plan ->
                    item {
                        val view = remember(plan) { nutritionPlanView(plan, resolver) }
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            Text(PlanStrings.NUTRITION, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Text(view.targetLine, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                view.meals.forEach { m -> Text("${m.label}: ${m.line}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Light) }
                                // M6-B: evidence citations (C6.1 — muted).
                                view.evidenceRows.forEach { c -> Text("• ${c.line}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text(view.disclaimer, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Light, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                // Safety warnings (plain body text).
                if (result.safety.warnings.isNotEmpty()) {
                    item { Text(result.safety.warnings.joinToString(" "), style = MaterialTheme.typography.bodyMedium) }
                }
                // Adaptation labeled section (M3-C).
                adaptationNote(result.signal)?.let { note ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            Text(PlanStrings.ADAPTATION, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Text(note.indicator, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(note.reason, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                // Sessions (Iter-2-clean, lazy because N can be large).
                items(result.week.sessions) { session ->
                    SessionCard(db = db, session = session, resolver = resolver, exerciseLibrary = exerciseLibrary, exerciseMedia = exerciseMedia, coachCredStore = coachCredStore, aiCoachEnabled = aiCoachEnabled, profile = p)
                }
            }
        }
        // M8-D ([DRE-90](/DRE/issues/DRE-90)): compact quick-actions row.
        item {
            QuickLogActions(onProgress = onProgress, onSymptoms = onSymptoms)
        }
    }
}

/**
 * M5-B ([DRE-62](/DRE/issues/DRE-62)): the retention landing screen — the whole
 * daily loop on ONE screen. Composes existing pieces, **no new domain logic, no
 * new persistence**: today's session (picked by day-of-week from the same
 * deterministic week [PlanScreen] renders via [generateLocalPlan]), today's
 * nutrition line ([nutritionPlanView], M4-C), the week's adaptation note
 * ([adaptationNote], M3-C — null on None), and one-tap entry to the progress +
 * symptom loggers. A logged entry is reflected on return (the plan is
 * recomputed from the same symptom/progress snapshots [PlanScreen] uses).
 *
 * Everything here is input/transparent display behind
 * [dreamteam.domain.safety.SafetyGuardedGateway]: the gate is unchanged,
 * composing the view never bypasses it. Framing is support/transparency only —
 * no diagnosis, no "у вас …", no treatment/cure.
 */
@Composable
private fun TodayScreen(
    modifier: Modifier,
    db: LocalDatabase,
    profile: Profile?,
    resolver: EvidenceResolver,
    exerciseLibrary: ExerciseLibraryResolver,
    exerciseMedia: ExerciseMediaResolver,
    coachCredStore: CoachCredentialStore,
    aiCoachEnabled: Boolean,
    // Redesign v2 ([DRE-211](/DRE/issues/DRE-211)): opens the dedicated Play
    // session scene for today's workout (founder p.6: a global Play button).
    onPlay: (dreamteam.domain.training.PlanSession) -> Unit,
    onSymptoms: () -> Unit,
    onProgress: () -> Unit,
) {
    val p = profile ?: run {
        Column(modifier.fillMaxSize().padding(Spacing.screen)) { Text("Профиль не найден."); Button(onClick = {}) {} }
        return
    }
    // M7-B (DRE-73): the export handoff needs an Android Context (file write +
    // FileProvider URI + ACTION_SEND); hoisted out of the item lambda per the
    // established [LocalContext] pattern at the app root.
    val shareContext = LocalContext.current
    // Same offline-first read + recompute keys as PlanScreen: a newly logged
    // symptom (escalation) or weight point (rapid-loss trend) is reflected the
    // next time this screen composes — same inputs → same plan.
    val symptoms = db.recentSymptoms()
    val progress = db.recentProgress()
    val today = LocalDate.now()
    val result = remember(p, symptoms, progress) { generateLocalPlan(p, today.toString(), symptoms, progress) }

    // Iter 1 ([DRE-257](/DRE/issues/DRE-257), DRE-254 Step C): the LazyColumn now
    // breathes at Spacing.section (32dp) between top-level groups — killing the
    // monotonous Spacing.lg (16dp) wall the DESIGN.md flagged. The session block
    // is a compact SessionSummaryCard (no inline SessionCard); nutrition +
    // adaptation are de-carded labeled sections; the quick-log/export tail is
    // bundled into one grouped item so the 32dp break only falls between sections.
    LazyColumn(modifier = modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.section)) {
        when (result) {
            is PlanResult.Blocked -> item { BlockCard(result, resolver) }
            is PlanResult.Ok -> {
                // Today's session is a pure pick from the SAME week PlanScreen
                // renders — no second source of truth.
                val session = todaySession(result.week, today)
                item { SessionSummaryCard(session = session, db = db, onPlay = onPlay) }
                result.nutritionPlan?.let { plan ->
                    // De-carded (C2.2): a labeled section — labelMedium label +
                    // content, NO Card border. Dividers and Spacing do the chunking.
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            Text(TodayStrings.NUTRITION, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val view = remember(plan) { nutritionPlanView(plan, resolver) }
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Text(view.targetLine, fontWeight = FontWeight.SemiBold)
                                view.meals.forEach { m -> Text("${m.label}: ${m.line}", fontWeight = FontWeight.Light) }
                                // M6-B: READABLE citations per ref, not raw ids.
                                view.evidenceRows.forEach { c -> Text("• ${c.line}", fontWeight = FontWeight.Light) }
                                Text(view.disclaimer, fontWeight = FontWeight.Light, fontStyle = FontStyle.Italic)
                            }
                        }
                    }
                }
                // On AdaptationSignal.None → null → nothing (baseline shows as today).
                adaptationNote(result.signal)?.let { note ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            Text(TodayStrings.ADAPTATION, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Text(note.indicator, fontWeight = FontWeight.SemiBold)
                                Text(note.reason, fontWeight = FontWeight.Light)
                            }
                        }
                    }
                }
            }
        }
        // The quick-log + export tail, bundled into one grouped item so the
        // Spacing.section break falls between this block and the sections above,
        // not between each tiny sub-row (the monotonous-spacing anti-pattern).
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(TodayStrings.LOG_HINT, style = MaterialTheme.typography.bodySmall)
                QuickLogActions(onProgress = onProgress, onSymptoms = onSymptoms)
                Text(ExportUiStrings.CAPTION, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                // Redesign v2 ([DRE-211](/DRE/issues/DRE-211), founder p.3): the export +
                // diagnostics handoffs split the row side-by-side (weight 1f each)
                // so two actions never collapse into a column that fills the screen.
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { launchDataExport(shareContext, db) }, modifier = Modifier.weight(1f)) { Text(ExportUiStrings.BUTTON) }
                    TextButton(onClick = { launchDiagnosticsExport(shareContext, db) }, modifier = Modifier.weight(1f)) { Text(DiagnosticsUiStrings.BUTTON) }
                }
                Text(DiagnosticsUiStrings.CAPTION, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            }
        }
    }
}

/**
 * Iter 1 ([DRE-257](/DRE/issues/DRE-257), DRE-254 Step C): the compact session
 * summary that REPLACES the full inline [SessionCard] on Today. Founder complaint:
 * Today stacked a Play button AND the entire dense exercise list (SessionCard) —
 * duplication plus a wall. This summary shows the day/label line, exercise count,
 * progress done/total (reuses [LocalDatabase.completedExercises]), and ONE primary
 * Play CTA. The full per-exercise detail lives in [PlayScene] via [onPlay].
 *
 * A labeled section (no Card border) per C2.2 — dividers and whitespace do the
 * chunking, not a card wall. Rest day (session == null): the [todayDateLine]
 * REST_DAY line, nothing else.
 */
@Composable
private fun SessionSummaryCard(
    session: dreamteam.domain.training.PlanSession?,
    db: LocalDatabase,
    onPlay: (dreamteam.domain.training.PlanSession) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        // The date line is the screen title (titleLarge display) — the largest
        // element on Today per C1.2.
        Text(todayDateLine(session), style = MaterialTheme.typography.titleLarge)
        session?.let { s ->
            Text(TodayStrings.TRAINING, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val completed = remember(s.id) { db.completedExercises(s.id) }
            val total = s.assignments.size
            val done = s.assignments.count { it.exerciseId in completed }
            Text("$total ${TodayStrings.EXERCISE_COUNT}", style = MaterialTheme.typography.titleMedium)
            Text("${TodayStrings.DONE}: $done/$total", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onPlay(s) }, modifier = Modifier.fillMaxWidth()) { Text(TodayStrings.PLAY) }
        }
    }
}

/**
 * M5-C ([DRE-63](/DRE/issues/DRE-63)): the read-only history/trend screen —
 * shows the logged progress points + symptoms + the deterministic trend, with
 * NO interpretation. Reads only ([LocalDatabase.recentProgress] +
 * [LocalDatabase.recentSymptoms]) — the same offline-first reads
 * [PlanScreen]/[TodayScreen] use — and renders via the pure
 * [progressHistoryView] + [symptomHistoryView]. **Never writes, never calls the
 * generator, never bypasses the gate** — it only reflects what is logged,
 * framed as support/transparency, not a diagnosis.
 */
@Composable
private fun HistoryScreen(modifier: Modifier, db: LocalDatabase, onBack: () -> Unit) {
    val progress = db.recentProgress()
    val symptoms = db.recentSymptoms()
    val view = remember(progress) { progressHistoryView(progress) }
    val symptomLines = remember(symptoms) { symptomHistoryView(symptoms) }
    LazyColumn(modifier = modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.section)) {
        // Screen header.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(HistoryStrings.TITLE, style = MaterialTheme.typography.headlineMedium)
                Text(HistoryStrings.SUPPORT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Trend summary.
        item { Text(view.trendLine, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        // Weight history — labeled section.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(HistoryStrings.WEIGHT_SECTION, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    view.points.forEach { p -> Text("• ${p.date}: ${p.weightKg} кг", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        // Symptoms — labeled section.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(HistoryStrings.SYMPTOMS_SECTION, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    symptomLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        // Back.
        item { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(HistoryStrings.BACK) } }
    }
}

/**
 * M9-C / Iter 2 ([DRE-260](/DRE/issues/DRE-260)): the authored strings the exercise
 * card renders — the RIR suffix on the prescription chip + the collapsible-detail
 * toggle affordances. Gathered as one list ([all]) so a JVM test can snapshot them
 * against the banned medical-claim phrase list. Support framing only: no diagnosis,
 * no treatment/cure claim.
 */
internal object DensityChipStrings {
    /** Suffix on the @RIR value in the single prescription chip ("@2 RIR"). */
    const val RIR = "RIR"
    /** Detail-body toggle affordance (real TextButton label). */
    const val DETAILS = "Подробнее"
    const val HIDE = "Скрыть"
    val all: List<String> = listOf(RIR, DETAILS, HIDE)
}

/**
 * Iter 3 (DRE-258, DRE-254 Step E): authored strings for the Onboarding surface.
 * Gathered as [all] so a JVM test snapshots them against the banned medical-claim
 * phrase list (mirrors [SettingsStrings] / [DensityChipStrings]). Support framing
 * only: no diagnosis, no treatment claim.
 */
internal object OnboardingStrings {
    const val TITLE = "Профиль"
    const val SUBTITLE = "Базовый профиль PoC. Приложение поддерживает тренировки и не заменяет врача."
    val all: List<String> = listOf(TITLE, SUBTITLE)
}

/**
 * Iter 3 (DRE-258, DRE-254 Step E): authored strings for the Plan surface's
 * de-carded labeled sections (nutrition / adaptation). Gathered as [all] for the
 * banned-phrase gate (G3).
 */
internal object PlanStrings {
    const val NUTRITION = "Питание"
    const val ADAPTATION = "Адаптация"
    val all: List<String> = listOf(NUTRITION, ADAPTATION)
}

/**
 * Iter 3 (DRE-258, DRE-254 Step E): authored strings for the Symptoms surface.
 * Gathered as [all] for the banned-phrase gate (G3).
 */
internal object SymptomStrings {
    const val TITLE = "Симптомы"
    const val PROMPT = "Как вы себя чувствуете?"
    const val SAVE = "Записать"
    const val RECENT_LABEL = "Недавние записи:"
    const val BACK = "Назад к плану"
    val all: List<String> = listOf(TITLE, PROMPT, SAVE, RECENT_LABEL, BACK)
}

/**
 * Iter 3 (DRE-258, DRE-254 Step E): authored strings for the Progress surface.
 * Gathered as [all] for the banned-phrase gate (G3).
 */
internal object ProgressStrings {
    const val TITLE = "Запись веса"
    const val SUPPORT = "Запишите вес (кг). Тренд — не одна точка — влияет на объём тренировок. Приложение поддерживает, а не заменяет врача."
    const val WEIGHT_LABEL = "Вес, кг"
    const val SAVE = "Записать"
    const val RECENT_LABEL = "Недавние записи:"
    const val BACK = "Назад к плану"
    val all: List<String> = listOf(TITLE, SUPPORT, WEIGHT_LABEL, SAVE, RECENT_LABEL, BACK)
}

/**
 * M9-C: one metadata tag on the denser exercise card. [label] is the whole
 * user-facing string (prefix + value) so the Compose [MetaTag] only does
 * `Text(label)` — no formatting logic in the tree (the [ResolvedCitation].line
 * pattern).
 */
internal data class DensityChip(val label: String)

/**
 * Iter 2 ([DRE-260](/DRE/issues/DRE-260), DRE-254 Step C): the SINGLE prescription
 * chip for one exercise assignment — "{sets}×{repScheme} @{RIR} RIR" (DESIGN.md
 * "Exercise row"). Equipment + evidence-level no longer crowd the row; they live in
 * [exerciseMetaChips] behind the detail toggle. Pure over already-resolved inputs
 * (no Android, no I/O); same inputs → same chip. Rep-values/equipment are the
 * catalog's verbatim vocab, rendered as-is (no app-side map that could drift).
 */
internal fun exerciseDensityChips(
    sets: Int,
    repScheme: String,
    rir: Int?,
): DensityChip {
    val label = buildString {
        append(sets)
        if (repScheme.isNotBlank()) { append('×'); append(repScheme) }
        rir?.let { append(" @"); append(it); append(' '); append(DensityChipStrings.RIR) }
    }
    return DensityChip(label)
}

/**
 * Iter 2 ([DRE-260](/DRE/issues/DRE-260)): the detail-only metadata chips —
 * equipment (when the catalog carries a non-blank / non-"none" value) + one chip
 * per distinct resolved evidence level. These do NOT appear in the collapsed row;
 * they render inside the expanded detail so the prescription row stays a single
 * chip (DESIGN.md "Exercise row"). Pure over already-resolved inputs.
 */
internal fun exerciseMetaChips(
    equipment: String?,
    evidenceLevels: List<String>,
): List<DensityChip> {
    val chips = mutableListOf<DensityChip>()
    equipment
        ?.takeUnless { it.isBlank() || it.trim().equals("none", ignoreCase = true) }
        ?.let { chips += DensityChip(it) }
    evidenceLevels.distinct().forEach { level -> chips += DensityChip(level) }
    return chips
}

/**
 * M9-C: a compact, non-selectable metadata tag (read-only badge). Deterministic
 * presentation — no selected state, no click semantics (unlike [FilterChip],
 * which implies selectability). Plain [Surface] + labelSmall text on
 * surfaceVariant: the densest honest render of an informational tag, no new
 * dependency. Pure render of a [DensityChip] label.
 */
@Composable
private fun MetaTag(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.tightGap),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QuickLogActions(onProgress: () -> Unit, onSymptoms: () -> Unit) {
    // M10 ([DRE-189](/DRE/issues/DRE-189)): the shared, width-adaptive quick-log
    // actions row rendered on both Today and Plan. BoxWithConstraints
    // (foundation-only, no new dep) branches on width: on a compact phone
    // (<600dp) the two buttons take their natural width inside a FlowRow so they
    // sit side-by-side when they fit and wrap to a second line when they do not —
    // never truncating a long RU label (criterion 2). On a wider screen they
    // split the row evenly (Row + weight). Same two writes; pure layout, the
    // gate/plan/logic is unchanged.
    BoxWithConstraints {
        if (maxWidth < 600.dp) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap),
                verticalArrangement = Arrangement.spacedBy(Spacing.itemGap),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilledTonalButton(onClick = onProgress) { Text(TodayStrings.LOG_PROGRESS) }
                FilledTonalButton(onClick = onSymptoms) { Text(TodayStrings.LOG_SYMPTOM) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = onProgress, modifier = Modifier.weight(1f)) { Text(TodayStrings.LOG_PROGRESS) }
                FilledTonalButton(onClick = onSymptoms, modifier = Modifier.weight(1f)) { Text(TodayStrings.LOG_SYMPTOM) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(
    db: LocalDatabase,
    session: dreamteam.domain.training.PlanSession,
    resolver: EvidenceResolver,
    exerciseLibrary: ExerciseLibraryResolver,
    exerciseMedia: ExerciseMediaResolver,
    // DRE-175: the coach call sites read the user's own encrypted creds at click
    // time so a Settings save is reflected immediately (no stale cached coach).
    coachCredStore: CoachCredentialStore,
    // M8-C ([DRE-89](/DRE/issues/DRE-89)): the coach needs the profile's medical
    // subset to run the pre-LLM red-flag gate + side-specific lock (#1).
    profile: Profile,
    aiCoachEnabled: Boolean,
) {
    var completed by remember(session.id) { mutableStateOf(db.completedExercises(session.id)) }
    val today = LocalDate.now().toString()
    // M8-C: coach dialog state. explainFor = exercise id to cue; report = the
    // gate-produced coach result of "Сообщить коучу". Null ⇒ no dialog.
    var explainFor by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<dreamteam.domain.coach.CoachReport?>(null) }
    // DRE-175: the LLM call is async (Android blocks network on the main thread).
    // reportLoading ⇒ the button shows a working hint while the coroutine runs.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var reportLoading by remember { mutableStateOf(false) }
    // The user's last choice in the original-vs-adaptation popup, shown inline as
    // visible feedback (reviewer p.3.4: adaptation default-selected; original preserved).
    var adaptationChoice by remember { mutableStateOf<Boolean?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        // M10 ([DRE-189](/DRE/issues/DRE-189)): adapt the session card to width.
        // BoxWithConstraints (foundation-only, no new dep) branches spacing on a
        // compact (<600dp) vs normal phone so a workout list breathes on a Pixel
        // and never cramps on a 360dp device — reversing the M9 over-densification
        // that read as «приложение для роботов». The gate/plan/data are untouched.
        BoxWithConstraints {
            val compact = maxWidth < 600.dp
            val exGap = if (compact) Spacing.tightGap else Spacing.itemGap
            Column(Modifier.padding(Spacing.card), verticalArrangement = Arrangement.spacedBy(Spacing.itemGap)) {
                Text("${session.day} · ${session.label}", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                // M10 (criterion 5): exercises are visually distinct + scannable,
                // not a solid wall — a divider + consistent gap separates each
                // assignment so the eye can pick one exercise out of the list.
                Column(verticalArrangement = Arrangement.spacedBy(exGap)) {
                    session.assignments.forEachIndexed { index, a ->
                        val name = BaselineProgram.exercises[a.exerciseId]?.name ?: a.exerciseId
                // M8-A ([DRE-80](/DRE/issues/DRE-80)): consolidate this exercise's
                // video / how-to / images / evidence into ONE tappable references
                // card (0 naked links). Resolved up here (not inline) so the M9-C
                // metadata tags below can read its equipment / evidenceLevel too.
                val refs = remember(a.exerciseId) {
                    resolveExerciseReferences(a.exerciseId, a.evidenceRefs, exerciseLibrary, resolver)
                }
                // M9-C ([DRE-120](/DRE/issues/DRE-120)): the exercise block is now a
                // denser, collapsible card. Always-visible header = checkbox + name +
                // a scannable chip row (sets / reps / RIR / equipment / evidence
                // level). The detail body (coach cue, references card, note field)
                // sits behind a "Подробнее / Скрыть" toggle — collapsed by default so
                // a workout list reads dense instead of like an expanded MD viewer
                // (founder review #4). Same data, same safety surface, no new claim.
                var detailOpen by remember(session.id, a.exerciseId) { mutableStateOf(false) }
                // DRE-123: hoist the note draft state ABOVE the `if (detailOpen)`
                // conditional so its lifetime is the per-assignment scope, not the
                // conditional. Before this the field lived inside the collapsible
                // detail: collapsing disposed the `remember`-ed draft, so an unsaved
                // note/outcome was lost mid-session on re-expand (regression from
                // M8-B once the field moved under the M9-C detail toggle). The caller
                // now owns the state; [ExerciseNoteField] is a stateless render of it.
                val noteSaved = remember(session.id, a.exerciseId) { db.exerciseNote(session.id, a.exerciseId) }
                var noteDraft by remember(session.id, a.exerciseId) { mutableStateOf(noteSaved?.note ?: "") }
                var outcomeDraft by remember(session.id, a.exerciseId) { mutableStateOf(noteSaved?.outcome) }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    // M10 (criterion 5): a hairline divider between exercises so a
                    // multi-exercise session reads as a list, not a wall. Skipped
                    // before the first so the title-to-first gap stays clean.
                    if (index > 0) {
                        HorizontalDivider(
                            color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(vertical = exGap),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = a.exerciseId in completed,
                            onCheckedChange = { checked ->
                                if (checked) { db.logWorkout(session.id, a.exerciseId, today); completed = completed + a.exerciseId }
                            },
                        )
                        Column(Modifier.weight(1f).padding(start = Spacing.xs)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            MetaTag(exerciseDensityChips(a.sets, a.repScheme, a.rir).label)
                        }
                        TextButton(onClick = { detailOpen = !detailOpen }) {
                            Text(if (detailOpen) DensityChipStrings.HIDE else DensityChipStrings.DETAILS)
                        }
                    }
                    AnimatedVisibility(
                        visible = detailOpen,
                        enter = fadeIn(tween(Motion.largeMs, easing = Motion.Emphasized)) +
                            expandVertically(tween(Motion.largeMs, easing = Motion.Emphasized)),
                        exit = fadeOut(tween(Motion.largeMs, easing = Motion.Emphasized)) +
                            shrinkVertically(tween(Motion.largeMs, easing = Motion.Emphasized)),
                    ) {
                        // Redesign v3: flattened references — card image, AI summary,
                        // how-to steps, and video/media buttons render directly.
                        // Citations sit behind a secondary "Источники (N)" toggle.
                        // ASK AI joins the unified action row (no standalone button).
                        val detailMedia = remember(a.exerciseId) {
                            resolveExerciseMedia(a.exerciseId, exerciseMedia)
                        }
                        ExerciseMediaSlot(name = name, media = detailMedia)
                        detailMedia.cardImage?.let { img ->
                            val attribution = listOfNotNull(img.credit, img.license)
                                .joinToString(" · ")
                                .takeUnless { it.isBlank() }
                            if (attribution != null) {
                                Text(
                                    "${ReferencesCardStrings.IMAGE_CREDIT}: $attribution",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(
                                ReferencesCardStrings.WHY,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            detailMedia.summary?.let { summary ->
                                Text(summary, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (refs.howToStepsRu.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Text(
                                    ReferencesCardStrings.HOW_TO,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                    refs.howToStepsRu.forEachIndexed { i, step ->
                                        Row(verticalAlignment = Alignment.Top) {
                                            Text(
                                                "${i + 1}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                step,
                                                style = MaterialTheme.typography.bodyLarge,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(start = Spacing.sm),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // Unified action row: ASK AI + video/image buttons.
                        // Width-constrained so buttons sit side-by-side, not
                        // full-width columns on expand.
                        val videoUrl = detailMedia.videoUrl ?: refs.videoUrl
                        val mediaButtons = buildList {
                            videoUrl?.takeUnless { it.isBlank() }?.let { url ->
                                add(url to ReferencesCardStrings.VIDEO)
                            }
                            refs.imageRefs.filter { it.isNotBlank() }.forEach { ref ->
                                add(ref to ReferencesCardStrings.IMAGE)
                            }
                        }
                        if (mediaButtons.isNotEmpty() || aiCoachEnabled) {
                            val ctx = LocalContext.current
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (aiCoachEnabled) {
                                    OutlinedButton(
                                        onClick = { explainFor = a.exerciseId },
                                        modifier = Modifier.weight(1f).heightIn(min = Spacing.touchTarget),
                                    ) {
                                        Icon(painterResource(R.drawable.zen_ic_ai), contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(Spacing.xs))
                                        Text(
                                            CoachStrings.ASK_AI,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                mediaButtons.take(2).forEach { (url, label) ->
                                    val mediaIcon = if (label == ReferencesCardStrings.VIDEO) R.drawable.zen_ic_video else R.drawable.zen_ic_camera
                                    OutlinedButton(
                                        onClick = { openUrl(ctx, url) },
                                        modifier = Modifier.weight(1f).heightIn(min = Spacing.touchTarget),
                                    ) {
                                        Icon(painterResource(mediaIcon), contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(Spacing.xs))
                                        Text(
                                            label,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        // Iter 2: detail-only metadata chips (equipment + evidence levels).
                        val metaChips = exerciseMetaChips(refs.equipment, refs.evidenceLevels)
                        if (metaChips.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                metaChips.forEach { chip -> MetaTag(chip.label) }
                            }
                        }
                        // Citations: secondary toggle to keep the detail compact.
                        if (refs.citations.isNotEmpty()) {
                            var citationsOpen by remember(a.exerciseId) {
                                mutableStateOf(false)
                            }
                            TextButton(onClick = { citationsOpen = !citationsOpen }) {
                                Text(
                                    "${ReferencesCardStrings.EVIDENCE} (${refs.citations.size})",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            AnimatedVisibility(
                                visible = citationsOpen,
                                enter = fadeIn(tween(Motion.largeMs, easing = Motion.Emphasized)) +
                                    expandVertically(tween(Motion.largeMs, easing = Motion.Emphasized)),
                                exit = fadeOut(tween(Motion.largeMs, easing = Motion.Emphasized)) +
                                    shrinkVertically(tween(Motion.largeMs, easing = Motion.Emphasized)),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                    refs.citations.forEach { c -> EvidenceCitationCard(c) }
                                }
                            }
                        }
                        if (!refs.hasMedia) {
                            Text(
                                ReferencesCardStrings.MEDIA_PENDING,
                                fontWeight = FontWeight.Light,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // M8-B ([DRE-78](/DRE/issues/DRE-78)): free-text note per exercise
                        // in the execution log ("что вышло / что нет / боль"), persisted
                        // like the symptom/progress logs and read back by the coach
                        // (input for M8-C). Verbatim self-report; no interpretation.
                        ExerciseNoteField(
                            note = noteDraft,
                            outcome = outcomeDraft,
                            onNoteChange = { noteDraft = it },
                            onOutcomeChange = { outcomeDraft = it },
                            onSave = {
                                // A flag alone (no text) is a valid self-report, so save on either.
                                if (noteDraft.isNotBlank() || outcomeDraft != null) {
                                    db.appendExerciseNote(session.id, a.exerciseId, noteDraft.trim(), outcomeDraft, today)
                                }
                            },
                        )
                    }
                }
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Text("Сделано: ${completed.size}/${session.assignments.size}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            // M8-C: inline feedback for the last original-vs-adaptation choice.
            adaptationChoice?.let { applied ->
                Text(
                    if (applied) CoachStrings.APPLIED_ADAPTATION else CoachStrings.KEPT_ORIGINAL,
                    fontWeight = FontWeight.Light,
                )
            }
            // M8-C ([DRE-89](/DRE/issues/DRE-89)): "Сообщить коучу" CTA — the
            // end-of-workout report. Reads the session's notes + the user's
            // symptoms/progress, runs the gated coach, and opens the
            // "оригинал vs адаптация" popup (adaptation = default, original
            // preserved). All safety-gated: a red flag surfaces as a block, not a plan.
            // DRE-175: dispatched off the main thread — the user's LLM call is a
            // network op Android forbids on main; reportLoading gates re-entry.
            if (aiCoachEnabled) {
                OutlinedButton(
                    enabled = !reportLoading,
                    onClick = {
                        reportLoading = true
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val r = coachReportForSession(
                                profile = profile,
                                notes = coachNotesFromRows(db.sessionExerciseNotes(session.id)),
                                symptoms = db.recentSymptoms(),
                                progress = db.recentProgress(),
                                today = today,
                                userCoach = coachForUserCreds(coachCredStore.load()),
                            )
                            report = r
                            reportLoading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (reportLoading) CoachStrings.REPORT_WORKING else CoachStrings.REPORT_CTA) }
            }
        }
        }
    }
    // M8-C: the explain cue popup (one exercise).
    explainFor?.let { exId ->
        ExerciseCoachDialog(
            exerciseId = exId,
            profile = profile,
            coachCredStore = coachCredStore,
            onDismiss = { explainFor = null },
        )
    }
    // M8-C: the report / original-vs-adaptation popup.
    report?.let { r ->
        CoachReportDialog(
            report = r,
            original = session,
            onChoose = { applied -> adaptationChoice = applied; report = null },
        )
    }
}

/**
 * M8-C: the "Спросить у AI" cue popup. Computes the coach cue and shows it
 * phone-readably. A red-flag profile shows the block line instead.
 *
 * DRE-175: the cue is computed off the main thread ([Dispatchers.IO]) because the
 * user's LLM endpoint is a network call Android forbids on main; while the
 * coroutine runs the dialog shows a working hint, and once it lands the result
 * is the same [CoachExplain] (fallback-or-LLM) the phone-readable render expects.
 */
@Composable
private fun ExerciseCoachDialog(
    exerciseId: String,
    profile: Profile,
    coachCredStore: CoachCredentialStore,
    onDismiss: () -> Unit,
) {
    var result by remember(exerciseId) { mutableStateOf<dreamteam.domain.coach.CoachExplain?>(null) }
    androidx.compose.runtime.LaunchedEffect(exerciseId) {
        result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            coachExplainForExercise(
                exerciseId = exerciseId,
                profile = profile,
                userCoach = coachForUserCreds(coachCredStore.load()),
            )
        }
    }
    val resolved = result
    val body = when (resolved) {
        null -> CoachStrings.EXPLAIN_WORKING
        is dreamteam.domain.coach.CoachExplain.Ok -> {
            val v = coachExplainView(resolved)
            "${v.summaryRu}\n(${v.sourceLabel})"
        }
        is dreamteam.domain.coach.CoachExplain.Blocked -> CoachStrings.REDFLAG_BLOCK
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text(CoachStrings.EXPLAIN_TITLE) },
        text = { Text(body) },
    )
}

/**
 * M8-C ([DRE-89](/DRE/issues/DRE-89)): the "оригинал vs адаптация" popup. Shows
 * the coach's summary + per-exercise cues and a side-by-side of the original
 * session vs the gate-produced adapted session, with **adaptation pre-selected**
 * (reviewer p.3.4: APPLY_ADAPTATION is the primary Button, KEEP_ORIGINAL the
 * secondary). The original is preserved by construction (the adapted plan gets a
 * new id; the baseline is untouched). A red-flag report shows the block line.
 */
@Composable
private fun CoachReportDialog(
    report: dreamteam.domain.coach.CoachReport,
    original: dreamteam.domain.training.PlanSession,
    onChoose: (appliedAdaptation: Boolean) -> Unit,
) {
    when (report) {
        is dreamteam.domain.coach.CoachReport.Blocked -> AlertDialog(
            onDismissRequest = { onChoose(false) },
            confirmButton = { TextButton(onClick = { onChoose(false) }) { Text("Закрыть") } },
            title = { Text(CoachStrings.REPORT_TITLE) },
            text = { Text(CoachStrings.REDFLAG_BLOCK) },
        )
        is dreamteam.domain.coach.CoachReport.Unavailable -> AlertDialog(
            // DRE-99 graceful degrade: gateway blocked the baseline plan (not a
            // red flag). The original plan is kept; only the close action runs.
            onDismissRequest = { onChoose(false) },
            confirmButton = { TextButton(onClick = { onChoose(false) }) { Text("Закрыть") } },
            title = { Text(CoachStrings.REPORT_TITLE) },
            text = { Text(CoachStrings.PLAN_UNAVAILABLE) },
        )
        is dreamteam.domain.coach.CoachReport.Ok -> {
            val view = remember(report) { coachReportView(report) }
            val adaptedSession = remember(report, original) { adaptedSessionOf(report, original) }
            AlertDialog(
                onDismissRequest = { onChoose(true) }, // back-tap ⇒ keep the default (adaptation)
                // Adaptation is the default-selected action (primary button).
                confirmButton = { TextButton(onClick = { onChoose(true) }) { Text(CoachStrings.APPLY_ADAPTATION) } },
                dismissButton = { TextButton(onClick = { onChoose(false) }) { Text(CoachStrings.KEEP_ORIGINAL) } },
                title = { Text(CoachStrings.REPORT_TITLE) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(view.summaryRu)
                        view.corrections.forEach { c -> Text("• ${c.exerciseName}: ${c.noteRu}", fontWeight = FontWeight.Light) }
                        Text(CoachStrings.ADAPTATION_DEFAULT_HINT, fontWeight = FontWeight.Medium)
                        if (view.isDeLoad && adaptedSession != null) {
                            Text(CoachStrings.ADAPTATION_LABEL, fontWeight = FontWeight.SemiBold)
                            adaptedSession.assignments.take(6).forEach { a ->
                                val n = BaselineProgram.exercises[a.exerciseId]?.name ?: a.exerciseId
                                Text("  $n — ${a.sets}×${a.repScheme}", fontWeight = FontWeight.Light)
                            }
                            Text(CoachStrings.ORIGINAL_LABEL, fontWeight = FontWeight.SemiBold)
                            original.assignments.take(6).forEach { a ->
                                val n = BaselineProgram.exercises[a.exerciseId]?.name ?: a.exerciseId
                                Text("  $n — ${a.sets}×${a.repScheme}", fontWeight = FontWeight.Light)
                            }
                        }
                        Text("(${view.sourceLabel})", fontWeight = FontWeight.Light)
                    }
                },
            )
        }
    }
}

/**
 * M8-B ([DRE-78](/DRE/issues/DRE-78)): the per-exercise note field shown under an
 * assignment in [SessionCard]. Loads the saved note (latest wins) so the user sees
 * what they wrote, and upserts on save via [LocalDatabase.appendExerciseNote].
 * Support-framed labels only ([ExerciseNoteStrings]); the note text itself is the
 * user's own words, never scanned for medical claims (cf. symptom free-text).
 *
 * M9-A ([DRE-112](/DRE/issues/DRE-112)): a row of outcome chips (`ok / hard /
 * painful / skipped`) lets the user attach a light structured self-report. The
 * flag is recorded/exported only — it never drives a plan change here (the gated
 * M9-D owns adaptation). Tapping a selected chip clears it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ExerciseNoteField(
    note: String,
    outcome: ExerciseNoteOutcome?,
    onNoteChange: (String) -> Unit,
    onOutcomeChange: (ExerciseNoteOutcome?) -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = Spacing.xxl, top = Spacing.tightGap), verticalArrangement = Arrangement.spacedBy(Spacing.tightGap)) {
        Text(ExerciseNoteStrings.OUTCOME_LABEL, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
            ExerciseNoteOutcome.entries.forEach { o ->
                FilterChip(
                    selected = outcome == o,
                    onClick = { onOutcomeChange(if (outcome == o) null else o) },
                    label = { Text(o.labelRu) },
                )
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            label = { Text(ExerciseNoteStrings.LABEL) },
            placeholder = { Text(ExerciseNoteStrings.HINT) },
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onSave) { Text(ExerciseNoteStrings.SAVE) }
    }
}

@Composable
private fun SymptomsScreen(modifier: Modifier, db: LocalDatabase, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var symptoms by remember { mutableStateOf(db.recentSymptoms()) }
    LazyColumn(modifier = modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.section)) {
        // Screen header.
        item { Text(SymptomStrings.TITLE, style = MaterialTheme.typography.headlineMedium) }
        // Input.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(SymptomStrings.PROMPT) }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    if (text.isNotBlank()) { db.appendSymptom(text.trim(), LocalDate.now().toString()); text = ""; symptoms = db.recentSymptoms() }
                }, modifier = Modifier.fillMaxWidth()) { Text(SymptomStrings.SAVE) }
            }
        }
        // Recent entries — labeled section.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(SymptomStrings.RECENT_LABEL, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    symptoms.forEach { s: SymptomEntry -> Text("• ${s.recordedOn}: ${s.text}", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        // Back.
        item { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(SymptomStrings.BACK) } }
    }
}

/**
 * M5-A ([DRE-61](/DRE/issues/DRE-61)): offline-first body-weight logger, mirroring
 * [SymptomsScreen] + [LocalDatabase.appendProgress]/[LocalDatabase.recentProgress].
 * MVP field set: **body weight (kg)** — the one input the RapidWeightLoss
 * adaptation trigger consumes. Framed as support data ("запишите вес"), never a
 * diagnosis or claim. Like symptom logging, the plan is recomputed on return to
 * [PlanScreen] so the trend feeds the same loop the symptoms already feed.
 */
@Composable
private fun ProgressScreen(modifier: Modifier, db: LocalDatabase, onBack: () -> Unit) {
    var weight by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(db.recentProgress()) }
    LazyColumn(modifier = modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.section)) {
        // Screen header + support framing.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(ProgressStrings.TITLE, style = MaterialTheme.typography.headlineMedium)
                Text(ProgressStrings.SUPPORT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Input.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text(ProgressStrings.WEIGHT_LABEL) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    val kg = weight.trim().replace(',', '.').toDoubleOrNull()
                    if (kg != null && kg > 0.0) {
                        db.appendProgress(kg, LocalDate.now().toString())
                        weight = ""
                        rows = db.recentProgress()
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(ProgressStrings.SAVE) }
            }
        }
        // Recent entries — labeled section.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(ProgressStrings.RECENT_LABEL, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    rows.forEach { r: ProgressRow -> Text("• ${r.recordedOn}: ${r.weightKg} кг", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        // Back.
        item { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(ProgressStrings.BACK) } }
    }
}

/**
 * DRE-175/DRE-238 — the user-facing Settings screen: ALL user options in ONE
 * screen, grouped into labeled sections (DRE-254 C2.2). Two sections:
 *  - **AI-коуч**: the enable/disable toggle (default OFF, [DRE-209]) + the
 *    encrypted credential fields (URL/token/model) shown only when enabled.
 *  - **Дыхание**: the breathing sound-cue toggle (DRE-235).
 *
 * Sections are separated by `Spacing.section` (32dp) — the calm rhythm. With creds saved
 * and the coach on, "Спросить у AI" calls the user's LLM directly; with it off,
 * the app runs the deterministic plan alone — it never crashes. Support framing
 * only: the screen configures tools, it makes no medical claim.
 */
@Composable
private fun SettingsScreen(modifier: Modifier, coachCredStore: CoachCredentialStore, aiCoachEnabled: Boolean, onToggleAiCoach: (Boolean) -> Unit) {
    val context = LocalContext.current
    val saved = remember { coachCredStore.load() }
    var baseUrl by remember { mutableStateOf(saved?.baseUrl ?: SettingsStrings.DEFAULT_URL) }
    var token by remember { mutableStateOf(saved?.token ?: "") }
    var model by remember { mutableStateOf(saved?.model ?: SettingsStrings.DEFAULT_MODEL) }
    var message by remember { mutableStateOf<String?>(null) }
    val breathingSettings = remember { BreathingSettings(context) }
    var soundEnabled by remember { mutableStateOf(breathingSettings.isSoundEnabled()) }
    LazyColumn(modifier = modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.section)) {
        // Screen header.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(SettingsStrings.TITLE, style = MaterialTheme.typography.headlineMedium)
                Text(SettingsStrings.HINT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // AI-coach section — labeled section (C2.2).
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(SettingsStrings.SECTION_COACH, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            SettingsStrings.COACH_TOGGLE_HINT,
                            modifier = Modifier.weight(1f).padding(end = Spacing.md),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(checked = aiCoachEnabled, onCheckedChange = onToggleAiCoach)
                    }
                    if (aiCoachEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text(SettingsStrings.URL_LABEL) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text(SettingsStrings.TOKEN_LABEL) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = { Text(SettingsStrings.MODEL_LABEL) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    coachCredStore.save(CoachCredentials(baseUrl.trim(), token.trim(), model.trim()))
                                    message = if (token.isBlank()) SettingsStrings.CLEARED else SettingsStrings.SAVED
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(SettingsStrings.SAVE) }
                            OutlinedButton(
                                onClick = {
                                    coachCredStore.clear()
                                    token = ""; baseUrl = SettingsStrings.DEFAULT_URL; model = SettingsStrings.DEFAULT_MODEL
                                    message = SettingsStrings.CLEARED
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(SettingsStrings.CLEAR) }
                        }
                        message?.let { m -> Text(m, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        // Breathing section — labeled section (C2.2).
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(SettingsStrings.SECTION_BREATHING, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = Spacing.md)) {
                            Text(SettingsStrings.SOUND_TITLE, style = MaterialTheme.typography.bodyLarge)
                            Text(SettingsStrings.SOUND_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = soundEnabled,
                            onCheckedChange = { soundEnabled = it; breathingSettings.setSoundEnabled(it) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * DRE-175/DRE-238 — the authored strings the Settings screen renders. Gathered as
 * [all] so a JVM test snapshots them against the banned medical-claim phrase
 * list (mirrors [UiStrings] / [CoachStrings]). Support framing only: the screen
 * configures tools — no diagnosis, no treatment claim.
 *
 * DRE-238: the screen title is now the general "Настройки" (all user options in
 * ONE screen), not the AI-coach-only title; settings are grouped into ZEN Cards
 * (section titles [SECTION_COACH] / [SECTION_BREATHING]).
 */
internal object SettingsStrings {
    const val TITLE = "Настройки"
    const val HINT = "Все параметры приложения в одном месте. Приложение поддерживает, не заменяет врача."
    const val SECTION_COACH = "AI-коуч"
    const val SECTION_BREATHING = "Дыхание"
    const val COACH_TOGGLE_HINT = "Выключен по умолчанию. Включите и укажите данные API, чтобы «Спросить у AI» и «Отправить коучу» вызывали вашу модель. Без данных приложение строит план офлайн. Приложение поддерживает, не заменяет врача."
    const val URL_LABEL = "URL API (ссылка)"
    const val TOKEN_LABEL = "Токен"
    const val MODEL_LABEL = "Модель"
    const val SAVE = "Сохранить"
    const val CLEAR = "Сбросить"
    const val SAVED = "Сохранено. Ключ хранится зашифрованным на устройстве."
    const val CLEARED = "Ключ сброшен. AI использует офлайн-план."
    const val SOUND_TITLE = "Звук дыхания"
    const val SOUND_HINT = "Мягкие звуковые сигналы при смене фазы дыхания."
    // Default base URL = Z.AI's OpenAI-compatible endpoint (server-confirmed); the
    // user can change it for any compatible provider. Default model = glm-4.6
    // (the server's working thinking flagship; "glm-5.2" / Max think is the spec
    // target — editable here when Z.AI ships that id).
    const val DEFAULT_URL = "https://api.z.ai/api/paas/v4"
    const val DEFAULT_MODEL = "glm-4.6"

    val all: List<String> = listOf(
        TITLE, HINT, SECTION_COACH, SECTION_BREATHING, COACH_TOGGLE_HINT,
        URL_LABEL, TOKEN_LABEL, MODEL_LABEL, SAVE, CLEAR, SAVED, CLEARED,
        SOUND_TITLE, SOUND_HINT, DEFAULT_URL, DEFAULT_MODEL,
    )
}
