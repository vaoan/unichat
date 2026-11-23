# Vault: easy ways in (passkey, approval from another computer) — design

## Intent
The person doesn't want to type a password or keep a code of their own to open the vault, and won't publish a
developer app (Google OAuth) because Unichat is published online. Two ways in give them a comfortable vault without
either:
1. **A passkey** kept in their Google account (Google Password Manager) or iCloud Keychain. It syncs to their devices
   by itself and opens with a fingerprint, face or PIN. No app is registered anywhere: the passkey's relying party is
   `localhost`.
2. **Approval from another computer**, like linking WhatsApp Web. A new computer asks, and a computer where the vault is
   already open approves.

This builds on `2026-11-19-vault-design.md` (keys, slots, computers, rotation, `Loopback`) and its phase 3. The probe
(`2026-11-19-passkey-probe.md`) tells whether PRF works on the person's Windows and Mac.

## Goals
- **Passkey:**
  - Add one from an open vault, open the vault with one on a new computer, and remove one.
  - Several passkeys are allowed: one in Google Password Manager, one in iCloud, a phone.
- **Approval:**
  - A new computer asks for access, and any open computer of the person's approves it.
  - The new computer then opens by itself from then on, as every computer does today.
- **Both are fully optional.** The recovery code stays as the last resort. It is shown once at creation, as today,
  and Unichat suggests keeping it as a note in the same password manager.
- **Mac and Windows both.**

## Non-goals
- A phone app.
- Google OAuth or any developer app.
- Replacing the recovery code.

## Anti-goals
- **Approval doesn't depend on the cloud being honest.** Anyone with the cloud account can write a request. Approving
  without seeing the new computer's own screen must not be possible.
- **No secret is stored outside the vault's sealed form.** The passkey's PRF output is never written anywhere.
- **No new network connection** beyond the cloud already connected, and the local page on `localhost:43117` that
  pCloud, Dropbox and the probe already use.

## Approaches
- **Passkey through a page Unichat serves on `localhost` (chosen).** It works in any browser the person uses, and the
  passkey syncs through their own password manager. Rejected alternatives:
  - The OS's own APIs (Windows Hello's or Apple's) only make passkeys for their own manager, need a signed app
    identity, and don't sync between Windows and Mac.
- **Approval as a request file in the vault folder, with a code typed back (chosen).** It reuses the computers'
  X25519 keys and slots. Rejected alternatives:
  - A direct connection between the computers (LAN or QR) needs both on the same network and a new listener.
  - Comparing two codes by eye is weaker than typing one, because a hurried person clicks "Sí".

## Design

### Passkey
- **Slot `PASSKEY`**, already in the enum. Its `params` are:
  - `{credentialId, salt, rpId: "localhost", created, label}`;
  - `salt`: 32 random bytes per slot, not the probe's fixed one (see the threat note).
- **Wrapping key:** HKDF-SHA-256 over the 32-byte PRF output, with info `"unichat-vault/passkey/1:" + vaultId`. It
  wraps VK the way every slot does, under the slot's AAD with its generation.
- **Adding** (vault open):
  1. Ajustes → Nube → Bóveda → "Agregar llave de acceso" opens `http://localhost:43117/vault-passkey` in the default
     browser.
  2. The page creates a passkey with `residentKey: required`, `userVerification: required` and `extensions.prf`. The
     user handle is random and the display name is "Unichat (bóveda)".
  3. It then calls `get()` on that credential with the slot's salt in `prf.eval.first`, and posts the PRF output back
     through `Loopback` (`state`, deadline, cancel), over loopback only.
  4. With no PRF result, nothing is saved, and the page and Ajustes say that this manager can't open the vault.
  5. The person names it ("Google", "iCloud"…).
- **Opening** (a vault in the account, not opened here): "Abrir con llave de acceso".
  1. The page runs `get()` with `allowCredentials` set to every passkey slot's id and `prf.evalByCredential` giving each
     slot its own salt.
  2. The browser offers whichever the person has. The one that answers unwraps VK.
  3. Then, as with every way in, this computer gets its own computer slot and opens by itself from then on.
- **Removing:** the slot is deleted. "Cambiar la llave" re-wraps the other passkey slots by asking each one again.
  - Ruling: re-wrapping passkeys needs each one's PRF, and nobody can be asked for all of them at once. So after a key
    change the passkey slots are dropped, and the screen offers "Agregar de nuevo" for each, as it does today for a
    password.
- **Threat note:**
  - The `localhost` relying party is shared by every page any program serves on `localhost`. Such a page could call
    `get()` after one prompt. But without a slot's salt, which sits in the cloud account, it gets a value that opens
    nothing.
  - Someone with both the cloud account and a program serving on the person's localhost, plus the person approving a
    prompt, could open it. This goes in PRIVACY as the passkey's limit.

### Approval from another computer
- **New computer:**
  1. "Pedir acceso desde otro equipo" makes its computer keypair (X25519, as computer slots use today).
  2. It writes `Unichat/Vault/requests/<random id>.json` with `{pub, label, created, expires: +15 min}`.
  3. It shows a **code of 6 digits** in groups of 3, derived from SHA-256(`"unichat-vault/approve/1"` ‖ vaultId ‖ pub),
     and waits. A round reads its request back every minute and "Revisar ahora" checks at once.
- **Open computers:**
  1. Each round lists `requests/`. A pending, unexpired request appears in Ajustes → Nube → Bóveda and as a
     notification: "<label> pide abrir la bóveda". The label comes from the account, shown only through `CloudTexts`
     as computers' names are today.
  2. **Approving needs the 6 digits typed** from the new computer's screen. Unichat compares them with the code it
     derives from the request's `pub`. A wrong code refuses, and three wrong codes delete the request.
  3. Approved: a computer slot for that `pub` (VK sealed to it, the current generation) and the peer pinned, as the
     confirmed set does today. Then the request is deleted.
- **The new computer** finds its slot in the next round, opens, and says so. A request deleted without a slot means it
  was refused or expired, and the screen says which ("Rechazado" / "Venció: pide de nuevo").
- **Limits:**
  - One pending request per computer.
  - A request past `expires` is ignored and deleted by any open computer.
  - A request whose `pub` is already a computer's is ignored.
- **Threat note:**
  - A request written by someone with cloud access shows a code on no screen of the person's, so it can't be approved
    by typing what the new computer shows.
  - A removed computer can still write requests, and it gets nothing unless approved again.
  - Approval is logged in the vault's computers list (who approved, when).

## Tests (each part, headless and with fakes)
- **Passkey:**
  - The page's script is checked as text: `prf`, `evalByCredential` and no external scripts.
  - The Loopback endpoint: `state`, deadline and cancel.
  - The slot: wrap, unwrap, a wrong PRF fails, and the AAD/generation.
  - Adding with no PRF saves nothing and says so.
  - Opening picks the slot by the credential that answered.
  - A key change drops the passkey slots and offers "Agregar de nuevo".
- **Approval:**
  - The code's derivation.
  - The request's lifecycle: written, seen, approved, the slot, deleted.
  - A wrong code refuses, and three wrong codes delete the request.
  - Expiry.
  - A request with an existing `pub` is ignored.
  - The new computer opens from its slot.
  - A refused or expired request says so.
  - The screens: Ajustes rows, the notification, typing the code by keyboard, and the text at 160 %.
- **Both:**
  - `CloudTexts` lines.
  - PRIVACY/PRIVACIDAD updated.

## Phases
1. **Approval from another computer.** It needs no probe and works on both systems.
2. **The passkey,** once the probe has run on the person's Windows and Mac:
   - If Windows gives no PRF with any manager, it ships for macOS only, and the person is told.
   - If Google Password Manager gives PRF on both, that is the recommended one.

## Left to the builder
- Class names, the page's look, and how the request list is read in the round.

## Builder's questions, settled (2026-11-23; my calls unless marked)
1. **The code can't be 6 digits.** Anyone who can write to the cloud could grind a key whose 6-digit code matches the person's (about 2^20 tries, seconds), and replace the request.
   - The code is **12 characters** in 3 groups of 4 (Crockford base32, about 60 bits): the first 60 bits of SHA-256(`"unichat-vault/approve/1"` ‖ vaultId ‖ requestId ‖ machineId ‖ pub). Grinding 2^60 X25519 keys is out of reach.
   - The person types it on the approving computer. Upper or lower case, and with or without dashes, both work.
   - Wrong tries are a slip guard only (see 4), not the defence.
   - Code length: 12 characters, approved by the person (2026-11-23).
2. **How VK reaches the new computer.** The approver doesn't write a computer slot. It writes a **grant** next to the request (`requests/<id>.grant.json`): VK sealed to the request's `pub` through an ephemeral X25519 ECDH + HKDF, the same seal `VaultNext`'s offers use (reuse it).
   - The grant's AAD binds requestId, machineId, vaultId and generation.
   - The new computer unwraps VK, writes its own normal computer slot (symmetric key in `session.properties`, as today), and deletes the request and the grant.
3. **Who is asking.** The request carries the machine id (`Ids.ours`).
   - If a computer slot already exists for that machine id, the approver says "reemplaza a <name>" before the code. Approving deletes that old slot when the new one appears.
   - A request with an existing computer's `pub` is ignored.
4. **Limits.**
   - Wrong-code tries are counted per request on the approving computer (3, then that computer hides it).
   - At most the 5 newest pending requests are listed, and each notifies once.
   - Requests past `expires` are deleted by any open computer.
5. **Races.**
   - Approving is a vault job (under `vaultPart`, as every vault job is). It seals the current generation.
   - "Cambiar la llave" deletes every pending grant, so the new computer sees its grant gone and its request still there: "La llave cambió: pide de nuevo".
   - A second approver finds the grant already there and only says "ya aprobado".
   - The approver is recorded in the new slot's label line ("aprobado por <name>, <date>").
6. **The new computer while it waits.**
   - "Pedir acceso desde otro equipo" is offered wherever "Abrir la bóveda" is (locked, removed, unrecognized, and declined, where it ends "seguir sin la bóveda" once granted).
   - Writing a request needs no key: it is plain JSON in `requests/`.
   - It checks every round, plus "Revisar ahora".
   - Open computers see requests in their small round, which keeps running while unseen (only media pauses), so no wait beyond a minute.
   - On expiry the screen also points to the recovery code.
7. **Passkey loose ends.**
   - Port 43117 busy: the same message as pCloud and Dropbox give today.
   - The page gets the slots' ids and salts only from its own state-protected GET, with the probe's Host/Origin checks.
   - A credential matching no slot: "Esta llave no es de esta bóveda".
   - The PRF value is posted on loopback only, with `state`, Host and Origin checked. While Unichat holds the port, no other process can.
8. **Phases.**
   - Approval ships on its own, first.
   - For the passkey, "Agregar llave de acceso" is always shown. A manager without PRF fails at adding, with the reason.
   - After a key change, "Agregar de nuevo" is offered on every computer, so the person re-adds from wherever they have that passkey.
