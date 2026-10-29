package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.vrchatapi.model.User;

/** Synthetic process-death boundaries: no application constructors or remote clients. */
class ScarletAuditAttributionHandoffTest
{
    @TempDir Path temp;
    static final String ACTOR = "usr_synthetic_secret", TARGET = "usr_training-target";
    static final long NOW = 1_900_000_000_000L;

    static class ProcessDeath extends Error { private static final long serialVersionUID = 1L; }
    static class Lookup extends ScarletVRChat
    {
        Lookup() { super(null, "unused", null); }
        Runnable beforeLookup;
        @Override public User getUser(String id)
        {
            if (beforeLookup != null) beforeLookup.run();
            return new User().displayName("Synthetic requester");
        }
    }
    static class CrashBeforeAcknowledge extends ScarletPendingModActions
    {
        CrashBeforeAcknowledge(File file) { super(null, file, () -> NOW); }
        @Override public synchronized boolean acknowledgePendingAttribution(GroupAuditType type, String target, String token)
        { throw new ProcessDeath(); }
    }
    static class RefuseMigration extends ScarletPendingModActions
    {
        RefuseMigration(File file) { super(null, file, () -> NOW); }
        @Override public synchronized boolean save() { return false; }
    }

    File pendingFile() { return temp.resolve("pending.json").toFile(); }
    ScarletPendingModActions pending()
    { return new ScarletPendingModActions(null, pendingFile(), () -> NOW); }
    ScarletData.AuditEntryMetadata metadata()
    {
        var meta = ScarletDiscordSafetyTest.metadata("gaud_handoff", "usr_synthetic_bot");
        meta.entry.setEventType("group.user.ban");
        return meta;
    }
    Scarlet context(ScarletPendingModActions pending, Runnable beforeLookup) throws Exception
    {
        Scarlet scarlet = inert(Scarlet.class);
        put(scarlet, "data", new ScarletData(temp.toFile()));
        put(scarlet, "pendingModActions", pending);
        Lookup lookup = inert(Lookup.class);
        put(lookup, "currentUserId", "usr_synthetic_bot");
        lookup.beforeLookup = beforeLookup;
        put(scarlet, "vrc", lookup);
        return scarlet;
    }
    ScarletDiscord delivery(Consumer<ScarletData.AuditEntryMetadata> delivered)
    {
        return (ScarletDiscord) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ScarletDiscord.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("processUserModeration"))
                {
                    delivered.accept((ScarletData.AuditEntryMetadata) arguments[1]);
                    return null;
                }
                if (!method.isDefault()) return null;
                return MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup())
                    .unreflectSpecial(method, method.getDeclaringClass()).bindTo(proxy)
                    .invokeWithArguments(arguments == null ? new Object[0] : arguments);
            });
    }
    void reserve(ScarletPendingModActions pending)
    { assertNotNull(pending.reservePending(GroupAuditType.USER_BAN, TARGET, ACTOR)); }
    void confidential(ScarletData.AuditEntryMetadata meta)
    {
        assertEquals(ACTOR, meta.auxActorId);
        assertFalse(ScarletAuditPrivacy.visible(meta, ACTOR::equals, false));
        assertFalse(ScarletAuditPrivacy.auxiliaryAllowed(meta, ACTOR::equals));
    }

    @Test void crashAfterSourceRemovalRetainsRequesterAndConfidentialRouting() throws Exception
    {
        var pending = pending(); reserve(pending);
        var outbox = new ScarletAuditOutbox(temp.toFile());
        var meta = metadata();
        var scarlet = context(pending, () -> {
            confidential(outbox.read(meta.entry.getId()));
            assertNull(pending.peekPendingAttribution(GroupAuditType.USER_BAN, TARGET));
            throw new ProcessDeath();
        });
        assertThrows(ProcessDeath.class, () -> delivery(m -> fail("No delivery before crash"))
            .processAuditEntry(scarlet, meta));
        AtomicInteger deliveries = new AtomicInteger();
        delivery(m -> { confidential(m); deliveries.incrementAndGet(); })
            .processAuditEntry(context(pending(), null), outbox.read(meta.entry.getId()));
        assertEquals(1, deliveries.get());
    }

    @Test void crashBeforeAcknowledgementRestartsWithBothStoresThenAcknowledgesOnlyItsToken() throws Exception
    {
        var pending = new CrashBeforeAcknowledge(pendingFile()); reserve(pending);
        String token = pending.peekPendingAttribution(GroupAuditType.USER_BAN, TARGET).token;
        var meta = metadata();
        assertThrows(ProcessDeath.class, () -> delivery(m -> fail("No delivery before crash"))
            .processAuditEntry(context(pending, null), meta));
        var restarted = pending();
        assertEquals(token, restarted.peekPendingAttribution(GroupAuditType.USER_BAN, TARGET).token);
        var saved = new ScarletAuditOutbox(temp.toFile()).read(meta.entry.getId());
        delivery(this::confidential).processAuditEntry(context(restarted, null), saved);
        assertNull(pending().peekPendingAttribution(GroupAuditType.USER_BAN, TARGET));
    }

    @Test void oldReceiptDoesNotConsumeNewerReservation() throws Exception
    {
        var pending = pending(); reserve(pending);
        var meta = metadata();
        delivery(this::confidential).processAuditEntry(context(pending, null), meta);
        assertNotNull(pending.reservePending(GroupAuditType.USER_BAN, TARGET, "usr_synthetic_new"));
        String newer = pending.peekPendingAttribution(GroupAuditType.USER_BAN, TARGET).token;
        var restarted = pending();
        delivery(this::confidential).processAuditEntry(context(restarted, null),
            new ScarletAuditOutbox(temp.toFile()).read(meta.entry.getId()));
        assertEquals(newer, pending().peekPendingAttribution(GroupAuditType.USER_BAN, TARGET).token);
    }

    @Test void failedOutboxWriteKeepsPendingAttributionAndNeverDelivers() throws Exception
    {
        var pending = pending(); reserve(pending);
        Files.writeString(temp.resolve("audit-outbox"), "synthetic write obstruction");
        assertThrows(java.io.UncheckedIOException.class, () -> delivery(m -> fail("No delivery on failed receipt"))
            .processAuditEntry(context(pending, null), metadata()));
        assertEquals(ACTOR, pending().peekPendingAttribution(GroupAuditType.USER_BAN, TARGET).actorUserId);
    }

    @Test void legacyTokensAreMigratedDurablyBeforeUse() throws Exception
    {
        Files.writeString(pendingFile().toPath(), "{\"pendingModActions\":{\"group.user.ban:usr_training-target\":\"usr_synthetic_secret\"}}");
        String token = pending().peekPendingAttribution(GroupAuditType.USER_BAN, TARGET).token;
        assertEquals(token, pending().peekPendingAttribution(GroupAuditType.USER_BAN, TARGET).token);
    }

    @Test void failedMigrationAndUnreadableStorageNeverPermitUnattributedDelivery() throws Exception
    {
        Files.writeString(pendingFile().toPath(), "{\"pendingModActions\":{\"group.user.ban:usr_training-target\":\"usr_synthetic_secret\"}}");
        var failed = new RefuseMigration(pendingFile());
        assertThrows(IllegalStateException.class, () -> failed.peekPendingAttribution(GroupAuditType.USER_BAN, TARGET));
        Files.writeString(pendingFile().toPath(), "not JSON");
        var broken = pending();
        assertThrows(IllegalStateException.class, () -> delivery(m -> fail("No ordinary-channel fallback"))
            .processAuditEntry(context(broken, null), metadata()));
    }

    @Test void failedInitialStorageCreationNeverPermitsUnattributedDelivery() throws Exception
    {
        Path blockedParent = temp.resolve("blocked");
        Files.writeString(blockedParent, "synthetic parent obstruction");
        var broken = new ScarletPendingModActions(null, blockedParent.resolve("pending.json").toFile(), () -> NOW);
        assertThrows(IllegalStateException.class, () -> delivery(m -> fail("No ordinary-channel fallback"))
            .processAuditEntry(context(broken, null), metadata()));
    }
}
