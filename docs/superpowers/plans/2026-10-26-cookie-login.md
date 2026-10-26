# Inicio de sesión sin cookies a mano — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Conectar las redes "de cookies" (Instagram, Messenger, X, LinkedIn, Google Messages) sin que el usuario abra las herramientas de desarrollador: usando la sesión que ya tiene en su navegador (A), una ventana de login dentro de la app (B), o un archivo cookies.txt (C).

**Architecture:** Una capa `app.unichat.login` independiente de la UI produce un `SessionCookies` (mapa nombre→valor) por tres fuentes. `CookieSpec` define qué cookies necesita cada red y arma el JSON que el asistente ya sabe enviar al bridge. La UI (asistente de Cuentas) ofrece las fuentes y muestra el progreso. Nada se guarda: las cookies se leen en el momento y se envían al bridge del servidor del propio usuario.

**Consentimiento (vale para TODO el plan):** cada fuente la inicia el usuario con un clic. No hay lectura automática ni en segundo plano. La lectura de navegadores basados en Chromium en macOS pasa por el llavero del sistema, que muestra su diálogo nativo de permiso; Unichat lo anuncia antes. Solo se leen las cookies de los dominios de la red que el usuario está conectando, nunca de otros sitios, y solo de la cuenta del propio usuario en su propia máquina. El destino es siempre el bridge del servidor del usuario; nada se sube a ningún otro lugar ni se persiste en disco.

**Tech Stack:** Java 21, JavaFX (WebView para B, vía `javafx-web`), `org.xerial:sqlite-jdbc` (leer `cookies.sqlite` de Firefox), Jackson (ya presente). Reutiliza `AccountsPane`, `ChatService.sendLoginInput`, `MatrixChatService`.

**Spec:** `docs/superpowers/specs/2026-10-26-cookie-login-design.md`

## Global Constraints

- Código y comentarios en inglés; texto visible y docs en **español**. Debe seguir funcionando en **Windows**.
- `./mvnw test` pasa antes de cada commit; surefire fija `unichat.dataDir` (no tocar la carpeta real).
- Dependencias nuevas con versión exacta de ≥2 semanas: `org.openjfx:javafx-web:23.0.1`, `org.xerial:sqlite-jdbc:3.46.1.3`. La config de WebP (`imageio-webp`) ya está.
- Ninguna fuente lee cookies de dominios ajenos a la red solicitada. Nada de sesiones se escribe a disco ni se registra en logs.
- Chrome/Edge en **Windows** y cookies Chromium "v20" (app-bound): se detectan y se reporta "no compatible" sin pedir privilegios de administrador; se cae a B o C.
- Safari: fuera de alcance.

## Review Focus

- **Archivo cookies.txt con cookies vencidas o de otra red** (Task 2): debe ignorar lo vencido y lo de otros dominios, y decir qué cookie imprescindible falta en vez de "conectado" en falso.
- **Perfil de Firefox con la base bloqueada** porque el navegador está abierto (Task 3): copiar el archivo antes de leer; si no se puede, reportar y seguir con otras fuentes, no romper.
- **Chromium en Windows / cookies v20** (Task 4): detectar y devolver "no compatible" limpio, sin lanzar ni pedir administrador.
- **2FA y checkpoint de Instagram en la ventana B** (Task 5): la ventana no se cierra hasta que existan las cookies imprescindibles, aunque la URL cambie varias veces (código, "¿fuiste tú?", etc.).
- **Red sin ninguna sesión encontrada** (Task 6): el asistente ofrece B y C con un texto claro, nunca una pantalla vacía.

---

## Task 1: `CookieSpec` — qué cookies necesita cada red y el JSON del bridge

**Files:**
- Create: `src/main/java/app/unichat/login/CookieSpec.java`
- Create: `src/main/java/app/unichat/login/SessionCookies.java`
- Test: `src/test/java/app/unichat/login/CookieSpecTest.java`

**Interfaces:**
- Produces:
  - `record SessionCookies(Network network, Map<String,String> values)` con `boolean complete()` y `String toBridgeJson()`.
  - `CookieSpec.forNetwork(Network) -> CookieSpec`; `Set<String> required()`, `Set<String> optional()`, `Set<String> domains()`, `boolean isForNetwork(String cookieDomain)`, `SessionCookies select(Map<String,String> allCookiesByName)`, `List<String> missing(Map<String,String>)`.

- [ ] **Step 1: Write the failing test** — `CookieSpecTest`:
  - `instagramRequiresSessionIdAndCsrf`: `forNetwork(INSTAGRAM).required()` = `{"sessionid","csrftoken"}`.
  - `selectKeepsRequiredAndOptionalDropsOthers`: dado `{sessionid,csrftoken,ds_user_id,unrelated}` → `values` no contiene `unrelated`, `complete()` true.
  - `missingNamesWhenIncomplete`: dado solo `{csrftoken}` → `missing()` = `["sessionid"]`, `complete()` false.
  - `bridgeJsonIsNameValueObject`: `toBridgeJson()` parsea a objeto `{"sessionid":"x",...}`.
  - `domainMatchForEachNetwork`: `forNetwork(MESSENGER).isForNetwork(".facebook.com")` true, `.instagram.com` false; `GOOGLE_MESSAGES` acepta `.google.com` y `messages.google.com`.
  - Tabla de redes: INSTAGRAM `sessionid,csrftoken` (+`ds_user_id,mid,ig_did`); MESSENGER `c_user,xs` (+`datr,fr`); X `auth_token,ct0`; LINKEDIN `li_at,JSESSIONID`; GOOGLE_MESSAGES requeridas = cookies Google `SAPISID,SID,__Secure-1PSID` (+ `HSID,SSID,APISID,__Secure-3PSID`).
- [ ] **Step 2: Run** `./mvnw test -Dtest=CookieSpecTest` → FAIL (clase no existe).
- [ ] **Step 3: Implement** `CookieSpec` y `SessionCookies`. Tabla estática por `Network`. `select` filtra por nombre (required∪optional). `toBridgeJson` con Jackson. `isForNetwork` compara sufijos de dominio.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(login): cookie requirements per network and bridge JSON`.

## Task 2: Importar cookies.txt (fuente C)

**Files:**
- Create: `src/main/java/app/unichat/login/CookieTxt.java`
- Test: `src/test/java/app/unichat/login/CookieTxtTest.java`
- Test resource: `src/test/resources/app/unichat/login/sample-cookies.txt`

**Interfaces:**
- Consumes: Task 1.
- Produces: `CookieTxt.read(Path, CookieSpec, Instant now) -> SessionCookies` (ignora vencidas y dominios ajenos a la red).

- [ ] **Step 1: Write the failing test** — `CookieTxtTest`, con un `sample-cookies.txt` sintético (formato Netscape) que incluya: una línea `#HttpOnly_.instagram.com ... sessionid VALID`, `csrftoken`, un comentario `# comment`, una cookie vencida (`expira`=1), y una cookie de `.otra.com`.
  - `readsRequiredInstagramCookies`: resultado `complete()` true, contiene `sessionid` y `csrftoken`.
  - `ignoresExpiredAndOtherDomains`: no incluye la vencida ni la de `.otra.com`.
  - `reportsMissing`: con un archivo sin `sessionid` → `missing()` = `["sessionid"]`, `complete()` false.
- [ ] **Step 2: Run** `./mvnw test -Dtest=CookieTxtTest` → FAIL.
- [ ] **Step 3: Implement** `CookieTxt.read`: parsea líneas TSV de 7 campos (quita prefijo `#HttpOnly_`, salta comentarios y vacías), descarta `expira>0 && expira<now`, filtra con `spec.isForNetwork(domain)`, pasa el mapa nombre→valor a `spec.select`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(login): import a cookies.txt file`.

## Task 3: Sesión de Firefox (fuente A, parte 1)

**Files:**
- Create: `src/main/java/app/unichat/login/FirefoxCookies.java`
- Create: `src/main/java/app/unichat/login/BrowserSession.java` (record común)
- Test: `src/test/java/app/unichat/login/FirefoxCookiesTest.java`
- Modify: `pom.xml` (añadir `sqlite-jdbc`)

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `record BrowserSession(String browser, String profile, SessionCookies cookies)`.
  - `FirefoxCookies.profilesRoot() -> Path` (por SO), `FirefoxCookies.read(Path cookiesSqlite, CookieSpec, Instant now) -> SessionCookies`, `FirefoxCookies.findSessions(CookieSpec) -> List<BrowserSession>` (recorre perfiles, copia la base a un temporal antes de abrirla, borra el temporal al terminar).

- [ ] **Step 1: Write the failing test** — `FirefoxCookiesTest`: crea una `cookies.sqlite` sintética con la tabla `moz_cookies(name,value,host,path,expiry,isSecure)` y filas de Instagram válidas + una de `.otra.com` + una vencida.
  - `readsSessionFromSqlite`: `read(db, INSTAGRAM, now)` → `complete()` true, sin la de otra red ni la vencida.
  - `profilesRootIsOsSpecific`: en macOS termina en `Firefox/Profiles` (usa `System.getProperty("os.name")`; test parametrizado por propiedad de sistema, no por SO real).
- [ ] **Step 2: Run** `./mvnw test -Dtest=FirefoxCookiesTest` → FAIL.
- [ ] **Step 3: Implement** con `sqlite-jdbc` (`SELECT name,value,host,expiry FROM moz_cookies`). `findSessions` lista subcarpetas de `profilesRoot`, copia `cookies.sqlite` a un tmp (para no chocar con el lock del navegador), lee y limpia. Rutas: macOS `~/Library/Application Support/Firefox/Profiles`, Windows `%APPDATA%\Mozilla\Firefox\Profiles`, Linux `~/.mozilla/firefox`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(login): read the user's Firefox session`.

## Task 4: Sesión de Chromium en macOS; Windows detectado como no compatible (fuente A, parte 2)

**Files:**
- Create: `src/main/java/app/unichat/login/ChromiumCookies.java`
- Test: `src/test/java/app/unichat/login/ChromiumCookiesTest.java`

**Interfaces:**
- Consumes: Task 1, Task 3 (`BrowserSession`).
- Produces: `ChromiumCookies.findSessions(CookieSpec) -> List<BrowserSession>` para Chrome/Edge/Brave; `Support classify(String os, byte[] encryptedValuePrefix)` devolviendo `SUPPORTED` / `NEEDS_IN_APP_LOGIN` (prefijo `v20` o SO Windows). Descifrado expuesto como `decryptValue(byte[] encrypted, byte[] key) -> String` para poder testearlo con datos sintéticos.

**Consentimiento:** `findSessions` en macOS pide la clave al llavero del sistema, que muestra su diálogo nativo; esta clase no se llama hasta que el usuario pulsó "Usar mi sesión" (Task 6). Solo cookies de `spec.domains()`.

- [ ] **Step 1: Write the failing test** — `ChromiumCookiesTest` (sin llavero ni navegador real):
  - `classifyMarksWindowsAndV20AsInApp`: `classify("Windows 11", anything)` = `NEEDS_IN_APP_LOGIN`; `classify("Mac OS X", "v20…")` = `NEEDS_IN_APP_LOGIN`; `classify("Mac OS X", "v10…")` = `SUPPORTED`.
  - `decryptsV10WithGivenKey`: cifra un valor de prueba con el esquema v10 y una clave fija, y `decryptValue` lo recupera (prueba el descifrado con datos sintéticos, no una sesión real).
- [ ] **Step 2: Run** `./mvnw test -Dtest=ChromiumCookiesTest` → FAIL.
- [ ] **Step 3: Implement** `classify` y `decryptValue` (v10). `findSessions`: solo si `classify` da `SUPPORTED`; en macOS obtiene la clave del llavero (`security find-generic-password` para "Chrome/Chromium/Brave/Edge Safe Storage"), abre la copia de la base `Cookies`, descifra y filtra por dominio; en Windows devuelve lista vacía (la UI usará B/C). No escribir valores en logs.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(login): read a Chromium session on macOS, detect unsupported cases`.

## Task 5: Ventana de login integrada (fuente B)

**Files:**
- Create: `src/main/java/app/unichat/ui/LoginWebView.java`
- Modify: `pom.xml` (añadir `javafx-web`)
- Test: `src/test/java/app/unichat/login/LoginUrlsTest.java` (lógica pura; el WebView se prueba a mano)

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `LoginWebView.open(Window owner, Network, CookieSpec, Consumer<SessionCookies> onDone, Runnable onCancel)`.
  - `LoginUrls.startUrl(Network) -> String` y `LoginUrls.cookieStore(Network) -> ...` (helpers puros).

- [ ] **Step 1: Write the failing test** — `LoginUrlsTest`: `startUrl(INSTAGRAM)` = `https://www.instagram.com/accounts/login/`, y las URLs de MESSENGER/X/LINKEDIN/GOOGLE_MESSAGES. `userAgent()` contiene una versión de navegador reciente (no el UA por defecto de JavaFX).
- [ ] **Step 2: Run** `./mvnw test -Dtest=LoginUrlsTest` → FAIL.
- [ ] **Step 3: Implement** `LoginUrls`, luego `LoginWebView`: `WebView` con `CookieManager` propio y vacío (para permitir una cuenta distinta), UA de navegador actual; tras cada carga, lee las cookies del store, arma `spec.select`, y cuando `complete()` cierra la ventana y llama `onDone`. Botón Cancelar → `onCancel`. Título y accesibilidad en el estilo del asistente.
- [ ] **Step 4: Run** `LoginUrlsTest` → PASS. (El flujo visual se valida a mano en Task 7.)
- [ ] **Step 5: Commit** `feat(login): in-app login window that captures the session`.

## Task 6: Orquestador de fuentes

**Files:**
- Create: `src/main/java/app/unichat/login/CookieLogin.java`
- Test: `src/test/java/app/unichat/login/CookieLoginTest.java`

**Interfaces:**
- Consumes: Tasks 2–5.
- Produces:
  - `CookieLogin.isCookieNetwork(Network) -> boolean`.
  - `CookieLogin.findBrowserSessions(Network) -> CompletableFuture<List<BrowserSession>>` (Firefox + Chromium-macOS; nunca lanza, lista vacía si nada).
  - `CookieLogin.fromCookieTxt(Path, Network) -> Result` (`Result` = ok(SessionCookies) | missing(List<String>) | error(msg)).

- [ ] **Step 1: Write the failing test** — `CookieLoginTest`: `isCookieNetwork` true para INSTAGRAM/MESSENGER/X/LINKEDIN/GOOGLE_MESSAGES y false para WHATSAPP/TELEGRAM/SIGNAL/DISCORD. `fromCookieTxt` con el sample de Task 2 → ok; con uno incompleto → missing con los nombres. `findBrowserSessions` no lanza cuando no hay navegadores (devuelve lista vacía) — se prueba apuntando las raíces a un tmp vacío vía propiedad de sistema `unichat.browserRoots`.
- [ ] **Step 2: Run** `./mvnw test -Dtest=CookieLoginTest` → FAIL.
- [ ] **Step 3: Implement** `CookieLogin` combinando las fuentes en el executor de E/S; captura excepciones por fuente. Añadir soporte de `unichat.browserRoots` a Firefox/Chromium para los tests.
- [ ] **Step 4: Run** → PASS; y `./mvnw test` completo → PASS.
- [ ] **Step 5: Commit** `feat(login): orchestrate browser, cookies.txt and in-app sources`.

## Task 7: Integrar en el asistente de Cuentas

**Files:**
- Modify: `src/main/java/app/unichat/ui/AccountsPane.java`
- Modify: `src/main/java/app/unichat/service/ChatService.java` (y `MockChatService`, `MatrixChatService` si hace falta un método para enviar cookies-JSON — reutilizar `sendLoginInput`)
- Modify: `docs/BRIDGES.md` (describir el nuevo flujo en español)
- Test: UI a mano con capturas (`-Dunichat.snapshot`), más que automatizado.

**Interfaces:**
- Consumes: Task 6.

- [ ] **Step 1:** En `AccountsPane`, para una red de cookies (`CookieLogin.isCookieNetwork`), el asistente muestra primero "Buscando tu sesión…" y llama `findBrowserSessions`.
- [ ] **Step 2:** Si hay sesiones, tarjeta(s) "Usar tu sesión de Instagram de Firefox (perfil X)" con **Usar esta sesión** (envía `cookies.toBridgeJson()` con `sendLoginInput`) y **Iniciar sesión con otra cuenta** (→ `LoginWebView`). Siempre un botón **Elegir archivo cookies.txt…** (`FileChooser` → `fromCookieTxt`; si `missing`, mostrar qué falta) y el enlace discreto "Pegar cookies manualmente".
- [ ] **Step 3:** Antes de leer Chromium en macOS, un texto: "macOS te pedirá permiso para usar tu sesión de <navegador>." El `onDone` del WebView y el "Usar esta sesión" desembocan en el mismo flujo de éxito/error del asistente (Task del wizard ya existente).
- [ ] **Step 4:** `./mvnw test` completo PASS. Capturas con el modo demo: tarjeta de sesión encontrada, pantalla de cookies.txt con error de cookie faltante, y la ventana B (contra una página local de prueba, no una red real).
- [ ] **Step 5: Commit** `feat(accounts): friendly login for cookie networks (browser, in-app, cookies.txt)`.

## Task 8 (aparte): proteger la lista de chats directos `m.direct`

**Files:**
- Modify: `src/main/java/app/unichat/matrix/MatrixChatService.java:addDirect`
- Test: `src/test/java/app/unichat/matrix/MatrixServiceDirectTest.java` (o añadir a uno existente de matrix)

**Interfaces:** sin API nueva.

- [ ] **Step 1: Write the failing test** — construir `m.direct` con una entrada previa para otro bot y comprobar que al agregar una sala para un bot nuevo **no se pierden** las entradas anteriores (hoy `addDirect` relee el contenido cacheado y podría pisar entradas si el sync va atrasado). Afirmar que el cuerpo PUT resultante conserva todas las claves.
- [ ] **Step 2: Run** → FAIL si procede (si ya es correcto, el test queda como regresión y se documenta).
- [ ] **Step 3: Implement** fusionar sobre el `m.direct` más reciente del servidor (GET antes de PUT) en vez de solo el cacheado.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `fix(matrix): merge m.direct so existing bot DMs aren't dropped`.

## Task 9 (added during execution): keep credentials out of the on-disk sync cache

**Why:** `MatrixChatService.saveSyncCache` writes the entire `/sync` response to disk (clear JSON),
including bridge-bot DM timelines. Those now carry the cookie JSON we send (and QR data / tokens), so
session credentials would sit on disk until logout. This contradicts the design's "nada se persiste en disco".

**Files:**
- Modify: `src/main/java/app/unichat/matrix/MatrixChatService.java` (`saveSyncCache`, maybe `SyncProcessor` to identify bridge-bot rooms)
- Test: `src/test/java/app/unichat/matrix/` (pure redaction helper)

**Interfaces:** no public API change.

- [ ] **Step 1: Write the failing test** — extract a pure static helper, e.g. `static JsonNode redactForCache(JsonNode fullSync, Set<String> botRoomIds)` that returns a deep copy of the `/sync` with, for each room id in `botRoomIds`, the `timeline.events[].content` (and `state` message content) removed/blanked while keeping membership/state needed for the room to resolve. Test: a bridge-bot room's message bodies (e.g. a value "sessionid=SECRET") are absent from the output; a normal room's timeline is untouched; `next_batch` preserved.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** the helper; in `saveSyncCache`, compute the bridge-bot room id set from the processor (rooms whose chat `bridgeBot()` is true) and write `redactForCache(fullSync, botRooms)` instead of the raw response. `loadSyncCache` still works (bot rooms load with empty timelines — fine; they're management chats).
- [ ] **Step 4: Run** → PASS; full `./mvnw test` PASS.
- [ ] **Step 5: Commit** `fix(matrix): keep bridge-bot credentials out of the on-disk sync cache`.
