---
paths:
  - "**/*.java"
---
# Null (NullAway, JSpecify)

- A package NullAway checks has a `package-info.java` with `@NullMarked` (each subpackage its own) and is listed in `NullMarkedTest.NULL_MARKED`, which reads the mark on the package declaration however it is written and fails on a class marked by itself in a package not listed. Its test classes share the package, so NullAway checks them too. Nothing is `@NullUnmarked`.
- In a marked package a type without `@Nullable` (`org.jspecify.annotations.Nullable`) never holds null. Write `@Nullable` where null really is a value: a field set later, a "null when none" return, a parameter the code checks for null. Put it on the type: `java.nio.file.@Nullable Path`, `List<@Nullable String>`, `String @Nullable []`.
- Fixes keep behaviour: annotate truthfully and handle the null, rather than invent a default. Make null impossible only when nothing changes (`getOrDefault` on a map's own keys).
- A record component that its compact constructor turns from null into a value (`Contact.identifiers`, `LinkPreview.title`) stays non-null: `@Nullable` there would make its accessor nullable too. A caller passes the empty value instead.
- `Settings.getString(key)` is "null when unset"; `getString(key, def)` takes a non-null default. A marked caller of `getString(key, null)` moves to the first.
- A listener told null (`Settings.addListener`: "many keys changed") is a `Consumer<@Nullable String>`.
- Tests: JUnit's `assertNotNull(x)` tells NullAway `x` isn't null (`HandleTestAssertionLibraries`). A test that passes null on purpose to see it refused carries `@SuppressWarnings("NullAway") // why` on that one method.
- A job that answers null for `Void` (`Cloud.Job`, `CloudVault.withKey`): the type parameter is `<T extends @Nullable Object>` and the method gives `CompletableFuture<@Nullable Void>`, or the lambda's `return null` is refused.
- A field null only in a mode where the code reading it never runs (`PreferencesFile.inMemory`'s paths, `VaultParts.Round.info()` while not open): pass the checked value on (`load(Path)`), or read it through `Objects.requireNonNull(field, "why")`, which fails as the dereference did. Never a default.
- A getter read twice (`x.f() != null && x.f().g()`) goes through a local: NullAway takes it, SpotBugs (`NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`) doesn't.
- An override of a `@Nullable` method (a wrapper such as `OldVault`, a test's `Through` or fake) says `@Nullable` too.
- Tests: `app.unichat.Present.of(x)` for a value the test goes on with (`svc.chat(id)`, a file's bytes, a slot), `Present.message(e)` for what a failure wrapped says; a field a helper every test calls sets carries `@SuppressWarnings("NullAway.Init") // why`.
- NullAway's own annotations (`com.uber.nullaway.annotations.MonotonicNonNull`, `EnsuresNonNull`, `RequiresNonNull`) are not JSpecify's: they need `com.uber.nullaway:nullaway-annotations` as a dependency, which the repo doesn't have. No marked package has needed them: a field set later is `@Nullable` and read through a local or `Objects.requireNonNull(field, "why")`.
- JSON from the homeserver or a bridge (Jackson's `asText(null)`, `get`) is a stranger's: NullAway takes an unmarked library's answer as non-null, so the field, parameter or future that holds it says `@Nullable` (`MatrixRoom.name`, `SyncProcessor.confirmLocalEcho`'s event id, `CompletableFuture<@Nullable String>` from a send). A missing field is skipped or defaulted as before, never thrown on: the sync loop reads it.
- `Set.of(…)`, `List.of(…)` and `Map.of(…)` throw on `contains(null)`/`get(null)`: a nullable value is checked before it's looked up (`NetworkFormats.sticker`).
- Generic helpers that may answer null (`MatrixCore.call`) take `<T extends @Nullable Object>`, so a `Callable` that returns null is accepted.
- A test field a request handler sets (`SendStickerTest.path`) is `@Nullable` and read with `Present.of`; one each test sets before use and `@AfterEach` checks carries `@SuppressWarnings("NullAway.Init") // why`, which lambdas can read too.
- A tool this computer may lack (`Ffmpeg.find()`, `AnimDump.find()`): a test takes it through `app.unichat.Present.orSkip(x, "no ffmpeg")`, which skips the test when it's null and gives it back non-null; NullAway doesn't read `assumeTrue(x != null)`.
- An override of an unmarked library's method that answers null (`WebSocket.Listener.onText`, InstaTray's `Fetch.Tools.ffmpeg()`) says `@Nullable` on its return; a getter of such a library read twice goes through a local too.
- A field a method sets and then hands on (`ChromeLogin.process`) is passed to the helper as the checked local (`awaitDebugPort(started, port)`), not read back from the field.
- `I18n.t`'s arguments are `@Nullable Object...`: an exception's message goes in as it is and shows "null" as before. A cleaner text is a behaviour change with its own test (`Errors.message`).
- A helper that answered null only for a null input (`ReplyPrompt.withoutMarks`) takes and gives non-null; its caller decides what a missing value is (`written == null ? "" : …`).
