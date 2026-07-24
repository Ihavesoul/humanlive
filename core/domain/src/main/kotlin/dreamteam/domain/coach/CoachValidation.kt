package dreamteam.domain.coach

import dreamteam.domain.ExerciseId
import dreamteam.domain.adaptation.AdaptationSignal
import dreamteam.domain.adaptation.deriveAdaptationSignal
import dreamteam.domain.coach.Coach.Companion.COACH_SYSTEM_PROMPT
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.safety.MedicalSafety
import dreamteam.domain.safety.SafetyEvaluation
import dreamteam.domain.symptom.Symptom
import dreamteam.domain.training.PlanSession
import dreamteam.domain.training.TrainingPlan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The coach system prompt body (M8-C [DRE-89](/DRE/issues/DRE-89)). Mirrors
 * `prompts/coach_prompt_ru.md` closely enough that the rules the validator
 * enforces here match what the prompt tells the model. GLM, think = Max.
 * Editing this string is a coach-behaviour change reviewed with
 * [validateProviderText].
 */
internal val COACH_SYSTEM_PROMPT_BODY: String = """
Ты — коуч по тренировкам внутри приложения DreamTeam. Ты не врач и не ставишь
диагнозов. Используй максимально глубокий режим самопроверки (think = Max), но в
интерфейс возвращай только итог. Вывод — строго один phone-readable JSON-объект,
без markdown, без пояснений вне JSON.

Обязательные ограничения:
- выводи только валидный JSON по схеме;
- exercise_id бери только из allowed_exercise_ids во входе;
- не придумывай DOI, PMID, URL, исследования, углы Cobb, проценты жира;
- не диагностируй причину боли, не обещай выпрямление сколиоза, не утверждай что
  приложение лечит/вылечивает/излечивает;
- не давай side-specific указаний при side_specific_lock = true (сторона, клин,
  стелька, направленное дыхание, деротация);
- не назначай подходы/вес и не меняй выбор упражнений — план формируется
  детерминированным движком через SafetyGate; твой текст — аннотации к нему;
- текст — поддержка, не назначение: «может помочь», «снизьте диапазон при
  дискомфорте»; никогда «у вас», «ваш диагноз»;
- при новых неврологических симптомах в заметках — рекомендуй обратиться за
  медицинской оценкой, не «тренируйтесь через боль».
""".trim()

/**
 * Conservative pain-keyword set the note-pain signal scans for (lowercased RU).
 * These are the user's own words surfaced back as a *de-load-only* trigger —
 * never a diagnosis. Kept narrow to avoid false positives on neutral text.
 */
internal val NOTE_PAIN: List<String> = listOf(
    "боль", "болит", "дискомфорт", "стреля", "остра", "жгуч", "ноет", "колет",
)

/**
 * Derive the coach's [AdaptationSignal] from the user's own per-exercise notes
 * + the logged symptoms/progress. De-load-only (the sealed type has no increase
 * variant): a pain keyword in any note, OR an escalation/rapid-loss from
 * [deriveAdaptationSignal], ⇒ a conservative volume cut; both ⇒ a stronger cut.
 * Pure; same inputs → same signal.
 *
 * This is a *mechanism* (note text → de-load), not a clinical rule: which words
 * and thresholds warrant a de-load is conservative by default and refines here.
 */
internal fun coachAdaptationSignal(
    notes: List<CoachNote>,
    symptoms: List<Symptom>,
    progress: List<ProgressEntry>,
): AdaptationSignal {
    val notePain = notes.any { it.note.isNotBlank() && NOTE_PAIN.any { kw -> kw in it.note.lowercase() } }
    val logged = deriveAdaptationSignal(progress, symptoms)
    return when {
        notePain && logged is AdaptationSignal.DeLoad -> AdaptationSignal.DeLoad(
            trigger = logged.trigger,
            volumeScale = AdaptationSignal.SCALE_STRONG,
            reason = "снижение объёма: заметки о боли/дискомфорте + сигнал из логов",
        )
        notePain -> AdaptationSignal.DeLoad(
            trigger = dreamteam.domain.adaptation.DeLoadTrigger.SymptomEscalation,
            volumeScale = AdaptationSignal.SCALE_MODERATE,
            reason = "снижение объёма: в заметках есть боль/дискомфорт",
        )
        else -> logged
    }
}

/**
 * Banned substrings (lowercased) the provider text may never carry — the same
 * no-medical-claim stance every app-authored surface pins, extended for the LLM:
 * diagnosis/treatment/cure framing, second-person clinical framing, fabricated
 * citations (DOI/PMID/URL), and (when the side-specific lock is engaged)
 * side-specific directives. A provider response containing any of these is
 * rejected wholesale → the deterministic fallback stands.
 */
private val BANNED_CLAIM: List<String> = listOf(
    // medical claims / framing
    "диагноз", "диагности",
    "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
    "болезнь",
    "у вас", "вы больн", "вы здоровы", "ваш диагноз",
    "предписываю", "назначаю", "прописываю",
    "diagnos", "treat", "cure", "heal", "disease", "you have", "you are", "prescribe",
    // fabricated citations (#2 — evidence by allowlist only)
    "doi", "pmid", "http://", "https://", "www.", "ncbi", "pubmed", "sci-hub",
    ".com/", ".org/", ".ru/",
)

/** Side-specific directive phrases banned only while the lock is engaged (#1). */
private val BANNED_SIDE_SPECIFIC: List<String> = listOf(
    "правую сторону", "левую сторону", "правой сторон", "левой сторон",
    "правую ногу", "левую ногу", "правую руку", "левую руку",
    "тренируйте прав", "тренируйте лев", "больше прав", "больше лев",
    "подложите", "стельк", "подпяточн", "вогнут", "деротаци", "клин под",
)

/**
 * Validate a chunk of provider text against the no-claim + no-fabricated-
 * citation rules (and the side-specific-directive ban when the lock is engaged).
 * True ⇒ safe to surface as the coach's annotation; false ⇒ reject → fallback.
 *
 * The crude-substring scan is intentionally conservative (false-positive →
 * fallback, which is safe); it is a defense layer on top of the deterministic
 * gated plan, not the sole guard. It never *allows* something the gate blocked.
 */
internal fun validateProviderText(text: String, medical: MedicalSafety): Boolean {
    if (text.isBlank()) return false
    if (text.length > MAX_TEXT_CHARS) return false
    val lower = text.lowercase()
    if (BANNED_CLAIM.any { it in lower }) return false
    val lockEngaged = !SafetyGateEvaluator.allowSideSpecific(medical)
    if (lockEngaged && BANNED_SIDE_SPECIFIC.any { it in lower }) return false
    return true
}

/** Hard cap on a single provider text field (UI/abuse bound). */
internal const val MAX_TEXT_CHARS: Int = 1200

/** The screening-eval re-projection the validator needs (keeps [SafetyGate] the single source). */
private object SafetyGateEvaluator {
    fun allowSideSpecific(medical: MedicalSafety): Boolean =
        dreamteam.domain.safety.SafetyGate.evaluate(medical).allowSideSpecificContent
}

// --- provider payloads -------------------------------------------------------

private val payloadJson: Json = Json { prettyPrint = false }

/**
 * Build the provider's user-message payload for a report annotation: the adapted
 * plan (so the model annotates the *actual* surfaced plan, not an invented one),
 * the notes, symptoms, and the screening locks. Serialized as phone-readable
 * JSON; the provider is told to return only the annotation schema.
 */
internal fun reportPayload(
    adaptedPlan: TrainingPlan,
    notes: List<CoachNote>,
    symptoms: List<Symptom>,
    medical: MedicalSafety,
    safety: SafetyEvaluation,
): String {
    val nextSession = adaptedPlan.weeks.firstOrNull()?.sessions?.firstOrNull()
    val obj = buildJsonObject {
        put("task", "report")
        put("side_specific_lock", !safety.allowSideSpecificContent)
        put("red_flag_gate_passed", safety.redFlagGatePassed)
        put("allowed_exercise_ids", buildJsonArray {
            dreamteam.domain.training.BaselineProgram.exerciseIds.forEach { add(it) }
        })
        put("next_session", nextSession?.let { sessionJson(it) } ?: JsonObject(emptyMap()))
        put("recent_symptoms", buildJsonArray {
            symptoms.take(5).forEach { s -> add(s.currentSymptoms.joinToString(", ")) }
        })
        put("exercise_notes", buildJsonArray {
            notes.take(12).forEach { n ->
                add(buildJsonObject {
                    put("exercise_id", n.exerciseId)
                    put("note", n.note)
                })
            }
        })
        put("untrusted_user_notes", notes.joinToString("\n") { it.note })
    }
    return payloadJson.encodeToString(JsonObject.serializer(), obj)
}

/** Build the provider's user-message payload for a single-exercise cue. */
internal fun explainPayload(
    exerciseId: ExerciseId,
    medical: MedicalSafety,
    safety: SafetyEvaluation,
): String {
    val obj = buildJsonObject {
        put("task", "explain")
        put("exercise_id", exerciseId)
        put("side_specific_lock", !safety.allowSideSpecificContent)
        put("red_flag_gate_passed", safety.redFlagGatePassed)
        put("allowed_exercise_ids", buildJsonArray {
            dreamteam.domain.training.BaselineProgram.exerciseIds.forEach { add(it) }
        })
    }
    return payloadJson.encodeToString(JsonObject.serializer(), obj)
}

@Serializable
private data class SessionJson(
    val id: String,
    val label: String,
    @SerialName("assignments") val assignments: List<AssignmentJson>,
)

@Serializable
private data class AssignmentJson(
    @SerialName("exercise_id") val exerciseId: String,
    val sets: Int,
    @SerialName("rep_scheme") val repScheme: String,
)

private fun sessionJson(session: PlanSession): JsonObject {
    val s = SessionJson(
        id = session.id,
        label = session.label,
        assignments = session.assignments.map {
            AssignmentJson(it.exerciseId, it.sets, it.repScheme)
        },
    )
    val encoded = payloadJson.encodeToString(SessionJson.serializer(), s)
    return payloadJson.parseToJsonElement(encoded).jsonObject
}
