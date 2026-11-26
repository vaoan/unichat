# Lighter Pictures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pictures Unichat only shows (server thumbnails, avatars, file previews) take less disk and less memory, with no visible change.

**Architecture:** A server thumbnail that lands in `cache/media` is rewritten as a JPEG when it's an opaque PNG and the JPEG is smaller (`support/LighterPictures`, called from `matrix/MediaDownloader.download`). `ui/media/ImageCache` keeps avatars in two tiers (small ≤ 44 px, large above), each decoded at its size × the screen's scale from the same 168 px server thumbnail. File previews decode just big enough for their 38 px square crop.

**Tech Stack:** Java 25, JavaFX 27, ImageIO (java.desktop), JUnit 5, `-Pheadless-ui` for UI tests.

**Spec:** `docs/superpowers/specs/2026-11-26-lighter-pictures-design.md`

## Global Constraints
- What is sent never changes: originals (`media(url, 0, 0)`, `sticker(url)`, direct media from a bridge, http(s) links) are never converted, resized or renamed; they are what's opened, saved, forwarded, autosaved and sent (the person, 2026-11-26: "lo que se envía sigue siendo igual. solo son los previews o cosas que yo uso y veo").
- Only a download made through `/thumbnail` (width and height > 0, and the thumbnail endpoint actually answered) is a candidate for the disk step.
- JPEG quality 0.85; keep the JPEG only when it's smaller than the file that came; any failure keeps the file as it came.
- "Opaque": PNG colour type 0 or 2 with no `tRNS` chunk before `IDAT` → convert without a pixel scan; colour type 3, 4 or 6, or any `tRNS` → decode and convert only when every pixel's alpha is 255; anything unreadable stays.
- Avatar tiers: SMALL for views ≤ 44 px (lists 44, header 34, messages 28, chat line 26, new chat 30, reactions 18, the preload); LARGE for > 44 px (info 84, settings 64). Decode px = ceil(tier's largest size × screen scale), where SMALL's largest is 44 and LARGE's 84; scale = the largest `outputScaleX` of `Screen.getScreens()`, at least 1. The server is still asked for one 168 px thumbnail per address (unchanged `MediaDownloader.cacheKey`): no re-download.
- Memory caps: SMALL 500 images, LARGE 60.
- No new dependency, no native tool, nothing on the FX thread, every text through `I18n` (none expected), English everywhere in code and docs.
- Nothing runs on the person's screen: UI tests `-Pheadless-ui`; mutations only in a throwaway worktree (`src/test/CLAUDE.md`).
- Files < 800 lines, methods < 100 (Checkstyle); `ArchitectureTest` package rules (JavaFX only in `ui`).

## Review Focus
1. A transparent sticker-like PNG thumbnail (colour type 6, some alpha < 255) must stay a PNG: no black background. → Task 1 `aTransparentPngStaysAsItCame`.
2. A palette PNG with `tRNS` (colour type 3) is transparent though it has no alpha channel. → Task 1 `aPalettePngWithTransparencyStaysAsItCame`.
3. A thumbnail request the server can't thumbnail falls back to `/download` (the original): it must never be converted. → Task 1 `anOriginalFetchedWhenTheThumbnailFailedIsNeverConverted`.
4. The info panel (84 px) opened on a contact whose small avatar is cached shows that one at once, never initials, then the large one. → Task 2 `aLargeViewShowsTheSmallPictureAtOnceThenTheLargeOne`.
5. A file preview of a very wide picture (10:1) must not decode a huge strip. → Task 3 `aVeryWidePictureIsCappedAtThreeTimesItsShortSide`.

---

### Task 1: Thumbnails on disk as the lightest file

**Files:**
- Create: `src/main/java/app/unichat/support/LighterPictures.java`
- Modify: `src/main/java/app/unichat/matrix/MediaDownloader.java` (`download`, ~line 240-275)
- Test: `src/test/java/app/unichat/support/LighterPicturesTest.java`, `src/test/java/app/unichat/matrix/LighterThumbnailDownloadTest.java` (a fake homeserver as the other `matrix` download tests do, e.g. `DownloadLeftoversTest` / `SendStickerTest`)
- Notes: one line in `src/main/java/app/unichat/matrix/CLAUDE.md` (media cache bullet), `docs/ARCHITECTURE.md` support row gains `LighterPictures`.

**Interfaces:**
- Produces: `public static Path LighterPictures.lighter(Path file)` — returns the file to keep: the same path when left as it came, or the new `<same name without extension>.jpg` (the PNG deleted). Never throws.
- Produces: `static boolean LighterPictures.opaque(Path png) throws IOException` (package-visible for tests).

- [ ] **Step 1: Write the failing tests** (`LighterPicturesTest`, pictures made in the test with ImageIO into `@TempDir`):
  - `anOpaquePngBecomesASmallerJpeg`: a 320×320 noisy-photo-like RGB PNG (type 2) → result ends `.jpg`, exists, smaller than the PNG was, PNG gone, `ImageHeaders.pictureMime(result) == "image/jpeg"`, same width/height (`ImageHeaders.size`).
  - `anRgbaPngWhoseAlphaIsAllOpaqueBecomesAJpeg` (type 6, alpha 255 everywhere) → `.jpg`.
  - `aTransparentPngStaysAsItCame` (type 6, one pixel alpha 0) → same path, bytes unchanged.
  - `aPalettePngWithTransparencyStaysAsItCame` (type 3 with `tRNS`) → same path.
  - `aPngWhoseJpegWouldBeBiggerStaysAsItCame` (a 16×16 flat single-colour PNG) → same path.
  - `aJpegWebpOrGifStaysAsItCame` → same path for each.
  - `aBrokenPngStaysAsItCame` (PNG signature then garbage) → same path, no exception.
  - `aPngPastThePixelLimitIsNotDecoded`: a header declaring 20000×20000 (hand-written IHDR, no pixel data) → same path, nothing decoded (finishes at once).
- [ ] **Step 2: Run** `JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw test -Dtest=LighterPicturesTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` → fails to compile (no class).
- [ ] **Step 3: Implement `LighterPictures`**: read the PNG signature, IHDR (width, height, colour type at byte 25) and the chunks up to `IDAT` for `tRNS`; pixel limit 1 000 000 (width × height) before any decode (16 000 000 at first; lowered in the final review: a server thumbnail is at most ~0.5 MP, and bigger means an original passed through, decoded on the unbounded io pool); decode with ImageIO; write JPEG quality 0.85 (`ImageWriteParam`) of an RGB copy to `<name>.jpg.part` in the same folder, keep it only if smaller, then move to `<name>.jpg` (`StandardCopyOption.REPLACE_EXISTING, ATOMIC_MOVE` where supported) and delete the PNG; on any exception delete the `.part` and return the file as it came.
- [ ] **Step 4: Run** the same command → PASS.
- [ ] **Step 5: Write the failing download tests** (`LighterThumbnailDownloadTest`, fake homeserver):
  - `aThumbnailThatIsAnOpaquePngIsKeptAsAJpeg`: `/thumbnail/…` answers an opaque PNG → `media(url, 168, 168)` gives a `.jpg`; asking again is a cache hit (no second request) and gives the same `.jpg`.
  - `anOriginalIsNeverConverted`: `media(url, 0, 0)` of an opaque PNG → the `.png` as it came, bytes equal.
  - `anOriginalFetchedWhenTheThumbnailFailedIsNeverConverted`: `/thumbnail` answers an error, `/download` an opaque PNG → `.png`, bytes equal.
  - `aBridgesDirectMediaIsNeverConverted`: an `mxc` on a direct-media server (`BridgePorts.directMediaBase`) with width/height → as it came.
- [ ] **Step 6: Run** with `-Dtest=LighterThumbnailDownloadTest` → FAIL (gets `.png`).
- [ ] **Step 7: Wire it in `MediaDownloader.download`**: a local `boolean thumbnail` set true only when the `/thumbnail` fetch answered; after the move to `target`, `if (thumbnail) target = LighterPictures.lighter(target);` and return that. `findCached` finds `<hash>.jpg` by its hash prefix as before.
- [ ] **Step 8: Run** both test classes → PASS; then `bash .claude/hooks/java.sh Lessons <changed files>`.
- [ ] **Step 9: Commit** by path: `feat(media): server thumbnails kept as the lightest file (opaque PNG to a smaller JPEG)`.

### Task 2: Avatars in two tiers at the screen's scale

**Files:**
- Create: `src/main/java/app/unichat/ui/media/ScreenScale.java`
- Modify: `src/main/java/app/unichat/ui/media/ImageCache.java`, `src/main/java/app/unichat/ui/chats/Avatar.java` (`set`), `src/main/java/app/unichat/ui/emoji/ReactionEmoji.java`, `src/main/java/app/unichat/ui/MainView.java` (`preloadAvatars`)
- Test: `src/test/java/app/unichat/ui/media/ImageCacheUiTest.java` (add), `src/test/java/app/unichat/ui/chats/AvatarTierUiTest.java` (new); `AvatarFlashUiTest` and `ReactionEmojiUiTest` must stay green unchanged (adapt only call signatures).
- Notes: replace the avatar line in `src/main/java/app/unichat/ui/media/CLAUDE.md` ("one decoded 168 px image per URL for every size") with the tiers.

**Interfaces:**
- Produces: `public enum ImageCache.Tier { SMALL, LARGE; public static Tier forSize(double px) }` (≤ 44 → SMALL).
- Produces: `static int ImageCache.decodePx(Tier tier, double scale)` = `ceil((tier == SMALL ? 44 : 84) * max(1, scale))`.
- Produces: `public static @Nullable Image ImageCache.get(String url, Tier tier)` — that tier's image only.
- Produces: `public static @Nullable Image ImageCache.any(String url)` — the asked-for tier's sibling or itself: LARGE if cached, else SMALL (for showing something at once).
- Produces: `public static CompletableFuture<@Nullable Image> ImageCache.load(String url, MediaSource svc, Tier tier)` (+ the package `decoder` overload with the tier) — server thumbnail still `PX = 168` (`svc.media(url, 168, 168)`), decoded with `AnimatedImage.still(path, px, px)` at `decodePx(tier, ScreenScale.max())`; `LOADING` keyed by `url + "|" + tier`; `FAILED` stays by url.
- Produces: `public static double ScreenScale.max()` — the largest `Screen.getOutputScaleX()` of `Screen.getScreens()`, at least 1 (read each call; cheap).
- Caps: SMALL `BoundedMap.byUse(500)`, LARGE `BoundedMap.byUse(60)`.

- [ ] **Step 1: Write the failing tests**:
  - `ImageCacheUiTest.eachTierIsDecodedAtItsSizeTimesTheScale`: `decodePx(SMALL, 1) == 44`, `decodePx(SMALL, 2) == 88`, `decodePx(LARGE, 2) == 168`, `decodePx(LARGE, 1.25) == 105`, `decodePx(SMALL, 0.5) == 44`.
  - `ImageCacheUiTest.aSmallLoadDecodesNoBiggerThanItsTier`: a 168×168 picture served for the url → `load(url, svc, SMALL)` image width ≤ `decodePx(SMALL, ScreenScale.max())`; `get(url, LARGE) == null`.
  - `ImageCacheUiTest.bothTiersComeFromOneDownload`: SMALL then LARGE load of one url → every `media` call the fake `MediaSource` saw for that url asked 168×168 (so `MediaDownloader` serves both from one cached file).
  - `ImageCacheUiTest.theSmallTierKeepsAtMost500AndTheLarge60`.
  - `AvatarTierUiTest.aLargeViewShowsTheSmallPictureAtOnceThenTheLargeOne`: SMALL cached for url; `new Avatar(84).set(…url…)` → its image is the SMALL one at once (visible, no initials), and after the load completes it is the LARGE one.
  - `AvatarTierUiTest.aListAvatarAsksTheSmallTier` (`new Avatar(44)` → only SMALL decoded).
- [ ] **Step 2: Run** `./mvnw test -Pheadless-ui -Dtest='ImageCacheUiTest,AvatarTierUiTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` (JAVA_HOME as above) → FAIL to compile.
- [ ] **Step 3: Implement** `ScreenScale`, the tiers in `ImageCache`, `Avatar.set` (tier from its `size`; show `get(url, tier)` if there, else `any(url)` at once if there, then load the tier and swap when it comes, still checking `url.equals(loadedUrl)`), `ReactionEmoji` and `preloadAvatars` ask SMALL.
- [ ] **Step 4: Run** the two classes plus `AvatarFlashUiTest,ReactionEmojiUiTest,RelocalizeUiTest` → PASS.
- [ ] **Step 5: Commit** by path: `perf(media): avatars decoded per size in two tiers at the screen's scale`.

### Task 3: File previews decoded for their crop

**Files:**
- Modify: `src/main/java/app/unichat/ui/media/MediaSizes.java` (pure), `src/main/java/app/unichat/ui/media/MessageMedia.java` (`showFilePreview`, ~line 420-436)
- Test: `src/test/java/app/unichat/ui/media/MediaSizesTest.java` (add; create if it doesn't exist), the existing file-preview UI test if one covers `showFilePreview` (find with `grep -rn FilePreview src/test`), else add `FilePreviewDecodeUiTest`.

**Interfaces:**
- Produces: `static int[] MediaSizes.coverBox(int width, int height, int side)` — the box to decode into so the middle square crop has `side` px: the short side = `side`, the long side = `min(ceil(side × long/short), 3 × side)`; `{side, side}` when width or height ≤ 0.

- [ ] **Step 1: Write the failing tests**:
  - `MediaSizesTest.aSquarePictureDecodesAtTheSide`: `coverBox(500, 500, 76)` → `{76, 76}`.
  - `MediaSizesTest.aLandscapePictureKeepsItsShortSideAtTheSide`: `coverBox(1600, 900, 76)` → `{136, 76}`.
  - `MediaSizesTest.aPortraitPictureToo`: `coverBox(900, 1600, 76)` → `{76, 136}`.
  - `MediaSizesTest.aVeryWidePictureIsCappedAtThreeTimesItsShortSide`: `coverBox(10000, 1000, 76)` → `{228, 76}`.
  - `MediaSizesTest.anUnknownSizeIsASquare`: `coverBox(0, 0, 76)` → `{76, 76}`.
  - UI: a file message with a 1600×900 picture → the preview's `Image` width ≤ 136 × `ScreenScale.max()` rounded up and height ≥ 76 × scale − 1 (was decoded into 152×152).
- [ ] **Step 2: Run** with `-Pheadless-ui -Dtest='MediaSizesTest,<the UI test>'` → FAIL.
- [ ] **Step 3: Implement**: in `showFilePreview` read `ImageHeaders.size(p)` and decode with `AnimatedImage.still(p, box[0], box[1])` where `box = coverBox(w, h, (int) Math.ceil(FILE_THUMB * ScreenScale.max()))`; the server request (`px = FILE_THUMB * 2`) and `FILE_PREVIEWS`' key stay as they are; fix its "300 of 152 px" comment.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** by path: `perf(media): file previews decoded just big enough for their square`.

### Task 4 (controller): verify, install, measure
- [ ] `JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw -q clean verify -Pheadless-ui` → exit 0 (JaCoCo floors: raise none, lower none without a pom comment).
- [ ] Snapshot at 100 % and 160 %, light and dark, of the chat list and the info panel (`snapshot` skill): avatars sharp, no initials where a photo was.
- [ ] Merge, push (token method), `./install-mac.sh`, reopen; after the same use, `jcmd <pid> GC.class_histogram` live set and `ps -o rss=` against today's 265 MB / ~550 MB, and the size of new files in `cache/media`. Report the numbers to the person.
