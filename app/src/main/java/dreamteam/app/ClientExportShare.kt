package dreamteam.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dreamteam.app.data.LocalDatabase
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * M7-B ([DRE-73](/DRE/issues/DRE-73)) — the user-facing data-export handoff for
 * [Milestone 7](/DRE/issues/DRE-71) (user-data portability). This is the Android
 * I/O edge that makes the M7-A pure serializer
 * ([ClientExport.kt](./ClientExport.kt)) user-reachable: it writes the
 * deterministic envelope to a file and hands it to the system share/save sheet
 * (`ACTION_SEND` over a `FileProvider` content URI). Fully offline — **no network
 * call anywhere in the path**.
 *
 * Composition (one serialization path, no second core): the action reads the FULL
 * on-device store, runs the pure [exportActionDocument] (which regenerates the
 * safety-gated plan via [regenerateLocalPlans] → [exportPlanFrom] and assembles
 * the document via [buildExportDocument]), encodes it via [encodeExportDocument],
 * writes the exact bytes to a deterministic cache filename, exposes it through a
 * `FileProvider` content URI, and launches `ACTION_SEND`. The user can then send
 * the file to a physio/clinician or save it via the system sheet.
 *
 * Hard invariants (M7 tracker): deterministic only; no LLM; no network;
 * **no new external dependency** — `androidx.core.content.FileProvider` is part
 * of the already-present `androidx.core` (core-ktx), and `Intent` is Android std;
 * no third-party share library. Adds no interpretation, no diagnosis, no new
 * guidance; never adds/weakens/bypasses a [dreamteam.domain.safety.SafetyRule];
 * `plan` is `null` whenever the gate blocks (the UI path never emits a non-gated
 * plan). The non-medical disclaimer is in the file envelope ([ExportStrings])
 * and surfaced in-app ([ExportUiStrings.CAPTION]) at the action.
 */

/** Deterministic export filename — stable, no random/UUID component; overwrites the prior export (no cache accumulation). */
internal const val EXPORT_FILE_NAME = "dreamteam-export.json"

/** Honest MIME for the JSON envelope; the system share/save sheet handles it for send + save. */
internal const val EXPORT_MIME = "application/json"

/** The `FileProvider` authority — `${applicationId}.fileprovider`, wired in `AndroidManifest.xml`. */
internal const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

/**
 * The authored export-UI framing strings. [CAPTION] is the short, non-medical
 * line surfaced in-app at the action (what the file contains + support/self-report
 * framing — mirrors the [ExportStrings.DISCLAIMER] baked into the envelope).
 * Gathered here so a JVM test can scan it against the banned medical-claim phrase
 * list, the same way every other authored surface is pinned.
 */
internal object ExportUiStrings {
    const val BUTTON = "Экспорт моих данных"
    const val SHARE_TITLE = "Поделиться данными"
    const val CAPTION =
        "Файл с профилем, записями и текущим планом — для врача или архива. " +
            "Ваш самоотчёт для прозрачности; приложение поддерживает, а не заменяет врача."

    val all: List<String> = listOf(BUTTON, SHARE_TITLE, CAPTION)
}

/**
 * Produce the export file for the current on-device store + regenerated plan and
 * hand it to the system share/save sheet via a `FileProvider` content URI.
 * Offline-first — no network. Pure byte-production via [exportActionDocument]
 * (the same M7-A serialization path); the bytes written are exactly
 * [encodeExportDocument] of that document. No-op if there is no saved profile
 * (export is only offered post-onboarding; the UI that calls this is itself
 * post-onboarding, so this is a defensive guard, not a user-facing branch).
 */
internal fun launchDataExport(context: Context, db: LocalDatabase) {
    val profile = db.loadProfile() ?: return
    val today = LocalDate.now().toString()
    val doc = exportActionDocument(
        profile = profile,
        workouts = db.allWorkouts(),
        symptoms = db.allSymptoms(),
        progress = db.allProgress(),
        today = today,
        generatedAt = Instant.now().toString(),
    )
    val bytes = encodeExportDocument(doc).toByteArray(Charsets.UTF_8)
    // cacheDir is the Android-standard transient location for share-via-FileProvider:
    // write, hand off, the system sheet copies/saves it where the user chooses.
    val file = File(context.cacheDir, EXPORT_FILE_NAME).apply { writeBytes(bytes) }
    val authority = context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX
    val uri = FileProvider.getUriForFile(context, authority, file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = EXPORT_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, ExportUiStrings.SHARE_TITLE))
}
