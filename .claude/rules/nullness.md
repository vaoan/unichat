---
paths:
  - "**/*.java"
---
# Null (NullAway, JSpecify)

- A package NullAway checks has a `package-info.java` with `@NullMarked` (each subpackage its own) and is listed in `BuildRulesTest.NULL_MARKED`. Its test classes share the package, so NullAway checks them too. Nothing is `@NullUnmarked`.
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
