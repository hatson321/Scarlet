package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletPendingBanReceiptTest
{
    @TempDir Path temp;
    static class Store extends ScarletPendingModActions
    {
        boolean refuse;
        Store(File file) { super(null, file, () -> 1900000000000L); }
        @Override public synchronized boolean save() { return !refuse && super.save(); }
    }
    Store store() { return new Store(temp.resolve("pending.json").toFile()); }
    ScarletDiscordJDA context(Store store) throws Exception
    {
        Scarlet app = inert(Scarlet.class);
        put(app, "pendingModActions", store);
        put(app, "data", new ScarletData(temp.toFile()));
        ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        put(discord, "scarlet", app);
        put(discord, "guildSf", "guild-test");
        return discord;
    }
    ScarletData.AuditEntryMetadata metadata()
    {
        var meta = ScarletDiscordSafetyTest.metadata("gaud_ban_receipt", "usr_synthetic_bot");
        meta.entry.setEventType("group.user.ban");
        return meta;
    }

    @Test void failedMutationsPreserveTheLastDurableRevision()
    {
        Store store = store();
        store.refuse = true;
        assertThrows(IllegalStateException.class, () -> store.addBanInfo("target"));
        assertNull(store.getBanInfo("target"));
        store.refuse = false;
        assertTrue(store.addBanInfo("target"));
        assertTrue(store.setBanInfoDescription("target", "saved"));
        assertTrue(store.setBanInfoTags("target", new String[]{"saved-tag"}));
        String token = store.getBanInfo("target").token;
        store.refuse = true;
        assertThrows(IllegalStateException.class, () -> store.setBanInfoDescription("target", "lost"));
        assertThrows(IllegalStateException.class, () -> store.setBanInfoTags("target", new String[]{"lost"}));
        assertThrows(IllegalStateException.class, () -> store.pollBanInfo("target"));
        assertEquals("saved", store.getBanInfo("target").description);
        assertArrayEquals(new String[]{"saved-tag"}, store.getBanInfo("target").tags);
        assertEquals(token, store().getBanInfo("target").token);
        assertFalse(store.setBanInfoTags("missing", new String[]{"tag"}));
        assertFalse(store.setBanInfoDescription("missing", "desc"));
    }

    @Test void outboxFailureKeepsPendingDataAndRetryPersistsBeforeRemoving() throws Exception
    {
        Store store = store(); var meta = metadata(); String target = meta.entry.getTargetId();
        store.addBanInfo(target);
        store.setBanInfoDescription(target, "saved description");
        var discord = context(store);
        Path obstacle = temp.resolve("audit-outbox");
        Files.writeString(obstacle, "synthetic obstruction");
        assertThrows(java.io.UncheckedIOException.class, () -> discord.captureModerationBanInfo(meta));
        assertEquals("saved description", store().getBanInfo(target).description);
        Files.delete(obstacle);
        discord.captureModerationBanInfo(meta);
        assertNull(store().getBanInfo(target));
        assertEquals("saved description", new ScarletAuditOutbox(temp.toFile()).read(meta.entry.getId()).entryDescription);
    }

    @Test void failedAcknowledgementRestartsAndOldReceiptCannotConsumeNewEdits() throws Exception
    {
        Store store = store(); var meta = metadata(); String target = meta.entry.getTargetId();
        store.addBanInfo(target); store.setBanInfoDescription(target, "original");
        store.refuse = true;
        assertThrows(IllegalStateException.class, () -> context(store).captureModerationBanInfo(meta));
        var receipt = new ScarletAuditOutbox(temp.toFile()).read(meta.entry.getId());
        Store restarted = store();
        restarted.setBanInfoDescription(target, "newer edit");
        context(restarted).captureModerationBanInfo(receipt);
        assertEquals("newer edit", store().getBanInfo(target).description);
        assertEquals("original", receipt.entryDescription);
    }

    @Test void crossGroupPendingInfoIsNeverAttached() throws Exception
    {
        Store store = store(); var meta = metadata(); String target = meta.entry.getTargetId();
        store.addBanInfo(target, "owner", "actor", "other-group", "guild-test");
        store.setBanInfoDescription(target, "private unrelated note");
        context(store).captureModerationBanInfo(meta);
        assertNotEquals("private unrelated note", meta.entryDescription);
        assertNotNull(store().getBanInfo(target));
    }
}
