# login — notes for Claude

Logging in to the bridges and browser cookies.

- Login steps asking for request headers (LinkedIn): `LoginWebView.openCapturing` watches the page's own XHR/fetch headers through a script and builds the Cookie header from the cookie store (`BridgeCookies.captured`); LinkedIn cancels a session used under another browser's identity within seconds (mautrix/linkedin#61: its bridge always says Chrome 141/Linux), so cookies from the person's own browser don't work, and Google refuses embedded windows. So `login/ChromeLogin` opens their real Chrome on a throwaway profile (on a Mac through `open`: a child process would have its permissions charged to Unichat, and passkeys on the phone need Bluetooth), uses a chosen debugging port (port 0 makes `navigator.webdriver` true and Google refuses), gives only the site's tab the bridge's identity over the debugging port (`Emulation.setUserAgentOverride`, client hints included; Google's popup is another tab, unattached, because Google rejects a browser that contradicts itself), reads the cookies once the feed loads and deletes the profile (`-Dunichat.chromeTests=true` runs it for real, off screen); the window remains for email and password, and stops waiting for headers after 8 s (`BridgeCookies.headerFallbacks`).

## Lessons
- `ChromeLogin` left Chrome's debugging port (any local program can drive the browser through it) open up to 15 minutes, and a cancel waited for the next look. Now `MAX_WAIT` is 5 minutes and `cancel` wakes every wait at once, so `run` closes Chrome right away (`ChromeLoginTest`).
