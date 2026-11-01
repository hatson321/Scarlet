package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;

/** Synthetic files only; no live profile, network, or game data is accessed. */
class ScarletLocalAuditHistoryTest
{
    @TempDir Path temp;
    static final OffsetDateTime FROM = OffsetDateTime.parse("2026-10-01T00:00:00Z");
    static final OffsetDateTime TO = FROM.plusDays(14);
    static final String GROUP = "grp_test";
    static final Set<String> TYPES = new HashSet<>(Arrays.asList("group.user.ban", "group.instance.kick", "group.member.join"));

    ScarletData.AuditEntryMetadata record(String id, OffsetDateTime time)
    {
        ScarletData.AuditEntryMetadata metadata = new ScarletData.AuditEntryMetadata();
        metadata.entry = new GroupAuditLogEntryUnknown().id(id).groupId(GROUP).createdAt(time)
            .eventType("group.user.ban").actorId("usr_actor").actorDisplayName("Local moderator").targetId("usr_target");
        return metadata;
    }

    void save(String folder, ScarletData.AuditEntryMetadata metadata) throws IOException
    {
        Path dir = Files.createDirectories(this.temp.resolve(folder));
        Files.write(dir.resolve(metadata.entry.getId() + (folder.equals("audit-outbox") ? ".json" : "")),
            Scarlet.GSON.toJson(metadata).getBytes(StandardCharsets.UTF_8));
    }

    ScarletLocalAuditHistory.Result read() { return ScarletLocalAuditHistory.read(this.temp.toFile(), GROUP, FROM, TO, TYPES); }

    @Test void readsBothDateBoundariesAndFiltersGroupTypeAndOutsidePeriod() throws Exception
    {
        this.save("gaud", this.record("gaud_begin", FROM.withOffsetSameInstant(java.time.ZoneOffset.ofHours(-5))));
        this.save("gaud", this.record("gaud_end", TO));
        this.save("gaud", this.record("gaud_old", FROM.minusNanos(1)));
        this.save("gaud", this.record("gaud_future", TO.plusNanos(1)));
        ScarletData.AuditEntryMetadata wrongGroup = this.record("gaud_other_group", FROM.plusDays(7));
        wrongGroup.entry.setGroupId("grp_other"); this.save("gaud", wrongGroup);
        ScarletData.AuditEntryMetadata wrongType = this.record("gaud_other_type", FROM);
        wrongType.entry.setEventType("group.role.update"); this.save("gaud", wrongType);
        ScarletLocalAuditHistory.Result result = this.read();
        assertFalse(result.hasReadProblems);
        assertEquals(new HashSet<>(Arrays.asList("gaud_begin", "gaud_end")), result.records.stream().map(m -> m.entry.getId()).collect(Collectors.toSet()));
    }

    @Test void retainsPrivacyAndAttributionWithoutLoadingSnapshots() throws Exception
    {
        ScarletData.AuditEntryMetadata metadata = this.record("gaud_private", FROM);
        metadata.entryRedacted = true;
        metadata.auxActorId = "usr_secret";
        metadata.auxActorDisplayName = "Secret moderator";
        metadata.parentEventId = "gaud_parent";
        metadata.snapshotTargetUser = new JsonObject();
        metadata.snapshotTargetUser.addProperty("private", "must not be retained");
        metadata.entry.putDataItem("private", "must not be retained");
        this.save("gaud", metadata);
        ScarletData.AuditEntryMetadata read = this.read().records.get(0);
        assertTrue(read.entryRedacted);
        assertEquals("usr_secret", read.auxActorId);
        assertEquals("Secret moderator", read.auxActorDisplayName);
        assertEquals("gaud_parent", read.parentEventId);
        assertNull(read.snapshotTargetUser);
        assertNull(read.snapshotTargetUserGroups);
        assertTrue(read.entry.getData() == null || read.entry.getData().isEmpty());
        assertFalse(ScarletAuditPrivacy.visible(read, "usr_secret"::equals, false));
    }

    @Test void compatibleDuplicateCountsOnceAndUsesCommittedDisplayName() throws Exception
    {
        ScarletData.AuditEntryMetadata committed = this.record("gaud_same", FROM);
        committed.auxActorId = "usr_aux"; committed.auxActorDisplayName = "Current name";
        this.save("gaud", committed);
        ScarletData.AuditEntryMetadata pending = this.record("gaud_same", FROM);
        pending.auxActorId = "usr_aux"; pending.auxActorDisplayName = "Stale name";
        this.save("audit-outbox", pending);
        ScarletLocalAuditHistory.Result result = this.read();
        assertFalse(result.hasReadProblems);
        assertFalse(result.hasUnprocessedEvents);
        assertEquals(1, result.records.size());
        assertEquals("Current name", result.records.get(0).auxActorDisplayName);
    }

    @Test void staleOutboxCannotRemoveCommittedRedaction() throws Exception
    {
        ScarletData.AuditEntryMetadata committed = this.record("gaud_same", FROM);
        committed.entryRedacted = true;
        this.save("gaud", committed);
        this.save("audit-outbox", this.record("gaud_same", FROM));
        ScarletLocalAuditHistory.Result result = this.read();
        assertTrue(result.hasReadProblems);
        assertTrue(result.hasFatalProblems);
        assertEquals(0, result.unreadableFileCount);
        assertTrue(result.records.isEmpty());
    }

    @Test void staleOutboxCannotRemoveSecretAuxActor() throws Exception
    {
        ScarletData.AuditEntryMetadata committed = this.record("gaud_same", FROM);
        committed.auxActorId = "usr_secret";
        this.save("gaud", committed);
        this.save("audit-outbox", this.record("gaud_same", FROM));
        ScarletLocalAuditHistory.Result result = this.read();
        assertTrue(result.hasFatalProblems);
        assertEquals(0, result.unreadableFileCount);
        assertTrue(result.records.isEmpty());
    }

    @Test void conflictingOtherGroupCopyCannotBecomeCurrentGroupEvent() throws Exception
    {
        ScarletData.AuditEntryMetadata committed = this.record("gaud_same", FROM);
        committed.entry.setGroupId("grp_previous"); this.save("gaud", committed);
        ScarletData.AuditEntryMetadata pending = this.record("gaud_same", FROM);
        pending.setAuxData("deliveryState", "delivered"); this.save("audit-outbox", pending);
        ScarletLocalAuditHistory.Result result = this.read();
        assertTrue(result.hasFatalProblems);
        assertEquals(0, result.unreadableFileCount);
        assertTrue(result.records.isEmpty());
    }

    @Test void terminalAndFollowupOutboxEventsAreCountedWithoutDiscordDeliveryDependency() throws Exception
    {
        for (String state : Arrays.asList("delivered", "suppressed", "followup_pending"))
        {
            ScarletData.AuditEntryMetadata metadata = this.record("gaud_" + state, FROM);
            metadata.setAuxData("deliveryState", state); this.save("audit-outbox", metadata);
        }
        ScarletLocalAuditHistory.Result result = this.read();
        assertFalse(result.hasReadProblems);
        assertFalse(result.hasUnprocessedEvents);
        assertEquals(3, result.records.size());
    }

    @Test void unresolvedPendingOutboxHasCoverageWarningRatherThanGuessedAttribution() throws Exception
    {
        for (String state : Arrays.asList("pending", "dependency_pending", "unexpected"))
        {
            ScarletData.AuditEntryMetadata metadata = this.record("gaud_" + state, FROM);
            metadata.setAuxData("deliveryState", state); this.save("audit-outbox", metadata);
        }
        ScarletLocalAuditHistory.Result result = this.read();
        assertFalse(result.hasReadProblems);
        assertTrue(result.hasUnprocessedEvents);
        assertTrue(result.records.isEmpty());
    }

    @Test void unrelatedPendingOutboxDoesNotWarnAboutSelectedGroupOrDates() throws Exception
    {
        this.save("audit-outbox", this.record("gaud_old", FROM.minusDays(1)));
        ScarletData.AuditEntryMetadata other = this.record("gaud_other", FROM);
        other.entry.setGroupId("grp_other"); this.save("audit-outbox", other);
        assertFalse(this.read().hasUnprocessedEvents);
    }

    @Test void bundledBanAndKickRemainSeparateObservedEvents() throws Exception
    {
        this.save("gaud", this.record("gaud_ban", FROM));
        ScarletData.AuditEntryMetadata kick = this.record("gaud_kick", FROM);
        kick.entry.setEventType("group.instance.kick"); kick.parentEventId = "gaud_ban";
        this.save("gaud", kick);
        assertEquals(2, this.read().records.size());
    }

    @Test void excludesTrainingAuditActorAuxiliaryActorAndTarget() throws Exception
    {
        this.save("gaud", this.record("gaud_training-test", FROM));
        ScarletData.AuditEntryMetadata actor = this.record("gaud_actor", FROM);
        actor.entry.setActorId("usr_training-actor"); this.save("gaud", actor);
        ScarletData.AuditEntryMetadata aux = this.record("gaud_aux", FROM);
        aux.auxActorId = "usr_training-aux"; this.save("gaud", aux);
        ScarletData.AuditEntryMetadata target = this.record("gaud_target", FROM);
        target.entry.setTargetId("usr_training-target"); this.save("gaud", target);
        assertTrue(this.read().records.isEmpty());
        assertFalse(this.read().hasReadProblems);
    }

    @Test void malformedMissingScopeAndMismatchedIdsWarn() throws Exception
    {
        Path gaud = Files.createDirectories(this.temp.resolve("gaud"));
        Files.write(gaud.resolve("gaud_bad"), "{broken".getBytes(StandardCharsets.UTF_8));
        Files.write(gaud.resolve("gaud_wrong_name"), Scarlet.GSON.toJson(this.record("gaud_actual", FROM)).getBytes(StandardCharsets.UTF_8));
        ScarletData.AuditEntryMetadata missing = this.record("gaud_missing_group", FROM);
        missing.entry.setGroupId(null); this.save("gaud", missing);
        ScarletLocalAuditHistory.Result result = this.read();
        assertTrue(result.hasReadProblems);
        assertFalse(result.hasFatalProblems);
        assertEquals(3, result.unreadableFileCount);
        assertTrue(result.records.isEmpty());
    }

    @Test void rejectsDuplicatePrivacyFieldsAndTrailingJson() throws Exception
    {
        Path gaud = Files.createDirectories(this.temp.resolve("gaud"));
        String json = Scarlet.GSON.toJson(this.record("gaud_duplicate", FROM));
        Files.write(gaud.resolve("gaud_duplicate"), ("{\"entryRedacted\":true," + json.substring(1)).getBytes(StandardCharsets.UTF_8));
        Files.write(gaud.resolve("gaud_trailing"), (Scarlet.GSON.toJson(this.record("gaud_trailing", FROM)) + "{}").getBytes(StandardCharsets.UTF_8));
        ScarletLocalAuditHistory.Result result = this.read();
        assertTrue(result.hasReadProblems);
        assertFalse(result.hasFatalProblems);
        assertEquals(2, result.unreadableFileCount);
        assertTrue(result.records.isEmpty());
    }

    @Test void ignoresAtomicTemporaryAndBackupFilesWithoutReadingThem() throws Exception
    {
        Path gaud = Files.createDirectories(this.temp.resolve("gaud"));
        Files.createDirectories(gaud.resolve("backups"));
        for (String name : Arrays.asList("gaud_example.12345.tmp", "gaud_example.20261015-120000.bak", "backups/not-json"))
            Files.write(gaud.resolve(name), "not JSON".getBytes(StandardCharsets.UTF_8));
        assertFalse(this.read().hasReadProblems);
        assertTrue(this.read().records.isEmpty());
    }

    @Test void refusesSymbolicRecordAndDirectoryLinks() throws Exception
    {
        Path outside = Files.createDirectories(this.temp.resolve("outside"));
        Path actual = outside.resolve("gaud_link");
        Files.write(actual, Scarlet.GSON.toJson(this.record("gaud_link", FROM)).getBytes(StandardCharsets.UTF_8));
        Path gaud = Files.createDirectories(this.temp.resolve("gaud"));
        try { Files.createSymbolicLink(gaud.resolve("gaud_link"), actual); }
        catch (IOException | UnsupportedOperationException | SecurityException unsupported) { assumeTrue(false, "Symbolic links unavailable"); }
        ScarletLocalAuditHistory.Result linkedRecord = this.read();
        assertTrue(linkedRecord.hasReadProblems);
        assertFalse(linkedRecord.hasFatalProblems);
        assertEquals(1, linkedRecord.unreadableFileCount);
        assertTrue(linkedRecord.records.isEmpty());
        Files.delete(gaud.resolve("gaud_link")); Files.delete(gaud);
        Files.createSymbolicLink(gaud, outside);
        ScarletLocalAuditHistory.Result linkedDirectory = this.read();
        assertTrue(linkedDirectory.hasFatalProblems);
        assertEquals(0, linkedDirectory.unreadableFileCount);
        assertTrue(linkedDirectory.records.isEmpty());
    }

    @Test void globalFileRecordByteAndElapsedBudgetsRemainFatal() throws Exception
    {
        this.save("gaud", this.record("gaud_a", FROM));
        this.save("gaud", this.record("gaud_b", FROM));
        for (ScarletLocalAuditHistory.Limits limits : Arrays.asList(
            new ScarletLocalAuditHistory.Limits(1, 100, 10000, 100000, 10000),
            new ScarletLocalAuditHistory.Limits(100, 1, 10000, 100000, 10000),
            new ScarletLocalAuditHistory.Limits(100, 100, 10000, 10, 10000),
            new ScarletLocalAuditHistory.Limits(100, 100, 10000, 100000, 0)))
        {
            ScarletLocalAuditHistory.Result result = ScarletLocalAuditHistory.read(this.temp.toFile(), GROUP, FROM, TO, TYPES, limits);
            assertTrue(result.hasFatalProblems);
            assertEquals(0, result.unreadableFileCount);
        }
    }

    @Test void interruptionStopsReadingAndPreservesInterruptFlag() throws Exception
    {
        this.save("gaud", this.record("gaud_a", FROM));
        Thread.currentThread().interrupt();
        try
        {
            ScarletLocalAuditHistory.Result result = this.read();
            assertTrue(result.hasReadProblems); assertTrue(result.records.isEmpty());
            assertTrue(result.hasFatalProblems); assertEquals(0, result.unreadableFileCount);
            assertTrue(Thread.currentThread().isInterrupted());
        }
        finally { Thread.interrupted(); }
    }

    void custom(String kind, String actor, String location, OffsetDateTime timestamp, int salt) throws IOException
    {
        ScarletData.CustomEvent event = new ScarletData.CustomEvent();
        event.id = new UUID(timestamp.toInstant().toEpochMilli(), ((long)salt << 32) | (0xFFFFFFFFL & kind.hashCode())).toString();
        event.typeEx = kind; event.actorId = actor; event.targetId = location; event.timestamp = timestamp;
        Path folder = Files.createDirectories(this.temp.resolve("ex"));
        Files.write(folder.resolve(event.id), Scarlet.GSON.toJson(event).getBytes(StandardCharsets.UTF_8));
    }

    @Test void customJoinsRequireExactCurrentGroupAndExcludeTrainingAndOtherDates() throws Exception
    {
        String location = "wrld_test:123~group(grp_test)~groupAccessType(members)";
        this.custom(GroupAuditTypeEx.STAFF_JOIN.id, "usr_staff", location, FROM, 1);
        this.custom(GroupAuditTypeEx.USER_JOIN.id, "usr_user", location, TO, 2);
        this.custom(GroupAuditTypeEx.USER_JOIN.id, "usr_training-user", location, FROM, 3);
        this.custom(GroupAuditTypeEx.USER_JOIN.id, "usr_other", "wrld_test:123~group(grp_other)", FROM, 4);
        this.custom(GroupAuditTypeEx.USER_JOIN.id, "usr_unscoped", "wrld_test:123", FROM, 5);
        this.custom(GroupAuditTypeEx.USER_JOIN.id, "usr_prefix", "wrld_test:123~group(grp_test2)", FROM, 6);
        this.custom(GroupAuditTypeEx.USER_JOIN.id, "usr_double", location + "~group(grp_other)", FROM, 7);
        this.custom(GroupAuditTypeEx.USER_JOIN.id, "usr_old", location, FROM.minusDays(1), 8);
        ScarletLocalAuditHistory.Result result = this.read();
        assertFalse(result.hasReadProblems);
        assertEquals(2, result.customEvents.size());
        assertEquals(new HashSet<>(Arrays.asList("usr_staff", "usr_user")), result.customEvents.stream().map(e -> e.actorId).collect(Collectors.toSet()));
    }

    @Test void customMalformedSelectedRecordIsCountedAndSkipped() throws Exception
    {
        this.custom(GroupAuditTypeEx.STAFF_JOIN.id, "usr_staff", "wrld_test:123~group(grp_test)", FROM, 1);
        Path record;
        try (java.util.stream.Stream<Path> stream = Files.list(this.temp.resolve("ex"))) { record = stream.findFirst().get(); }
        Files.write(record, "{}".getBytes(StandardCharsets.UTF_8));
        ScarletLocalAuditHistory.Result result = this.read();
        assertTrue(result.hasReadProblems);
        assertFalse(result.hasFatalProblems);
        assertEquals(1, result.unreadableFileCount);
        assertTrue(result.customEvents.isEmpty());
    }

    @Test void unreadableFilesDoNotPreventOtherAuditAndJoinRecordsBeingRead() throws Exception
    {
        this.save("gaud", this.record("gaud_good", FROM));
        ScarletData.AuditEntryMetadata delivered = this.record("gaud_delivered", FROM);
        delivered.setAuxData("deliveryState", "delivered"); this.save("audit-outbox", delivered);
        Files.write(this.temp.resolve("gaud/gaud_bad"), "{broken".getBytes(StandardCharsets.UTF_8));
        Files.write(this.temp.resolve("audit-outbox/gaud_bad_outbox.json"), "{broken".getBytes(StandardCharsets.UTF_8));
        this.custom(GroupAuditTypeEx.STAFF_JOIN.id, "usr_staff", "wrld_test:123~group(grp_test)", FROM, 1);
        this.custom(GroupAuditTypeEx.USER_JOIN.id, "usr_user", "wrld_test:123~group(grp_test)", FROM, 2);
        String badId = new UUID(FROM.toInstant().toEpochMilli(), (3L << 32) | (0xFFFFFFFFL & GroupAuditTypeEx.USER_JOIN.id.hashCode())).toString();
        Files.write(this.temp.resolve("ex").resolve(badId), "{broken".getBytes(StandardCharsets.UTF_8));
        ScarletLocalAuditHistory.Result result = this.read();
        assertFalse(result.hasFatalProblems);
        assertTrue(result.hasReadProblems);
        assertEquals(3, result.unreadableFileCount);
        assertEquals(new HashSet<>(Arrays.asList("gaud_good", "gaud_delivered")),
            result.records.stream().map(m -> m.entry.getId()).collect(Collectors.toSet()));
        assertEquals(2, result.customEvents.size());
    }

    @Test void readableReceiptCannotResurrectUnreadableCommittedPrivacyMetadata() throws Exception
    {
        this.save("gaud", this.record("gaud_good", FROM));
        Files.write(this.temp.resolve("gaud/gaud_failed"), "{\"entryRedacted\":true,\"entry\":".getBytes(StandardCharsets.UTF_8));
        ScarletData.AuditEntryMetadata receipt = this.record("gaud_failed", FROM);
        receipt.setAuxData("deliveryState", "delivered"); this.save("audit-outbox", receipt);
        ScarletLocalAuditHistory.Result result = this.read();
        assertFalse(result.hasFatalProblems);
        assertEquals(1, result.unreadableFileCount, "The readable receipt is not itself an unreadable file");
        assertEquals(1, result.records.size());
        assertEquals("gaud_good", result.records.get(0).entry.getId());
    }

    @Test void unreadableReceiptRemovesEarlierReadableCopyOfSameAuditId() throws Exception
    {
        this.save("gaud", this.record("gaud_good", FROM));
        this.save("gaud", this.record("gaud_failed", FROM));
        Files.createDirectories(this.temp.resolve("audit-outbox"));
        Files.write(this.temp.resolve("audit-outbox/gaud_failed.json"), "{\"auxActorId\":\"usr_secret\",".getBytes(StandardCharsets.UTF_8));
        ScarletLocalAuditHistory.Result result = this.read();
        assertFalse(result.hasFatalProblems);
        assertEquals(1, result.unreadableFileCount);
        assertEquals(1, result.records.size());
        assertEquals("gaud_good", result.records.get(0).entry.getId());
    }

    @Test void twoUnreadableCopiesCountAsTwoDistinctFilesButNeverAsEvents() throws Exception
    {
        Files.createDirectories(this.temp.resolve("gaud"));
        Files.createDirectories(this.temp.resolve("audit-outbox"));
        Files.write(this.temp.resolve("gaud/gaud_failed"), "{broken".getBytes(StandardCharsets.UTF_8));
        Files.write(this.temp.resolve("audit-outbox/gaud_failed.json"), "{broken".getBytes(StandardCharsets.UTF_8));
        ScarletLocalAuditHistory.Result result = this.read();
        assertFalse(result.hasFatalProblems);
        assertEquals(2, result.unreadableFileCount);
        assertTrue(result.records.isEmpty());
    }

    @Test void repeatedReadOfOneFailedCommittedPathCountsOnlyOnce() throws Exception
    {
        Files.createDirectories(this.temp.resolve("gaud"));
        Path path = this.temp.resolve("gaud/gaud_failed");
        Files.write(path, "{broken".getBytes(StandardCharsets.UTF_8));
        Class<?> loaderType = Class.forName(ScarletLocalAuditHistory.class.getName() + "$Loader");
        java.lang.reflect.Constructor<?> constructor = loaderType.getDeclaredConstructor(java.io.File.class, String.class,
            OffsetDateTime.class, OffsetDateTime.class, Set.class, ScarletLocalAuditHistory.Limits.class);
        constructor.setAccessible(true);
        Object loader = constructor.newInstance(this.temp.toFile(), GROUP, FROM, TO, TYPES,
            new ScarletLocalAuditHistory.Limits(100, 100, 10000, 100000, 10000));
        java.lang.reflect.Method read = loaderType.getDeclaredMethod("readAuditSafely", Path.class, String.class);
        read.setAccessible(true);
        assertNull(read.invoke(loader, path, "gaud_failed"));
        assertNull(read.invoke(loader, path.resolveSibling(".").resolve("gaud_failed"), "gaud_failed"));
        java.lang.reflect.Constructor<ScarletLocalAuditHistory.Result> resultConstructor =
            ScarletLocalAuditHistory.Result.class.getDeclaredConstructor(loaderType);
        resultConstructor.setAccessible(true);
        ScarletLocalAuditHistory.Result result = resultConstructor.newInstance(loader);
        assertEquals(1, result.unreadableFileCount);
        assertFalse(result.hasFatalProblems);
    }

    @Test void oversizedSingleFileIsSkippedWhileSmallFilesAreCounted() throws Exception
    {
        this.save("gaud", this.record("gaud_small", FROM));
        ScarletData.AuditEntryMetadata big = this.record("gaud_big", FROM);
        big.snapshotTargetUser = new JsonObject();
        big.snapshotTargetUser.addProperty("large", String.join("", java.util.Collections.nCopies(2000, "x")));
        this.save("gaud", big);
        long smallBytes = Files.size(this.temp.resolve("gaud/gaud_small"));
        ScarletLocalAuditHistory.Result result = ScarletLocalAuditHistory.read(this.temp.toFile(), GROUP, FROM, TO, TYPES,
            new ScarletLocalAuditHistory.Limits(100, 100, smallBytes + 10, 100000, 10000));
        assertFalse(result.hasFatalProblems);
        assertEquals(1, result.unreadableFileCount);
        assertEquals(1, result.records.size());
        assertEquals("gaud_small", result.records.get(0).entry.getId());
    }

    @Test void invalidScopeAndUnreadableDirectoryStillBlockTotals() throws Exception
    {
        ScarletLocalAuditHistory.Result scope = ScarletLocalAuditHistory.read(this.temp.toFile(), null, FROM, TO, TYPES);
        assertTrue(scope.hasFatalProblems); assertEquals(0, scope.unreadableFileCount);
        Files.write(this.temp.resolve("gaud"), "not a directory".getBytes(StandardCharsets.UTF_8));
        ScarletLocalAuditHistory.Result directory = this.read();
        assertTrue(directory.hasFatalProblems); assertEquals(0, directory.unreadableFileCount);
    }

    @Test void diagnosticsContainRelativeRecordNameAndFixedReasonNotPrivatePayloads()
    {
        String secret = "private-account-cookie-DO-NOT-LOG";
        Path path = this.temp.resolve(secret).resolve("gaud/gaud_failed");
        assertEquals("gaud/gaud_failed", ScarletLocalAuditHistory.diagnosticPath(path));
        assertEquals("record could not be read or contains malformed/incomplete data",
            ScarletLocalAuditHistory.diagnosticReason(new IOException(secret)));
        assertEquals("record access denied", ScarletLocalAuditHistory.diagnosticReason(new java.nio.file.AccessDeniedException(secret)));
        assertEquals("record disappeared before it could be read", ScarletLocalAuditHistory.diagnosticReason(new java.nio.file.NoSuchFileException(secret)));
        assertFalse(ScarletLocalAuditHistory.diagnosticPath(this.temp.resolve("gaud").resolve(secret + " unexpected name")).contains(secret));
        if (java.io.File.separatorChar != '\\')
        {
            String diagnostic = ScarletLocalAuditHistory.diagnosticPath(this.temp.resolve("gaud").resolve(secret + "\nforged-log"));
            assertFalse(diagnostic.contains(secret));
            assertFalse(diagnostic.contains("\n"));
        }
        assertFalse(ScarletLocalAuditHistory.diagnosticPath(path).contains(this.temp.toString()));
    }
}
