package net.sybyline.scarlet.server.discord;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.server.discord.DAVEManagerConcurrencyTest.proxy;
import java.util.ArrayList;
import java.util.List;
import moe.kyokobot.libdave.*;
import moe.kyokobot.libdave.jda.internal.DAVEManager;
import org.junit.jupiter.api.Test;

class DAVEManagerConstructionTest
{
    @Test void eachConstructionFailureClosesOnlyAllocatedResourcesInReverseOrder()
    {
        for (String failureAt : List.of("session", "encryptor", "passthrough", "version"))
        {
            List<String> closes = new ArrayList<>();
            IllegalStateException failure = new IllegalStateException("synthetic " + failureAt);
            Session session = proxy(Session.class, (p, m, a) -> {
                if (m.getName().equals("close")) closes.add("session");
                return null;
            });
            Encryptor encryptor = proxy(Encryptor.class, (p, m, a) -> {
                if (m.getName().equals("close")) closes.add("encryptor");
                if (m.getName().equals("setPassthroughMode") && failureAt.equals("passthrough")) throw failure;
                return null;
            });
            DaveFactory factory = proxy(DaveFactory.class, (p, m, a) -> {
                switch (m.getName()) {
                case "createSession": if (failureAt.equals("session")) throw failure; return session;
                case "createEncryptor": if (failureAt.equals("encryptor")) throw failure; return encryptor;
                case "maxSupportedProtocolVersion": throw failure;
                default: throw new AssertionError(m.getName());
                }
            });
            assertSame(failure, assertThrows(IllegalStateException.class, () -> new DAVEManager(factory, 1, 2, null)));
            assertEquals(failureAt.equals("session") ? List.of() : failureAt.equals("encryptor") ? List.of("session") : List.of("encryptor", "session"), closes);
        }
    }

    @Test void cleanupExceptionsDoNotMaskOriginalOrPreventRemainingCleanup()
    {
        List<String> closes = new ArrayList<>();
        Session session = proxy(Session.class, (p, m, a) -> { closes.add("session"); throw new IllegalStateException("session cleanup"); });
        Encryptor encryptor = proxy(Encryptor.class, (p, m, a) -> {
            if (m.getName().equals("close")) { closes.add("encryptor"); throw new IllegalStateException("encryptor cleanup"); }
            return null;
        });
        LinkageError original = new LinkageError("synthetic native version failure");
        DaveFactory factory = proxy(DaveFactory.class, (p, m, a) -> {
            if (m.getName().equals("createSession")) return session;
            if (m.getName().equals("createEncryptor")) return encryptor;
            throw original;
        });
        assertSame(original, assertThrows(LinkageError.class, () -> new DAVEManager(factory, 1, 2, null)));
        assertEquals(List.of("encryptor", "session"), closes);
        assertEquals(2, original.getSuppressed().length);
    }
}
