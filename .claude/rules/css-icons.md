---
paths:
  - "src/main/resources/app/unichat/css/**"
  - "src/main/resources/app/unichat/icons/**"
  - "src/main/java/app/unichat/ui/Theme.java"
  - "src/main/java/app/unichat/ui/Icons.java"
  - "src/main/java/app/unichat/ui/NetworkIcons.java"
  - "src/main/java/app/unichat/ui/Nodes.java"
  - "src/main/java/app/unichat/ui/Chips.java"
  - "tools/IconGen.java"
---
# CSS, sizes and icons

- `css/app.css` is the single stylesheet; light palette on `.root`, dark on `.root.dark`; `-accent` and font size set inline by `Theme`. Sizes in `em` so they grow with the text (160 %); check with the `snapshot` skill at the biggest text.

## Lessons
- Author CSS beats setters: `-fx-min-width: 20` on `.unread-badge` overrode `setMinWidth(USE_PREF_SIZE)` and wide glyphs ("@", "4") were truncated to "". Don't set min sizes in CSS for text labels.
- Fixed-width chips truncated to "Tod…" at 125 % text / contrast borders: chips need `setMinWidth(USE_PREF_SIZE)` and a wrapping `FlowPane` row.
- `-fx-background-radius: 999` on a thin Slider track made it report ~2000 px bounds and draw across the whole settings row. Use small fixed radii on thin shapes.
- A self-referencing looked-up color (`-accent: derive(-accent, …)`) is a loop; compute derived colors in code (`Theme.accessibleAccent`).
- A Region with an SVG shape stretches it to fill the box, so non-square glyphs (⋯, chevrons) were distorted. `Icons` appends two invisible specks at (0,0) and (24,24) *after* the path (prepending broke paths starting with a relative `m`).
