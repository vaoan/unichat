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
