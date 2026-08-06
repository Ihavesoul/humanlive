package dreamteam.app

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Redesign v2 ([DRE-211](/DRE/issues/DRE-211)) — pins the [cardImageUrl] transform
 * that adapts the media catalog's LINK-OUT URL (DRE-207) into a Coil-loadable
 * DIRECT image URL. The catalog carries Wikimedia `File:` description pages and
 * Flickr photo pages (both serve HTML, not image bytes — verified), so the card
 * must NOT hand a raw page URL to AsyncImage (silent failure). This test pins:
 *
 * 1. A Wikimedia `File:` page becomes the `Special:FilePath` direct-image endpoint
 *    (which 302-redirects to the real file on upload.wikimedia.org).
 * 2. A direct `upload.wikimedia.org` URL passes through unchanged.
 * 3. A Flickr photo page (HTML) and any other link-out page yield `null` → the card
 *    keeps its branded placeholder (the link-out button still opens the page).
 * 4. null/blank input yields `null`.
 * 5. Integration: across the WHOLE bundled catalog, every sourced image URL is
 *    either transformed to a renderable URL or correctly `null` — none is a raw
 *    HTML page URL handed to the image loader.
 */
class CardImageUrlTest {

    @Test
    fun `a Wikimedia File page becomes the direct-image endpoint`() {
        val out = cardImageUrl("https://commons.wikimedia.org/wiki/File:ChildsPose3.jpg")
        out shouldBe "https://commons.wikimedia.org/wiki/Special:FilePath/ChildsPose3.jpg?width=640"
    }

    @Test
    fun `a direct upload wikimedia url passes through unchanged`() {
        val direct = "https://upload.wikimedia.org/wikipedia/commons/3/3e/ChildsPose3.jpg"
        cardImageUrl(direct) shouldBe direct
    }

    @Test
    fun `a Flickr photo page yields null - not handed to the image loader as HTML`() {
        cardImageUrl("https://www.flickr.com/photos/121183998@N08/42990005625") shouldBe null
    }

    @Test
    fun `null and blank input yield null`() {
        cardImageUrl(null) shouldBe null
        cardImageUrl("") shouldBe null
        cardImageUrl("   ") shouldBe null
    }

    @Test
    fun `every sourced catalog image URL is renderable or null - never a raw HTML page to the loader`() {
        val raw = CardImageUrlTest::class.java.getResourceAsStream("/exercise_media.json")!!
            .use { it.readBytes().decodeToString() }
        val resolver = ExerciseMediaResolver.fromJson(raw)
        val ids = dreamteam.domain.training.BaselineProgram.exerciseIds
        // Every BaselineProgram exercise: resolve its media, then its load URL.
        // A URL that loads must NOT be an HTML page (no /wiki/File: , no flickr photo page).
        ids.forEach { id ->
            val loadUrl = cardImageUrl(resolveExerciseMedia(id, resolver).cardImage?.url)
            if (loadUrl != null) {
                // A renderable URL is either the Special:FilePath endpoint or a direct
                // upload URL — never a /wiki/File: page or a flickr.com/photos page.
                ("commons.wikimedia.org/wiki/File:" in loadUrl) shouldBe false
                ("commons.wikimedia.org/wiki/Special:FilePath/" in loadUrl ||
                    loadUrl.startsWith("https://upload.wikimedia.org/")) shouldBe true
            }
        }
    }
}
