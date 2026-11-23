# Signing in without cookies by hand — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect the "cookie" networks (Instagram, Messenger, X, LinkedIn, Google Messages) without the user opening the developer tools: using the session they already have in their browser (A), a sign-in window inside the app (B), or a cookies.txt file (C).

**Architecture:** An `app.unichat.login` layer, independent of the UI, produces a `SessionCookies` (a name→value map) from three sources. `CookieSpec` defines which cookies each network needs and builds the JSON the wizard already knows how to send to the bridge. The UI (the Accounts wizard) offers the sources and shows the progress. Nothing is kept: the cookies are read at that moment and sent to the bridge on the user's own server.

**Consent (holds for the WHOLE plan):** the user starts each source with a click. There's no automatic or background reading. Reading Chromium-based browsers on macOS goes through the system keychain, which shows its native permission dialog; Unichat announces it first. Only the cookies of the domains of the network the user is connecting are read, never of other sites, and only of the user's own account on their own machine. The destination is always the bridge on the user's server; nothing is uploaded anywhere else nor persisted to disk.

**Tech Stack:** Java 21, JavaFX (WebView for B, through `javafx-web`), `org.xerial:sqlite-jdbc` (to read Firefox's `cookies.sqlite`), Jackson (already there). Reuses `AccountsPane`, `ChatService.sendLoginInput`, `MatrixChatService`.

**Spec:** `docs/superpowers/specs/2026-10-26-cookie-login-design.md`

## Global Constraints

- Code and comments in English; visible text and docs in **Spanish** (as the repo was then). It must keep working on **Windows**.
- `./mvnw test` passes before each commit; surefire sets `unichat.dataDir` (don't touch the real folder).
- New dependencies with an exact version at least 2 weeks old: `org.openjfx:javafx-web:23.0.1`, `org.xerial:sqlite-jdbc:3.46.1.3`. The WebP config (`imageio-webp`) is already there.
- No source reads cookies of domains outside the requested network. No session is written to disk nor logged.
- Chrome/Edge on **Windows** and Chromium "v20" cookies (app-bound): detected and reported as "not supported" without asking for administrator rights; it falls back to B or C.
- Safari: out of scope.

## Review Focus

- **A cookies.txt file with expired cookies or another network's** (Task 2): it must ignore the expired ones and other domains, and say which essential cookie is missing instead of a false "connected".
- **A Firefox profile with the database locked** because the browser is open (Task 3): copy the file before reading; if that can't be done, report it and go on with other sources, don't break.
- **Chromium on Windows / v20 cookies** (Task 4): detect them and return a clean "not supported", without throwing or asking for administrator.
- **Instagram's 2FA and checkpoint in window B** (Task 5): the window doesn't close until the essential cookies exist, even if the URL changes several times (code, "was this you?", etc.).
- **A network with no session found** (Task 6): the wizard offers B and C with a clear text, never an empty screen.

---

## Task 1: `CookieSpec` — which cookies each network needs, and the bridge's JSON

**Files:**
- Create: `src/main/java/app/unichat/login/CookieSpec.java`
- Create: `src/main/java/app/unichat/login/SessionCookies.java`
- Test: `src/test/java/app/unichat/login/CookieSpecTest.java`

**Interfaces:**
- Produces:
  - `record SessionCookies(Network network, Map<String,String> values)` with `boolean complete()` and `String toBridgeJson()`.
  - `CookieSpec.forNetwork(Network) -> CookieSpec`; `Set<String> required()`, `Set<String> optional()`, `Set<String> domains()`, `boolean isForNetwork(String cookieDomain)`, `SessionCookies select(Map<String,String> allCookiesByName)`, `List<String> missing(Map<String,String>)`.

- [ ] **Step 1: Write the failing test** — `CookieSpecTest`:
  - `instagramRequiresSessionIdAndCsrf`: `forNetwork(INSTAGRAM).required()` = `{"sessionid","csrftoken"}`.
  - `selectKeepsRequiredAndOptionalDropsOthers`: given `{sessionid,csrftoken,ds_user_id,unrelated}` → `values` doesn't contain `unrelated`, `complete()` true.
  - `missingNamesWhenIncomplete`: given only `{csrftoken}` → `missing()` = `["sessionid"]`, `complete()` false.
  - `bridgeJsonIsNameValueObject`: `toBridgeJson()` parses to an object `{"sessionid":"x",...}`.
  - `domainMatchForEachNetwork`: `forNetwork(MESSENGER).isForNetwork(".facebook.com")` true, `.instagram.com` false; `GOOGLE_MESSAGES` accepts `.google.com` and `messages.google.com`.
  - The network table: INSTAGRAM `sessionid,csrftoken` (+`ds_user_id,mid,ig_did`); MESSENGER `c_user,xs` (+`datr,fr`); X `auth_token,ct0`; LINKEDIN `li_at,JSESSIONID`; GOOGLE_MESSAGES required = the Google cookies `SAPISID,SID,__Secure-1PSID` (+ `HSID,SSID,APISID,__Secure-3PSID`).
- [ ] **Step 2: Run** `./mvnw test -Dtest=CookieSpecTest` → FAIL (the class doesn't exist).
- [ ] **Step 3: Implement** `CookieSpec` and `SessionCookies`. A static table per `Network`. `select` filters by name (required∪optional). `toBridgeJson` with Jackson. `isForNetwork` compares domain suffixes.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(login): cookie requirements per network and bridge JSON`.

## Task 2: Importing cookies.txt (source C)

**Files:**
- Create: `src/main/java/app/unichat/login/CookieTxt.java`
- Test: `src/test/java/app/unichat/login/CookieTxtTest.java`
- Test resource: `src/test/resources/app/unichat/login/sample-cookies.txt`

**Interfaces:**
- Consumes: Task 1.
- Produces: `CookieTxt.read(Path, CookieSpec, Instant now) -> SessionCookies` (ignores expired cookies and domains outside the network).

- [ ] **Step 1: Write the failing test** — `CookieTxtTest`, with a synthetic `sample-cookies.txt` (Netscape format) holding: a line `#HttpOnly_.instagram.com ... sessionid VALID`, `csrftoken`, a comment `# comment`, an expired cookie (`expires`=1), and a cookie of `.other.com`.
  - `readsRequiredInstagramCookies`: the result's `complete()` is true, it contains `sessionid` and `csrftoken`.
  - `ignoresExpiredAndOtherDomains`: it includes neither the expired one nor the one of `.other.com`.
  - `reportsMissing`: with a file without `sessionid` → `missing()` = `["sessionid"]`, `complete()` false.
- [ ] **Step 2: Run** `./mvnw test -Dtest=CookieTxtTest` → FAIL.
- [ ] **Step 3: Implement** `CookieTxt.read`: parses TSV lines of 7 fields (strips the `#HttpOnly_` prefix, skips comments and empty lines), drops `expires>0 && expires<now`, filters with `spec.isForNetwork(domain)`, passes the name→value map to `spec.select`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(login): import a cookies.txt file`.

## Task 3: The Firefox session (source A, part 1)

**Files:**
- Create: `src/main/java/app/unichat/login/FirefoxCookies.java`
- Create: `src/main/java/app/unichat/login/BrowserSession.java` (a shared record)
- Test: `src/test/java/app/unichat/login/FirefoxCookiesTest.java`
- Modify: `pom.xml` (add `sqlite-jdbc`)

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `record BrowserSession(String browser, String profile, SessionCookies cookies)`.
  - `FirefoxCookies.profilesRoot() -> Path` (per OS), `FirefoxCookies.read(Path cookiesSqlite, CookieSpec, Instant now) -> SessionCookies`, `FirefoxCookies.findSessions(CookieSpec) -> List<BrowserSession>` (walks the profiles, copies the database to a temporary file before opening it, deletes the temporary file when done).

- [ ] **Step 1: Write the failing test** — `FirefoxCookiesTest`: creates a synthetic `cookies.sqlite` with the table `moz_cookies(name,value,host,path,expiry,isSecure)` and valid Instagram rows + one of `.other.com` + an expired one.
  - `readsSessionFromSqlite`: `read(db, INSTAGRAM, now)` → `complete()` true, without the other network's nor the expired one.
  - `profilesRootIsOsSpecific`: on macOS it ends in `Firefox/Profiles` (uses `System.getProperty("os.name")`; the test is parameterized by a system property, not by the real OS).
- [ ] **Step 2: Run** `./mvnw test -Dtest=FirefoxCookiesTest` → FAIL.
- [ ] **Step 3: Implement** with `sqlite-jdbc` (`SELECT name,value,host,expiry FROM moz_cookies`). `findSessions` lists the subfolders of `profilesRoot`, copies `cookies.sqlite` to a tmp file (so as not to clash with the browser's lock), reads and cleans up. Paths: macOS `~/Library/Application Support/Firefox/Profiles`, Windows `%APPDATA%\Mozilla\Firefox\Profiles`, Linux `~/.mozilla/firefox`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(login): read the user's Firefox session`.

## Task 4: The Chromium session on macOS; Windows detected as not supported (source A, part 2)

**Files:**
- Create: `src/main/java/app/unichat/login/ChromiumCookies.java`
- Test: `src/test/java/app/unichat/login/ChromiumCookiesTest.java`

**Interfaces:**
- Consumes: Task 1, Task 3 (`BrowserSession`).
- Produces: `ChromiumCookies.findSessions(CookieSpec) -> List<BrowserSession>` for Chrome/Edge/Brave; `Support classify(String os, byte[] encryptedValuePrefix)` returning `SUPPORTED` / `NEEDS_IN_APP_LOGIN` (a `v20` prefix or the Windows OS). Decryption exposed as `decryptValue(byte[] encrypted, byte[] key) -> String` so it can be tested with synthetic data.

**Consent:** on macOS `findSessions` asks the system keychain for the key, which shows its native dialog; this class isn't called until the user has pressed "Use my session" (Task 6). Only the cookies of `spec.domains()`.

- [ ] **Step 1: Write the failing test** — `ChromiumCookiesTest` (no keychain nor real browser):
  - `classifyMarksWindowsAndV20AsInApp`: `classify("Windows 11", anything)` = `NEEDS_IN_APP_LOGIN`; `classify("Mac OS X", "v20…")` = `NEEDS_IN_APP_LOGIN`; `classify("Mac OS X", "v10…")` = `SUPPORTED`.
  - `decryptsV10WithGivenKey`: encrypts a test value with the v10 scheme and a fixed key, and `decryptValue` gets it back (it tests the decryption with synthetic data, not a real session).
- [ ] **Step 2: Run** `./mvnw test -Dtest=ChromiumCookiesTest` → FAIL.
- [ ] **Step 3: Implement** `classify` and `decryptValue` (v10). `findSessions`: only if `classify` gives `SUPPORTED`; on macOS it gets the key from the keychain (`security find-generic-password` for "Chrome/Chromium/Brave/Edge Safe Storage"), opens the copy of the `Cookies` database, decrypts and filters by domain; on Windows it returns an empty list (the UI will use B/C). Don't write values to the logs.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(login): read a Chromium session on macOS, detect unsupported cases`.

## Task 5: The built-in sign-in window (source B)

**Files:**
- Create: `src/main/java/app/unichat/ui/LoginWebView.java`
- Modify: `pom.xml` (add `javafx-web`)
- Test: `src/test/java/app/unichat/login/LoginUrlsTest.java` (pure logic; the WebView is tested by hand)

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `LoginWebView.open(Window owner, Network, CookieSpec, Consumer<SessionCookies> onDone, Runnable onCancel)`.
  - `LoginUrls.startUrl(Network) -> String` and `LoginUrls.cookieStore(Network) -> ...` (pure helpers).

- [ ] **Step 1: Write the failing test** — `LoginUrlsTest`: `startUrl(INSTAGRAM)` = `https://www.instagram.com/accounts/login/`, and the URLs of MESSENGER/X/LINKEDIN/GOOGLE_MESSAGES. `userAgent()` contains a recent browser version (not JavaFX's default UA).
- [ ] **Step 2: Run** `./mvnw test -Dtest=LoginUrlsTest` → FAIL.
- [ ] **Step 3: Implement** `LoginUrls`, then `LoginWebView`: a `WebView` with its own empty `CookieManager` (to allow a different account), a current browser's UA; after each load it reads the store's cookies, builds `spec.select`, and when `complete()` it closes the window and calls `onDone`. A Cancel button → `onCancel`. Title and accessibility in the wizard's style.
- [ ] **Step 4: Run** `LoginUrlsTest` → PASS. (The visual flow is checked by hand in Task 7.)
- [ ] **Step 5: Commit** `feat(login): in-app login window that captures the session`.

## Task 6: The sources' orchestrator

**Files:**
- Create: `src/main/java/app/unichat/login/CookieLogin.java`
- Test: `src/test/java/app/unichat/login/CookieLoginTest.java`

**Interfaces:**
- Consumes: Tasks 2–5.
- Produces:
  - `CookieLogin.isCookieNetwork(Network) -> boolean`.
  - `CookieLogin.findBrowserSessions(Network) -> CompletableFuture<List<BrowserSession>>` (Firefox + Chromium-macOS; never throws, an empty list if nothing).
  - `CookieLogin.fromCookieTxt(Path, Network) -> Result` (`Result` = ok(SessionCookies) | missing(List<String>) | error(msg)).

- [ ] **Step 1: Write the failing test** — `CookieLoginTest`: `isCookieNetwork` true for INSTAGRAM/MESSENGER/X/LINKEDIN/GOOGLE_MESSAGES and false for WHATSAPP/TELEGRAM/SIGNAL/DISCORD. `fromCookieTxt` with Task 2's sample → ok; with an incomplete one → missing with the names. `findBrowserSessions` doesn't throw when there are no browsers (it returns an empty list) — tested by pointing the roots at an empty tmp folder through the system property `unichat.browserRoots`.
- [ ] **Step 2: Run** `./mvnw test -Dtest=CookieLoginTest` → FAIL.
- [ ] **Step 3: Implement** `CookieLogin` combining the sources on the I/O executor; it catches exceptions per source. Add `unichat.browserRoots` support to Firefox/Chromium for the tests.
- [ ] **Step 4: Run** → PASS; and the whole `./mvnw test` → PASS.
- [ ] **Step 5: Commit** `feat(login): orchestrate browser, cookies.txt and in-app sources`.

## Task 7: Wiring it into the Accounts wizard

**Files:**
- Modify: `src/main/java/app/unichat/ui/AccountsPane.java`
- Modify: `src/main/java/app/unichat/service/ChatService.java` (and `MockChatService`, `MatrixChatService` if a method to send the cookie JSON is needed — reuse `sendLoginInput`)
- Modify: `docs/BRIDGES.md` (describe the new flow, in Spanish as the docs were then)
- Test: the UI by hand with snapshots (`-Dunichat.snapshot`), more than automated.

**Interfaces:**
- Consumes: Task 6.

- [ ] **Step 1:** In `AccountsPane`, for a cookie network (`CookieLogin.isCookieNetwork`), the wizard first shows "Looking for your session…" and calls `findBrowserSessions`.
- [ ] **Step 2:** If there are sessions, card(s) "Use your Instagram session from Firefox (profile X)" with **Use this session** (sends `cookies.toBridgeJson()` with `sendLoginInput`) and **Sign in with another account** (→ `LoginWebView`). Always a **Choose cookies.txt file…** button (`FileChooser` → `fromCookieTxt`; if `missing`, show what's missing) and the discreet "Paste cookies by hand" link.
- [ ] **Step 3:** Before reading Chromium on macOS, a text: "macOS will ask your permission to use your <browser> session." The WebView's `onDone` and "Use this session" end in the same success/error flow of the wizard (the existing wizard task).
- [ ] **Step 4:** The whole `./mvnw test` PASS. Snapshots in demo mode: the found-session card, the cookies.txt screen with a missing-cookie error, and window B (against a local test page, not a real network).
- [ ] **Step 5: Commit** `feat(accounts): friendly login for cookie networks (browser, in-app, cookies.txt)`.

## Task 8 (separate): protect the `m.direct` list of direct chats

**Files:**
- Modify: `src/main/java/app/unichat/matrix/MatrixChatService.java:addDirect`
- Test: `src/test/java/app/unichat/matrix/MatrixServiceDirectTest.java` (or add to an existing matrix one)

**Interfaces:** no new API.

- [ ] **Step 1: Write the failing test** — build `m.direct` with an earlier entry for another bot and check that adding a room for a new bot **doesn't lose** the earlier entries (today `addDirect` rereads the cached contents and could overwrite entries if the sync is behind). Assert that the resulting PUT body keeps every key.
- [ ] **Step 2: Run** → FAIL if it applies (if it's already right, the test stays as a regression test and that is documented).
- [ ] **Step 3: Implement** merging onto the server's most recent `m.direct` (GET before PUT) instead of only the cached one.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `fix(matrix): merge m.direct so existing bot DMs aren't dropped`.

## Task 9 (added during execution): keep credentials out of the on-disk sync cache

**Why:** `MatrixChatService.saveSyncCache` writes the entire `/sync` response to disk (clear JSON),
including bridge-bot DM timelines. Those now carry the cookie JSON we send (and QR data / tokens), so
session credentials would sit on disk until logout. This contradicts the design's "nothing is persisted to disk".

**Files:**
- Modify: `src/main/java/app/unichat/matrix/MatrixChatService.java` (`saveSyncCache`, maybe `SyncProcessor` to identify bridge-bot rooms)
- Test: `src/test/java/app/unichat/matrix/` (pure redaction helper)

**Interfaces:** no public API change.

- [ ] **Step 1: Write the failing test** — extract a pure static helper, e.g. `static JsonNode redactForCache(JsonNode fullSync, Set<String> botRoomIds)` that returns a deep copy of the `/sync` with, for each room id in `botRoomIds`, the `timeline.events[].content` (and `state` message content) removed/blanked while keeping membership/state needed for the room to resolve. Test: a bridge-bot room's message bodies (e.g. a value "sessionid=SECRET") are absent from the output; a normal room's timeline is untouched; `next_batch` preserved.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** the helper; in `saveSyncCache`, compute the bridge-bot room id set from the processor (rooms whose chat `bridgeBot()` is true) and write `redactForCache(fullSync, botRooms)` instead of the raw response. `loadSyncCache` still works (bot rooms load with empty timelines — fine; they're management chats).
- [ ] **Step 4: Run** → PASS; full `./mvnw test` PASS.
- [ ] **Step 5: Commit** `fix(matrix): keep bridge-bot credentials out of the on-disk sync cache`.
