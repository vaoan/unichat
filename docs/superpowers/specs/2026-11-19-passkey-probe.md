# Passkey probe — can a passkey open the vault?

Phase 3 of the vault (`2026-11-19-vault-design.md`) makes a **passkey** the comfortable way in, kept in the person's
password manager (Google Password Manager, iCloud Keychain, Windows Hello). It only works if, on the person's own
computers and browsers:

1. a passkey made for Unichat (relying party `localhost`) can be made at all;
2. it gives back a **PRF** value (WebAuthn's `prf` extension) for a fixed salt: the vault's slot key would be
   HKDF(PRF, "unichat-vault passkey"); no PRF, no way in;
3. a passkey made on one computer is **offered on the other** (synced by Google Password Manager or iCloud Keychain,
   or through a phone), since the passkey is meant to open the vault on a new computer.

The probe checks the three before any slot or screen is built. Nothing is written to the vault; the passkeys it makes
are only for this test (they can be deleted afterwards from the password manager: they show as "localhost" / "Unichat").

## What it is

- `cloud/PasskeyPage` serves a page on `http://localhost:43117/passkey` (the same port and rules as the cloud sign-ins,
  `Loopback`: 127.0.0.1 only, a random `state`, ten minutes, then the port is free). The page has no script or style
  from anywhere else (`Content-Security-Policy: default-src 'none'`).
- **Crear una llave**: `navigator.credentials.create` with `rp.id = "localhost"`, a random user, ES256 or RS256, a
  discoverable key (`residentKey: required`), `userVerification: required`, and `extensions.prf.eval.first` = the
  fixed salt (SHA-256 of "unichat-vault passkey prf").
- **Usar una llave**: `navigator.credentials.get` with `rpId = "localhost"`, `allowCredentials: []` (any passkey made
  for Unichat, here or synced), `userVerification: required`, the same PRF salt.
- Each step posts back, with `state`: the step, whether PRF was enabled at creation, **how many** PRF bytes came back
  (never the bytes), the passkey's id, whether it was this computer's own authenticator (`platform`) or another
  (`cross-platform`: a phone, a security key), the browser's user agent, and any error's name and message. Unichat
  answers in plain words, which the page shows:
  - "La llave devolvió la clave que necesita la bóveda." — PRF works with that passkey;
  - "Esta llave no la devolvió." — that passkey can't open the vault;
  - "Llave creada, y dice que puede dar la clave…" / "…pero no puede dar…" / "…el navegador no dijo…" at creation;
  - "La prueba falló: NotAllowedError: …" — cancelled, timed out, or refused (the message says which).
- Each finding is appended to **`<data>/logs/passkey-probe.txt`** (and to `unichat.log`), one line each, with the
  time and the computer's system:
  - Windows: `%APPDATA%\Unichat\logs\passkey-probe.txt`
  - Mac: `~/Library/Application Support/Unichat/logs/passkey-probe.txt`

  It holds no secret: the passkey's id is public and the PRF value is never sent to Unichat.

## How to run it

The probe is in this branch's build only (not in a release). Unichat must be **closed** first: it only reads the flag
when it starts.

### Mac

1. Quit Unichat: ⌘Q (or the menu bar icon → Salir).
2. Open **Terminal** (Spotlight: ⌘Space, type "Terminal", Enter) and paste:

   ```
   open -a Unichat --args --passkey-probe
   ```

3. Unichat starts and opens the probe page in the default browser. To try another browser, copy the page's address
   (`http://localhost:43117/passkey`, always `localhost`, never `127.0.0.1`) into it: both report to Unichat. After
   ten minutes the page stops answering: Ajustes → Nube → Bóveda → **Probar llave de acceso (prueba)** (shown with
   a cloud account connected) opens it again, or start Unichat again as above.
4. Follow "On each computer" below.

### Windows

1. Quit Unichat: the tray icon (next to the clock) → right click → Salir.
2. Press **Windows + R**, paste this and press Enter:

   ```
   "%LOCALAPPDATA%\Unichat\Unichat.exe" --passkey-probe
   ```

   If Windows says it can't find it (Unichat installed elsewhere): Start menu → right click "Unichat" → Más →
   Abrir ubicación del archivo → right click the shortcut → Propiedades → copy "Destino", and use that path in its
   place, keeping `--passkey-probe` after it.
3. Unichat starts and opens the probe page in the default browser (Chrome 147 or later: Chrome → ⋮ → Ayuda →
   Información de Google Chrome shows the version and updates it).
4. Follow "On each computer" below.

### On each computer

On the **first** computer, for each password manager to try:

1. Press **Crear una llave**. The system's window asks where to keep it:
   - Windows Hello: "Windows Hello" / "Este dispositivo" (PIN or fingerprint);
   - Google Password Manager: in Chrome, signed in to Chrome with your Google account, choose "Gestor de contraseñas
     de Google";
   - iCloud Keychain: on the Mac, Safari or Chrome, choose "Contraseñas de iCloud" / "Llavero de iCloud".
2. Read the line the page shows, then press **Usar una llave**, choose the same passkey, and read the line again.

On the **second** computer (to check that the passkey travels): press only **Usar una llave**, and see whether the
passkey made on the first computer is offered (Google Password Manager: same Google account in Chrome; iCloud: same
Apple account). If only "usar un teléfono" (a QR code) is offered, try that too with the phone where the passkey is,
and note it.

When done, send the file `passkey-probe.txt` (paths above) and fill the table below. The page stops after ten minutes;
start again to go on.

## What the results mean

- `step=get … prfLength=32` → **PRF works** with that passkey on that browser: it can open the vault there.
- `step=get … prfLength=none` with no error → that passkey/browser gives no PRF: it can't be a way in there.
- `step=create … prfEnabled=true` → made with PRF; `false` → without (it won't give one at `get` either);
  `unknown` → the browser didn't say: the `get` line decides.
- `attachment=platform` → the computer's own (Windows Hello, iCloud Keychain on the Mac, Chrome's own profile);
  `cross-platform` → a phone or a security key.
- `error="NotAllowedError: …"` → cancelled or timed out; `"SecurityError: …"` → the page wasn't at `localhost`;
  `"NoWebAuthn: …"` → the browser has no passkeys.
- The second computer's `step=get` line with the **same `credentialId`** as the first's `step=create` → the passkey
  travels: it can open the vault on a new computer. If not, the passkey is offered only as a quick way in on the
  computer that made it, and the screen will say so (design, phase 3).

**Stop rule (task 13):** if Windows (Windows Hello or Google Password Manager on Chrome 147+) gives no PRF, the passkey
isn't built; the person is told.

## Results

Fill one row per try (Sí / No / — not tried); "Ofrecida en el otro" = the passkey made here is offered on the other
computer.

| Computer | Browser (version) | Where the passkey is kept | Created | PRF at create | PRF at use (length) | Offered on the other computer | Notes |
|---|---|---|---|---|---|---|---|
| Windows 11 (build: ) | Chrome ( ) | Windows Hello | | | | | |
| Windows 11 | Chrome ( ) | Google Password Manager | | | | | |
| Mac (macOS ) | Safari ( ) | iCloud Keychain | | | | | |
| Mac | Chrome ( ) | iCloud Keychain | | | | | |
| Mac | Chrome ( ) | Google Password Manager | | | | | |
| Phone (QR, from either) | | | | | | | |

Conclusion (to fill): PRF on Windows: … · passkey travels Windows ↔ Mac through: … · phase 3 goes ahead as: …
