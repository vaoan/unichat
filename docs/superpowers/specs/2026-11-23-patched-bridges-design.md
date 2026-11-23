# Patched bridges on both systems, and no bridge without an account — design

## Intent
Two bridges are broken upstream, and the fixes run only on the person's Mac, swapped in by hand on 2026-11-22/23:
- **X:** since 2026-11-19/20, X encrypts messages under per-message keys, and mautrix-twitter v0.2609.0 can't read them. The fix is upstream PR mautrix/twitter#257, which is open and not yet released.
- **Discord:** in mautrix-discord v0.7.7, `resumeHandler` subscribes to every guild at once. With 123 guilds, Discord closes the socket with 4008 "Rate limited", and the bridge resumes and subscribes again, in a loop (about 140 times in 2 minutes). The bridge's own connect path already waits 2 s between guilds.

A reinstall, or the person's Windows PC, still gets the broken versions. Unichat must ship both fixes on macOS and Windows.

Separately, the Google Messages bridge runs with no account ("No user logins found") and costs battery for nothing. A bridge with no account should not run until the person goes to sign in to it.

## Goals
- **One way to say a bridge is Unichat's own build.** `versions.env` names a bridge whose source Unichat builds itself on every platform: from a commit (`<repo>@<commit sha>`) or a tag, with patches under `native/patches/<bridge>/*.patch`.
  - `natives.yml` builds those bridges for darwin-arm64 (on macos-15, like tuwunel) and for windows-amd64 (as now), applying the patches.
  - `Manifest` downloads them from the `NATIVES` release on every platform.
  - Every file stays pinned by SHA-256 (`pin-hashes.sh`, `ManifestTest`).
- **The two fixes:**
  - twitter is pinned to commit `8d47b07` (PR #257's head, on top of main `77b2290`).
  - discord stays on v0.7.7, plus `native/patches/discord/resume-subscribe-pace.patch`, which turns the one line into `go user.subscribeGuilds(2 * time.Second)`.
- **`NATIVES` goes to `natives-2`.** CI publishes it on the push to main, and the hashes are pinned after that.
- **No bridge without an account runs:**
  - A bridge with no login known to Unichat (`matrix.logins.<protocol>` absent or empty) is not started at launch.
  - It starts when the person opens its sign-in in Unichat.
  - Bridges with an account start as today.
  - The watchdog never restarts a bridge that isn't meant to run.

## Non-goals
- Sending the Discord fix upstream. That is an outward action: ask the person.
- Other bridges, and Signal on Windows (still `NATIVES_UNBUILT`).

## Anti-goals
- **A bridge that has an account doesn't start.** Accounts would go silent, which is worse than the battery cost.
- **An unpinned binary**, or a build whose patches didn't apply cleanly (the build must fail, not skip the patch).
- **Shipping a patched bridge the person can't trace.** The release notes name each patch and the commit, as they already do for Discord's Windows change.

## Approaches
1. **Unichat builds the patched bridges for both platforms in `natives.yml`** (chosen). It uses the pipeline that already builds tuwunel for macOS and every bridge for Windows. One source of truth is `versions.env` plus the patch files.
2. **Ship the hand-built binaries.** Rejected: not reproducible, and not pinned through CI.
3. **Wait for upstream releases.** Rejected: X is unreadable now, and the Discord fix has no upstream PR at all.

## Left to the builder
- The exact `versions.env` syntax for "built by Unichat everywhere".
- How the patch step reports a patch that fails to apply.
- How `LocalServer` learns that a sign-in was opened.

Ruling (controller): the person approved both changes ("sí, haz las dos cosas", 2026-11-23). This short design stands as approved.
