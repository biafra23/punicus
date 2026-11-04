package eth.serotine.ui

import androidx.compose.ui.ImageComposeScene
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The directory [renderEach] writes each preview into as a PNG, from the `screenshots`
 * project property (`-Pscreenshots=<dir>`, ui/build.gradle.kts); null, and nothing is
 * written, otherwise. CI points it at `docs/screenshots`, the screenshots a PR that changes
 * the UI carries (CLAUDE.md, "Pull requests and code review"); [ScreenshotIndex] writes the
 * overview pages beside them.
 */
internal val screenshotDir: File? = System.getProperty("serotine.screenshots")?.let(::File)

/** Where a [preview] of [screen] is written under [screenshotDir]: `<slug>/<name>.png`. */
internal fun screenshotPath(screen: Screen, preview: ScreenPreview): String = "${screen.slug}/${preview.name}.png"

/**
 * Composes and draws each preview of [screen] headless, at a phone's size: a preview that throws
 * fails the test, so a broken one shows up in the build rather than in Android Studio. With a
 * [screenshotDir], each is also saved as `<slug>/<name>.png` there, leaving the folder untouched
 * where nothing changed: a file that draws the same pixels as the fresh render is kept as it is
 * (CI's optimizer rewrote its bytes, and `git status` stays quiet), one that differs is replaced,
 * and a PNG of a state no longer in the list is removed. Anything that is not a PNG is left
 * alone, as [ScreenshotIndex.write] leaves it. Two previews with one name would overwrite each
 * other, so that fails here too, not only in `ScreensTest`.
 */
internal fun renderEach(screen: Screen) {
    assertTrue(screen.previews.isNotEmpty(), "${screen.slug}: no previews to render")
    val names = screen.previews.map { it.name }
    assertEquals(names.distinct(), names, "${screen.slug}: two previews share a name")
    val folder = screenshotDir?.let { File(it, screen.slug) }
    folder?.let { dir ->
        dir.mkdirs()
        val expected = names.map { "$it.png" }.toSet()
        dir.listFiles { file -> file.isFile && file.extension == "png" && file.name !in expected }
            ?.forEach { it.delete() }
    }
    screen.previews.forEach { preview ->
        val scene = ImageComposeScene(width = 412, height = 892) { preview.content() }
        try {
            // Closed here, not left to the cleaner: native memory stays bounded across every preview.
            scene.render().use { image ->
                folder?.let { dir ->
                    val png = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) {
                        "${screenshotPath(screen, preview)}: no PNG"
                    }
                    val bytes = png.use { it.bytes }
                    val file = File(dir, "${preview.name}.png")
                    if (!file.exists() || !samePixels(file.readBytes(), bytes)) file.writeBytes(bytes)
                }
            }
        } finally {
            scene.close()
        }
    }
}

/**
 * Whether two PNGs draw the same picture, whatever their bytes: CI's optimizer rewrites a file
 * without changing a pixel, and a render that changed nothing must not undo that. A file that does
 * not decode counts as different, so it is written afresh.
 */
private fun samePixels(a: ByteArray, b: ByteArray): Boolean {
    val x = ImageIO.read(ByteArrayInputStream(a)) ?: return false
    val y = ImageIO.read(ByteArrayInputStream(b)) ?: return false
    if (x.width != y.width || x.height != y.height) return false
    val xs = x.getRGB(0, 0, x.width, x.height, null, 0, x.width)
    val ys = y.getRGB(0, 0, y.width, y.height, null, 0, y.width)
    return xs.contentEquals(ys)
}
