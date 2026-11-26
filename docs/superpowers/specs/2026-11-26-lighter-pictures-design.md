# Lighter pictures — design

## Why
The person wants Unichat as light as possible, in memory while it runs **and** on disk (2026-11-26: "ambas"). After the
G1 change (`PackagingJvmTest`) the live heap is ~265 MB, ~150 MB of it decoded pictures; `cache/media` sits at its
1 GB limit (5 611 files: 1 757 PNG = 260 MB, 1 699 JPG = 280 MB, 937 WebP = 54 MB).

## What we found (measured on the person's Mac)
- The "signature" the person asked for already exists: a Matrix picture's address (`mxc://…`) never changes content, a new
  photo is a new address, and the cache is keyed by address + size (`MediaDownloader.cacheKey`). Nothing new is needed to
  know when to refresh.
- In memory a picture costs its decoded pixels (4 bytes each, ×~1.4 in JavaFX), whatever its file format. WebP on disk
  saves disk, not RAM.
- Avatars: `ImageCache` decodes every avatar at 168 px (for the 84 px info panel on a 2x screen) and keeps 500: ~42 MB.
  Most are shown at 44, 34, 28 or 26 px.
- File-chip previews (`MessageMedia.FILE_PREVIEWS`): shown at 38 px, decoded to fit 152 px.
- Server thumbnails (avatars and timeline photos, `/thumbnail?…&method=scale`) are often PNG: 40 real cached PNGs over
  20 KB, the 23 without an alpha channel: 5.5 MB as PNG, 1.7 MB as JPEG quality 85 (`sips`).
- Stickers: their files are the originals (forwarding and saving send them), and the grids already decode them at the size
  shown; their frames are capped (`AnimationBudget`, `DecodeBudget`, the 64 MB decoded cache).

## Goals
1. Disk: every server **thumbnail** we store (a download with width and height) is kept as the lightest file that looks
   the same: an opaque picture as a JPEG (quality 85) when that is smaller than what came; the rest as it came.
2. Memory: each avatar is decoded at the size it is shown times the screen's scale, in two tiers, with fewer kept; file
   previews decoded at the size they're shown.
3. No visible change: no flash of initials, no blurrier picture, no slower chat.

## Non-goals
- Originals (`media(url, 0, 0)`, `sticker(url)`) are never touched: they are what's opened, saved, forwarded and
  autosaved.
- Stickers and GIFs: no change on disk (originals) nor in memory (already at the size shown, already capped). The person
  asked for "the same system" for stickers; the finding is that they already have it, and this goes in the hand-over.
- No conversion of files already in the cache: they age out under the cache's limit.
- No new native tool, and no WebP encoding (Java can't write WebP; `img2webp` is Mac-only): JPEG is ImageIO's, on every OS.

## Anti-goals (a failure even if it works)
- A picture with real transparency turned black or white behind.
- An avatar that flashes its initials on scroll or on a click again (`AvatarFlashUiTest`'s lesson).
- A blurry avatar on a 2x screen, or the info panel's photo upscaled from a small one for good.
- A conversion that runs on the FX thread, or decodes a stranger's huge picture (header first: `AnimatedImage` rules).

## Approaches (picked: A)
- **A. Small copies on disk and in memory** (picked by the person): thumbnails stored as JPEG when opaque and smaller;
  avatars decoded per tier at the screen's scale; fewer kept in memory, since the small file reloads in a moment.
- B. Memory only (decode size, fewer kept) and a lower disk limit: simpler, leaves the disk as it is.
- C. A plus each animated sticker's small frames on disk: saves CPU, not RAM, and costs disk: against the goal.

## Decisions
- **Where the disk step runs:** in `matrix/MediaDownloader.download`, after the file is complete and only when `w > 0 &&
  h > 0` (a thumbnail), never for direct media from a bridge (that's the original) nor for http(s) links. A new small
  class (e.g. `matrix/LighterThumbnail` or in `support`) does it: read the header (`support/ImageHeaders`), only a PNG
  is converted (a JPEG, WebP or GIF stays as it came), skip past a pixel limit,
  decode with ImageIO, keep as is when any pixel's alpha < 255, else write JPEG q85 to a temp file and keep it only when
  it's smaller, then move it to `<hash>.jpg` (and delete the PNG). Any failure keeps the file as it came. Off the FX
  thread (the download's own).
- **Avatar tiers:** `ImageCache` keeps two sizes per address: small (for ≤ 44 px) and large (for > 44 px: info 84,
  settings 64). Decode px = tier's largest size × the largest output scale among the screens (`Screen.getScreens()`,
  at least 1, rounded up), and the server thumbnail is asked at that px too. A large view with only the small one at hand
  shows the small one at once, then the large one when it's here (no initials). Caps: small 500, large 60 (decided here;
  measured before/after with `jcmd`).
- **Preload:** the 150 most recent chats' small avatars, as now.
- **File previews:** decoded so the shorter side is 38 px × the screen scale (a "cover" fit), not into a 152 px box.
- **Tests:** each part gets its own: the JPEG step (opaque PNG → smaller JPEG; transparent PNG kept; a JPEG that would be
  bigger kept; a broken file kept; an original never touched), the tiers (sizes per tier and scale, the large view showing
  the small one first, no flash: `AvatarFlashUiTest` stays green), file previews' decode size. A memory measurement on the
  installed app before and after goes in the hand-over, not in a test.

## Answers to the builder check (my calls, 2026-11-26)
- **The other ~100 MB of decoded pictures** (timeline photos, GIF and sticker frames) are out of scope: photos are already
  decoded at the size shown (`Stills`, soft references), animations already capped (`AnimationBudget`, `DecodeBudget`, the
  64 MB decoded cache). The hand-over says so, with the measured before/after (`jcmd <pid> GC.class_histogram` live set and
  resident size, same chats open).
- **No re-download of avatars:** the server is still asked for one 168 px thumbnail per address (same `cacheKey`, the
  files already cached stay valid); both tiers are decoded from that one file. Only the decode size changes.
- **Tier from the caller's size:** `Avatar.size` ≤ 44 → small, else large. `ReactionEmoji` and the preload (`MainView`)
  ask the small tier. A view asks by its size; `ImageCache.get/load` take that size.
- **Both tiers from the same file** (direct media, nothing thumbnailable): the same rule, small shown first, large swapped
  in when decoded; no waiting on anything but that decode.
- **File previews:** keep the middle-square crop; decode so the crop's side is 38 × the screen scale, the long side capped
  at 3× the short one; the server request stays as it is (no new cache key).
- **"Opaque":** read the PNG's colour type and chunks first: colour types without alpha (0, 2) and no `tRNS` convert
  without a scan; types with alpha (4, 6) or a `tRNS` are decoded and scanned, converted only when every pixel's alpha is
  255; 16-bit PNGs too (ImageIO reads them). Anything unusual stays as it came.
- **The pin question** (`MediaDownloader.pinned` keys at `cacheKey(u, 0, 0)`): unrelated to avatars (pins are saved
  stickers and GIFs, originals); left as is.

## Left to the builder
- The class names and where the pixel scan lives.
- Whether the screen scale is read once or followed when a window moves to another screen (once at start is enough unless
  it's trivial to follow).
- The exact pixel limit for the disk step (reuse `AnimatedImage.MAX_STILL_PIXELS`'s idea; thumbnails are small anyway).
