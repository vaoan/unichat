# .github — notes for Claude

`workflows/build.yml` (tests, headless UI tests on Linux, packages, releases on a `v<version>` tag) and `workflows/natives.yml` (what has no official build). Packaging and signing: `packaging/CLAUDE.md`.

- Token permissions: `contents: read` at the top, `write` only in the job that publishes. Jobs that build third-party code (cargo, go) run their build scripts with the token, so they never get write, and checkouts use `persist-credentials: false`.
- No `${{ … }}` inside `run:` with values that could come from outside (PR titles, branch names, matrix values from files): pass them through `env:`.
- Actions are pinned by tag (known debt, `docs/ARCHITECTURE.md`); a new one is pinned by SHA.
