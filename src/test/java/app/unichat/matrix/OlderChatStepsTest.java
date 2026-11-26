package app.unichat.matrix;

import app.unichat.model.Network;
import app.unichat.service.OlderChats.Answer;
import app.unichat.service.OlderChats.Plan;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OlderChatStepsTest {
    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    /** The settings as the steps read them; every write through it is recorded (none should come). */
    private final List<String> writes = new ArrayList<>();
    private final Map<String, String> settings = new HashMap<>() {
        private static final long serialVersionUID = 1L;

        @Override
        public @Nullable String put(String key, String value) {
            writes.add(key);
            return super.put(key, value);
        }
    };
    private Instant now = NOW;
    private final Set<Network> accounts = EnumSet.of(Network.TELEGRAM, Network.WHATSAPP, Network.DISCORD, Network.LINKEDIN,
            Network.GOOGLE_MESSAGES, Network.INSTAGRAM, Network.MESSENGER, Network.X);

    private OlderChatSteps steps() {
        return new OlderChatSteps(settings::get, accounts, () -> now);
    }

    /** Settings set up by the test itself, not by the steps. */
    private void saved(String key, String value) {
        settings.put(key, value);
        writes.remove(key);
    }

    private OlderChatSteps.Step run(Network n) {
        return assertInstanceOf(OlderChatSteps.Next.Run.class, steps().next(n)).step();
    }

    private Answer done(Network n) {
        return assertInstanceOf(OlderChatSteps.Next.Done.class, steps().next(n)).answer();
    }

    private Plan plan(Network n) {
        return steps().plans(List.of(n)).getFirst();
    }

    @Test
    void telegramAsksForAHundredMore() {
        OlderChatSteps.Step s = run(Network.TELEGRAM);
        assertEquals(OlderChatSteps.Kind.RAISE_RESTART_COMMAND, s.kind());
        assertEquals(Map.of("olderChats.telegram.extra", "100"), s.settings());
        assertEquals("sync-chats", s.command());
        assertEquals(Duration.ofMinutes(10), s.commandPatience());
        assertEquals(new Plan(Network.TELEGRAM, 100, true, false, false, null), s.plan());
        saved("olderChats.telegram.extra", "100");
        assertEquals(Map.of("olderChats.telegram.extra", "200"), run(Network.TELEGRAM).settings(), "each step adds 100 more");
    }

    @Test
    void whatsappAsksGroupsFirst() {
        OlderChatSteps.Step s = run(Network.WHATSAPP);
        assertEquals(OlderChatSteps.Kind.COMMAND, s.kind());
        assertEquals("sync groups", s.command());
        assertEquals(new Plan(Network.WHATSAPP, -1, false, false, false, null), s.plan());
    }

    @Test
    void whatsappThenAsksAHundredHistoryChats() {
        saved("olderChats.whatsapp.groups", "done");
        OlderChatSteps.Step s = run(Network.WHATSAPP);
        assertEquals(OlderChatSteps.Kind.COMMAND, s.kind());
        assertEquals("sync-history-chats 100", s.command());
        assertEquals(100, s.plan().chats());
        assertFalse(s.plan().restarts());
    }

    @Test
    void discordAsksFiftyMoreAndSaysItReconnectsEveryServer() {
        OlderChatSteps.Step s = run(Network.DISCORD);
        assertEquals(OlderChatSteps.Kind.RAISE_RESTART, s.kind());
        assertEquals("50", s.settings().get("olderChats.discord.extra"));
        assertEquals(new Plan(Network.DISCORD, 50, true, true, false, null), s.plan());
    }

    @Test
    void discordWaitsTenMinutesBetweenSteps() {
        saved("olderChats.discord.at", NOW.minus(Duration.ofMinutes(3)).toString());
        assertEquals(Duration.ofMinutes(7), plan(Network.DISCORD).waitFirst());
        assertInstanceOf(Answer.NotPossible.class, done(Network.DISCORD), "no runnable step while it waits");
        assertTrue(steps().hasMore(List.of(Network.DISCORD)), "it still has more, later");
        now = NOW.plus(Duration.ofMinutes(7));
        assertNull(run(Network.DISCORD).plan().waitFirst(), "at 10 minutes it runs");
    }

    @Test
    void linkedinAndGoogleMessagesAskForEverythingOnce() {
        for (Network n : List.of(Network.LINKEDIN, Network.GOOGLE_MESSAGES)) {
            OlderChatSteps.Step s = run(n);
            assertEquals(OlderChatSteps.Kind.RAISE_RESTART, s.kind(), n.name());
            assertEquals(-1, s.plan().chats(), n.name());
            assertTrue(s.plan().restarts(), n.name());
            assertFalse(s.plan().risky(), n.name());
        }
        assertEquals(Map.of("olderChats.linkedin.all", "true"), run(Network.LINKEDIN).settings());
        assertEquals(Map.of("olderChats.gmessages.once", "true"), run(Network.GOOGLE_MESSAGES).settings());
        saved("olderChats.linkedin.done", "true");
        saved("olderChats.gmessages.done", "true");
        assertEquals(new Answer.NothingMore(), done(Network.LINKEDIN));
        assertEquals(new Answer.NothingMore(), done(Network.GOOGLE_MESSAGES));
    }

    @Test
    void instagramIsRiskyAndOneShot() {
        OlderChatSteps.Step s = run(Network.INSTAGRAM);
        assertEquals(new Plan(Network.INSTAGRAM, -1, true, false, true, null), s.plan());
        assertEquals(Map.of("olderChats.instagram.once", "true"), s.settings());
        saved("olderChats.instagram.done", "true");
        assertEquals(new Answer.NothingMore(), done(Network.INSTAGRAM));
    }

    @Test
    void facebookAndXHaveNothingMore() {
        assertEquals(new Answer.NothingMore(), done(Network.MESSENGER));
        assertEquals(new Answer.NothingMore(), done(Network.X));
        assertEquals(List.of(), steps().plans(List.of(Network.MESSENGER, Network.X)));
    }

    @Test
    void aNetworkWithoutAnAccountIsNotPossible() {
        accounts.remove(Network.TELEGRAM);
        assertInstanceOf(Answer.NotPossible.class, done(Network.TELEGRAM));
        assertEquals(List.of(), steps().plans(List.of(Network.TELEGRAM)));
    }

    @Test
    void aNetworkThatLoadedNothingHasNothingMore() {
        saved("olderChats.telegram.done", "true");
        assertEquals(new Answer.NothingMore(), done(Network.TELEGRAM));
        assertFalse(steps().hasMore(List.of(Network.TELEGRAM)));
    }

    @Test
    void hasMoreIsTrueWhileAnyNetworkInViewHasAStep() {
        assertTrue(steps().hasMore(List.of(Network.MESSENGER, Network.TELEGRAM)));
        assertFalse(steps().hasMore(List.of(Network.MESSENGER, Network.X)));
    }

    @Test
    void theOrderIsFixed() {
        assertEquals(List.of(Network.TELEGRAM, Network.DISCORD, Network.INSTAGRAM),
                steps().inViewOrder(List.of(Network.INSTAGRAM, Network.TELEGRAM, Network.DISCORD)));
        assertEquals(List.of(Network.TELEGRAM, Network.WHATSAPP, Network.DISCORD, Network.LINKEDIN, Network.GOOGLE_MESSAGES,
                Network.INSTAGRAM), steps().plans(List.copyOf(accounts)).stream().map(Plan::network).toList());
    }

    @Test
    void noPlanTriggersTheTelegramResync() {
        OlderChatSteps s = steps();
        var _ = s.plans(List.copyOf(accounts));
        var _ = s.hasMore(List.copyOf(accounts));
        for (Network n : Network.values()) {
            var _ = s.next(n);
        }
        assertEquals(List.of(), writes, "building steps and plans writes nothing");
        assertFalse(settings.containsKey("telegram.chatSyncLimit"));
    }
}
