package eth.serotine.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The overview pages name every screenshot, and are written beside the screenshots when asked. */
class ScreenshotIndexTest {

    @Test
    fun bothPagesNameEveryScreenAndState() {
        val markdown = ScreenshotIndex.markdown(Screens.all)
        val html = ScreenshotIndex.html(Screens.all)
        Screens.all.forEach { screen ->
            assertTrue(markdown.contains("## ${screen.title}"), "README lacks ${screen.slug}")
            assertTrue(html.contains("<section id=\"${screen.slug}\">"), "index.html lacks ${screen.slug}")
            screen.previews.forEach { preview ->
                val path = screenshotPath(screen, preview)
                assertTrue(markdown.contains("src=\"$path\""), "README lacks $path")
                assertTrue(html.contains("src=\"$path\""), "index.html lacks $path")
            }
        }
    }

    @Test
    fun writingPrunesOnlyTheImageFoldersOfScreensNoLongerInTheMap() {
        val dir = Files.createTempDirectory("screenshots").toFile()
        try {
            val stale = File(dir, "gone-screen").apply { mkdirs(); File(this, "state.png").writeBytes(byteArrayOf()) }
            val notOurs = File(dir, "notes").apply { mkdirs(); File(this, "todo.txt").writeText("keep") }
            val current = File(dir, Screens.send.slug).apply { mkdirs(); File(this, "x.png").writeBytes(byteArrayOf()) }
            ScreenshotIndex.write(dir, Screens.all)
            assertFalse(stale.exists(), "a PNG-only folder of an unmapped screen stays")
            assertTrue(notOurs.exists(), "a folder that is not screenshots was deleted")
            assertTrue(current.exists(), "a mapped screen's folder was deleted")
            assertEquals(ScreenshotIndex.markdown(Screens.all), File(dir, "README.md").readText())
            assertEquals(ScreenshotIndex.html(Screens.all), File(dir, "index.html").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** With `-Pscreenshots=<dir>` the pages land beside the images the previews tests write there. */
    @Test
    fun writtenBesideTheScreenshotsWhenAsked() {
        screenshotDir?.let { ScreenshotIndex.write(it, Screens.all) }
    }
}
