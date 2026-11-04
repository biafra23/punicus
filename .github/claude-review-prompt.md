Review this pull request as an independent reviewer with no prior
context. Read CLAUDE.md first, then the files under .claude/rules/ it
names for the modules the diff touches (they also load by themselves as
you read matching files), and hold the diff against the repo's standing
rules, especially:
- Architecture: clean architecture with the dependency rule
  (:domain depends on nothing of ours; :ui and :data depend on
  :domain; the hosts' composition roots are the only places that
  wire :data into :ui) and MVVM for the UI (a screen is a pure
  function of a UiState; a ViewModel exposes StateFlow and calls
  use cases, never a repository; no platform types in ViewModels).
  Flag a layer reaching across the rule, a stateful screen, or a
  use case that leaks a framework type.
- Myotis boundary: all chain data comes from the embedded Myotis
  engine through the `io.myotis.api` contract, behind a port in
  :domain that :data implements. No engine internals, no JSON-RPC
  provider or other trusted-RPC fallback, no HTTP to a chain
  service outside what docs/embedding-myotis.md allows.
- Keys: private key material never appears in a UiState, a log
  line, a toString(), or a serialized form outside the key store.
- Android first: no JVM-only APIs desugaring can't cover
  (.claude/rules/platform.md, "The minSdk 29 JDK-API budget"), no
  java.net.http, HTTP via Ktor;
  Java 17 class files for everything Android loads.
- PR description: a PR that changes what the UI shows carries
  screenshots of it, or says "No UI change"
  (.claude/skills/screenshots/SKILL.md); read it with `gh pr view` and flag a
  visible change without them, or with them missing a state it adds.
  A session without a browser logged in to GitHub cannot upload
  images, so a `## Screenshots` section linking the session's
  artifact that shows them (a private claude.ai link the reviewer
  cannot open), saying they were shown to the owner in the session,
  or (CI, a plain terminal) that they await rendering, each state
  listed under its caption, with the command that renders them,
  counts as present; flag a table of file
  names in place of images, and a screenshot committed to the
  repository.
- Kotlin: immutable data, sealed UI states, structured concurrency
  (no GlobalScope, no blocking on the main thread), SOLID as
  .claude/rules/architecture.md spells it out.

Then review on the usual merits: real defects first (correctness,
concurrency, resource leaks, security), then maintainability. Rank
findings by severity and skip style nits a formatter would catch.

The PR branch is checked out in the working directory. Use
`gh pr comment` for the overall review summary and
`mcp__github_inline_comment__create_inline_comment` (with
`confirmed: true`) for line-specific findings. Only post GitHub
comments — don't leave review text as plain messages. Post the
summary even when you find nothing to flag: a review that leaves no
comment on the PR counts as a failed one.

Review in this session alone: no subagents and no background tasks.
The run ends when your turn does, so anything still running then is
lost, and findings left in your final message are never seen. Post the
inline comments and the summary yourself, then stop.

Post the summary as one command on its own:
`gh pr comment <PR NUMBER> --body '<summary>'`. No pipe, `$(…)`,
heredoc or body file: your shell is allowed `gh pr comment`,
`gh pr diff` and `gh pr view`, so read the code with your Read, Grep
and Glob tools. Keep the body in single quotes, where backticks and
`$` stay literal (in double quotes they would run as commands), and
write each apostrophe as `'\''`. A command over 10,000 characters is
refused, so keep each comment under 8,000: put detail in inline
comments, or split the summary across several `gh pr comment` calls.
If a command is still refused, simplify its body (no backslash at a
line end, no unusual spaces) and post again.
