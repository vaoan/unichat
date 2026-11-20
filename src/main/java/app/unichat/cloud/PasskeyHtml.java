package app.unichat.cloud;

/**
 * The passkey probe's HTML: two buttons, one makes a passkey for Unichat and one uses one (any made for {@code localhost},
 * here or synced from another computer or a phone), both asking for PRF with the fixed salt; each result goes back to
 * Unichat, whose answer, in the person's words, is what the page shows. The PRF bytes never leave the page: only how
 * many came.
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
                // Only what was found, never the PRF's bytes: how many came is all Unichat needs to know.
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
                function prfOf(credential) {
                  const prf = credential.getClientExtensionResults().prf || {};
                  const first = prf.results && prf.results.first;
                  return {enabled: prf.enabled, length: first ? first.byteLength : null};
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
                    const prf = prfOf(c);
                    const found = {step: 'create', credentialId: c.id, attachment: c.authenticatorAttachment || ''};
                    if (prf.enabled !== undefined) found.prfEnabled = String(prf.enabled);
                    if (prf.length !== null) found.prfLength = String(prf.length);
                    report(found);
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
                    const prf = prfOf(a);
                    const found = {step: 'get', credentialId: a.id, attachment: a.authenticatorAttachment || ''};
                    if (prf.length !== null) found.prfLength = String(prf.length);
                    report(found);
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
