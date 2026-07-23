package dreamteam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import dreamteam.app.data.LocalDatabase
import dreamteam.app.data.Profile
import dreamteam.app.data.ProgressRow
import dreamteam.app.data.SymptomEntry
import dreamteam.domain.adaptation.AdaptationSignal
import dreamteam.domain.safety.ContraindicationStubs
import dreamteam.domain.safety.MedicalSafety
import dreamteam.domain.safety.SafetyEvaluation
import dreamteam.domain.safety.SafetyGate
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.StructuralSafetyRules
import dreamteam.domain.training.BaselineProgram
import dreamteam.domain.training.GeneratedPlan
import dreamteam.domain.training.DeterministicPlanGenerator
import dreamteam.domain.training.PlanWeek
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
private enum class Screen { Onboarding, Today, Plan, Symptoms, Progress, History }

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
    data class Blocked(val reason: String) : PlanResult
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
    val medical = MedicalSafety(
        scoliosisReported = profile.scoliosisReported,
        redFlags = profile.redFlags,
        currentCurveDataAvailable = false,
        clinicianCurveSpecificPlanAvailable = false,
    )
    val safety = SafetyGate.evaluate(medical)
    if (!safety.allowTrainingGeneration) return PlanResult.Blocked("Красный флаг: обратитесь за медицинской оценкой. Приложение не ставит диагноз.")
    val context = ScreeningContext(
        allowedExerciseIds = BaselineProgram.exerciseIds,
        allowedEvidenceIds = BaselineProgram.evidenceIds,
        sideSpecificLockEngaged = !safety.allowSideSpecificContent,
        conditionFlags = if (profile.scoliosisReported) setOf("scoliosis_flagged") else emptySet(),
    )
    val gateway = SafetyGuardedGateway(context, StructuralSafetyRules.all + ContraindicationStubs.all)
    val signal = localAdaptationSignal(symptoms, progress)
    return when (val g = DeterministicPlanGenerator(gateway).generate(userId = "local", createdAt = today, adaptation = signal)) {
        is GeneratedPlan.Ok -> {
            // M4-C: surface the FULL deterministic NutritionPlan (target + meal
            // structure) behind its own nutrition-appropriate gate. Only a
            // gate-Ok plan reaches the UI; a block surfaces nothing for
            // nutrition (training still renders). Same inputs → same plan.
            val nutritionPlan = when (val n = localNutritionPlan(profile, today)) {
                is GeneratedNutritionPlan.Ok -> n.plan
                is GeneratedNutritionPlan.Blocked -> null
            }
            PlanResult.Ok(g.plan.weeks.first(), nutritionPlan, safety, signal)
        }
        is GeneratedPlan.Blocked -> PlanResult.Blocked("План заблокирован шлюзом безопасности: ${g.ruleIds.joinToString()}.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DreamTeamApp(db: LocalDatabase) {
    var screen by remember { mutableStateOf(if (db.loadProfile() == null) Screen.Onboarding else Screen.Today) }
    var profile by remember { mutableStateOf(db.loadProfile()) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("DreamTeam") })
    }) { padding ->
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
                onSymptoms = { screen = Screen.Symptoms },
                onProgress = { screen = Screen.Progress },
                onPlan = { screen = Screen.Plan },
                // M5-C (DRE-63): one-tap entry to the read-only history/trend view.
                onHistory = { screen = Screen.History },
            )
            // M5-C (DRE-63): the read-only history/trend screen — shows logged
            // progress + symptoms + the deterministic trend, no interpretation.
            Screen.History -> HistoryScreen(
                modifier = Modifier.padding(padding),
                db = db,
                onBack = { screen = Screen.Today },
            )
            Screen.Plan -> PlanScreen(
                modifier = Modifier.padding(padding),
                db = db,
                profile = profile,
                onSymptoms = { screen = Screen.Symptoms },
                onProgress = { screen = Screen.Progress },
                onBack = { screen = Screen.Today },
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

    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Профиль (базовый PoC). Это приложение поддерживает тренировки — " +
                    "оно не диагностирует и не лечит.",
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

@Composable
private fun PlanScreen(modifier: Modifier, db: LocalDatabase, profile: Profile?, onSymptoms: () -> Unit, onProgress: () -> Unit, onBack: () -> Unit) {
    val p = profile ?: run {
        Column(modifier.fillMaxSize().padding(16.dp)) { Text("Профиль не найден."); Button(onClick = {}) {} }
        return
    }
    // Local, offline-first read; cheap SQLite query, no network. Keying the plan
    // on the symptom + progress snapshots means a newly logged symptom (escalation)
    // or weight point (rapid-loss trend) is reflected the next time this screen
    // composes — same inputs → same plan.
    val symptoms = db.recentSymptoms()
    val progress = db.recentProgress()
    val result = remember(p, symptoms, progress) { generateLocalPlan(p, LocalDate.now().toString(), symptoms, progress) }

    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(TodayStrings.BACK_TO_TODAY) } }
        when (result) {
            is PlanResult.Blocked -> item {
                Card(modifier = Modifier.fillMaxWidth()) { Text(result.reason, modifier = Modifier.padding(12.dp)) }
            }
            is PlanResult.Ok -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Неделя ${result.week.weekNumber} · ${result.week.phase}", fontWeight = FontWeight.Bold)
                            // M4-C ([DRE-57](/DRE/issues/DRE-57)): render the full surfaced
                            // NutritionPlan via the pure [nutritionPlanView] (extracted so its
                            // strings are banned-phrase-tested, [NutritionPlanViewTest]): target +
                            // deterministic meal structure + the cataloged evidence ids + an
                            // explicit support-not-treatment disclaimer. No diagnosis / prescription /
                            // claim. null (gate-blocked) renders nothing for nutrition. Offline-first:
                            // produced locally from the profile, no network to view it.
                            result.nutritionPlan?.let { plan ->
                                val view = remember(plan) { nutritionPlanView(plan) }
                                Text(view.targetLine, fontWeight = FontWeight.SemiBold)
                                view.meals.forEach { m -> Text("${m.label}: ${m.line}", fontWeight = FontWeight.Light) }
                                Text(view.evidenceLine, fontWeight = FontWeight.Light)
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
                                Spacer(Modifier.height(4.dp))
                                Text(note.indicator, fontWeight = FontWeight.SemiBold)
                                Text(note.reason, fontWeight = FontWeight.Light)
                            }
                        }
                    }
                }
                items(result.week.sessions) { session ->
                    SessionCard(db = db, session = session)
                }
            }
        }
        item {
            OutlinedButton(onClick = onSymptoms, modifier = Modifier.fillMaxWidth()) { Text("Записать симптом") }
        }
        // M5-A (DRE-61): entry to the progress logger next to the symptom logger.
        item {
            OutlinedButton(onClick = onProgress, modifier = Modifier.fillMaxWidth()) { Text("Записать прогресс") }
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
    onSymptoms: () -> Unit,
    onProgress: () -> Unit,
    onPlan: () -> Unit,
    // M5-C (DRE-63): entry to the read-only history/trend view.
    onHistory: () -> Unit,
) {
    val p = profile ?: run {
        Column(modifier.fillMaxSize().padding(16.dp)) { Text("Профиль не найден."); Button(onClick = {}) {} }
        return
    }
    // Same offline-first read + recompute keys as PlanScreen: a newly logged
    // symptom (escalation) or weight point (rapid-loss trend) is reflected the
    // next time this screen composes — same inputs → same plan.
    val symptoms = db.recentSymptoms()
    val progress = db.recentProgress()
    val today = LocalDate.now()
    val result = remember(p, symptoms, progress) { generateLocalPlan(p, today.toString(), symptoms, progress) }

    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (result) {
            is PlanResult.Blocked -> item {
                Card(modifier = Modifier.fillMaxWidth()) { Text(result.reason, modifier = Modifier.padding(12.dp)) }
            }
            is PlanResult.Ok -> {
                // Today's session is a pure pick from the SAME week PlanScreen
                // renders — no second source of truth.
                val session = todaySession(result.week, today)
                item { Text(todayDateLine(session), fontWeight = FontWeight.Bold) }
                item { Text(TodayStrings.TRAINING, fontWeight = FontWeight.SemiBold) }
                session?.let { s -> item { SessionCard(db = db, session = s) } }
                item { Text(TodayStrings.NUTRITION, fontWeight = FontWeight.SemiBold) }
                result.nutritionPlan?.let { plan ->
                    item {
                        val view = remember(plan) { nutritionPlanView(plan) }
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(view.targetLine, fontWeight = FontWeight.SemiBold)
                                view.meals.forEach { m -> Text("${m.label}: ${m.line}", fontWeight = FontWeight.Light) }
                                Text(view.evidenceLine, fontWeight = FontWeight.Light)
                                Text(view.disclaimer, fontWeight = FontWeight.Light, fontStyle = FontStyle.Italic)
                            }
                        }
                    }
                }
                item { Text(TodayStrings.ADAPTATION, fontWeight = FontWeight.SemiBold) }
                // On AdaptationSignal.None → null → nothing (baseline shows as today).
                adaptationNote(result.signal)?.let { note ->
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(note.indicator, fontWeight = FontWeight.SemiBold)
                                Text(note.reason, fontWeight = FontWeight.Light)
                            }
                        }
                    }
                }
            }
        }
        // One-tap logging + full plan are always reachable.
        item { Text(TodayStrings.LOG_HINT, fontWeight = FontWeight.Light) }
        item { OutlinedButton(onClick = onProgress, modifier = Modifier.fillMaxWidth()) { Text(TodayStrings.LOG_PROGRESS) } }
        item { OutlinedButton(onClick = onSymptoms, modifier = Modifier.fillMaxWidth()) { Text(TodayStrings.LOG_SYMPTOM) } }
        item { OutlinedButton(onClick = onPlan, modifier = Modifier.fillMaxWidth()) { Text(TodayStrings.FULL_PLAN) } }
        // M5-C ([DRE-63](/DRE/issues/DRE-63)): one-tap entry to the read-only
        // history/trend view — the visibility half of the retention loop.
        item { OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text(HistoryStrings.TITLE) } }
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
    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun SessionCard(db: LocalDatabase, session: dreamteam.domain.training.PlanSession) {
    var completed by remember(session.id) { mutableStateOf(db.completedExercises(session.id)) }
    val today = LocalDate.now().toString()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${session.day} · ${session.label}", fontWeight = FontWeight.SemiBold)
            session.assignments.forEach { a ->
                val name = BaselineProgram.exercises[a.exerciseId]?.name ?: a.exerciseId
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = a.exerciseId in completed,
                        onCheckedChange = { checked ->
                            if (checked) { db.logWorkout(session.id, a.exerciseId, today); completed = completed + a.exerciseId }
                        },
                    )
                    Text("$name — ${a.sets}×${a.repScheme}" + (a.rir?.let { " @${it} RIR" } ?: ""))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Сделано: ${completed.size}/${session.assignments.size}", fontWeight = FontWeight.Light)
        }
    }
}

@Composable
private fun SymptomsScreen(modifier: Modifier, db: LocalDatabase, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var symptoms by remember { mutableStateOf(db.recentSymptoms()) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Как вы себя чувствуете?") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (text.isNotBlank()) { db.appendSymptom(text.trim(), LocalDate.now().toString()); text = ""; symptoms = db.recentSymptoms() }
        }) { Text("Записать") }
        OutlinedButton(onClick = onBack) { Text("Назад к плану") }
        Spacer(Modifier.size(0.dp))
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
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Запишите вес (кг). Тренд — не одна точка — влияет на объём тренировок. " +
                "Приложение поддерживает, не диагностирует.",
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
        Spacer(Modifier.size(0.dp))
        Text("Недавние записи:", fontWeight = FontWeight.SemiBold)
        rows.forEach { r: ProgressRow -> Text("• ${r.recordedOn}: ${r.weightKg} кг") }
    }
}
