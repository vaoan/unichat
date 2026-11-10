# support — notes for Claude

Leaf utilities, no dependencies in the project. Use them instead of writing another: process timeouts, folder deletion and URL encoding had each drifted apart.

- `Processes` (start, drain, timeouts, kill with children), `Folders` (deleting), `Urls` (encoding, links in text), `Downloads` (fetch with a pinned SHA-256), `Archives` (extract to a fixed target, no links: no zip-slip), `Hashes`, `Os`, `BundledTools`, `ConvertedFiles`, `ImageHeaders` (a picture's size from its header, never decoding it), `MarkOfTheWeb` (Windows: what came from a chat is marked as from the internet, so SmartScreen asks before running it), `Threads`, `WakeWatch`, `AppVersion`, `ErrorReport`, `Errors`.
- `Redactor` filters what goes to the logs and error reports: tokens, phones, emails, Matrix ids. Anything new that can carry a secret (a cookie, a refresh token) gets a case in `RedactorTest`.
- `I18n`: `.claude/rules/i18n.md`.

## Lessons
- An HTTP answer read as a stream (`BodyHandlers.ofInputStream`) is closed even when it's an error: `Downloads` threw on a non-200 and left the body open, the connection busy for good (`DownloadsTest.aRefusalLetsItsConnectionGo`). And one `HttpClient` per class, never one per call: each keeps its own threads and connections until collected (`.claude/lessons.tsv` `http-client-per-call`).
- Three classes ran programs their own way (and wrote "last line" three times), each missing something `Processes` had: interrupting their thread left the program and its children running. `Processes.capture(builder, Limit, input, eachLine)` covers what they needed (a prompt on stdin, a silence limit) and `lastLines` the reason; `.claude/lessons.tsv` `own-process` keeps the converted packages on it.
