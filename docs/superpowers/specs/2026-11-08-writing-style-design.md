# Learning how the person writes (AI writing mode)

## Why

Claude/Codex drafts don't sound like the person: they write "jajaja" when she writes "hahaha", or use words she would
never say ("qué chido"). Writing those rules by hand in the instructions is tedious and never ends. Unichat should learn
them by itself, little by little, and hand them to the AI with each draft.

## Goals

- A `writing-style.md` file with how the person writes, which Unichat improves each time the draft button is used and
  which goes in the prompt of every draft.
- It learns from two sources: **their sent messages** (how they write) and **what they correct in a draft before
  sending it** (what they wouldn't say). Corrections weigh more.
- Visible and editable in Settings → Write with AI, in case it learns something wrong; with a button to erase what it
  learned.

## Out of scope

- Different styles per contact or network (a single file; the AI may note differences inside it, e.g. "more formal with
  clients", if it sees them).
- Learning without using the button: nothing is analyzed if the person doesn't use the writing mode.

## What must not happen

- The file keeping data about other people or the chats' contents (names, phone numbers, quotes of others' messages):
  only the person's style traits.
- Learning making the draft fail (if it fails, the draft goes out with the file there was).
- It growing without limit and eating the prompt, or filling with rules until the AI gets confused: **short** (at most
  40 bullets, 3,000 characters), and each update merges, corrects or removes rules instead of only adding.

## Approaches considered

- **A. The AI updates the file each time the button is used, then writes the draft with it (chosen).** It understands
  nuances (Mexican slang, the tone with clients), and what was corrected already counts in that very draft. It costs one
  extra call when there's something new to learn, and that draft takes a little longer.
- B. Unichat works it out without AI (word and emoji counts): no cost, but it stays at lists and doesn't catch the tone.
- C. Mixed, in batches (the AI every ~5 corrections): fewer calls, slower learning. An easy step from A if the cost
  bothers.

## How it works

1. **Remembering the draft.** When a draft lands in a chat's box, the `Composer` remembers its text for that chat. When
   a text message is sent in that chat:
   - if the text sent differs from the draft, it saves the correction (draft → sent);
   - if it's the same, nothing (it teaches nothing new);
   - either way it forgets the draft. Switching chats doesn't forget it (the draft stays in that chat).
   Pending corrections are kept in `writing-style-corrections.json` (the last 20; this computer), so they aren't lost
   if the app closes.
   Pressing the draft button runs two steps, in order:
2. **Step 1: learn.** If there's something new (pending corrections, or my messages in the chat since the last time it
   learned from that chat), one call to the same program (Claude Code or Codex), with no folder to read, with:
   - the current `writing-style.md`;
   - my last ~30 text messages in that chat (only mine);
   - the pending corrections;
   - the order to return the whole file, corrected: only style traits (words and filler words, laughs, emojis,
     punctuation, capitals, length, greetings and goodbyes, language and variety, what they avoid), in short bullets,
     with no names, numbers or quotes of other people; **at most 40 bullets**: add only what's really new, correct
     what was wrong, merge what repeats and remove the least useful if it doesn't fit; what's learned from corrections
     weighs more than what's inferred, and a new rule that contradicts an old one replaces it.
   If it fails or takes more than 2 minutes, one line in the log (never the prompt nor the answer), the corrections stay
   pending and the draft goes on with the file there was: learning never stops the draft. If it works, the file is
   saved (one over 3,000 characters is thrown away) and the corrections used are emptied. With nothing new, this step
   doesn't run and the draft doesn't wait.
3. **Step 2: the draft.** It carries the file after the person's instructions, as "How this person writes (learned
   from their messages; their instructions above win if they clash)". Correcting (⌘O) carries it too, so it doesn't
   change "hahaha" into "jajaja".

## Settings → Write with AI

- "Learn how I write" (on by default): explains that, when there's something new, it uses one more call before the
  draft.
- "What it learned": the file in an editable text box (saved as you type, like the instructions) and "Forget what it
  learned" (empties the file and the pending corrections).
- The privacy notice says that, to learn, your messages in that chat and your corrections are sent to the AI.

## Files

- `writing-style.md`: travels with the person (like `settings.properties`).
- `writing-style-corrections.json`: stays on this computer; emptied when it learns.

## My decisions (to review)

- Correcting (⌘O) also uses the learned style.
- Learning only takes my messages in the open chat, not from all of them: it's what already goes to the AI with the
  draft, and it doesn't widen what leaves the computer.
- Limits: 30 messages, 20 corrections, 40 bullets, 3,000 characters, 2 minutes to learn.

## For whoever builds it

Class names, where the pure logic lives (the learning prompt, the trimming, the comparison) and the tests: pure logic in
`assist/` with tests; the UI in `ui/` (`Composer`, `AiReplyButton`, `AiReplyPage`). No change to the package
architecture.
