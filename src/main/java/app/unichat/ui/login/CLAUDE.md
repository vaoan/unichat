# ui.login — notes for Claude

Signing in and the accounts' screens: `LoginView` (the Matrix sign-in, the one UI class allowed to name `MatrixChatService`: `docs/ARCHITECTURE.md`), `LoginWizard`, `LoginChoosers`, `LoginWebView`, `LoginTexts` (pure, tested), `LoginUrls`, `CookieFileButton`, `BridgeLoginPane`, `AccountsPane` (+ `DiscordServersPage`, `LoadingView`), `TelegramKeysForm`. How bridge logins and cookies work: `login/CLAUDE.md`.

## Lessons
- `LoginWebView`: JavaFX's WebView keeps the first CookieHandler it saw for the whole process (`HTTP2Loader.HTTP_CLIENT`), so a second login window never saw its cookies. `LoginWebView.CurrentCookies` stays installed and forwards. More in `login/CLAUDE.md`.
