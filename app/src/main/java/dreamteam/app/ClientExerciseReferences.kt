package dreamteam.app

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dreamteam.app.ui.Spacing
import dreamteam.app.ui.AppCardShape
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
 * Adapt the catalog's image URL into a Coil-loadable DIRECT image URL, or `null`
 * when it cannot be rendered inline. Pure; a JVM test pins the transform.
 *
 * **Why this exists.** The media catalog ([DRE-207](/DRE/issues/DRE-207)) carries
 * LINK-OUT URLs — Wikimedia `File:` description pages and Flickr photo pages —
 * which serve HTML, not image bytes (verified: `content-type: text/html`). Loading
 * such a URL in Coil silently fails. For inline rendering, Wikimedia's documented
 * media endpoint `Special:FilePath` redirects (302→301→200) to the actual image
 * file, and `?width=` caps the download to a card-sized thumbnail; Coil follows
 * the redirect chain. A direct image URL (e.g. `upload.wikimedia.org`) passes
 * through unchanged. Flickr photo pages and other link-out pages have no stable
 * direct-image transform and yield `null` — the card then keeps its branded
 * placeholder and the link-out button still opens the source page. Bundled CC0
 * art ([DRE-246](/DRE/issues/DRE-246)) uses an `asset://` pseudo-scheme that maps
 * to `file:///android_asset/` — no network, served by Coil's AssetUriFetcher.
 *
 * This is a presentation-layer adaptation of the catalog's link-out URL, NOT data
 * authorship: if the Evidence Analyst later sources direct image URLs
 * (`upload.wikimedia.org/…`) the Wikimedia branch becomes a no-op passthrough.
 */
internal fun cardImageUrl(rawUrl: String?): String? {
    if (rawUrl.isNullOrBlank()) return null
    // Bundled CC0 art ([DRE-246](/DRE/issues/DRE-246)): original exercise diagrams
    // shipped in `assets/exercise_art/`. The `asset://` pseudo-scheme keeps the data
    // platform-agnostic; here it becomes the Coil-loadable `file:///android_asset/` URI
    // that Coil's built-in AssetUriFetcher serves with no network round-trip (offline-first).
    if (rawUrl.startsWith("asset://")) {
        val path = rawUrl.removePrefix("asset://")
        return "file:///android_asset/$path"
    }
    // Wikimedia `File:` description page → the documented direct-image endpoint.
    if ("commons.wikimedia.org/wiki/File:" in rawUrl) {
        val name = rawUrl.substringAfter("commons.wikimedia.org/wiki/File:")
        return "https://commons.wikimedia.org/wiki/Special:FilePath/$name?width=640"
    }
    // A direct image URL (upload.wikimedia.org, or any future direct ref) loads as-is.
    if (rawUrl.startsWith("https://upload.wikimedia.org/")) return rawUrl
    // Flickr photo pages / other link-out pages serve HTML → not inline-renderable.
    return null
}

/**
 * Redesign v2 ([DRE-211](/DRE/issues/DRE-211)): the reserved 16:9 image area at
 * the top of an exercise card (founder: «картинку в карточке упражнения»). When
 * the media library ([DRE-207](/DRE/issues/DRE-207)) sourced a license-clean
 * direct image for this exercise, it renders via Coil [AsyncImage] (cropped to
 * the app card shape); otherwise a calm branded block with the exercise name —
 * never an empty box, never a fabricated image. The image is remote; Coil's
 * disk cache makes a once-loaded image available offline, and a cache miss /
 * loading state degrades gracefully to the placeholder colour while it loads.
 *
 * [name] is the image's contentDescription (accessibility) and the placeholder
 * label. Pure render of [ResolvedExerciseMedia]; the load itself is Coil's job.
 */
@Composable
internal fun ExerciseMediaSlot(name: String, media: ResolvedExerciseMedia, modifier: Modifier = Modifier) {
    val loadUrl = cardImageUrl(media.cardImage?.url)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Spacing.exerciseMediaHeight)
            .clip(AppCardShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        // The exercise name is always rendered as the fallback label, so the slot
        // never reads as an empty box: it shows while the image loads AND on a
        // cache-miss / network error (offline-first — a remote image is the only
        // network call in the app). When the image paints it covers the label.
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (loadUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(loadUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * M9 polish ([DRE-180](/DRE/issues/DRE-180)): the shared, readable + tappable
 * render of ONE resolved citation — a phone-density Card instead of a dense
 * markdown bullet. Always visible: the source [ResolvedCitation.title]
 * (SemiBold) + the verbatim `evidenceLevel` label + the one-line
 * [ResolvedCitation.keyFinding] ("what it shows"). Tap the row → the detail
 * folds out ([ResolvedCitation.design] / [application] / [limitations]) and, if
 * the catalog carries a [ResolvedCitation.sourceUrl], a labeled "open source"
 * button (0 naked links — the URL sits behind the button, never raw text).
 *
 * A ghost/placeholder id renders the [EVIDENCE_NOT_SOURCED] transparency line
 * only — no card, no tap target (nothing is behind it; never an invented
 * citation). Shared by SessionCard (per-exercise, inline) and
 * [EvidenceSourcesScreen] (the full catalog) so there is ONE citation render
 * path — no drift between how a citation appears in-plan vs on the sources
 * screen. Pure render of [resolveCitations] output; the verbatim catalog values
 * are not interpreted (no appraisal; the Evidence & Research Analyst owns the
 * catalog). Expand state is per-citation, keyed to [ResolvedCitation.id].
 */
@Composable
internal fun EvidenceCitationCard(citation: ResolvedCitation, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (!citation.resolved) {
        Text(citation.line, fontWeight = FontWeight.Light)
        return
    }
    var expanded by remember(citation.id) { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.card), verticalArrangement = Arrangement.spacedBy(Spacing.tightGap)) {
            Column(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalArrangement = Arrangement.spacedBy(Spacing.tightGap),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(citation.title.orEmpty(), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(if (expanded) EvidenceCitationStrings.HIDE else EvidenceCitationStrings.SHOW, fontWeight = FontWeight.Light)
                }
                Text("уровень: ${citation.evidenceLevel}", fontWeight = FontWeight.Light)
                Text(citation.keyFinding.orEmpty(), fontWeight = FontWeight.Light)
            }
            if (expanded) {
                citation.design?.let { Text("${EvidenceCitationStrings.DESIGN}: $it", fontWeight = FontWeight.Light) }
                citation.application?.let { Text("${EvidenceCitationStrings.APPLICATION}: $it", fontWeight = FontWeight.Light) }
                citation.limitations?.let { Text("${EvidenceCitationStrings.LIMITATIONS}: $it", fontWeight = FontWeight.Light) }
                citation.sourceUrl?.let { url ->
                    OutlinedButton(onClick = { openUrl(context, url) }) {
                        Text(EvidenceCitationStrings.OPEN_SOURCE)
                    }
                }
            }
        }
    }
}

/**
 * The authored strings the shared [EvidenceCitationCard] renders. Gathered as
 * one list ([all]) so a JVM test can snapshot them against the banned
 * medical-claim phrase list (mirrors [ReferencesCardStrings] /
 * [EvidenceSourcesStrings]). Support/transparency framing only — labels for the
 * catalog's verbatim detail fields + an "open source" affordance; no diagnosis,
 * no treatment claim. The verbatim catalog DETAIL rows are deliberately NOT in
 * [all] (study vocabulary false-positives, the M6-B design call).
 */
internal object EvidenceCitationStrings {
    /** M9-B-style toggle affordance on the tappable citation row. */
    const val SHOW = "Показать"
    const val HIDE = "Скрыть"
    /** Labels for the catalog's verbatim detail fields (no appraisal added). */
    const val DESIGN = "Дизайн"
    const val APPLICATION = "Применение"
    const val LIMITATIONS = "Ограничения"
    /** 0 naked links: the source URL sits behind this labeled button. */
    const val OPEN_SOURCE = "Открыть источник"

    val all: List<String> = listOf(SHOW, HIDE, DESIGN, APPLICATION, LIMITATIONS, OPEN_SOURCE)
}

/**
 * Authored strings for the inline exercise-detail block (formerly ReferencesCard).
 * Gathered as one list ([all]) so a JVM test can snapshot them against the banned
 * medical-claim phrase list. Support/transparency framing only.
 */
internal object ReferencesCardStrings {
    const val TITLE = "Материалы и источники"
    const val IMAGE_CREDIT = "Источник изображения"
    const val WHY = "Почему это упражнение"
    const val HOW_TO = "Как выполнять"
    const val VIDEO = "Видео"
    const val IMAGE = "Фото"
    const val EVIDENCE = "Источники"
    const val SHOW = "Показать"
    const val HIDE = "Скрыть"
    const val MEDIA_PENDING = "Медиа-материалы ожидаются"

    val all: List<String> = listOf(TITLE, IMAGE_CREDIT, WHY, HOW_TO, VIDEO, IMAGE, EVIDENCE, SHOW, HIDE, MEDIA_PENDING)
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
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}
