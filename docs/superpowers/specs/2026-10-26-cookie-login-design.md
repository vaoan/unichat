# Signing in without cookies by hand (Instagram, Messenger, X, LinkedIn, Google Messages)

## Why
Today, connecting a "cookie" network means opening the developer tools and pasting a cURL. The user called it
"horrible": they want something friendly, ideally using the browser they already use, like Spotify's sign-in. Instagram
offers no official sign-in that works for the bridges (OAuth only for business accounts, and with a token useless to
the bridge), and a web page can't hand another app the session of another site. So the session is taken on the user's
machine.

## Goals
- Connect Instagram, Messenger, X, LinkedIn and Google Messages **without ever seeing a cookie**.
- If the user already has the session open in their browser, use it with one click (A).
- If that can't be done, a sign-in window inside Unichat (B), with user, password and two-step verification as on the
  web.
- An alternative: choose a **cookies.txt** file (C) and, if it has what's needed, connect by itself.
- Work on Windows (the main target) and macOS. Several accounts per network.

## Non-goals
- Safari (it requires "Full Disk Access").
- Chrome/Edge on Windows with the new encryption ("v20" cookies, app-bound encryption, Chrome 127+): it's detected and
  goes straight to B, without asking for administrator rights.
- A browser extension of our own (rejected for now).

## Anti-goals
- Keeping the browser's session in Unichat: it's only read at that moment and sent to the bridge on the user's own
  server. Nothing is uploaded anywhere else.
- Reading cookies of other sites: only the chosen network's domains.
- Asking for permissions without explaining why (the macOS keychain prompt is announced first).

## Approaches considered
- **A. The browser's session** (reading cookies locally, as Beeper does with `credential-extractor`). Nothing to type,
  but it doesn't work with Chrome/Edge on Windows.
- **B. A built-in sign-in window** (a JavaFX WebView with the real page). It always works; the password has to be typed
  once.
- **A browser extension of our own.** The closest to Spotify, but it requires installing an extension (and "developer
  mode" until it's published). Rejected.
- **C. A cookies.txt file** (the Netscape format, the one extensions such as "Get cookies.txt LOCALLY" export, which the
  user already uses in other projects). Useful when A can't see the browser (e.g. Chrome on Windows) and one doesn't
  want to type the password.
- **Chosen by the user: A + B + C.** A first; if it finds no session, B, with C always available.

## How it works (decided)
1. In the wizard, on choosing a cookie network: "Looking for your Instagram session in your browsers…".
2. A — it looks for **Firefox** profiles (Windows/macOS/Linux; `cookies.sqlite` unencrypted, copied so as not to clash
   with the lock) and **Chrome, Edge and Brave** profiles (macOS: the "… Safe Storage" keychain key, AES-CBC "v10";
   Windows: DPAPI "v10"; "v20" = not supported). It checks that the network's essential cookies are there:
   - Instagram `sessionid`, `csrftoken` (+ `ds_user_id`, `mid`, `ig_did` if present)
   - Messenger/Facebook `c_user`, `xs` (+ `datr`, `fr`)
   - X `auth_token`, `ct0`
   - LinkedIn `li_at`, `JSESSIONID`
   - Google Messages: the `google.com` cookies (`SAPISID`, `SID`, `__Secure-1PSID`…) + `messages.google.com`
3. If there are one or more sessions: a card "Use your Instagram session from Firefox (profile X)" with the buttons
   **Use this session** and **Sign in with another account** (→ B).
4. B — a window with the real web (a current browser's user agent, an empty cookie store so another account can be
   added). When the essential cookies appear, the window closes by itself.
5. The cookies are sent to the bridge as JSON `{"name": "value"}` (the format the cookie bridges accept) through the
   wizard's same flow; the wizard still shows success or error.
6. If the bridge asks for an extra step (Google Messages: confirming an emoji on the phone), the wizard shows it as it
   does today.
7. C — a **Choose cookies.txt file…** button (on A's card and on B's screen). The Netscape lines are read
   (`domain  flag  path  secure  expires  name  value`, the `#HttpOnly_` ones included), the network's domains are
   kept and, if the essential ones are there, it connects at once; if some are missing, it says which ("The file lacks
   `sessionid`: export the cookies with the Instagram session open"). Expired cookies are ignored.
8. A discreet "Paste cookies by hand" link always remains for odd cases.

## Tests
- Chrome decryption (macOS v10, Windows v10) with synthetic data; detecting v20.
- Reading Firefox with a synthetic `cookies.sqlite` database.
- Reading cookies.txt: `#HttpOnly_` lines, comments, expired cookies, other networks' domains, and the missing-cookies
  message.
- Choosing cookies by network and building the JSON.
- A check by hand with the user's real browsers (Chrome with 3 profiles on this Mac).

## For whoever builds it
- New dependencies: `javafx-web` (WebView) and a SQLite driver for Java.
- UI details within the wizard's current style and the accessibility rules (accessible texts, keyboard).
