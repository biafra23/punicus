package eth.serotine.ui

import androidx.compose.runtime.Composable

/**
 * One preview of a screen, named for the screenshot it renders to.
 *
 * [name] is the file name under the screen's folder in `docs/screenshots/<screen>/<name>.png`
 * (lowercase words joined by hyphens; the dark twin of a state ends in `-dark`), so the same
 * state is at the same path in every commit, whatever its preview function is called. It is
 * unique within its screen, which `ScreensTest` checks. [content] is the preview itself, as
 * `@Preview` draws it in Android Studio and as the previews test renders it headless.
 */
internal class ScreenPreview(val name: String, val content: @Composable () -> Unit)
