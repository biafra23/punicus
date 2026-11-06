package eth.serotine.ui

/**
 * A screen in the screenshot map ([Screens]).
 *
 * [slug] is the screen's folder under `docs/screenshots/` (lowercase words joined by hyphens; a
 * dialog's ends in `-dialog`). It names the screen, not its class: a class rename never moves a
 * screenshot, and the slug changes only when the owner decides the screen is a different one.
 * [title] names the screen in the index pages. [previews] are its states, each named for its
 * file ([ScreenPreview]).
 */
internal class Screen(val slug: String, val title: String, val previews: List<ScreenPreview>)
