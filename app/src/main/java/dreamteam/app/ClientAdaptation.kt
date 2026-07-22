package dreamteam.app

import dreamteam.app.data.SymptomEntry
import dreamteam.domain.adaptation.AdaptationSignal
import dreamteam.domain.adaptation.deriveAdaptationSignal
import dreamteam.domain.symptom.Symptom

/**
 * M3-C ([DRE-52](/DRE/issues/DRE-52)): bridge the client's logged symptom rows
 * to the domain signal the plan generator consumes. **Pure** — no Android, no
 * I/O — so it is JVM-testable without a device and deterministic (same logs →
 * same signal; offline-first, ADR 0002). The weight-progress → RapidWeightLoss
 * trigger is intentionally NOT wired here: the client logs workout completion
 * and free-text symptoms today, not `ProgressEntry.weightKg`, so only the
 * SymptomEscalation trigger can fire client-side (named deferral in DRE-52).
 *
 * [tokenize] splits free text on whitespace/commas (engineer's call, kept
 * coarse): a new *token* in the latest entry vs the prior union is what the
 * SymptomEscalation trigger keys on. No clinical NLP, no diagnosis — just set
 * membership over the user's own words, behind the same de-load-only signal
 * type the server uses (M3-A [DRE-49](/DRE/issues/DRE-49)).
 *
 * ponytail: per-entry ids are synthesized from `recordedOn` + list index —
 * unique within a window and stable for the same window contents; the signal
 * derivation never reads `id`, only `recordedOn` (sorted) + `currentSymptoms`.
 */
internal fun clientSymptoms(entries: List<SymptomEntry>): List<Symptom> =
    entries.mapIndexed { i, e ->
        Symptom(
            id = "local-symptom-${e.recordedOn}-$i",
            userId = "local",
            recordedOn = e.recordedOn,
            source = "client_log",
            currentSymptoms = tokenize(e.text),
        )
    }

/**
 * The client-side half of the weekly loop: derive the de-load-only signal from
 * the user's own logged symptom rows, with `progress = emptyList()` (weight
 * logging is a later slice). Fed straight into
 * [dreamteam.domain.training.DeterministicPlanGenerator.generate] — still
 * behind [dreamteam.domain.safety.SafetyGuardedGateway.surface]; a red flag
 * still blocks, the signal never bypasses the gate.
 */
internal fun localAdaptationSignal(symptoms: List<SymptomEntry>): AdaptationSignal =
    deriveAdaptationSignal(progress = emptyList(), symptoms = clientSymptoms(symptoms))

/**
 * What [PlanScreen] shows for an [AdaptationSignal]: a plain, support-framed
 * note on [AdaptationSignal.DeLoad] (behavioral — "объём снижен" + why), nothing
 * on [AdaptationSignal.None]. Extracted from the Compose tree into a **pure**
 * function so a JVM test can pin the two M3-C guarantees ([DRE-53](/DRE/issues/DRE-53)):
 * (1) it renders exactly on DeLoad and carries the domain reason verbatim, and
 * (2) the rendered strings never contain a banned medical-claim phrase. No
 * diagnosis, no "у вас …", no treatment/cure framing — the note is volume-only.
 */
internal data class AdaptationNote(val indicator: String, val reason: String)

internal fun adaptationNote(signal: AdaptationSignal): AdaptationNote? =
    (signal as? AdaptationSignal.DeLoad)?.let { AdaptationNote(indicator = "Объём снижен", reason = it.reason) }

private fun tokenize(text: String): List<String> =
    text.split(Regex("[\\s,]+"))
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
