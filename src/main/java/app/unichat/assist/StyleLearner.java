package app.unichat.assist;

import app.unichat.model.Message;
import app.unichat.model.MessageType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The first step of a draft: when there's something new (corrections to drafts, or messages of mine in the chat since
 * the last time), the AI program updates {@code writing-style.md} from it. Learning never stops the draft: whatever
 * goes wrong, the draft gets the file as it was.
 */
public final class StyleLearner {
    /** My last messages of the chat that are read each time. */
    static final int MY_MESSAGES = 30;
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    /** The AI program; tests use their own. */
    public interface Program {
        String write(String prompt) throws ReplyWriter.Failure;

        /** The draft was cancelled: stop learning too. */
        default void cancel() { }
    }

    /** One learning at a time: they all share the file. Not the file's own lock, which the AI call would hold for minutes. */
    private static final Object LEARNING = new Object();

    private final WritingStyle store;
    private final Program program;

    public StyleLearner(WritingStyle store, Program program) {
        this.store = store;
        this.program = program;
    }

    /** The person's program, with no folder to read (as a fix). */
    public static Program program(AiEngine engine) {
        ReplyWriter writer = new ReplyWriter(TIMEOUT);
        return new Program() {
            @Override public String write(String prompt) throws ReplyWriter.Failure { return writer.write(engine, null, prompt); }

            @Override public void cancel() { writer.cancel(); }
        };
    }

    /** The style to write {@code chatId}'s draft with: updated first if there's something new. Blocking. */
    public String learn(String chatId, List<Message> chat) {
        synchronized (LEARNING) {
            List<Message> mine = mine(chat);
            String newest = mine.isEmpty() ? null : mine.get(mine.size() - 1).id();
            List<StylePrompt.Correction> corrections = store.pending();
            boolean fresh = newest != null && !newest.equals(store.learnedUpTo(chatId));
            String current = store.text();
            if (corrections.isEmpty() && !fresh) return current;
            try {
                String answer = program.write(StylePrompt.learn(current, mine.stream().map(Message::body).toList(), corrections));
                if (answer == null) return current; // cancelled
                String learned = StylePrompt.accept(answer);
                if (learned == null) {
                    System.err.println("Writing style not learned: the answer wasn't a short list of traits");
                    return current;
                }
                return store.commit(current, learned, corrections.size(), chatId, newest) ? learned : store.text();
            } catch (ReplyWriter.Failure e) {
                System.err.println("Writing style not learned: the AI program failed");
                return current;
            }
        }
    }

    /** My last text messages of the chat, oldest first. */
    private List<Message> mine(List<Message> chat) {
        List<Message> out = new ArrayList<>();
        for (int i = chat.size() - 1; i >= 0 && out.size() < MY_MESSAGES; i--) {
            Message m = chat.get(i);
            if (m.outgoing() && m.type() == MessageType.TEXT && !m.deleted() && m.body() != null && !m.body().isBlank()
                    && !store.isWrittenByAi(m.body())) out.add(0, m);
        }
        return out;
    }
}
