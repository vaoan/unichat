# Vault — design

> **Change (2026-11-20):** no "Google" way in through OAuth. The person wants nothing of theirs registered anywhere
> (Unichat is public: no client id of theirs can ship in it, and an OAuth app publishes its developer's details). The
> comfortable way in is the **passkey**, kept in the person's password manager (Google Password Manager or iCloud
> Keychain), which needs no registration at all; recovery code and password stay. Where this document says Google,
> read: dropped.

## Intent
Everything dangerous, personal or identifying that Unichat keeps lives in one place, the vault, which goes to the
person's cloud account encrypted with a key the cloud never sees. On a new computer the person connects the cloud,
opens the vault with any of their ways in (Google, a passkey, a password, or the printed recovery code) and Unichat has
everything back: the keys it needs, the settings, what the AI learned, the texts kept. The person uses several
computers at once; each keeps its own sessions with the networks, and each can be replaced from the vault if it's lost.

Today none of it is encrypted: the settings, `preferences.json`, `writing-style.md` and the texts kept go to the cloud
as they are, and the secrets don't travel at all (`Settings.staysOnThisMachine`), so a new computer starts without its
Telegram API keys, its Giphy key or its cloud apps' keys.

## Goals
- One vault per person, in their cloud account (`Unichat/Bóveda/`), every file in it encrypted and authenticated.
- Opened by any of several ways in, each optional except the recovery code: the **recovery code** (always, shown once
  at creation), a **password**, **Google** (a key kept in the person's Google Drive), a **passkey** (WebAuthn PRF). Any
  of them opens the same vault; adding or removing one never touches the data.
- What every computer shares (the person's): the secrets Unichat needs that aren't a computer's own (below), the
  settings, `preferences.json`, what the AI learned, the texts kept. Kept the same on all of them, live, as today.
- What each computer keeps for itself (its sessions with the networks and its own server's secrets): a backup of it in
  the vault, in that computer's own part, so a computer that replaces a lost one comes back without signing in again.
- Once the vault is on, nothing of the above is left in the cloud unencrypted (the old plain copies are removed, after
  saying so).
- Optional everywhere: without a vault Unichat works as today, everything entered by hand; and a computer that finds a
  vault in the account can choose not to use it ("Seguir sin la bóveda en este equipo"), entering its keys by hand as
  before, without being asked again (it can open the vault later from Ajustes → Nube).
- The vault and media are independent: each is on or off by itself, neither needs nor implies the other. Media goes up
  (or not, by its switch) whatever the vault's state; the vault syncs whatever media's switch says (the texts kept go
  with the vault when it's open, no longer behind media's switch).
- Accessible and keyboard-reachable like every screen; bilingual.

## Non-goals
- Photos, videos and files kept by auto-save: they go up as today, unencrypted (the person's choice: size).
- Sharing a network session between computers that run at the same time (the networks close one; WhatsApp and Signal
  may block the account). A computer added to the person's set signs in to each network as a device of its own.
- A Unichat server, or any account with us: the vault is the person's cloud and, for that way in, their Google.
- The cloud account's own login (`cloud.*`): it's how the vault is reached, so it can't be inside it.

## Anti-goals
- Anything readable in the cloud, or in Google, alone: the cloud holds only ciphertext, Google only a key that opens
  nothing without the cloud's files.
- Losing every way in and the vault with it: the recovery code always exists, and the person is asked to confirm they
  kept it before the vault is made.
- The vault deciding what runs here: what makes this computer run something (`stt.command`, `stt.engine`), and paths of
  this computer (`autosave.*`, `ai.folder`), never come from it, even encrypted.
- A computer's sessions restored onto a computer while the one they belong to still runs (the network sees two devices
  with one identity). Restoring a computer's part is an explicit "this computer replaces <name>", with a warning.
- Logging or printing a secret, the vault key, a way in's secret or the recovery code (the existing redaction rules).

## Approaches considered
1. **One password** derives the key that encrypts everything. Simple, no third party; the password is the only way in,
   and changing it re-encrypts everything.
2. **Google holds the key** (Drive's hidden app folder) and nothing else. Comfortable; Google alone decides access, and
   no way in survives losing the Google account.
3. **Passkey only** (WebAuthn PRF). The strongest; depends on browser and OS support, which only arrived on Windows
   Hello in February 2026 (Windows 11 24H2/25H2 with KB5077181, Chrome 147+; Google Password Manager's passkeys have
   PRF everywhere). Unverified on the person's machines yet.
4. **Chosen: a random vault key, wrapped once per way in** (envelope encryption, as LUKS key slots or 1Password's
   account kit). The data is encrypted once with the vault key; each way in only wraps that key. So all four ways can
   coexist, any of them opens it, one is added or removed by writing or deleting its slot, and none of them is the only
   way. The person asked for all of them; they arrive in phases (below), each usable as soon as it's done.

## Design

### Keys and slots
- **Vault key (VK):** 256 random bits (`SecureRandom`), made once when the vault is created.
- **Encryption:** AES-256-GCM (the JDK's), a fresh 96-bit nonce per write; the associated data is the file's name in
  the vault and the format version, so a file can't be swapped for another or replayed under another name.
- **Slots** (`Unichat/Bóveda/llaves/<id>.json`, one file per slot, so adding one never overwrites another's; not
  secret by themselves): one per way in, each `{method, id, created, generation, params, wrapped}`, where `wrapped` is
  VK encrypted (AES-GCM) with that way's key:
  - **recovery**: a code of 128+ random bits shown as groups of words or characters (e.g. 8 groups of 4, Crockford
    base32), never stored here; its key comes from it by HKDF-SHA-256 (high entropy: no slow KDF needed).
  - **password**: the key comes from it by Argon2id (memory-hard; the JDK has none: Bouncy Castle's, a pinned
    dependency like the others), with its salt and parameters in `params`. A minimum strength is asked for.
  - **google**: a random 256-bit wrapping key kept as a small file in the person's Google Drive app data folder
    (`drive.appdata` scope: Unichat sees only its own hidden folder, nothing else of the person's Drive). Opening:
    sign in with Google in the browser, read that file, unwrap. The cloud holds the wrapped VK, Google the wrapper:
    either alone opens nothing.
  - **passkey**: a passkey made for Unichat; its PRF output for a fixed salt, through HKDF, is the wrapping key; the
    credential id is in `params`. Nothing of it is stored anywhere.
  - **computer**: each computer the vault was opened on gets a slot of its own, wrapped with a random key kept in that
    computer's machine-only file (`session.properties`, owner-only, next to the Matrix token it already holds), with
    the computer's name and when it was last seen. This is how a computer reads the vault every minute without asking.
    Ajustes → Nube → Bóveda lists the computers; removing one (a lost or sold one) deletes its slot.
- **The vault itself:** `Unichat/Bóveda/boveda.json` `{id, created, generation}`. Two computers creating a vault at the
  same moment: the one whose file is in the account first wins; the other discards its own and offers to open that one.
- **Changing the key:** removing a way in or a computer deletes its slot, but whoever copied that slot and its secret
  before still has VK. "Cambiar la llave de la bóveda" makes a new VK, re-encrypts every file (small: no media), raises
  the generation and re-wraps every way and every computer left, so the person's other computers go on without being
  opened again and a removed one can't read anything new; the recovery code is shown again. A computer that finds the
  generation raised and no slot of its own says it was removed and stops sending.

### What's in it
- **Shared by every computer** (one copy, kept the same everywhere, `SyncedPart` semantics over the decrypted content:
  only one side changed → it goes to the other; both → the newer; a new computer takes what's there):
  - `secrets`: the keys that are the person's, not a computer's: `server.telegram.apiId` / `apiHash`, `gif.*.key`,
    `cloud.dropboxAppKey`, `cloud.pcloudClientId`. Applied live as their screens apply them.
  - `settings`, `preferences`, `writing-style` (+ what it learned): what `Bundle` and `SyncedPart` send today, now
    encrypted.
  - `texts`: `TextSync`'s per-computer files, each encrypted, same layout (each computer writes only its own).
  - `pinned-messages.json` and transcripts of voice notes (`transcripts/`): personal, small. (`media-library.json`
    stays out, as `Bundle` keeps it today: it changes at every use, and "newest wins" between two computers in use at
    once would overwrite each other's recents.)
- **Each computer's own part** (`Unichat/Bóveda/equipos/<machine>/`, written only by that computer): a backup for
  replacing it, never applied to another computer that's running:
  - its machine-only keys that are its sessions (`matrix.*`, `session.*`), its server's secrets
    (`server/secrets.properties`, registrations, `doublepuppet`), and its bridges' sessions.
  - **Open, needs a spike before it's built:** what a bridge's session is, at the least. A bridge's database holds its
    login and its portals; the portals point at rooms of this computer's homeserver (`server/tuwunel`, 7.3 GB here: far
    too big to back up). The spike finds, per bridge, whether its logins restored into a fresh bridge database on a
    fresh homeserver bring the account back (bridgev2's `user_login` and the network's own session tables, e.g.
    whatsmeow's device keys, Telegram's session), and what each network then does (re-sync, history). Until it does,
    this part isn't built; the rest doesn't depend on it.
- **Never in it:** media; `cloud.*` (the way to the vault); `stt.command`, `stt.engine`, `autosave.*`, `ai.folder`
  (what runs here and this computer's paths); `window.*`, `app.update.*` and the other markers.

### Files in the cloud
- `Unichat/Bóveda/boveda.json`, `Unichat/Bóveda/llaves/` (slots), `Unichat/Bóveda/<part>.enc` (shared parts), `Unichat/Bóveda/textos/<machine>/…`
  (texts, encrypted per file, with an encrypted index), `Unichat/Bóveda/equipos/<machine>/…`. Names are ours only
  (parts, machine UUIDs), never anything from a chat.
- Each `.enc` is `{version, nonce, ciphertext}`; the agreed hash (`cloud.base.<part>`) is of the plaintext, so
  re-encrypting (a new nonce) is never mistaken for a change.

### Turning it on, opening it, the screens
- Ajustes → Nube gains "Bóveda":
  - **Off** (today): "Crear bóveda" makes VK, shows the recovery code (copy, save as a file, print), asks to type two of
    its groups back, then offers the other ways in. The first round moves the plain copies into the vault and removes
    them from the cloud, saying which.
  - **A vault in the account but not opened here** (a new computer): "Abrir la bóveda" with each way in that has a slot.
    Opened, the shared parts are applied live, as settings from another computer are today (`SyncedSettings`), and if
    the account has computers' parts: "¿Este equipo reemplaza a otro?" with each one's name and when it was last seen,
    and the warning that the other must not be running.
  - **Open**: the ways in (add, remove) and the computers (remove), "Hacer un código nuevo" (the code is never stored,
    so it can't be shown again: a new recovery slot, the old one deleted), "Cambiar la llave", "Olvidar la bóveda en
    este equipo" (its slot and its key here deleted; it stops syncing what the vault holds until opened again), and
    "Apagar la bóveda".
  - **Apagar la bóveda** (asked twice, the second time saying what happens): the shared parts go back to the plain
    sync of today, decrypted into the places they had before; once each plain copy is confirmed in the account, the
    vault is deleted from it (`Unichat/Bóveda/` whole, each computer's part too). Every other computer finds the vault
    gone and goes back to plain sync by itself, and forgets its key.
- Google and the passkey open in the browser through `Loopback` (127.0.0.1:43117, `state`, a deadline, cancel), as
  pCloud and Dropbox do: Google with OAuth 2 + PKCE for a desktop client; the passkey on a page Unichat serves on
  `http://localhost:43117` (a secure context; the passkey's relying party is `localhost`), which runs WebAuthn with the
  PRF extension and posts the result back.
- While a vault exists but isn't opened here, nothing personal is sent up unencrypted from this computer: its shared
  parts wait. The same on a computer that chose to go on without it: its settings, AI and texts stay on it (media go
  up as today), and its keys are entered by hand.
- A computer of the person's still running a Unichat without the vault keeps sending plain copies. Computers with the
  vault don't delete them over and over: they notice plain files newer than the move, say which computer sends them
  ("<name> usa una versión sin bóveda: actualízalo"), and delete them once it's updated (no newer plain files for a
  day).
- `CloudTexts` gains the vault's states, in the same words in Ajustes → Nube, the tray and the gear's ring.

### Who can do what (threat model, short)
- Someone with the cloud account: ciphertext and slots; nothing without a way in.
- Someone with the Google account: a wrapping key; nothing without the cloud's files.
- Someone with both: everything (as with the person's own two accounts); a passkey or a password alone avoid this.
- Someone with this computer's disk: what this computer already has in the clear (it runs with it), plus its
  computer slot's key; removing that computer and changing the key shuts it out of anything new.
- Someone who can write to the cloud account can put back an older copy of a part, or delete slots (a denial, which the
  recovery code survives), but can't read or forge one. Rollback protection (a counter per part) is out of scope.
- Someone who can only write to the cloud account can't hand a computer a new key: every offer (`next`) carries what
  the old vault key sealed of it (`auth`: its ephemeral key and the hash of what it seals), and `boveda.json` a
  `vouch` of its generation sealed with the key it replaced. A generation raised without either leaves each computer
  `UNRECOGNIZED` (its key kept, nothing sent) rather than removed, and a way in from before still opens the vault there,
  only for the key its own slot opens. A new computer uses the current generation's ways in only, and each slot is
  sealed with its generation ("slot:<vault>:<method>:<id>:<generation>"), so an older one put back claiming the current
  generation opens nothing. A slot written before that (no generation in what sealed it) still opens and is written
  again by whoever opens it; until then its generation is the account's to say.
- Deleting `boveda.json` by hand sends nobody plain: turning the vault off first writes `Unichat/Ajustes/boveda-apagada.json`
  (the vault and generation sealed with a key made from that generation's vault key, kept by each computer while the
  vault is open there), and a computer holding a vault goes back to plain only when that mark opens with its key;
  otherwise it is `MISSING` and the person decides ("Volver a sincronizar sin cifrar", asked twice). A computer that
  declined the vault, or never opened it, has no key to check the mark with: it keeps the id of the vault it last saw
  (`cloud.vault.seen`, any state) and is `MISSING` too, so a real turn-off asks it once.
- A removed computer still holds the old vault key, so it can vouch for an offer of its own to a computer that was off
  during the removal (and missed the change that shut it out) and keep reading what that computer then seals. The
  limit holds until that computer is opened with a way in (recovery code, password), which writes it a slot of its own.
- Texts come into the vault from plain folders only for the machines sending plain when the move started, and only as
  new files: a folder or a longer copy put in through the account is named, never sealed in. That list is the vault's
  own, sealed (`Ajustes/unichat-senders.json`), written once by the computer that made the vault; the others adopt it,
  and one finding none (deleted through the account) takes nothing in.
- A stranger in a chat: nothing new (names never become vault paths; nothing from the vault runs).

### Phases
1. The vault, the recovery code and the password; shared parts (secrets, settings, preferences, writing style, texts,
   the small personal files); moving the plain copies in. Usable alone.
2. Google, once Unichat's Google OAuth app is registered (consent screen; check whether `drive.appdata` needs Google's
   verification, and its privacy policy link: `docs/PRIVACY.md`).
3. The passkey, after trying PRF on the person's Windows (Windows Hello, Chrome 147+) and Mac (iCloud Keychain, Google
   Password Manager) with a probe page. It's meant to open the vault on a new computer: the probe checks that a
   passkey made for `localhost` on one computer is offered on another (synced by Google Password Manager or iCloud
   Keychain, or from a phone). If it isn't, it's offered only as a quick way in on the computer that made it, and the
   screen says so.
4. Each computer's own part, after the spike above.

### Docs and tests
- `docs/PRIVACY.md` and `docs/PRIVACIDAD.md` (what goes where, encrypted, and the new connection to Google), README /
  README.es, `cloud/CLAUDE.md`, `settings/CLAUDE.md` (what's in the vault vs machine-only).
- Turning it off: every shared part back where it was, plain, and the vault gone only after; another computer finds it
  gone and goes back by itself.
- Tests (fakes for the cloud, Google and the browser, as `FakeDav`, `FakePCloud`, `FakeDropbox`): every slot opens VK
  and a wrong secret doesn't; a file renamed or swapped fails to open; re-encrypting isn't a change; two computers
  editing the same part converge; a new computer takes what's there; the plain copies leave only once their encrypted
  copy is confirmed there; nothing machine-only or from `stt.*` is ever in a part (a test like `SyncedSettingsUiTest`
  over every key in the source); a vault not opened here sends nothing plain; each screen and each way to reach it,
  headless, at 160 % and by keyboard; secrets never in a log (`Redactor`).

## Left to the builder
- Argon2id's parameters (aim near a second on the slowest computer the person uses), the recovery code's alphabet and
  grouping, the exact file format and its versioning.
- How the shared parts map onto `SyncedPart` (one class wrapping it with encryption, or a sibling), and how `TextSync`
  learns to encrypt its files.
- The screens' wording and layout within the rules of `ui/settings` and `.claude/rules/i18n.md`.
