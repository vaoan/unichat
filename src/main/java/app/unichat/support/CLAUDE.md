# support — notes for Claude

Leaf utilities, no dependencies in the project. Use them instead of writing another: process timeouts, folder deletion and URL encoding had each drifted apart.

- `Processes` (start, drain, timeouts, kill with children), `Folders` (deleting), `Urls` (encoding, links in text), `Downloads` (fetch with a pinned SHA-256), `Archives` (extract to a fixed target, no links: no zip-slip), `Hashes`, `Os`, `BundledTools`, `ConvertedFiles`, `ImageHeaders` (a picture's size from its header, never decoding it), `Threads`, `WakeWatch`, `AppVersion`, `ErrorReport`, `Errors`.
- `Redactor` filters what goes to the logs and error reports: tokens, phones, emails, Matrix ids. Anything new that can carry a secret (a cookie, a refresh token) gets a case in `RedactorTest`.
- `I18n`: `.claude/rules/i18n.md`.
