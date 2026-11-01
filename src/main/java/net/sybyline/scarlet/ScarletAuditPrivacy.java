package net.sybyline.scarlet;

import java.util.function.Predicate;
import java.util.Objects;

import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;

/** Shared audience policy for audit history and secondary destinations. */
final class ScarletAuditPrivacy
{
    private ScarletAuditPrivacy() {}

    static boolean confidential(ScarletData.AuditEntryMetadata metadata, Predicate<String> secretStaff)
    {
        return metadata != null && metadata.entry != null
            && (secretStaff.test(metadata.entry.getActorId()) || secretStaff.test(metadata.auxActorId));
    }

    static boolean visible(ScarletData.AuditEntryMetadata metadata, Predicate<String> secretStaff, boolean confidentialAudience)
    {
        return metadata != null && metadata.entry != null && !metadata.entryRedacted
            && (confidentialAudience || !confidential(metadata, secretStaff));
    }

    static boolean historyVisible(GroupAuditLogEntryUnknown entry, ScarletData.AuditEntryMetadata metadata,
                                  Predicate<String> secretStaff, boolean confidentialAudience)
    {
        // API entries alone cannot establish auxiliary actor or local redaction status.
        return entry != null && entry.getId() != null && visible(metadata, secretStaff, confidentialAudience)
            && entry.getId().equals(metadata.entry.getId())
            && (confidentialAudience || !secretStaff.test(entry.getActorId()));
    }

    static String historyLine(GroupAuditLogEntryUnknown entry, ScarletData.AuditEntryMetadata metadata,
                              Predicate<String> secretStaff, boolean confidentialAudience)
    {
        if (!historyVisible(entry, metadata, secretStaff, confidentialAudience))
            return null;
        String description = entry.getDescription() == null ? "Audit event" : entry.getDescription();
        return "\n`" + entry.getId() + "`"
            + (entry.getCreatedAt() == null ? " " : " <t:" + entry.getCreatedAt().toEpochSecond() + ":f>: ")
            + (metadata.hasMessage() ? "[" + description + "](" + metadata.getMessageUrl() + ")" : description);
    }

    static boolean recordedMessage(ScarletData.AuditEntryMetadata metadata, String configuredGuild,
                                   String approvedChannel, String ownAuthor, String author,
                                   boolean webhook, String guild, String channel, String message)
    {
        return metadata != null && metadata.entry != null && !metadata.entryRedacted && !metadata.hasParentEvent()
            && ownAuthor != null && ownAuthor.equals(author) && !webhook
            && configuredGuild != null && configuredGuild.equals(guild)
            && configuredGuild.equals(metadata.guildSnowflake)
            && approvedChannel != null && !approvedChannel.trim().isEmpty() && approvedChannel.equals(channel)
            && approvedChannel.equals(metadata.channelSnowflake)
            && metadata.messageSnowflake != null && metadata.messageSnowflake.equals(message);
    }

    static boolean recordedThread(ScarletData.AuditEntryMetadata metadata, String configuredGuild,
                                  String approvedChannel, String guild, String parentChannel, String thread)
    {
        return metadata != null && metadata.entry != null && !metadata.entryRedacted && !metadata.hasParentEvent()
            && configuredGuild != null && configuredGuild.equals(guild) && configuredGuild.equals(metadata.guildSnowflake)
            && approvedChannel != null && !approvedChannel.trim().isEmpty() && approvedChannel.equals(parentChannel)
            && approvedChannel.equals(metadata.channelSnowflake)
            // A public thread started from a message has that message's snowflake.
            && metadata.messageSnowflake != null && metadata.messageSnowflake.equals(thread)
            && (metadata.threadSnowflake == null || Objects.equals(metadata.threadSnowflake, thread));
    }

    static boolean sameRecordedAudit(ScarletData.AuditEntryMetadata snapshot, ScarletData.AuditEntryMetadata current)
    {
        // Repair jobs retain an object while current metadata is read independently from disk.
        // Reject a changed security context rather than restoring the queued object's older state.
        return snapshot != null && snapshot.entry != null && current != null && current.entry != null
            && !snapshot.entryRedacted && !current.entryRedacted
            && !snapshot.hasParentEvent() && !current.hasParentEvent()
            && Objects.equals(snapshot.entry.getId(), current.entry.getId())
            && Objects.equals(snapshot.entry.getActorId(), current.entry.getActorId())
            && Objects.equals(snapshot.auxActorId, current.auxActorId)
            && Objects.equals(snapshot.entry.getTargetId(), current.entry.getTargetId())
            && Objects.equals(snapshot.entry.getEventType(), current.entry.getEventType())
            && Objects.equals(snapshot.guildSnowflake, current.guildSnowflake)
            && Objects.equals(snapshot.channelSnowflake, current.channelSnowflake)
            && Objects.equals(snapshot.messageSnowflake, current.messageSnowflake)
            && Objects.equals(snapshot.threadSnowflake, current.threadSnowflake);
    }

    static boolean auxiliaryAllowed(ScarletData.AuditEntryMetadata metadata, Predicate<String> secretStaff)
    {
        // Auxiliary routes have no separately verified confidential audience.
        return metadata != null && metadata.entry != null && !confidential(metadata, secretStaff)
            && (metadata.entry.getId() == null || !metadata.entry.getId().startsWith(ScarletSimulation.TRAINING_AUDIT_ID_PREFIX));
    }

    static String redactedWebhook(String ignored)
    {
        return "Configured (credential hidden)";
    }
}
