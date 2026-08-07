package dreamteam.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
        when (screen) {
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

    LazyColumn(modifier = modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item {
            Text(
                // DRE-193: aligned to the app-wide scan-clean support framing
                // ("поддерживает, а не заменяет врача"); the prior copy used the
                // banned morphemes "диагности"/"лечит" even in negation — every
                // other surface avoids them (cf. HistoryStrings.SUPPORT).
                "Профиль (базовый PoC). Это приложение поддерживает тренировки и не заменяет врача.",
                fontWeight = FontWeight.Medium,
            )
        }
        item { OutlinedTextField(value = sex, onValueChange = { sex = it }, label = { Text("Пол для уравнений (male/female)") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = age, onValueChange = { age = it }, label = { Text("Возраст") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = height, onValueChange = { height = it }, label = { Text("Рост, см") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = weight, onValueChange = { weight = it }, label = { Text("Вес, кг") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = bodyFat, onValueChange = { bodyFat = it }, label = { Text("Жир, % (BIA, необязательно)") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = scoliosis, onCheckedChange = { scoliosis = it })
                Text("Сколиоз (по самооценке)")
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = redFlag, onCheckedChange = { redFlag = it })
                Text("Есть красный флаг (см. оценку врача)")
            }
        }
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

    LazyColumn(modifier = modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        when (result) {
            is PlanResult.Blocked -> item { BlockCard(result, resolver) }
            is PlanResult.Ok -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.card)) {
                            // M9-C density pass ([DRE-181](/DRE/issues/DRE-181)): the
                            // week title carries week # + phase in ONE scannable line.
                            // The chip row DRE-120 added here duplicated those exact
                            // [PlanWeek] fields verbatim — pure redundancy, removed.
                            // (Exercise cards KEEP their chips: sets/reps/RIR/equipment/
                            // evidence are distinct facts that read well as tags; the week
                            // header had only these two, already in the title.) Same data,
                            // same gate; no new claim, no new state.
                            Text("Неделя ${result.week.weekNumber} · ${result.week.phase}", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                            // M4-C ([DRE-57](/DRE/issues/DRE-57)): render the full surfaced
                            // NutritionPlan via the pure [nutritionPlanView] (extracted so its
                            // strings are banned-phrase-tested, [NutritionPlanViewTest]): target +
                            // deterministic meal structure + the cataloged evidence ids + an
                            // explicit support-not-treatment disclaimer. No diagnosis / prescription /
                            // claim. null (gate-blocked) renders nothing for nutrition. Offline-first:
                            // produced locally from the profile, no network to view it.
                            result.nutritionPlan?.let { plan ->
                                val view = remember(plan) { nutritionPlanView(plan, resolver) }
                                Text(view.targetLine, fontWeight = FontWeight.SemiBold)
                                view.meals.forEach { m -> Text("${m.label}: ${m.line}", fontWeight = FontWeight.Light) }
                                // M6-B: render READABLE citations (author/year + keyFinding +
                                // evidenceLevel) per ref, not raw ids; a ghost id renders the
                                // blocked-until-sourced placeholder.
                                view.evidenceRows.forEach { c -> Text("• ${c.line}", fontWeight = FontWeight.Light) }
                                Text(view.disclaimer, fontWeight = FontWeight.Light, fontStyle = FontStyle.Italic)
                            }
                            if (result.safety.warnings.isNotEmpty()) Text(result.safety.warnings.joinToString(" "))
                            // M3-C: surface a de-load as a plain "объём снижен" indicator + the
                            // support-framed reason (authored in M3-A). No diagnosis, no "у вас …",
                            // no medical framing — only that the week's volume was reduced and why.
                            // On AdaptationSignal.None nothing extra is rendered (baseline as today).
                            // M3-C: surface a de-load via the pure adaptationNote(signal)
                            // (extracted so its strings are banned-phrase-tested, DRE-53).
                            // Support-framed only: "объём снижен" + the domain reason; no
                            // diagnosis/claim. On AdaptationSignal.None → null → nothing.
                            adaptationNote(result.signal)?.let { note ->
                                Spacer(Modifier.height(Spacing.xs))
                                Text(note.indicator, fontWeight = FontWeight.SemiBold)
                                Text(note.reason, fontWeight = FontWeight.Light)
                            }
                        }
                    }
                }
                items(result.week.sessions) { session ->
                    SessionCard(db = db, session = session, resolver = resolver, exerciseLibrary = exerciseLibrary, exerciseMedia = exerciseMedia, coachCredStore = coachCredStore, aiCoachEnabled = aiCoachEnabled, profile = p)
                }
            }
        }
        // M8-D ([DRE-90](/DRE/issues/DRE-90)): compact quick-actions row
        // (matching Today) instead of a stacked link pair — same writes, less
        // MD-viewer feel. The bottom nav carries the read destinations.
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

    LazyColumn(modifier = modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        when (result) {
            is PlanResult.Blocked -> item { BlockCard(result, resolver) }
            is PlanResult.Ok -> {
                // Today's session is a pure pick from the SAME week PlanScreen
                // renders — no second source of truth.
                val session = todaySession(result.week, today)
                // M9-C density pass ([DRE-181](/DRE/issues/DRE-181)): demote the date
                // line headlineMedium→titleLarge (headline was tall sprawl at the top),
                // and fold each section label + its content into ONE item so the label
                // sits 2dp above its card — a grouped scannable block instead of a
                // loose label floating 8dp above its card (the MD-outline feel). Same
                // strings ([TodayStrings.all] unchanged), same data, same writes; pure
                // layout tightening, no new state, no new screen.
                item { Text(todayDateLine(session), style = androidx.compose.material3.MaterialTheme.typography.titleLarge) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.tightGap)) {
                        Text(TodayStrings.TRAINING, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                        session?.let { s ->
                            // Redesign v2 ([DRE-211](/DRE/issues/DRE-211), founder p.6):
                            // the global Play CTA — a prominent full-width primary button
                            // that opens today's workout in the dedicated Play scene.
                            Button(onClick = { onPlay(s) }, modifier = Modifier.fillMaxWidth()) { Text(TodayStrings.PLAY) }
                            SessionCard(db = db, session = s, resolver = resolver, exerciseLibrary = exerciseLibrary, exerciseMedia = exerciseMedia, coachCredStore = coachCredStore, aiCoachEnabled = aiCoachEnabled, profile = p)
                        }
                    }
                }
                result.nutritionPlan?.let { plan ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.tightGap)) {
                            Text(TodayStrings.NUTRITION, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                            val view = remember(plan) { nutritionPlanView(plan, resolver) }
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(Spacing.card)) {
                                    Text(view.targetLine, fontWeight = FontWeight.SemiBold)
                                    view.meals.forEach { m -> Text("${m.label}: ${m.line}", fontWeight = FontWeight.Light) }
                                    // M6-B: READABLE citations per ref, not raw ids.
                                    view.evidenceRows.forEach { c -> Text("• ${c.line}", fontWeight = FontWeight.Light) }
                                    Text(view.disclaimer, fontWeight = FontWeight.Light, fontStyle = FontStyle.Italic)
                                }
                            }
                        }
                    }
                }
                // On AdaptationSignal.None → null → nothing (baseline shows as today).
                adaptationNote(result.signal)?.let { note ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.tightGap)) {
                            Text(TodayStrings.ADAPTATION, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(Spacing.card)) {
                                    Text(note.indicator, fontWeight = FontWeight.SemiBold)
                                    Text(note.reason, fontWeight = FontWeight.Light)
                                }
                            }
                        }
                    }
                }
            }
        }
        // M8-D ([DRE-90](/DRE/issues/DRE-90)): the read destinations (Plan /
        // Journal / Sources) moved to the bottom [AppNavigationBar], so this tail
        // now holds only the *writes* (progress / symptom) as a compact
        // quick-actions row, plus the data-export handoff as a quieter text
        // action. Fewer stacked link-buttons → less of the MD-viewer feel. The
        // plan/logic is unchanged: a logged write still recomputes the
        // deterministic plan on return (same snapshot keys as PlanScreen).
        item { Text(TodayStrings.LOG_HINT, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
        item {
            QuickLogActions(onProgress = onProgress, onSymptoms = onSymptoms)
        }
        item { Text(ExportUiStrings.CAPTION, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic) }
        // Redesign v2 ([DRE-211](/DRE/issues/DRE-211), founder p.3): the export +
        // diagnostics handoffs used to be two stacked full-width TextButtons — a
        // vertical "столбик". They now split the row side-by-side (weight 1f each)
        // so two actions never collapse into a column that fills the screen.
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { launchDataExport(shareContext, db) }, modifier = Modifier.weight(1f)) { Text(ExportUiStrings.BUTTON) }
                TextButton(onClick = { launchDiagnosticsExport(shareContext, db) }, modifier = Modifier.weight(1f)) { Text(DiagnosticsUiStrings.BUTTON) }
            }
        }
        // M10-D ([DRE-191](/DRE/issues/DRE-191)): the diagnostics handoff — a lean
        // support bundle (version, data volume, gate decisions; NO user free-text)
        // reusing the SAME share edge as export.
        item { Text(DiagnosticsUiStrings.CAPTION, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic) }
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
    LazyColumn(modifier = modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item { Text(HistoryStrings.TITLE, fontWeight = FontWeight.Bold) }
        item { Text(HistoryStrings.SUPPORT, fontWeight = FontWeight.Light) }
        item { Text(view.trendLine, fontWeight = FontWeight.SemiBold) }
        item { Text(HistoryStrings.WEIGHT_SECTION, fontWeight = FontWeight.SemiBold) }
        items(view.points) { p -> Text("• ${p.date}: ${p.weightKg} кг") }
        item { Text(HistoryStrings.SYMPTOMS_SECTION, fontWeight = FontWeight.SemiBold) }
        items(symptomLines) { Text(it) }
        item { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(HistoryStrings.BACK) } }
    }
}

/**
 * M9-C ([DRE-120](/DRE/issues/DRE-120)): the authored strings the denser exercise
 * card renders — the metadata-tag prefixes + the collapsible-detail toggle.
 * Gathered as one list ([all]) so a JVM test can snapshot them against the
 * banned medical-claim phrase list (mirrors [ReferencesCardStrings] /
 * [TodayStrings]). Support framing only: no diagnosis, no treatment/cure claim.
 * Catalog-vocab tag VALUES (equipment / evidenceLevel) are rendered VERBATIM and
 * are NOT in [all] — they are the Evidence & Research Analyst's controlled
 * vocabulary, not app-authored copy (same stance as the M6-B citation rows).
 */
internal object DensityChipStrings {
    /** Suffix on the sets tag ("3 подх."). */
    const val SETS = "подх."
    /** Suffix on the reps tag ("8–12 повт."). */
    const val REPS = "повт."
    /** Prefix on the RIR tag ("RIR 2"). */
    const val RIR = "RIR"
    /** Collapsible-detail toggle affordances (always-visible header). */
    const val DETAILS = "Подробнее"
    const val HIDE = "Скрыть"

    val all: List<String> = listOf(SETS, REPS, RIR, DETAILS, HIDE)
}

/**
 * M9-C: one metadata tag on the denser exercise card. [label] is the whole
 * user-facing string (prefix + value) so the Compose [MetaTag] only does
 * `Text(label)` — no formatting logic in the tree (the [ResolvedCitation].line
 * pattern).
 */
internal data class DensityChip(val label: String)

/**
 * M9-C: the deterministic, pure list of metadata tags for one exercise
 * assignment. Replaces the single dull "${sets}×${reps} @RIR" text line with
 * scannable chips: sets, reps, RIR (when present), equipment (when the catalog
 * carries one and it is not "none"), and one tag per distinct resolved evidence
 * level. Pure over already-resolved inputs (no Android, no I/O) so a JVM test
 * pins the determinism + content guarantees without a device — same inputs →
 * same chips (rendering determinism, mirrors [referencesHeaderLine]).
 *
 * The tag VALUES for equipment / evidenceLevel are the catalog's raw controlled
 * vocab, rendered VERBATIM — a label, not an appraisal (RU translation of
 * catalog vocab is an Evidence & Research Analyst task, never an app-side map
 * that could drift; same stance as the M6-B `(уровень: …)` render).
 */
internal fun exerciseDensityChips(
    sets: Int,
    repScheme: String,
    rir: Int?,
    equipment: String?,
    evidenceLevels: List<String>,
): List<DensityChip> {
    val chips = mutableListOf<DensityChip>()
    chips += DensityChip("$sets ${DensityChipStrings.SETS}")
    if (repScheme.isNotBlank()) chips += DensityChip("$repScheme ${DensityChipStrings.REPS}")
    rir?.let { chips += DensityChip("${DensityChipStrings.RIR} $it") }
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
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
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
                            Text(name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                exerciseDensityChips(a.sets, a.repScheme, a.rir, refs.equipment, refs.evidenceLevels)
                                    .forEach { chip -> MetaTag(chip.label) }
                            }
                        }
                        // Collapsible-detail toggle — separate tappable so the
                        // checkbox only toggles completion (no dual semantics).
                        Text(
                            if (detailOpen) DensityChipStrings.HIDE else DensityChipStrings.DETAILS,
                            modifier = Modifier.clickable { detailOpen = !detailOpen },
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (detailOpen) {
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
                        Text(
                            ReferencesCardStrings.WHY,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        detailMedia.summary?.let { summary ->
                            Text(summary, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (refs.howToStepsRu.isNotEmpty()) {
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
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (aiCoachEnabled) {
                                    OutlinedButton(
                                        modifier = Modifier.widthIn(max = 220.dp),
                                        onClick = { explainFor = a.exerciseId },
                                    ) {
                                        Text(
                                            CoachStrings.ASK_AI,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                mediaButtons.forEach { (url, label) ->
                                    OutlinedButton(
                                        modifier = Modifier.widthIn(max = 220.dp),
                                        onClick = { openUrl(ctx, url) },
                                    ) {
                                        Text(
                                            label,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        // Citations: secondary toggle to keep the detail compact.
                        if (refs.citations.isNotEmpty()) {
                            var citationsOpen by remember(a.exerciseId) {
                                mutableStateOf(false)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    citationsOpen = !citationsOpen
                                },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${ReferencesCardStrings.EVIDENCE} (${refs.citations.size})",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (citationsOpen) ReferencesCardStrings.HIDE
                                    else ReferencesCardStrings.SHOW,
                                    fontWeight = FontWeight.Light,
                                )
                            }
                            if (citationsOpen) {
                                refs.citations.forEach { c -> EvidenceCitationCard(c) }
                            }
                        }
                        if (!refs.hasMedia) {
                            Text(ReferencesCardStrings.MEDIA_PENDING, fontWeight = FontWeight.Light)
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
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
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
    Column(modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Как вы себя чувствуете?") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (text.isNotBlank()) { db.appendSymptom(text.trim(), LocalDate.now().toString()); text = ""; symptoms = db.recentSymptoms() }
        }) { Text("Записать") }
        OutlinedButton(onClick = onBack) { Text("Назад к плану") }
        Text("Недавние записи:", fontWeight = FontWeight.SemiBold)
        symptoms.forEach { s: SymptomEntry -> Text("• ${s.recordedOn}: ${s.text}") }
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
    Column(modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            // DRE-193: aligned to the app-wide scan-clean support framing
            // ("поддерживает, а не заменяет врача"); the prior copy used the
            // banned morpheme "диагности" even in negation.
            "Запишите вес (кг). Тренд — не одна точка — влияет на объём тренировок. " +
                "Приложение поддерживает, а не заменяет врача.",
            fontWeight = FontWeight.Medium,
        )
        OutlinedTextField(
            value = weight,
            onValueChange = { weight = it },
            label = { Text("Вес, кг") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            val kg = weight.trim().replace(',', '.').toDoubleOrNull()
            if (kg != null && kg > 0.0) {
                db.appendProgress(kg, LocalDate.now().toString())
                weight = ""
                rows = db.recentProgress()
            }
        }) { Text("Записать") }
        OutlinedButton(onClick = onBack) { Text("Назад к плану") }
        Text("Недавние записи:", fontWeight = FontWeight.SemiBold)
        rows.forEach { r: ProgressRow -> Text("• ${r.recordedOn}: ${r.weightKg} кг") }
    }
}

/**
 * DRE-175 — the user-facing AI-coach credential screen. Three fields (URL, токен,
 * модель) persisted encrypted-at-rest via [CoachCredentialStore] (Android Keystore
 * AES-GCM, not plaintext SharedPreferences). With creds saved, "Спросить у AI" /
 * "Сообщить коучу" call the user's LLM directly; with none, the app behaves exactly
 * as before (deterministic fallback) — it never crashes. Support framing only:
 * the screen lets the user *configure* a tool, it makes no medical claim.
 */
@Composable
private fun SettingsScreen(modifier: Modifier, coachCredStore: CoachCredentialStore, aiCoachEnabled: Boolean, onToggleAiCoach: (Boolean) -> Unit) {
    val saved = remember { coachCredStore.load() }
    var baseUrl by remember { mutableStateOf(saved?.baseUrl ?: SettingsStrings.DEFAULT_URL) }
    var token by remember { mutableStateOf(saved?.token ?: "") }
    var model by remember { mutableStateOf(saved?.model ?: SettingsStrings.DEFAULT_MODEL) }
    var message by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier = modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item { Text(SettingsStrings.TITLE, fontWeight = FontWeight.Bold) }
        item { Text(SettingsStrings.HINT, fontWeight = FontWeight.Light) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = Spacing.md)) {
                    Text(SettingsStrings.COACH_TOGGLE_TITLE, fontWeight = FontWeight.Bold)
                    Text(SettingsStrings.COACH_TOGGLE_HINT, fontWeight = FontWeight.Light, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = aiCoachEnabled, onCheckedChange = onToggleAiCoach)
            }
        }
        if (aiCoachEnabled) {
        item {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(SettingsStrings.URL_LABEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(SettingsStrings.TOKEN_LABEL) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(SettingsStrings.MODEL_LABEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
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
        }
        message?.let { m -> item { Text(m, fontWeight = FontWeight.Light) } }
        }
    }
}

/**
 * DRE-175 — the authored strings the Settings screen renders. Gathered as
 * [all] so a JVM test snapshots them against the banned medical-claim phrase
 * list (mirrors [UiStrings] / [CoachStrings]). Support framing only: the screen
 * configures a tool — no diagnosis, no treatment claim.
 */
internal object SettingsStrings {
    const val TITLE = "Настройки AI-коуча"
    const val HINT = "Укажите ссылку API и токен, чтобы «Спросить у AI» вызывал вашу модель напрямую. Без них приложение работает как раньше. Приложение поддерживает, не заменяет врача."
    const val URL_LABEL = "URL API (ссылка)"
    const val TOKEN_LABEL = "Токен"
    const val MODEL_LABEL = "Модель"
    const val SAVE = "Сохранить"
    const val CLEAR = "Сбросить"
    const val SAVED = "Сохранено. Ключ хранится зашифрованным на устройстве."
    const val CLEARED = "Ключ сброшен. AI использует офлайн-план."
    const val COACH_TOGGLE_TITLE = "AI-коуч"
    const val COACH_TOGGLE_HINT = "Выключен по умолчанию. Включите и укажите данные API, чтобы «Спросить у AI» и «Отправить коучу» вызывали вашу модель. Без включения приложение строит план офлайн. Приложение поддерживает, не заменяет врача."
    // Default base URL = Z.AI's OpenAI-compatible endpoint (server-confirmed); the
    // user can change it for any compatible provider. Default model = glm-4.6
    // (the server's working thinking flagship; "glm-5.2" / Max think is the spec
    // target — editable here when Z.AI ships that id).
    const val DEFAULT_URL = "https://api.z.ai/api/paas/v4"
    const val DEFAULT_MODEL = "glm-4.6"

    val all: List<String> = listOf(
        TITLE, HINT, COACH_TOGGLE_TITLE, COACH_TOGGLE_HINT, URL_LABEL, TOKEN_LABEL, MODEL_LABEL, SAVE, CLEAR, SAVED, CLEARED, DEFAULT_URL, DEFAULT_MODEL,
    )
}
