package dreamteam.domain.safety

/**
 * DRE-100 ([plan](/DRE/issues/DRE-100#document-plan)): a deterministic, pure
 * scanner that derives [RedFlag](s) from the user's own free-text workout-note
 * language. The coach merges any derived flags into the red-flag gate input
 * **before** [SafetyGate.evaluate] (see the `evaluate(medical, notes)` overload),
 * so a note-derived flag is indistinguishable from a structured one — it blocks
 * → routes to assessment → [dreamteam.domain.coach.CoachReport.Blocked].
 * Enforced in code, not a model instruction.
 *
 * ## Clinical content (verbatim from the Safety Reviewer's plan)
 *
 * The phrase → flag table is **precision-first**: it escalates only on
 * high-precision, *distribution/function-qualified* phrasing. A bare common word
 * («немеет нога», «болит спина») does **not** escalate — that stays on the
 * existing [dreamteam.domain.coach.coachAdaptationSignal] de-load path.
 *
 * Matching is case-insensitive, RU morphology-tolerant **stem-substring**
 * («онемен|немеет|онеме» matches онемение/онемела/немеют). Phrases are anchored
 * on the distribution/function, never the bare symptom.
 *
 * **Evidence:** escalation semantics (a red flag ⇒ block / route to assessment)
 * come from `ACR-LBP-RED-FLAGS` in `data/evidence_catalog.json`, whose
 * `application` field states *"The app blocks self-programming when new
 * bowel/bladder dysfunction, saddle anaesthesia, progressive weakness or other
 * major red flags are reported."* The phrase→flag mapping is a conservative
 * heuristic from patient-reported symptom language; the Evidence Analyst may
 * tune phrase anchors (child issue) — the mechanism here is unaffected.
 *
 * This scanner carries no medical judgement of its own: it never diagnoses and
 * never emits guidance text. It only derives the closed [RedFlag] set the gate
 * already understands. Adding/tuning a phrase is a Safety Reviewer decision, not
 * an engineering one.
 */
object NoteRedFlagScreening {

    // --- phrase table (verbatim stems from the DRE-100 plan; case-insensitive) ---

    /** Numbness stems («онемен|немеет|онеме»). */
    private val NUMB = listOf("онемен", "немеет", "онеме")
    /** Saddle/groin/perineal/genital distribution («промежност | пах | седл | между ног | генитал»). */
    private val SADDLE_DISTRIBUTION = listOf("промежност", "пах", "седл", "между ног", "генитал")
    /** Bilateral lower-extremity numbness phrasing («обе ноги немеют | онемение обеих ног | немеют обе»). */
    private val BILATERAL_LE_NUMB = listOf("обе ноги немеют", "онемение обеих ног", "немеют обе")
    /** Bladder mention stem («моч»). */
    private val BLADDER = listOf("моч")
    /** Bowel mention stems («кал | кишечн»). */
    private val BOWEL = listOf("кал", "кишечн")
    /** Incontinence stem («недерж»). */
    private val INCONTINENCE = listOf("недерж")
    /** Retention phrasing («задержка моч» / «не могу помочиться»). */
    private val RETENTION = listOf("задержка моч", "не могу помочиться")
    /** Loss-of-control stem («потеря контрол»). */
    private val CONTROL_LOSS = listOf("потеря контрол")
    /** Weakness stem («слабост»). */
    private val WEAK = listOf("слабост")
    /** Progressive/spreading stems («нараста» / «распространя»). */
    private val PROGRESS = listOf("нараста", "распространя")
    /** Neurological term stems («онемен | слабост | немеет»). */
    private val NEURO = listOf("онемен", "слабост", "немеет")
    /** Foot-drop / progressive motor phrasing. */
    private val FOOT_DROP = listOf("нога подкашива", "волочит ног", "стопа падает", "не могу встать на носки")
    /** Strict "wakes from sleep" night-pain phrasing («боль будит ночью» / «просыпаюсь от бол…»). */
    private val NIGHT_PAIN_STRICT = listOf("боль будит ночью", "просыпаюсь от бол")

    /**
     * Derive the [RedFlag] set carried by the user's free-text notes. Empty ⇒ no
     * red-flag language; the note stays on the de-load path. Pure: same notes →
     * same set, every time. Notes are scanned as one joined utterance so a
     * constellation split across notes still fires (e.g. numbness in one note +
     * bladder mention in another).
     */
    fun derive(notes: List<String>): Set<RedFlag> {
        val t = notes.joinToString(" ").lowercase().replace(Regex("\\s+"), " ")
        fun any(list: List<String>): Boolean = list.any { it in t }

        val numb = any(NUMB)
        val saddleDistrib = any(SADDLE_DISTRIBUTION)
        val bareSaddle = "седловидн" in t
        val bilateralNumb = any(BILATERAL_LE_NUMB)
        val bladder = any(BLADDER)
        val bowel = any(BOWEL)
        val incontinence = any(INCONTINENCE)
        val retention = any(RETENTION)
        val controlLoss = any(CONTROL_LOSS)
        val weak = any(WEAK)
        val progress = any(PROGRESS)
        val neuro = any(NEURO)
        val footDrop = any(FOOT_DROP)
        val nightPain = any(NIGHT_PAIN_STRICT) || ("боль" in t && "не дает спать" in t)

        // Location-qualified saddle/groin numbness, bilateral LE numbness, or the
        // bare clinical term «седловидн». Bare single-leg numbness does NOT escalate.
        val saddleNumbness = (numb && saddleDistrib) || bilateralNumb || bareSaddle

        val flags = mutableSetOf<RedFlag>()

        // Highest-acuity constellation (cauda equina pattern): saddle/groin/perineal
        // numbness + any bladder/bowel mention ⇒ escalate BOTH flags unconditionally.
        if (saddleNumbness && (bladder || bowel)) {
            flags += RedFlag.NUMBNESS_OR_SADDLE_ANAESTHESIA
            flags += RedFlag.BOWEL_OR_BLADDER_DYSFUNCTION
        } else {
            if (saddleNumbness) flags += RedFlag.NUMBNESS_OR_SADDLE_ANAESTHESIA
            // Dysfunction-qualified: incontinence/retention/loss-of-control + the
            // relevant system, never the bare word.
            if ((incontinence && (bladder || bowel)) || retention || (controlLoss && (bladder || bowel))) {
                flags += RedFlag.BOWEL_OR_BLADDER_DYSFUNCTION
            }
        }

        // Progressive/distribution-qualified weakness; post-workout fatigue does NOT escalate.
        if ((weak && progress) || footDrop) flags += RedFlag.PROGRESSIVE_LEG_WEAKNESS

        // Combination of progression/spread with a neuro term.
        if (progress && neuro) flags += RedFlag.RAPID_NEUROLOGICAL_PROGRESSION

        // Strict "wakes from sleep" only; bare «ночью болит» does NOT escalate.
        if (nightPain) flags += RedFlag.NIGHT_PAIN

        return flags
    }
}
