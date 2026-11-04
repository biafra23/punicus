package eth.serotine.ui

import androidx.compose.ui.ImageComposeScene
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
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
 * [screenshotDir], each is also saved as `<slug>/<name>.png` there, after the PNGs already in the
 * screen's folder are removed: a list that shrank leaves no stale state behind, and anything that
 * is not a PNG is left alone, as [ScreenshotIndex.write] leaves it. Two previews with one name
 * would overwrite each other, so that fails here too, not only in `ScreensTest`.
 */
internal fun renderEach(screen: Screen) {
    assertTrue(screen.previews.isNotEmpty(), "${screen.slug}: no previews to render")
    val names = screen.previews.map { it.name }
    assertEquals(names.distinct(), names, "${screen.slug}: two previews share a name")
    val folder = screenshotDir?.let { File(it, screen.slug) }
    folder?.let { dir ->
        dir.mkdirs()
        dir.listFiles { file -> file.isFile && file.extension == "png" }?.forEach { it.delete() }
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
                    png.use { File(dir, "${preview.name}.png").writeBytes(it.bytes) }
                }
            }
        } finally {
            scene.close()
        }
    }
}
