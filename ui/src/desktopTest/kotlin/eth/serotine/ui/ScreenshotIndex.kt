package eth.serotine.ui

import java.io.File

/**
 * The two overview pages of `docs/screenshots/`, both pure functions of the screen map, so what CI
 * commits beside the images never drifts from them:
 *
 * - `README.md`, for GitHub and the other git hosts, which render markdown in place but show HTML
 *   as source: a table of every screen, then each screen's states as small images.
 * - `index.html`, for a browser on a checkout: every screen and state on one page, no scripts.
 *
 * [write] also removes a folder under the directory that belongs to no screen in the map and holds
 * only PNGs, so a screen taken out of the map leaves no stale images behind; anything else in the
 * directory is left alone.
 */
internal object ScreenshotIndex {
    private const val THUMB_WIDTH = 206

    private val intro = """
        Every screen of Serotine in every state its previews draw, rendered headless from the
        previews (`ui/src/commonMain/kotlin/eth/serotine/ui/**/<Feature>ScreenPreviews.kt`). CI
        renders them on every pull request and commits the ones the PR changes, so the images
        come from one environment and a diff of this folder shows only real changes. The same
        screen is always at the same path, `<screen>/<state>.png`; the map of screens is
        `ui/src/desktopTest/kotlin/eth/serotine/ui/Screens.kt`, and a state's name sits beside its
        preview in the screen's previews list. A state's dark twin ends in `-dark`.
    """.trimIndent().lines().joinToString(" ") { it.trim() }

    /** How to keep the images out of a clone, and the two ways to purge the folder (owner rulings, 2026-11-04). */
    private val weight = listOf(
        """
            Clone without the images: `git clone --filter=blob:none https://github.com/biafra23/serotine.git`
            fetches a file only when a command needs it, so the history of this folder costs nothing until it
            is looked at. `git sparse-checkout set --no-cone '/*' '!/docs/screenshots/'` leaves it out of the
            working tree as well.
        """,
        """
            If the folder grows too much, there are two levers, both the owner's. Deleting the folder stops
            the growth and keeps every past image in the history. Rewriting the history with
            `git filter-repo --path docs/screenshots --invert-paths` drops them for good, but changes the
            SHA of every commit since the first screenshot: tags, links to commits and every existing clone
            must follow. CI fails a pull request once the folder, or one image, passes its size budget
            (`scripts/screenshots.py`).
        """,
    ).map { paragraph -> paragraph.trimIndent().lines().joinToString(" ") { it.trim() } }

    /** The README, as markdown. */
    fun markdown(screens: List<Screen>): String = buildString {
        appendLine("# Screenshots")
        appendLine()
        appendLine(intro)
        appendLine()
        appendLine("[index.html](index.html) shows them all on one page from a checkout.")
        appendLine()
        weight.forEach {
            appendLine(it)
            appendLine()
        }
        appendLine("| Screen | Folder | States |")
        appendLine("|---|---|---|")
        screens.forEach { appendLine("| [${it.title}](#${it.slug}) | `${it.slug}/` | ${it.previews.size} |") }
        screens.forEach { screen ->
            appendLine()
            appendLine("<a name=\"${screen.slug}\"></a>")
            appendLine("## ${screen.title}")
            appendLine()
            val states = screen.previews.joinToString { "`${it.name}`" }
            appendLine("`${screen.slug}/`, ${screen.previews.size} states: $states")
            appendLine()
            screen.previews.forEach { preview ->
                val path = screenshotPath(screen, preview)
                appendLine("<a href=\"$path\"><img src=\"$path\" width=\"$THUMB_WIDTH\" alt=\"${preview.name}\"></a>")
            }
        }
    }

    /** The one-page overview, as HTML. */
    fun html(screens: List<Screen>): String = buildString {
        appendLine("<!DOCTYPE html>")
        appendLine("<html lang=\"en\">")
        appendLine("<head>")
        appendLine("<meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        appendLine("<title>Serotine screenshots</title>")
        appendLine("<style>")
        appendLine("body { font-family: system-ui, sans-serif; margin: 1.5rem; color: #1a1a1a; background: #f4f4f6; }")
        appendLine("nav a { margin-right: .75rem; white-space: nowrap; }")
        appendLine("section { margin-top: 2.5rem; }")
        appendLine("h2 small { font-weight: normal; color: #666; font-size: .7em; }")
        appendLine(".shots { display: flex; flex-wrap: wrap; gap: .75rem; }")
        appendLine("figure { margin: 0; width: ${THUMB_WIDTH}px; }")
        appendLine("img { width: ${THUMB_WIDTH}px; height: auto; border: 1px solid #ccc; background: #fff; }")
        appendLine("figcaption { font: .75rem/1.3 monospace; text-align: center; word-break: break-word; }")
        appendLine("</style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("<h1>Serotine screenshots</h1>")
        (listOf(intro) + weight).forEach {
            appendLine("<p>${escape(it).replace(Regex("`([^`]+)`"), "<code>$1</code>")}</p>")
        }
        appendLine("<nav>" + screens.joinToString("") { "<a href=\"#${it.slug}\">${escape(it.title)}</a> " } + "</nav>")
        screens.forEach { screen ->
            appendLine("<section id=\"${screen.slug}\">")
            val count = "${screen.slug}/ · ${screen.previews.size} states"
            appendLine("<h2>${escape(screen.title)} <small>$count</small></h2>")
            appendLine("<div class=\"shots\">")
            screen.previews.forEach { preview ->
                val path = screenshotPath(screen, preview)
                append("<figure><a href=\"$path\"><img src=\"$path\" loading=\"lazy\" alt=\"${preview.name}\"></a>")
                appendLine("<figcaption>${preview.name}</figcaption></figure>")
            }
            appendLine("</div>")
            appendLine("</section>")
        }
        appendLine("</body>")
        appendLine("</html>")
    }

    /** Writes both pages into [dir] and prunes the PNG-only folders of screens no longer in [screens]. */
    fun write(dir: File, screens: List<Screen>) {
        dir.mkdirs()
        val slugs = screens.map { it.slug }.toSet()
        dir.listFiles { file -> file.isDirectory && file.name !in slugs }
            ?.filter { folder -> folder.listFiles().orEmpty().all { it.isFile && it.extension == "png" } }
            ?.forEach { it.deleteRecursively() }
        File(dir, "README.md").writeText(markdown(screens))
        File(dir, "index.html").writeText(html(screens))
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
