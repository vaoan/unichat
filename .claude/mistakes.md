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
