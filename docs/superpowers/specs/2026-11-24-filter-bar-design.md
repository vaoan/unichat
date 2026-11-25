# Quick filters as one segmented bar — short design

## Intent
The person (2026-11-24): the chat list's quick filters ("pills") don't look right and should sit on one line without scrolling, perhaps as icons. At 160 % text, "Low priority" wraps to a second line today. "All" stays as text.

## Decided
- **The bar:** one segmented bar spanning the column's width, in four parts: "All" (text, as today), then Unread, Favorites and Low priority as icons. The person chose this over separate round icons, and over icons that show their label when on.
- **One line always:** at 100 % and 160 % text, light and dark, and in high contrast. It never wraps and never scrolls.
- **Same behaviour as the chips:**
  - one is always selected (a `ToggleGroup`, and re-clicking the selected one keeps it);
  - it follows the filter however it changed (rail, shortcuts, leaving the archive);
  - each part applies the same `ChatFilter` change as today.
- **Accessibility:**
  - each icon part has a tooltip and an accessible text with its name (the existing `chatList.chip.*` keys);
  - it is reachable and togglable by keyboard;
  - the selected part is visible without relying on colour alone in high contrast.
- **Icons:** in the app's own icon set and style, following `.claude/rules/css-icons.md`. No Beeper assets.

## Approaches
1. **Segmented bar** (chosen by the person).
2. Separate round icon buttons.
3. Icons that show their label when selected.

## Left to the builder
- The exact icons, from the existing set if suitable:
  - Unread: a filled dot or an envelope;
  - Favorites: a star;
  - Low priority: an arrow down or a "later" mark.
- Whether "All" takes more width than the icon parts.
- The CSS details, matching the current chip colours.
