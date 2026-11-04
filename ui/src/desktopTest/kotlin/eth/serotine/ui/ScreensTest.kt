package eth.serotine.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The screen map's invariants: a path per screen and state that is stable, unique and a file name. */
class ScreensTest {

    private val pathWord = Regex("[a-z0-9]+(-[a-z0-9]+)*")

    @Test
    fun everySlugIsUniqueAndAFolderName() {
        val slugs = Screens.all.map { it.slug }
        assertEquals(slugs.distinct(), slugs, "two screens share a slug")
        slugs.forEach { assertTrue(pathWord.matches(it), "slug '$it' is not lowercase words joined by hyphens") }
    }

    @Test
    fun everyStateIsUniqueWithinItsScreenAndAFileName() {
        Screens.all.forEach { screen ->
            val names = screen.previews.map { it.name }
            assertTrue(names.isNotEmpty(), "${screen.slug} has no previews")
            assertEquals(names.distinct(), names, "${screen.slug}: two previews share a name")
            names.forEach {
                assertTrue(pathWord.matches(it), "${screen.slug}/$it is not lowercase words joined by hyphens")
            }
        }
    }

    @Test
    fun everyPreviewsListIsRegisteredOnce() {
        val lists = Screens.all.map { it.previews }
        assertEquals(lists.distinct().size, lists.size, "a previews list is registered under two screens")
        // Gradle runs the tests from the module directory; a previews file is `<Feature>ScreenPreviews.kt`
        // or `<Feature>DialogPreviews.kt` (.claude/rules/ui.md, "Every screen has previews").
        val sources = File("src/commonMain/kotlin")
        assertTrue(sources.isDirectory, "run from the ui module: ${sources.absolutePath} is not a directory")
        val previewsFiles = sources.walkTopDown().filter { it.isFile && it.name.endsWith("Previews.kt") }.count()
        assertEquals(
            previewsFiles,
            Screens.all.size,
            "$previewsFiles previews files but ${Screens.all.size} screens in Screens.kt: register the new screen",
        )
    }

    @Test
    fun theOrderIsBySlug() {
        val slugs = Screens.all.map { it.slug }
        assertEquals(slugs.sorted(), slugs, "Screens.all is not sorted by slug")
    }
}
