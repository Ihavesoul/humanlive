package dreamteam.app

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dreamteam.domain.EvidenceId
import dreamteam.domain.ExerciseId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * M8-A ([DRE-80](/DRE/issues/DRE-80)): the per-exercise **references card** —
 * one tappable block that consolidates everything a user needs to do an
 * exercise safely and see WHY it is recommended: the how-to steps (RU), the
 * demo video, the schematic images, and the evidence citations. The reviewer's
 * ask was "no naked links / no scattered URLs" — every reference lives behind a
 * labeled tappable button, never raw URL text, all in one card per exercise.
 *
 * **Source of truth = data, not the model.** The media (video / steps / images)
 * is read off the bundled `data/exercises.json` — the data allowlist the
 * Evidence & Research Analyst populates (M8-A1). It is NEVER LLM output
 * (ADR 0001 #2 — citations/media resolve only from the catalog). Empty fields
 * mean "not yet sourced", never "invent a link": a missing media field renders
 * nothing, and a surfaced evidence ref that fails to resolve renders the
 * [EVIDENCE_NOT_SOURCED] placeholder (honors EvidenceLinked).
 *
 * Two layers keep resolution pure + JVM-testable (the [nutritionPlanView] /
 * [EvidenceResolver] pattern):
 *  - [ExerciseLibraryResolver] / [resolveExerciseReferences] — pure over a
 *    decoded library; a JVM unit test pins: media resolves by id, ghost id → no
 *    media, evidence rows reuse the M6-A/M6-B render (0 naked ids).
 *  - [loadExerciseLibrary] — the single Android-I/O point (mirrors
 *    [loadEvidenceResolver]); the Compose [ReferencesCard] only renders.
 *
 * M9-B ([DRE-117](/DRE/issues/DRE-117)) — readable references consolidation:
 * the card is now **collapsible** (founder review #1: "схлопывать во что-то более
 * читаемое"). The whole reference body sits behind one always-visible tappable
 * header, so a workout list stays dense instead of reading like an expanded MD
 * viewer. Deterministic presentation only — the resolved data is unchanged
 * (pure render of [resolveExerciseReferences]; only already-resolved data,
 * never mutated, never a claim). External media sourcing stays Tier 2 (M9-E).
 *
 * M8-A1 content is seeded (DRE-79): every exercise carries `how_to_steps_ru`,
 * and most carry `video_url` / `image_refs` (Commons file-page URLs). The card
 * auto-rendered them with zero code change — it reads whatever the allowlist
 * carries. Exercises still without any media fall back to the transparent
 * "materials pending" note (never silent, never a fabricated link); the schema
 * guard in `ExerciseMediaSchemaTest` pins the slot is always ready to receive
 * more.
 */

/**
 * The snake_case load shape of one `data/exercises.json` entry — the read-only
 * seed the resolver decodes. Mirrors the server's `ExerciseSeed` (server/…/
 * BaselinePlan.kt) but carries the M8-A media fields (`video_url`,
 * `how_to_steps_ru`, `image_refs`). NOT the domain [dreamteam.domain.exercise.Exercise]
 * model (that is camelCase / `name` / `evidenceRefs` and is not the on-disk
 * shape); a local seed avoids coupling the client to a model the server does
 * not load either. Read-only: no write path. `ignoreUnknownKeys` (via
 * [evidenceJson]) skips the rest of the entry.
 */
@Serializable
internal data class ExerciseLibraryEntry(
    val id: String,
    @SerialName("video_url") val videoUrl: String? = null,
    @SerialName("how_to_steps_ru") val howToStepsRu: List<String> = emptyList(),
    @SerialName("image_refs") val imageRefs: List<String> = emptyList(),
    /**
     * M9-C ([DRE-120](/DRE/issues/DRE-120)): the catalog's raw `equipment`
     * vocab for the exercise (e.g. "dumbbell", "mat"). Already present in the
     * bundled `data/exercises.json`; decoding it here is NOT a data-model change
     * — the domain [dreamteam.domain.exercise.Exercise] model is untouched, and
     * `ignoreUnknownKeys` already skipped this field. Surfaced so the denser
     * exercise card can render an equipment tag. Verbatim controlled-vocab; RU
     * translation of catalog values is an Evidence & Research Analyst task, not
     * an app-side map (same stance as evidence level — avoids drift).
     */
    val equipment: String? = null,
)

/**
 * A pure id→[ExerciseLibraryEntry] view over a decoded library. Built from
 * bytes (no Android dependency), so a JVM test pins the guarantees without a
 * device. Mirrors [EvidenceResolver]. Never invents media: a missing id resolves
 * to `null` and the caller renders no media block (never a fabricated URL).
 */
internal class ExerciseLibraryResolver(library: List<ExerciseLibraryEntry>) {
    private val byId: Map<ExerciseId, ExerciseLibraryEntry> = library.associateBy { it.id }

    /** Resolve an exercise id to its library media entry, or `null` if absent. */
    fun resolveExercise(id: ExerciseId): ExerciseLibraryEntry? = byId[id]

    companion object {
        /** Decode + build the resolver from raw library JSON (the exact server decode). */
        fun fromJson(rawJson: String): ExerciseLibraryResolver =
            ExerciseLibraryResolver(evidenceJson.decodeFromString(rawJson))
    }
}

/**
 * The single Android-I/O point: decode the bundled `exercises.json` asset into a
 * pure [ExerciseLibraryResolver]. Offline-first — no network; the library ships
 * in the APK (the same `data/` assets srcDir [loadEvidenceResolver] reads, one
 * copy of the data, no drift).
 */
internal fun loadExerciseLibrary(assets: AssetManager): ExerciseLibraryResolver =
    ExerciseLibraryResolver.fromJson(assets.open("exercises.json").use { it.readBytes().decodeToString() })

/**
 * What [ReferencesCard] renders for one exercise: the resolved media
 * (`videoUrl`, `howToStepsRu`, `imageRefs`) plus the READABLE evidence
 * citations (M6-B [ResolvedCitation] rows — author/year + keyFinding +
 * evidenceLevel, or the blocked-until-sourced placeholder for a ghost id).
 *
 * [evidenceRefs] passed to [resolveExerciseReferences] is the authoritative
 * surfaced set (the [dreamteam.domain.training.ExerciseAssignment.evidenceRefs]
 * the plan/gate produced) — NOT a second copy read off the library, so the card
 * can never drift from what the gate actually surfaced. Media is resolved by id
 * from the library (the allowlist); a missing exercise renders empty media, not
 * a crash.
 */
internal data class ResolvedReferences(
    val exerciseId: ExerciseId,
    val videoUrl: String?,
    val howToStepsRu: List<String>,
    val imageRefs: List<String>,
    val citations: List<ResolvedCitation>,
    /**
     * M9-C ([DRE-120](/DRE/issues/DRE-120)): the catalog's raw `equipment` vocab
     * for the exercise (null when the library has no entry / no value). Verbatim
     * — a label, not an appraisal; RU translation is an Evidence Analyst task.
     */
    val equipment: String?,
    /**
     * M9-C: the distinct raw `evidenceLevel` values among the RESOLVED citations
     * (ghost ids contribute nothing). Deterministic given the same resolved
     * refs (catalog insertion order preserved via [resolveCitations]). One tag
     * per distinct level — a label of what is there, never an ordering/appraisal
     * (this slice does not vet studies; the Evidence Analyst owns the catalog).
     */
    val evidenceLevels: List<String>,
)

/** True when there is any media (video/steps/images) to render for the exercise. */
internal val ResolvedReferences.hasMedia: Boolean
    get() = !videoUrl.isNullOrBlank() || howToStepsRu.isNotEmpty() || imageRefs.isNotEmpty()

/**
 * Build the references-card view for one exercise. Pure (no Android, no I/O):
 * media resolves by id from [library] (null/empty when not yet sourced — never
 * invented), citations reuse the M6-A/M6-B [resolveCitations] render path (one
 * render, no second source of truth). `evidenceRefs` is the surfaced set; a
 * ghost id there renders the [EVIDENCE_NOT_SOURCED] placeholder.
 */
internal fun resolveExerciseReferences(
    exerciseId: ExerciseId,
    evidenceRefs: List<EvidenceId>,
    library: ExerciseLibraryResolver,
    resolver: EvidenceResolver,
): ResolvedReferences {
    val entry = library.resolveExercise(exerciseId)
    val citations = resolveCitations(evidenceRefs, resolver)
    return ResolvedReferences(
        exerciseId = exerciseId,
        videoUrl = entry?.videoUrl,
        howToStepsRu = entry?.howToStepsRu ?: emptyList(),
        imageRefs = entry?.imageRefs ?: emptyList(),
        citations = citations,
        equipment = entry?.equipment,
        evidenceLevels = citations.mapNotNull { it.evidenceLevel }.distinct(),
    )
}

/**
 * The always-visible header line for the (M9-B) collapsed card: exercise name +
 * the card title + a numeric count of the references behind it (media +
 * citations). Pure over [ResolvedReferences] so a JVM test can pin the card is
 * "present" on every exercise even when collapsed — same inputs → same header
 * (rendering determinism). The count is a plain tally, not a claim; it only
 * signals "there are N things to expand" so the collapsed chip stays informative.
 */
internal fun referencesHeaderLine(name: String, refs: ResolvedReferences): String {
    val mediaCount =
        (if (!refs.videoUrl.isNullOrBlank()) 1 else 0) +
            (if (refs.howToStepsRu.isNotEmpty()) 1 else 0) +
            refs.imageRefs.count { it.isNotBlank() }
    val count = mediaCount + refs.citations.size
    return "$name — ${ReferencesCardStrings.TITLE} ($count)"
}

/**
 * The authored strings the references card renders. Gathered as one list
 * ([all]) so a JVM test can snapshot them against the banned medical-claim
 * phrase list (mirrors [TodayStrings] / [EvidenceSourcesStrings]). Support
 * framing only: no diagnosis, no treatment claim. The verbatim catalog citation
 * ROWS are deliberately NOT in [all] — they legitimately carry study vocabulary
 * inside titles, so the crude-substring scan would false-positive on vetted
 * evidence (the M6-B design call); the citation-side claim guard is rendering
 * them verbatim.
 */
internal object ReferencesCardStrings {
    const val TITLE = "Материалы и источники"
    /**
     * M9 polish ([DRE-179](/DRE/issues/DRE-179)): one scoliosis-safe, phone-readable
     * "why this exercise is in your plan" line at the top of the expanded card.
     * Support framing only — it states the exercise is personalized ("из вашего
     * плана": the plan is curve-aware via the safety gate / conditionFlags) and
     * points at the materials/sources below, WITHOUT asserting a condition-
     * specific benefit. "Good for your curve" / "safe for scoliosis" would be a
     * condition claim the Safety Reviewer owns; the evidence section is the
     * sourced basis (EvidenceLinked), this line only invites the user to it.
     * No banned diagnostic/treatment substring (verified by the [all] scan).
     */
    const val WHY = "Это упражнение — из вашего плана. Ниже — как выполнять и материалы."
    const val HOW_TO = "Как выполнять"
    const val VIDEO = "Смотреть видео"
    const val IMAGE = "Открыть схему"
    const val EVIDENCE = "Источники"
    /** Transparent about the seed state — not a claim, just "not populated yet". */
    const val MEDIA_PENDING = "Видео, пошаговая инструкция и схемы будут добавлены."
    /** M9-B: the collapsed-card toggle affordance on the always-visible header. */
    const val SHOW = "Показать"
    const val HIDE = "Скрыть"

    val all: List<String> = listOf(TITLE, WHY, HOW_TO, VIDEO, IMAGE, EVIDENCE, MEDIA_PENDING, SHOW, HIDE)
}

/**
 * M8-A/M9-B: the references card. One block per exercise — how-to steps, video,
 * schematic images, and evidence citations — with **0 naked links**: every URL
 * sits behind a labeled [OutlinedButton], never raw URL text. Pure render of
 * [resolveExerciseReferences] (no logic in the tree); Android I/O only at the
 * edge ([loadExerciseLibrary] / [loadEvidenceResolver] at the root).
 *
 * M9-B ([DRE-117](/DRE/issues/DRE-117)): the card is **collapsible** — collapsed
 * by default, expanded on tapping the header (founder review #1: "схлопывать
 * во что-то более читаемое на что можно перейти"). A workout list therefore
 * stays dense: each exercise shows one tappable header line (name + title + a
 * reference count), and the how-to / media / citations body unfolds on demand.
 * The resolved data is unchanged by the collapse — the card only ever displays
 * already-resolved data, never mutates a plan or claims (rendering determinism).
 *
 * A card with no media and no citations renders nothing (defensive — in practice
 * every surfaced exercise carries evidence per DRE-6). Exercises without any
 * media show the evidence + the transparent [ReferencesCardStrings.MEDIA_PENDING]
 * note when expanded — so the deliverable ("a card on every exercise") holds
 * whether or not media is sourced yet.
 */
@Composable
internal fun ReferencesCard(name: String, refs: ResolvedReferences, modifier: Modifier = Modifier) {
    if (!refs.hasMedia && refs.citations.isEmpty()) return
    val context = LocalContext.current
    // M9-B: collapsed by default. Keyed to the exercise id so each card expands
    // independently and survives recomposition of the session list.
    var expanded by remember(refs.exerciseId) { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Always-visible, tappable header. Density win: the whole reference
            // body sits behind this one line, not expanded inline per exercise.
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(referencesHeaderLine(name, refs), fontWeight = FontWeight.SemiBold)
                Text(
                    if (expanded) ReferencesCardStrings.HIDE else ReferencesCardStrings.SHOW,
                    fontWeight = FontWeight.Light,
                )
            }
            if (expanded) {
                // M9 polish (DRE-179): the one-line scoliosis-safe "why this is in
                // your plan" intro — support framing, points at the detail below,
                // never a condition claim. First thing read on expand so the
                // how-to / media / evidence that follows has a purpose line.
                Text(ReferencesCardStrings.WHY, fontWeight = FontWeight.Light)
                if (refs.howToStepsRu.isNotEmpty()) {
                    Text(ReferencesCardStrings.HOW_TO, fontWeight = FontWeight.Medium)
                    refs.howToStepsRu.forEachIndexed { i, step -> Text("${i + 1}. $step") }
                }
                // 0 naked links: video + each image sit behind a labeled button.
                refs.videoUrl?.takeUnless { it.isBlank() }?.let { url ->
                    OutlinedButton(onClick = { openUrl(context, url) }, modifier = Modifier.fillMaxWidth()) {
                        Text(ReferencesCardStrings.VIDEO)
                    }
                }
                refs.imageRefs.filter { it.isNotBlank() }.forEach { ref ->
                    OutlinedButton(onClick = { openUrl(context, ref) }, modifier = Modifier.fillMaxWidth()) {
                        Text(ReferencesCardStrings.IMAGE)
                    }
                }
                if (refs.citations.isNotEmpty()) {
                    Text(ReferencesCardStrings.EVIDENCE, fontWeight = FontWeight.Medium)
                    refs.citations.forEach { c -> EvidenceCitationRender(c) }
                }
                if (!refs.hasMedia) {
                    // Transparent about the seed state — never silent, never a fabricated link.
                    Text(ReferencesCardStrings.MEDIA_PENDING, fontWeight = FontWeight.Light)
                }
            }
        }
    }
}

/**
 * The shared render of a single resolved citation (M6-B/M8-A). One row:
 * resolved → author/year + evidenceLevel + keyFinding; ghost → the
 * [EVIDENCE_NOT_SOURCED] placeholder. Used by [ReferencesCard]; the
 * plan/block/nutrition surfaces may adopt it in a follow-up (kept here to keep
 * the M8-A diff to the exercise surface — YAGNI on the others until asked).
 * Never a raw id; never an invented citation.
 */
@Composable
internal fun EvidenceCitationRender(citation: ResolvedCitation) {
    Text("• ${citation.line}", fontWeight = FontWeight.Light)
}

/**
 * Open [url] in the system viewer (video/image refs). A bare ACTION_VIEW on the
 * exact catalog URL — no in-app browser, no URL rewriting. A catalog URL the
 * Evidence Analyst vetted is trusted at the edge; a ref with no installed
 * handler (or a malformed scheme) is swallowed rather than crashing the workout
 * screen — the labeled button still shows, the user is not blocked, and the
 * session is never killed over a link. Mirrors the Intent pattern in
 * [shareExportFile] (ClientExportShare.kt); reuses platform Intent, no new dep.
 */
internal fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
