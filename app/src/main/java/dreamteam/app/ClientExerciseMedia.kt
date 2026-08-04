package dreamteam.app

import android.content.res.AssetManager
import dreamteam.domain.ExerciseId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Redesign v2 ([DRE-210](/DRE/issues/DRE-210)) — the data backbone for the
 * exercise-card image (founder review DRE-205: «картинку мы должны показывать в
 * карточке упражнения»). The richer per-exercise media catalog
 * `data/exercise_media.json` (Evidence Analyst output, [DRE-207](/DRE/issues/DRE-207))
 * carries the structured image the 16:9 card slot needs: a license-clean direct
 * image URL **plus its license + credit**. The license note in that file is
 * explicit: «If the UI ever renders images inline, surface {url,license,credit}
 * per ref». This resolver is the single place that surfaces exactly that, so the
 * Compose track (DRE-211) renders an image without each call site re-deciding
 * what a card image is.
 *
 * **Division of labour (DRE-210 vs DRE-211).** This is the Founding Engineer's
 * data track: a pure id→media resolver + the one Android-I/O edge. It is NOT a
 * Compose screen and does not touch the UI files the Mobile UI Engineer owns; it
 * hands resolved data off for DRE-211 to render. Mirrors the proven two-layer
 * shape of [ExerciseLibraryResolver] / [loadExerciseLibrary] (the references
 * card's data source): pure + JVM-testable, with [AssetManager] only at the edge.
 *
 * **Source of truth = data, never invented.** A missing id resolves to `null`;
 * an image not yet `sourced` (status `media_pending`) resolves to `null` too —
 * the card then keeps its placeholder, never a fabricated image or a bare URL
 * without attribution (the license note is honoured by construction). The
 * verbatim license/credit strings are surfaced as-is: attribution is an
 * Evidence Analyst concern, not app-side formatting (same stance as evidence
 * level — avoid drift).
 *
 * No new dependency: pure kotlinx.serialization, already on the classpath. The
 * catalog ships in the APK via the same repo-root `data/` assets `srcDir`
 * [loadExerciseLibrary] reads, so this resolver and the references library never
 * drift (one copy of the data — ADR 0001).
 */

/**
 * The snake_case load shape of one `data/exercise_media.json` `image` object.
 * Read-only seed; fields are nullable because a `media_pending` entry carries
 * null url/source_page/license/credit + a human note (a gap the Evidence
 * Analyst is commissioning CC0 art for), never a fabricated value.
 */
@Serializable
internal data class ExerciseImageSeed(
    val url: String? = null,
    @SerialName("source_page") val sourcePage: String? = null,
    val license: String? = null,
    val credit: String? = null,
    /** `sourced` (ready) or `media_pending` (no license-clean image yet). */
    val status: String? = null,
    val note: String? = null,
)

/**
 * The snake_case load shape of one `data/exercise_media.json` entry. Read-only
 * seed decoded with `ignoreUnknownKeys` (via [evidenceJson]); the catalog also
 * carries a `video` block, but video is already surfaced by [ExerciseLibraryResolver]
 * off `data/exercises.json` — decoding it here would be a second source. This
 * resolver owns the card IMAGE + the readable summary, the two things the
 * references library does not carry.
 */
@Serializable
internal data class ExerciseMediaEntry(
    @SerialName("exercise_id") val exerciseId: ExerciseId,
    @SerialName("name_ru") val nameRu: String,
    @SerialName("ai_summary_ru") val aiSummaryRu: String? = null,
    val image: ExerciseImageSeed? = null,
)

/**
 * A pure id→[ExerciseMediaEntry] view over a decoded catalog. Built from bytes
 * (no Android dependency), so a JVM test pins the guarantees without a device.
 * Mirrors [ExerciseLibraryResolver]. Never invents media: a missing id resolves
 * to `null` and the caller keeps its placeholder (0 fabricated images).
 */
internal class ExerciseMediaResolver(library: List<ExerciseMediaEntry>) {
    private val byId: Map<ExerciseId, ExerciseMediaEntry> = library.associateBy { it.exerciseId }

    /** Resolve an exercise id to its media entry, or `null` if the catalog has none. */
    fun resolve(exerciseId: ExerciseId): ExerciseMediaEntry? = byId[exerciseId]

    companion object {
        /** Decode + build the resolver from raw catalog JSON (the exact client decode). */
        fun fromJson(rawJson: String): ExerciseMediaResolver =
            ExerciseMediaResolver(evidenceJson.decodeFromString<ExerciseMediaCatalog>(rawJson).exercises)
    }
}

/**
 * The snake_case load shape of the `data/exercise_media.json` wrapper — the
 * catalog is an object (`schema_version`/`source_issue`/`exercises`), not a bare
 * array. Only the `exercises` list is consumed; the provenance fields
 * (`method`/`license_note`) are skipped by `ignoreUnknownKeys` and live on in the
 * data file for audit.
 */
@Serializable
internal data class ExerciseMediaCatalog(val exercises: List<ExerciseMediaEntry> = emptyList())

/**
 * The single Android-I/O point: decode the bundled `exercise_media.json` asset
 * into a pure [ExerciseMediaResolver]. Offline-first — no network; the catalog
 * ships in the APK (the same `data/` assets srcDir [loadExerciseLibrary] reads,
 * one copy of the data, no drift).
 */
internal fun loadExerciseMedia(assets: AssetManager): ExerciseMediaResolver =
    ExerciseMediaResolver.fromJson(assets.open("exercise_media.json").use { it.readBytes().decodeToString() })

/**
 * A license-clean card image: the direct image URL **plus its attribution**.
 * [license]/[credit] are surfaced verbatim so an inline render can credit the
 * source (the [exercise_media.json] license note). Never constructed from a
 * `media_pending` entry — those have no URL, so the card keeps its placeholder.
 */
internal data class CardImage(
    val url: String,
    val license: String?,
    val credit: String?,
)

/**
 * What the card slot renders for one exercise: the license-clean [cardImage]
 * (null until the catalog sources one) and the readable RU [summary] (null when
 * the catalog carries none). Pure over [ExerciseMediaEntry]; only already-sourced
 * data, never a fabricated image or summary. [hasCardImage] is the single
 * render-time gate the UI checks before it attempts to load an image.
 */
internal data class ResolvedExerciseMedia(
    val exerciseId: ExerciseId,
    val cardImage: CardImage?,
    val summary: String?,
) {
    val hasCardImage: Boolean get() = cardImage != null
}

/**
 * Build the card-media view for one exercise. Pure (no Android, no I/O): the
 * image resolves only when the catalog entry is `sourced` with a non-blank URL
 * (a `media_pending` entry yields `null` — the card keeps its placeholder, never
 * a bare/unattributed image); the summary is surfaced verbatim when present.
 */
internal fun resolveExerciseMedia(exerciseId: ExerciseId, resolver: ExerciseMediaResolver): ResolvedExerciseMedia {
    val entry = resolver.resolve(exerciseId) ?: return ResolvedExerciseMedia(exerciseId, null, null)
    val cardImage = entry.image
        ?.takeIf { it.status == "sourced" && !it.url.isNullOrBlank() }
        ?.let { CardImage(it.url!!, it.license, it.credit) }
    val summary = entry.aiSummaryRu?.takeUnless { it.isBlank() }
    return ResolvedExerciseMedia(exerciseId, cardImage, summary)
}
