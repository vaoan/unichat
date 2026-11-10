# model — notes for Claude

Domain data, no dependencies in the project: mutable `Chat`, `Message`; records `Member`, `Account`.

- `Network` maps each network to its bridge bot localpart / puppet prefix / protocol id: it must match `server/setup.*` and `src/main/resources/app/unichat/server/`. It is queried with `switch` in several places (known debt): a new network touches all of them, grep for `Network.`.
- `Message.revision()` decides whether a timeline row redraws: a new drawn field goes in there (`.claude/rules/ui-timeline.md`).
- Pure logic with tests: `Markup` (WhatsApp/Discord marks → `formatted_body`, escaping before tags), `TextFormat`, `MimeTypes` (the one extension ↔ type table, both ways: `extension`, `ofFileName`, `of(Path)`; use it instead of another), `Mxc` (a media address, parsed and checked), `MediaLibrary`.
