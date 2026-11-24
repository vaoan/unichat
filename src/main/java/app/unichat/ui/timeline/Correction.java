package app.unichat.ui.timeline;

import app.unichat.assist.FixPrompt;
import app.unichat.assist.ReplyWriter;
import app.unichat.assist.WritingStyle;
import app.unichat.support.Errors;
import app.unichat.support.I18n;
import org.jspecify.annotations.Nullable;

/**
 * ⌘O's correction of a text, for what goes without the person reviewing it (a message already sent, one waiting to
 * be sent): the same prompt, and an answer that adds what the text didn't have isn't one ({@link FixPrompt#addsWhatWasntThere}).
 */
final class Correction {
    /** The corrected text, or why there's none (in the person's words). */
    record Result(@Nullable String text, @Nullable String failure) {}

    private Correction() {}

    /** Asks {@code writer} to correct {@code original}; on the caller's thread, for as long as the AI takes. */
    static Result of(AiReplyButton.Writer writer, String original) {
        try {
            String answer = writer.write(FixPrompt.build(original, WritingStyle.get().text()));
            if (answer == null) return new Result(null, I18n.t("messageFix.noAnswer")); // stopped: it gives nothing
            String fixed = FixPrompt.keepWrapping(original, answer);
            if (FixPrompt.addsWhatWasntThere(original, fixed)) return new Result(null, I18n.t("messageFix.addsThings"));
            return new Result(fixed, null);
        } catch (ReplyWriter.Failure | RuntimeException e) {
            return new Result(null, Errors.message(e));
        }
    }
}
