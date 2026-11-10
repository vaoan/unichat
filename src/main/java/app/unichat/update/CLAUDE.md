# update — notes for Claude

`UpdateChecker` asks GitHub's latest release once a day (`app.checkUpdates`, Ajustes → Privacidad; `app.update.*` stay on this machine); `UpdatePolicy` holds the rules without network (a `-suffix` tag is a pre-release and skipped; "dev" is never offered one). It only opens a `https://github.com/` page, never installs. Releases and versions: `packaging/CLAUDE.md`.
