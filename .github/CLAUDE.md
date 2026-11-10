# .github — notes for Claude

`workflows/build.yml` (tests, headless UI tests on Linux, packages, releases on a `v<version>` tag) and `workflows/natives.yml` (what has no official build). Packaging and signing: `packaging/CLAUDE.md`.

- Token permissions: `contents: read` at the top, `write` only in the job that publishes. Jobs that build third-party code (cargo, go) run their build scripts with the token, so they never get write, and checkouts use `persist-credentials: false`.
- No `${{ … }}` inside `run:` with values that could come from outside (PR titles, branch names, matrix values from files): pass them through `env:`.
- Actions are pinned by tag (known debt, `docs/ARCHITECTURE.md`); a new one is pinned by SHA.

## Lessons
- `natives.yml` pasted `${{ matrix.bridge.* }}` (read from `versions.env`) and other matrix values into `run:` scripts: a crafted value became shell code. They go through `env:` and are used as `"$VAR"`; check with `grep -n '\${{' .github/workflows/*.yml` that none is left inside a `run:`.
- `ui-headless` blocks the build and the release (since 2026-11-10). It was informative and failed on every run for days without anyone seeing it: `ComposerHeightUiTest`'s sentence still took two lines at 704 px with Linux's wider font. A test's failure message saying what it measured (its sizes) is what found it from CI alone.
