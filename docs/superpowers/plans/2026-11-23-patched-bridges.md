# Patched bridges on both systems — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** X's and Discord's fixed bridges reach macOS and Windows installs through Unichat's own pinned builds, and a bridge with no account doesn't run.

**Architecture:** `versions.env` marks the bridges Unichat builds itself on every platform. `natives.yml` builds them for darwin-arm64 and windows-amd64, applying `native/patches/<bridge>/*.patch`. `Manifest` downloads those from `NATIVES` on every platform. `LocalServer` starts a bridge only when it has a login, or when its sign-in is opened.

**Tech Stack:** Java 25, GitHub Actions, Go builds of mautrix bridges.

**Spec:** `docs/superpowers/specs/2026-11-23-patched-bridges-design.md`

## Global Constraints
- **Exact versions and the patch:**
  - twitter: `mautrix/twitter@8d47b07…`. Use the full 40-character SHA of PR #257's head. Resolve it with `git ls-remote https://github.com/mautrix/twitter refs/pull/257/head`, and write that exact SHA.
  - discord: `mautrix/discord@v0.7.7`, plus `native/patches/discord/resume-subscribe-pace.patch`. The patch changes `user.go`'s `resumeHandler` line `user.subscribeGuilds(0 * time.Second)` to `go user.subscribeGuilds(2 * time.Second)`, with a comment.
  - `NATIVES=vaoan/unichat@natives-2`.
- **Pinning:** every downloaded file stays pinned by SHA-256 (`sha256sums.txt`, `native/pin-hashes.sh`, `ManifestTest`). A patch that doesn't apply fails the build.
- **CI rules:** `.github/CLAUDE.md`. No `${{ }}` inside `run:`. Pass values through `env:`, and use `persist-credentials: false`.
- **Repo rules** (`CLAUDE.md`):
  - every change has a test that fails without it;
  - no waits on time in tests;
  - files stay under 800 lines;
  - docs stay in English, with Spanish translations kept in step (README.es, PRIVACIDAD) if they mention bridges.
- **Run in the foreground:** `JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw -q verify -DskipTests` plus the affected tests. The controller runs the full verify.

## Review Focus
1. A bridge that has an account always starts at launch. Task 2, `aBridgeWithALoginStartsAtLaunch`.
2. Opening a bridge's sign-in starts it before the sign-in needs it. Task 2, `openingTheSignInStartsTheBridge`.
3. A bridge built by Unichat on macOS is downloaded from `NATIVES`, never from mautrix's release. Task 1, `aBridgeBuiltByUnichatComesFromNativesOnEveryPlatform`.
4. A patch that doesn't apply fails CI. Task 1's workflow step uses `git apply --check` first.
5. The watchdog doesn't restart, or count, a bridge that isn't running by design. Task 2, `theWatchdogLeavesABridgeWithoutAccountsAlone`.

### Task 1: Bridges Unichat builds everywhere, with patches
**Files:**
- `src/main/resources/app/unichat/server/versions.env`
- `src/main/java/app/unichat/server/Manifest.java`
- `.github/workflows/natives.yml`
- `native/patches/discord/resume-subscribe-pace.patch`
- `native/CLAUDE.md`, `server/CLAUDE.md`, `docs/NATIVE-SERVER.md`
- Test: `ManifestTest`

The steps:
- [ ] Write `ManifestTest.aBridgeBuiltByUnichatComesFromNativesOnEveryPlatform`: a bridge listed as built everywhere (the builder picks the key, e.g. `NATIVES_ALL=twitter,discord`) gets the `NATIVES` release URL on `MAC_ARM64` and `WINDOWS_X64`. The others keep the official URL on Mac. Watch it fail.
- [ ] Implement it in `Manifest`. A component whose ref is a commit SHA must work too, for its asset name and its version file.
- [ ] `natives.yml`:
  - Build the listed bridges on `macos-15` (darwin-arm64, cgo with goolm, the same flags as the Windows build) as well as for Windows.
  - Check out a commit ref.
  - Apply `native/patches/<name>/*.patch` with `git apply --check`, then `git apply`, for both platforms. The patches come from Unichat's checkout, passed in through `env:` paths.
  - The release notes list each patch and each commit.
- [ ] Update `versions.env`:
  - twitter pinned to the full SHA;
  - discord's patch file added;
  - `NATIVES=natives-2`;
  - comments that say why each one is pinned or patched (X: per-message keys, PR #257; Discord: resume rate-limit loop).
- [ ] `pin-hashes.sh` can only run once CI has published natives-2. Leave `sha256sums.txt` alone and say so in the report. `ManifestTest` will fail until the pins are written, so mark that one test as expected-red in the report. Don't disable it.
- [ ] Commit: `build(natives): X and Discord built by Unichat on every platform, with their fixes`.

### Task 2: No bridge without an account runs
**Files:**
- `src/main/java/app/unichat/server/LocalServer.java` (the start-at-launch loop, around lines 96/164/190)
- the sign-in entry point that opens a bridge's login (find it from `ui/login` or `matrix/BridgeAccounts`) through `service`, keeping `ArchitectureTest` happy
- `server/Watchdog.java`, for `running`
- Test: `LocalServerTest` (or the nearest existing one), `WatchdogTest`

The steps:
- [ ] Write the tests and watch them fail:
  - `aBridgeWithoutALoginIsNotStartedAtLaunch`
  - `aBridgeWithALoginStartsAtLaunch`
  - `openingTheSignInStartsTheBridge`: the start happens before the sign-in's first call, and the sign-in waits for the bridge to answer.
  - `theWatchdogLeavesABridgeWithoutAccountsAlone`
- [ ] Implement it.
  - "Has a login" means what `BridgeAccounts` already keeps: `matrix.logins.<protocolId>` with at least one login.
  - A login made in this session keeps its bridge running.
  - Logging out of the last account stops the bridge.
- [ ] Commit: `perf(server): a bridge with no account doesn't run until its sign-in is opened`.

### Controller steps (not tasks)
1. Merge and push to main, so CI builds natives-2.
2. Run `native/pin-hashes.sh` and commit `sha256sums.txt`.
3. Run the full verify, then install and reopen.
4. Check that the X and Discord binaries came from natives-2 and run.
