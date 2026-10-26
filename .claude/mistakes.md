# Mistakes log

- **`*/` inside Javadoc** (`*italic*/_italic_`) closed the comment → "illegal character" compile errors. Avoid
  markdown-ish asterisks before a slash in comments.
- **Region + SVG shape is stretched to fill the box**: non-square glyphs (⋯, chevrons) were distorted. `Icons`
  appends two invisible specks at (0,0) and (24,24) *after* the path (prepending broke paths starting with a
  relative `m`).
- **Author CSS beats setters**: `-fx-min-width: 20` on `.unread-badge` overrode `setMinWidth(USE_PREF_SIZE)` and
  wide glyphs ("@", "4") were truncated to "". Don't set min sizes in CSS for text labels.
- **Reused empty ListCell kept the `:current` pseudo-class** → a grey "selected" bar under the list. Reset
  pseudo-classes in the empty branch of `updateItem`.
- **AWT on the FX thread / System.exit on the FX thread hung the app on macOS.** All `TrayIcon`/`SystemTray`
  calls go through `EventQueue.invokeLater`; exit happens on a separate thread.
- **JavaFX 21 rendered emoji monochrome** → upgraded to JavaFX 23 and forced emoji font families on emoji-only
  controls.
- **Labels with a min width from the parent HBox**: list cells must use `setPrefWidth(0)` so they size to the
  viewport instead of content.
- **Appending to a generated YAML without a trailing newline** glued `rc_message:` to a comment line (Synapse
  test config). Use `printf '\n...'` when appending.
- Matrix media messages keep the name in `fileName`, not `body` (body = caption) — match on both in tests.
- **`-fx-background-radius: 999` on a thin Slider track** made the slider report ~2000 px bounds and draw across the
  whole settings row. Use small fixed radii on thin shapes.
- **Fixed-width chips truncated to "Tod…"** at 125 % text / contrast borders: chips need `setMinWidth(USE_PREF_SIZE)`
  and a wrapping `FlowPane` row. Always check UI at max text size.
- **Self-referencing looked-up color** (`-accent: derive(-accent, …)`) is a loop; compute derived colors in code
  (`Theme.accessibleAccent`).
- **Networks only appeared after the first /sync** (WhatsApp, Telegram… all of them): cached `list-logins` were
  loaded after the sync and `accounts()` skipped networks without known rooms; every start downloaded ~6 MB.
  Now logins load at start, the last full sync is cached on disk and the network sync resumes from its token.
  `StartupBeforeSyncTest` (fake homeserver with a 5 s /sync) guards it — it fails if either fix is removed.
- **Stickers never animated**: images loaded the server *thumbnail* (a static PNG of frame 1), and JavaFX can't
  read WebP at all. Stickers/GIF/WebP now load the original; animated WebP is composited from its ANMF chunks
  (TwelveMonkeys only returns raw patches without durations) — `AnimatedImageTest` uses a synthetic fixture.
  Decoding long stickers takes seconds: show the thumbnail first. Reduce-motion shows frame 1 only.
- **Avatars flashed** on every inbox refresh: recycled cells hid the photo and reloaded it (background-loaded
  `Image` is blank until decoded). `ImageCache` keeps fully decoded images; `AvatarFlashUiTest` guards it.
- **Node.isFocused() needs the window to have OS focus**: F6 into the timeline only selected a message via the
  focused listener, so it silently failed when the window wasn't frontmost (and KeyboardUiTest failed while the
  user's app was open). Select explicitly in `focusTimeline()`.
- **Snapshot runs stole the user's keyboard** (typed text landed in the test window): snapshot mode now shows an
  invisible, off-screen window sent to the back.
- **Avatar flash, second round**: the first fix cached decoded images *per size*, but one contact shows at 44 px
  (list), 34 (header), 28 (messages) and 84 (info) — every click on a chat missed the cache for header/info and
  showed the initials. Measured with a click simulation (0 visible→hidden events, 38 misses) instead of guessing.
  Now one decoded 168 px image per URL serves every size, loads are de-duplicated, failed URLs (bridge-bot
  avatars on maunium.net, unreachable from a "localhost" Synapse) aren't retried per click, and the 150 most
  recent chats' photos are preloaded. Result: 0 flashes for already-seen photos.
- **macOS drops AWT tray notifications from a plain `java` process** (usernoted: "dropping msg … app not found");
  osascript ones show but clicking opens Script Editor. macOS uses Unichat's own card (`DesktopToast`); Windows
  keeps the native balloon. Tray/balloon callbacks run on the AWT thread: hop to FX with `Platform.runLater`.
