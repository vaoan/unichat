# update — notes for Claude

`UpdateChecker` asks GitHub's latest release once a day (`app.checkUpdates`, Ajustes → Privacidad; `app.update.*` stay on this machine); `UpdatePolicy` holds the rules without network (a `-suffix` tag is a pre-release and skipped; "dev" is never offered one). It only opens a `https://github.com/` page, never installs. Releases and versions: `packaging/CLAUDE.md`.

While nobody can see Unichat (`support/Seen`) the daily ticks don't ask; when seen again the checker looks once on its own worker and asks if a day has passed (missed ticks count as one). The manual check (`checkNow`) ignores `Seen`. Tests give the checker a fake clock and fetcher (`UpdateCheckerUnseenTest`).
