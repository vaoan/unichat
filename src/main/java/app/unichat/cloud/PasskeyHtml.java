package app.unichat.cloud;

/**
 * The passkey probe's HTML: two buttons, one makes a passkey for Unichat and one uses one (any made for {@code localhost},
 * here or synced from another computer or a phone), both asking for PRF with the fixed salt; each result goes back to
 * Unichat, whose answer, in the person's words, is what the page shows. The PRF bytes never leave the page: only how
 * many came and a fingerprint of them.
 */
final class PasskeyHtml {
    private PasskeyHtml() {}

    /** {@code state} is hex and {@code salt} base64, both ours; {@code texts} a JSON object safe inside a script. */
    static String page(String state, String salt, String texts) {
        return """
                <!doctype html><html><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1"><title>Unichat</title>
                <style>
                body { font-family: system-ui, sans-serif; max-width: 40em; margin: 3em auto; padding: 0 1em; line-height: 1.5 }
                button { font: inherit; padding: .6em 1.2em; margin: .5em .5em .5em 0 }
                li { margin: .4em 0 }
                @media (prefers-color-scheme: dark) { body { background: #1e1e1e; color: #eee } }
                </style>
                <body><main>
                <h1 id="title"></h1><p id="intro"></p>
                <p><button id="create"></button><br><span id="createHelp"></span></p>
                <p><button id="use"></button><br><span id="useHelp"></span></p>
                <ul id="log" aria-live="polite"></ul>
                </main>
                """ + script(texts, state, salt);
    }

    /** What runs on the page: the two steps and the report back. */
    private static String script(String texts, String state, String salt) {
        return """
                <script>
                const T = %s;
                const STATE = "%s";
                const SALT = Uint8Array.from(atob("%s"), c => c.charCodeAt(0));
                document.documentElement.lang = T.lang;
                document.title = T.title;
                for (const id of ['title', 'intro', 'create', 'use', 'createHelp', 'useHelp']) {
                  document.getElementById(id).textContent = T[id];
                }
                function random(n) { const b = new Uint8Array(n); crypto.getRandomValues(b); return b; }
                function say(text) {
                  const li = document.createElement('li');
                  li.textContent = text;
                  document.getElementById('log').appendChild(li);
                }
                // Only what was found, never the PRF's bytes: how many came and their fingerprint.
                async function report(found) {
                  found.state = STATE;
                  found.userAgent = navigator.userAgent;
                  try {
                    const r = await fetch('/passkey/result', {method: 'POST', body: new URLSearchParams(found)});
                    say(await r.text());
                  } catch (e) {
                    say(T.notSent);
                  }
                }
                // The same PRF value shows as the same fingerprint, here and on another computer; the value stays here.
                async function fingerprint(value) {
                  const label = new TextEncoder().encode('unichat probe fp');
                  const both = new Uint8Array(label.length + value.byteLength);
                  both.set(label);
                  both.set(new Uint8Array(value), label.length);
                  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', both));
                  return Array.from(hash.slice(0, 8), b => b.toString(16).padStart(2, '0')).join('');
                }
                async function prfOf(credential, found) {
                  const prf = credential.getClientExtensionResults().prf || {};
                  const first = prf.results && prf.results.first;
                  if (prf.enabled !== undefined) found.prfEnabled = String(prf.enabled);
                  if (first) {
                    found.prfLength = String(first.byteLength);
                    found.prfFingerprint = await fingerprint(first);
                  }
                  return found;
                }
                function failed(step, e) {
                  report({step: step, error: (e && e.name ? e.name : 'Error') + ': ' + (e && e.message ? e.message : e)});
                }
                async function create() {
                  if (!window.PublicKeyCredential) return failed('create', {name: 'NoWebAuthn', message: T.noWebAuthn});
                  say(T.working);
                  try {
                    const c = await navigator.credentials.create({publicKey: {
                      challenge: random(32),
                      rp: {id: 'localhost', name: 'Unichat'},
                      user: {id: random(16), name: 'Unichat', displayName: 'Unichat'},
                      pubKeyCredParams: [{type: 'public-key', alg: -7}, {type: 'public-key', alg: -257}],
                      authenticatorSelection: {residentKey: 'required', requireResidentKey: true, userVerification: 'required'},
                      extensions: {prf: {eval: {first: SALT}}}
                    }});
                    report(await prfOf(c, {step: 'create', credentialId: c.id, attachment: c.authenticatorAttachment || ''}));
                  } catch (e) {
                    failed('create', e);
                  }
                }
                async function use() {
                  if (!window.PublicKeyCredential) return failed('get', {name: 'NoWebAuthn', message: T.noWebAuthn});
                  say(T.working);
                  try {
                    const a = await navigator.credentials.get({publicKey: {
                      challenge: random(32),
                      rpId: 'localhost',
                      allowCredentials: [],
                      userVerification: 'required',
                      extensions: {prf: {eval: {first: SALT}}}
                    }});
                    report(await prfOf(a, {step: 'get', credentialId: a.id, attachment: a.authenticatorAttachment || ''}));
                  } catch (e) {
                    failed('get', e);
                  }
                }
                document.getElementById('create').addEventListener('click', create);
                document.getElementById('use').addEventListener('click', use);
                </script>
                """.formatted(texts, state, salt);
    }
}
