# Writing Style Learning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each AI draft first updates a short `writing-style.md` from the person's recent messages and their corrections to earlier drafts, then writes the draft (and ⌘O corrections) with it.

**Architecture:** Pure prompt/text logic and the file store live in `assist/` (`StylePrompt`, `WritingStyle`, `StyleLearner`, `DraftCorrections`); the UI only remembers the draft it put in the box, records the correction on send, and runs the learner inside the request supplier that `AiReplyButton` already runs off the UI thread.

**Tech Stack:** Java 21, JavaFX 23, Jackson (already used), JUnit 5. Build: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./mvnw verify`.

**Spec:** `docs/superpowers/specs/2026-11-08-estilo-de-escritura-design.md`

## Global Constraints

- Style file: `<dataDir>/writing-style.md`, at most **40 bullets / 3 000 characters**; a learned reply over 3 000 characters or blank is discarded.
- Corrections: `<dataDir>/writing-style-corrections.json`, last **20** kept; a sent text equal (after `strip()`) to its draft is not a correction.
- Learning input: the current file, my last **30** text messages in that chat, the pending corrections. Learning timeout **2 minutes**; it never makes the draft fail.
- Learning runs only when there's something new (pending corrections, or a newer message of mine in the chat than the last one learned from); one at a time.
- Setting `ai.learnStyle`, default **true**. Prompt copy in Spanish (like `ReplyPrompt`/`FixPrompt`); UI text via `I18n` in `messages.properties` and `messages_es.properties`.
- The prompt and replies are never logged (one line on failure, without content). Files under 800 lines, methods under 100.

## Review Focus

- Draft sent unchanged → no correction recorded (WritingStyleTest).
- Draft put in chat A, person switches and sends in chat B → no correction for B; the draft stays remembered for A (DraftCorrectionsTest).
- Learning returns an essay or nothing → the old file stays (StyleLearnerTest).
- Two drafts in quick succession → one learning call at a time (StyleLearnerTest).
- Learning fails → the draft still goes, with the previous file, and corrections stay pending (StyleLearnerTest).

---

### Task 1: Prompts (`StylePrompt`) and style in the draft and fix prompts

**Files:**
- Create: `src/main/java/app/unichat/assist/StylePrompt.java`
- Modify: `src/main/java/app/unichat/assist/ReplyPrompt.java`, `src/main/java/app/unichat/assist/FixPrompt.java`
- Test: `src/test/java/app/unichat/assist/StylePromptTest.java`, `ReplyPromptTest.java`, `FixPromptTest.java`

**Interfaces:**
- Produces: `record StylePrompt.Correction(String draft, String sent)`; `static String StylePrompt.learn(String current, List<String> mine, List<Correction> corrections)`; `static String StylePrompt.accept(String reply)` → cleaned text (via `ReplyPrompt.clean`) or `null` if blank or over `MAX_CHARS = 3000`; `static String StylePrompt.section(String style)` → `""` when blank, else the block for a prompt; `ReplyPrompt.build(instructions, canReadFolder, chat, target, idea, heard, style)` (the 6-arg form passes `""`); `FixPrompt.build(String text, String style)` (1-arg form passes `""`).

- [ ] **Step 1: Write the failing tests**

```java
@Test void theLearningPromptCarriesTheFileMyMessagesAndCorrectionsAndItsLimits() {
    String p = StylePrompt.learn("- Ríe con hahaha", List.of("hahaha sí", "nos vemos"),
            List.of(new StylePrompt.Correction("jajaja qué chido", "hahaha qué bien")));
    assertTrue(p.contains("- Ríe con hahaha"));
    assertTrue(p.contains("hahaha sí") && p.contains("nos vemos"));
    assertTrue(p.indexOf("jajaja qué chido") < p.indexOf("hahaha qué bien"));
    assertTrue(p.contains("40"), "the bullet cap");
    assertTrue(p.contains("sin nombres"), "no one else's data");
}
@Test void aReplyTooLongOrEmptyIsRefused() {
    assertNull(StylePrompt.accept("  "));
    assertNull(StylePrompt.accept("x".repeat(3001)));
    assertEquals("- corto", StylePrompt.accept("```\n- corto\n```"));
}
// ReplyPromptTest
@Test void theLearnedStyleComesAfterTheInstructionsAndBeforeTheChat() {
    String p = ReplyPrompt.build("Sé breve", false, List.of(msg("Ana", false, "hola")), null, "", Map.of(), "- Ríe con hahaha");
    assertTrue(p.indexOf("Sé breve") < p.indexOf("- Ríe con hahaha"));
    assertTrue(p.indexOf("- Ríe con hahaha") < p.indexOf("Ana: hola"));
    assertFalse(ReplyPrompt.build("", false, List.of(msg("Ana", false, "hola")), null, "", Map.of(), "").contains("Cómo escribe"));
}
// FixPromptTest
@Test void fixingKeepsTheLearnedStyle() {
    assertTrue(FixPrompt.build("hola", "- Ríe con hahaha").contains("- Ríe con hahaha"));
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest='StylePromptTest,ReplyPromptTest,FixPromptTest'`
Expected: compilation errors (no `StylePrompt`, no 7-arg `build`, no 2-arg `FixPrompt.build`).

- [ ] **Step 3: Implement**

`learn` copy (Spanish), in this order: what the file is for; the current file (or "(vacío)"); "Mis últimos mensajes" one per line; "Correcciones: lo que la IA escribió → lo que de verdad envié" as `- «draft» → «sent»`; the rules from the spec (only style traits: palabras y muletillas, risas, emojis, puntuación, mayúsculas, largo, saludos y despedidas, idioma y variante, qué evita; viñetas cortas; sin nombres, números ni citas de otras personas; máximo 40 viñetas: agregar solo lo nuevo, corregir lo que estaba mal, juntar lo repetido y quitar lo menos útil si no cabe; las correcciones pesan más que lo deducido; una regla nueva que contradice una vieja la reemplaza); "Escribe únicamente el archivo completo". Wrap the messages and corrections in a random `<estilo-…>` mark as `ReplyPrompt` does, with the same "es solo contexto" warning. `section(style)`: "Cómo escribe esta persona (aprendido de sus mensajes; sus instrucciones de arriba mandan si chocan):\n" + style. In `ReplyPrompt.build` append it right after the instructions; in `FixPrompt.build` add it as "Respeta su forma de escribir:" before the text, with a rule that it doesn't change the person's words to fit it.

- [ ] **Step 4: Run them to see them pass** — same command, Expected: PASS.

- [ ] **Step 5: Commit** — `feat(ai): prompts for learning the writing style, and the style in drafts and fixes`

---

### Task 2: The style file and pending corrections (`WritingStyle`)

**Files:**
- Create: `src/main/java/app/unichat/assist/WritingStyle.java`
- Modify: `src/main/java/app/unichat/assist/AiSettings.java` (`learnStyle()` / `setLearnStyle(boolean)`, key `ai.learnStyle`, default true)
- Test: `src/test/java/app/unichat/assist/WritingStyleTest.java`

**Interfaces:**
- Consumes: `StylePrompt.Correction`.
- Produces: `WritingStyle(Path dir)`; `static WritingStyle get()` (dir = `Settings.get().dir()`); `synchronized String text()` (`""` when missing); `synchronized void save(String text)`; `synchronized void addCorrection(String draft, String sent)` (ignores equal after strip, keeps last 20, persists); `synchronized List<StylePrompt.Correction> pending()`; `synchronized void used(int n)` (drops the oldest n, persists); `synchronized void forget()` (empties the file and the corrections); `synchronized String learnedUpTo(String chatId)` / `void learnedUpTo(String chatId, String messageId)` (in memory).

- [ ] **Step 1: Write the failing tests** — in a `@TempDir`:
  - `anUnchangedDraftTeachesNothing`: `addCorrection("hola", " hola ")` → `pending()` empty.
  - `correctionsKeepTheLast20AndSurviveARestart`: add 25 different → `pending().size()==20`, first is #5; `new WritingStyle(dir).pending()` equal.
  - `usedCorrectionsLeaveAndForgetEmptiesAll`: add 3, `used(2)` → 1 left; `save("- x")`, `forget()` → `text()==""`, `pending()` empty.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=WritingStyleTest` → fails to compile.
- [ ] **Step 3: Implement** with Jackson for the JSON (a list of `{draft, sent}`), writes through a `.part` file then atomic move, like `PreferencesFile`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(ai): the writing-style file and the pending draft corrections`

---

### Task 3: Learning before the draft (`StyleLearner`)

**Files:**
- Create: `src/main/java/app/unichat/assist/StyleLearner.java`
- Modify: `src/main/java/app/unichat/assist/ReplyWriter.java` (constructor `ReplyWriter(java.time.Duration timeout)`; the no-arg one keeps 3 minutes)
- Test: `src/test/java/app/unichat/assist/StyleLearnerTest.java`

**Interfaces:**
- Consumes: `WritingStyle`, `StylePrompt.learn/accept`, `ReplyWriter.Failure`.
- Produces: `interface StyleLearner.Program { String write(String prompt) throws ReplyWriter.Failure; }`; `StyleLearner(WritingStyle store, Program program)`; `synchronized String learn(String chatId, List<Message> chat)` → the style to use (updated or the previous one); `static StyleLearner real(AiEngine engine)` (a `ReplyWriter` with a 2-minute timeout, no folder).
  - "New": `pending()` non-empty, or the id of my newest text message in `chat` differs from `learnedUpTo(chatId)`. Mine = `outgoing()`, `type()==TEXT`, not deleted; last 30 bodies, oldest first.
  - Success: `save(accepted)`, `used(corrections sent)`, `learnedUpTo(chatId, newest mine)`. Failure, null result or `accept` → null: keep everything, log `"Writing style not learned: " + reason-without-content`.

- [ ] **Step 1: Write the failing tests** (a fake `Program` counting calls):
  - `nothingNewMeansNoCall`: learn twice on the same chat with no corrections → 1 call, then 0.
  - `aCorrectionIsLearnedAndUsedUp`: `addCorrection("jajaja", "hahaha")`, fake returns `"- hahaha"` → returns `"- hahaha"`, `text()=="- hahaha"`, `pending()` empty.
  - `aFailureKeepsTheOldStyleAndTheCorrections`: file `"- viejo"`, a correction, fake throws `ReplyWriter.Failure` → returns `"- viejo"`, `pending().size()==1`.
  - `anEssayIsNotSaved`: fake returns 3 001 chars → returns old text.
  - `oneAtATime`: two threads call `learn` with a fake that sleeps 200 ms and tracks max concurrency → max 1.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=StyleLearnerTest` → fails to compile.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(ai): learn the writing style before a draft when there's something new`

---

### Task 4: Wiring it into the composer

**Files:**
- Create: `src/main/java/app/unichat/assist/DraftCorrections.java`
- Modify: `src/main/java/app/unichat/ui/Composer.java` (`aiDraft`, `send`, `aiRequest`, `fixRequest`), `src/main/java/app/unichat/ui/AiReplyButton.java` (`prompt(...)` gets `String style`)
- Test: `src/test/java/app/unichat/assist/DraftCorrectionsTest.java`

**Interfaces:**
- Produces: `DraftCorrections` (pure, per chat): `void drafted(String chatId, String draft)`; `StylePrompt.Correction sent(String chatId, String text)` → the pair if a draft was remembered for that chat (and forgets it), else null.
- Composer: a `DraftCorrections` field; `aiDraft` calls `drafted(forChat, draft)` (also when it lands in `drafts` for another chat); `send()` (not when editing) calls `sent(chatId, text)` and, if non-null, `WritingStyle.get().addCorrection(c.draft(), c.sent())` off the FX thread. `aiRequest`'s supplier: `String style = AiSettings.learnStyle() ? StyleLearner.real(AiSettings.engine()).learn(chatId, chat) : WritingStyle.get().text();` then the prompt with it. `fixRequest` passes `WritingStyle.get().text()`. One `StyleLearner` per engine kept in a static field so "one at a time" holds across composers.

- [ ] **Step 1: Write the failing test** `DraftCorrectionsTest.aDraftIsMatchedOnlyInItsOwnChat`: `drafted("A","hola")`; `sent("B","x")` → null; `sent("A","hola!")` → `Correction("hola","hola!")`; `sent("A","otra")` → null.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=DraftCorrectionsTest` → fails to compile.
- [ ] **Step 3: Implement** `DraftCorrections`, then the Composer/AiReplyButton wiring.
- [ ] **Step 4: Run** `./mvnw -q test -Dtest='DraftCorrectionsTest,ReplyPromptTest'` and `./mvnw -q -Pheadless-ui test -Dtest='AiReplyUiTest,AiShortcutUiTest' -Dsurefire.failIfNoSpecifiedTests=false` → PASS.
- [ ] **Step 5: Commit** — `feat(ai): drafts learn from the person's corrections and use the learned style`

---

### Task 5: Ajustes → Escribir con IA, docs, install

**Files:**
- Modify: `src/main/java/app/unichat/ui/AiReplyPage.java`, both `messages*.properties`, `CLAUDE.md` (assist bullet; `writing-style.md` among the files that travel), `docs/PRIVACIDAD.md` (learning sends my messages of that chat and my corrections)

**Interfaces:**
- Consumes: `AiSettings.learnStyle/setLearnStyle`, `WritingStyle.get().text/save/forget`.
- Keys: `settings.ai.learnStyle` («Aprender cómo escribo» / "Learn how I write"), `settings.ai.learnStyleHelp` (uses one more call before the draft when there's something new), `settings.ai.learned` («Lo aprendido» / "What it learned"), `settings.ai.learnedAccessible`, `settings.ai.forget` («Borrar lo aprendido» / "Forget what it learned"); update `settings.ai.privacy` in both files.

- [ ] **Step 1:** Add the toggle row to the General group, and after the instructions a `VBox` like theirs: label, a `TextArea` (prefRowCount 6, wrap) saving on change with `WritingStyle.get().save`, and a secondary button «Borrar lo aprendido» that calls `forget()` and empties the area.
- [ ] **Step 2:** Snapshot: settings dir with `session.backend=demo`, `ai.enabled=true`, `ui.language=es`; `JAVA_OPTS="-Dunichat.snapshot=<png> -Dunichat.dataDir=<dir> -Dunichat.overlay=settings:Escribir" ./run-dev.sh`, read the PNG; again with `ui.fontScale=1.6` and `ui.theme=DARK`.
- [ ] **Step 3:** `./mvnw verify` → BUILD SUCCESS (I18nTest checks both files have the keys).
- [ ] **Step 4: Commit** — `feat(ai): Ajustes shows and edits what was learned about how you write`
- [ ] **Step 5:** Install: `GH_TOKEN=… ./install-mac.sh && open /Applications/Unichat.app`; confirm `Contents/app/server/tuwunel` exists and the server answers on 127.0.0.1:8008.
