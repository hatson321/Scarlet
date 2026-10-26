package net.sybyline.scarlet;

import java.io.Closeable;
import java.io.File;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import io.github.vrchatapi.model.InventoryItem;
import io.github.vrchatapi.model.ModelFile;
import io.github.vrchatapi.model.Print;
import io.github.vrchatapi.model.Prop;
import io.github.vrchatapi.model.User;

import net.sybyline.scarlet.util.VersionedFile;

public interface ScarletDiscord extends Closeable
{

    /**
     * Posts an operational health alert (auth lost/recovered, log tailer stalled,
     * hard rate limiting, updates available) to the configured staff ops channel,
     * if one is configured. No-op otherwise: ops alerts are best-effort and must
     * never affect the pipeline that raised them.
     */
    default void emitOpsAlert(String title, String detail, int color) {}

    /** Current-session operator request; token is opaque, expiring and not a join link. */
    default void emitClientExitConflict(String token, String counts) {}


    // U+2727 White Four Pointed Star (the character separating the footer and timestamp differs by client platform...)
    String FOOTER_PREFIX = String.format("%s %s \u2727 ", Scarlet.APP_NAME, Scarlet.VERSION);

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/Discord");

    public void updateCommandList();

    public long pollQueuedAction();

    public boolean submitAudio(File file);

    public boolean isEmitting(GroupAuditType auditType);

    public boolean isEmitting(GroupAuditTypeEx auditTypeEx);

    /** Current locally observed roster; null location means caught up with no matching group instance. */
    default void updateLivePlayerList(String location, List<ScarletLivePlayerlist.PlayerSnapshot> roster) {}

    /** The local VRChat client stopped observing this instance; this is not a group-close audit. */
    default void endLivePlayerList(String location) {}

    public default void observeClientExitAudit(Scarlet scarlet, GroupAuditLogEntryUnknown entry)
    {
        // Local client lifecycle must not depend on a Discord channel or delivery status.
        if (scarlet.eventListener != null && scarlet.eventListener.clientExitMonitor != null) try
        {
            scarlet.eventListener.clientExitMonitor.audit(entry);
        }
        catch (RuntimeException ex)
        {
            LOG.warn("Client exit audit observation failed; normal audit delivery continues", ex);
        }
    }

    public default void process(Scarlet scarlet, GroupAuditLogEntryUnknown entry)
    {
        this.observeClientExitAudit(scarlet, entry);
        // Timer cancellation is independent of Discord deduplication, channel configuration,
        // redaction and delivery retries. A failed timer save must not drop the audit event.
        if (scarlet.pendingModActions != null) try
        {
            scarlet.pendingModActions.observeTimedBanAudit(entry);
        }
        catch (RuntimeException ex)
        {
            LOG.warn("Timed-ban reconciliation could not be saved; expiry still requires fresh server proof", ex);
        }
        synchronized (this)
        {
            ScarletAuditOutbox outbox = new ScarletAuditOutbox(scarlet.data.dir);
            boolean pending = outbox.exists(entry.getId());
            if (!pending && scarlet.data.auditEntryMetadataExists(entry.getId()))
                return;
            ScarletData.AuditEntryMetadata metadata = pending ? outbox.read(entry.getId()) : new ScarletData.AuditEntryMetadata();
            if (!pending) metadata.entry = entry;
            if (ScarletAuditOutbox.complete(metadata)) { outbox.finish(scarlet, metadata); return; }
            if (!ScarletAuditOutbox.due(metadata, System.currentTimeMillis()) && !outbox.dependencyMayProceed(scarlet, metadata)) return;
            if (ScarletAuditOutbox.isFollowup(metadata))
            {
                this.scheduleAuditFollowup(scarlet, entry.getId());
                return;
            }
            metadata.setAuxData("deliveryState", "pending");
            outbox.save(metadata);
            try
            {
                this.processAuditEntry(scarlet, metadata);
                if (this.requiresAuditFollowup(metadata))
                {
                    // The acknowledged parent is visible now. Persist the follow-up phase before
                    // handing off: restart/retry must never resend it or repeat foreground work.
                    metadata.setAuxData("deliveryState", "followup_pending");
                    metadata.setAuxData("deliveryRetryAt", 0L);
                    outbox.save(metadata);
                    scarlet.data.auditEntryMetadata(entry.getId(), metadata);
                    this.scheduleAuditFollowup(scarlet, entry.getId());
                    return;
                }
                metadata.setAuxData("deliveryState", metadata.getAuxData("deliveryAcknowledged", false, e -> e.getAsBoolean())
                    ? "delivered" : "suppressed");
                outbox.finish(scarlet, metadata);
            }
            catch (ScarletAuditDependencyPending wait)
            {
                ScarletAuditOutbox.waitForDependency(metadata, wait.parentEventId, System.currentTimeMillis());
                outbox.save(metadata);
                LOG.debug("Audit event {} is waiting for its associated moderation thread {}; receipt retained",
                    entry.getId(), wait.parentEventId);
            }
            catch (Exception ex)
            {
                // A terminal receipt must never become pending again after a local save failure.
                if (!ScarletAuditOutbox.complete(metadata))
                {
                    int attempts = metadata.getAuxData("deliveryAttempts", 0, e -> e.getAsInt()) + 1;
                    metadata.setAuxData("deliveryAttempts", attempts);
                    metadata.setAuxData("deliveryRetryAt", System.currentTimeMillis() + ScarletAuditOutbox.retryDelay(attempts));
                    outbox.save(metadata);
                    if (attempts == 1 || (attempts & (attempts - 1)) == 0)
                        this.emitOpsAlert("Audit delivery pending", "An audit event could not be delivered. Scarlet retained it and will retry. Check local logs and channel permissions.", 0xFEE75C);
                }
                LOG.error("Audit delivery remains pending for event {} ({}). Retry record retained.", entry.getId(), ex.getClass().getSimpleName());
            }
        }
    }

    public default boolean requiresAuditFollowup(ScarletData.AuditEntryMetadata metadata) { return false; }

    /** Implementations enqueue only the durable identifier, not a stale mutable metadata object. */
    public default void scheduleAuditFollowup(Scarlet scarlet, String id) {}

    /** Mark dependent receipts eligible, then enqueue identifiers only; never deliver inline. */
    public default void wakeAuditDependents(Scarlet scarlet, String parentId)
    {
        synchronized (this)
        {
            ScarletAuditOutbox outbox = new ScarletAuditOutbox(scarlet.data.dir);
            int scheduled = 0;
            for (String id : outbox.ids()) try
            {
                ScarletData.AuditEntryMetadata waiting = outbox.read(id);
                if (!ScarletAuditOutbox.isDependency(waiting) || !Objects.equals(parentId, ScarletAuditOutbox.dependency(waiting))) continue;
                waiting.setAuxData("deliveryRetryAt", 0L);
                outbox.save(waiting);
                this.scheduleAuditFollowup(scarlet, id);
                if (++scheduled >= 100) break; // Remaining durable receipts are recovered by polling.
            }
            catch (RuntimeException ex)
            {
                LOG.warn("Unable to wake dependent audit {} ({}); polling will retry", id, ex.getClass().getSimpleName());
            }
        }
    }

    public default boolean isAuditProcessed(Scarlet scarlet, String id)
    {
        ScarletAuditOutbox outbox = new ScarletAuditOutbox(scarlet.data.dir);
        return !outbox.exists(id) && scarlet.data.auditEntryMetadataExists(id);
    }

    public default void retryPending(Scarlet scarlet)
    {
        ScarletAuditOutbox outbox = new ScarletAuditOutbox(scarlet.data.dir);
        int attempted = 0;
        for (String id : outbox.ids()) try
        {
            ScarletData.AuditEntryMetadata metadata = outbox.read(id);
            if (!ScarletAuditOutbox.due(metadata, System.currentTimeMillis()) && !outbox.dependencyMayProceed(scarlet, metadata)) continue;
            if (++attempted > 100) break;
            this.process(scarlet, metadata.entry);
        }
        catch (Exception ex)
        {
            LOG.error("Unable to replay audit outbox record {} ({}); record retained.", id, ex.getClass().getSimpleName());
        }
    }

    public default void saveAuditDeliveryProgress(Scarlet scarlet, ScarletData.AuditEntryMetadata metadata)
    {
        ScarletAuditOutbox outbox = new ScarletAuditOutbox(scarlet.data.dir);
        if (outbox.exists(metadata.entry.getId())) outbox.save(metadata);
    }

    public default void processAuditEntry(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        GroupAuditLogEntryUnknown entry = entryMeta.entry;
        GroupAuditType latype = GroupAuditType.of(entry.getEventType());
        if (latype == null)
            return;
        if (entry.getActorDisplayName() == null)
        {
            User actor = scarlet.vrc.getUser(entry.getActorId());
            if (actor != null)
            {
                entry.setActorDisplayName(actor.getDisplayName());
            }
        }
        
        if (!entryMeta.hasAuxActor() && (scarlet.vrc.currentUserId == null || Objects.equals(scarlet.vrc.currentUserId, entry.getActorId())))
        {
            String pendingActorId = scarlet.pendingModActions.pollPending(latype, entry.getTargetId());
            if (pendingActorId != null)
            {
                entryMeta.auxActorId = pendingActorId;
                User auxActor = scarlet.vrc.getUser(pendingActorId);
                if (auxActor != null)
                {
                    entryMeta.auxActorDisplayName = auxActor.getDisplayName();
                }
                else
                {
                    entryMeta.auxActorDisplayName = pendingActorId;
                }
            }
        }
        
        try
        {
            switch (latype)
            {
            case INSTANCE_WARN:
//            case INSTANCE_MUTE:
            case INSTANCE_KICK:
            case MEMBER_REMOVE:
            case USER_BAN: 
            case USER_UNBAN: {
                LOG.info(String.format("Moderation event %s: %s (%s)", entry.getEventType(), entry.getDescription(), entry.getCreatedAt()));
                this.processUserModeration(scarlet, entryMeta);
            } break;
            case INSTANCE_CREATE: {
                this.processInstanceCreate(scarlet, entryMeta);
            } break;
            case INSTANCE_CLOSE: {
                this.processInstanceClose(scarlet, entryMeta);
            } break;
            case MEMBER_JOIN: {
                this.processMemberJoin(scarlet, entryMeta);
            } break;
            case MEMBER_LEAVE: {
                this.processMemberLeave(scarlet, entryMeta);
            } break;
            case MEMBER_USER_UPDATE: {
                this.processMemberUserUpdate(scarlet, entryMeta);
            } break;
            case POST_CREATE: {
                this.processPostCreate(scarlet, entryMeta);
            } break;
            case ANNOUNCEMENT: {
                this.processAnnouncement(scarlet, entryMeta);
            } break;
            case MEMBER_ROLE_ASSIGN: {
                this.processMemberRoleAssign(scarlet, entryMeta);
            } break;
            case MEMBER_ROLE_UNASSIGN: {
                this.processMemberRoleUnassign(scarlet, entryMeta);
            } break;
            case ROLE_CREATE: {
                this.processRoleCreate(scarlet, entryMeta);
            } break;
            case ROLE_DELETE: {
                this.processRoleDelete(scarlet, entryMeta);
            } break;
            case ROLE_UPDATE: {
                this.processRoleUpdate(scarlet, entryMeta);
            } break;
            case INVITE_CREATE: {
                this.processInviteCreate(scarlet, entryMeta);
            } break;
            case REQUEST_BLOCK: {
                this.processRequestBlock(scarlet, entryMeta);
            } break;
            case REQUEST_CREATE: {
                this.processRequestCreate(scarlet, entryMeta);
            } break;
            case REQUEST_REJECT: {
                this.processRequestReject(scarlet, entryMeta);
            } break;
            case UPDATE: {
                this.processUpdate(scarlet, entryMeta);
            } break;
            default: {
                this.processDefault(scarlet, entryMeta);
            } break;
            }
        }
        finally
        {
            if (entryMeta.hasMessage())
            {
                LOG.info(entry.getEventType().replace('.', ' ')+" ("+entryMeta.getMessageDelineated()+")");
            }
        }
    }

    public void emitUserModeration(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, User actor, User target, ScarletData.UserMetadata actorMeta, ScarletData.UserMetadata targetMeta, String history, String recent, ScarletData.AuditEntryMetadata parentEntryMeta, boolean reactiveKickFromBan);
    public default void processUserModeration(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        long moderationStarted = System.nanoTime();
        LOG.info("Preparing moderation audit {} for Discord", entryMeta.entry.getId());
        boolean isSecretStaff = scarlet.secretStaffList.isSecretStaffId(entryMeta.entry.getActorId())
                || scarlet.secretStaffList.isSecretStaffId(entryMeta.auxActorId);
        String //type = entryMeta.entry.getEventType(),
               auditId = entryMeta.entry.getId(),
               //description = entryMeta.entry.getDescription(),
               actorId = entryMeta.entry.getActorId(),
               //actorDisplayName = entryMeta.entry.getActorDisplayName(),
               targetId = entryMeta.entry.getTargetId();
        OffsetDateTime createdAt = entryMeta.entry.getCreatedAt();
        
        if (entryMeta.hasAuxActor())
        {
            actorId = entryMeta.auxActorId;
        }
        
        ScarletData.UserMetadata actorMeta = scarlet.data.userMetadata(actorId),
                                 targetMeta = scarlet.data.userMetadata(targetId);
        // Training (simulated) users don't exist on VRChat: skip the lookup rather than spend API calls on a 404.
        User actor = ScarletSimulation.isTrainingId(actorId) ? null : scarlet.vrc.getUser(actorId),
             target = ScarletSimulation.isTrainingId(targetId) ? null : scarlet.vrc.getUser(targetId);
        
        if (targetMeta == null)
            targetMeta = new ScarletData.UserMetadata();
        List<ScarletData.AuditEntryMetadata> auditEntryMetas = null;
        targetMeta = scarlet.data.updateUserMetadata(targetId, metadata -> { metadata.addAuditEntryId(auditId); return metadata; });
        String[] auditEntryIds = targetMeta.auditEntryIds;
        
        if (auditEntryIds != null)
        {
            for (String auditEntryId : auditEntryIds)
            {
                if (auditEntryId != null)
                {
                    ScarletData.AuditEntryMetadata found = scarlet.data.auditEntryMetadata(auditEntryId);
                    if (found != null)
                    {
                        if (!Objects.equals(auditId, auditEntryId)
                            && ScarletAuditPrivacy.visible(found, scarlet.secretStaffList::isSecretStaffId, isSecretStaff))
                        {
                            if (auditEntryMetas == null)
                                auditEntryMetas = new ArrayList<>();
                            auditEntryMetas.add(found);
                        }
                    }
                }
            }
        }
        
        String history = null, recent = null;
        boolean reactiveKickFromBan = false;
        ScarletData.AuditEntryMetadata parentEntryMeta = null;
        if (auditEntryMetas != null)
        {
            int iwarns = 0, imutes = 0, ikicks = 0, gkicks = 0, gbans = 0;
            int iwarnsR = 0, imutesR = 0, ikicksR = 0, gkicksR = 0, gbansR = 0;
            OffsetDateTime prevR = createdAt.minusHours(24);
            ScarletData.AuditEntryMetadata mostRecent = auditEntryMetas.get(0);
            for (ScarletData.AuditEntryMetadata auditEntryMeta : auditEntryMetas)
            {
                boolean isRecent = prevR.isBefore(auditEntryMeta.entry.getCreatedAt());
                // mostRecent must be the NEWEST prior entry: the "Most recent" embed field and the
                // reactive-kick-from-ban detection both need the latest action (the ban that VRChat
                // auto-kicked alongside). Using isBefore here selected the OLDEST, so for anyone with
                // prior history the co-occurring ban was missed and its auto-kick was logged as a kick.
                if (auditEntryMeta.entry.getCreatedAt().isAfter(mostRecent.entry.getCreatedAt()))
                {
                    mostRecent = auditEntryMeta;
                }
                if (auditEntryMeta.entryRedacted || auditEntryMeta.hasParentEvent())
                    continue;
                switch (auditEntryMeta.entry.getEventType())
                {
                case "group.instance.warn": {
                    iwarns++;
                    if (isRecent)
                        iwarnsR++;
                } break;
                case "group.instance.mute": {
                    imutes++;
                    if (isRecent)
                        imutesR++;
                } break;
                case "group.instance.kick": {
                    ikicks++;
                    if (isRecent)
                        ikicksR++;
                } break;
                case "group.member.remove": {
                    gkicks++;
                    if (isRecent)
                        gkicksR++;
                } break;
                case "group.user.ban": {
                    gbans++;
                    if (isRecent)
                        gbansR++;
                } break;
                }
            }
            {
                StringBuilder allModStr = new StringBuilder();
                if (iwarns > 0)
                {
                    allModStr.append(iwarns).append(" instance warn(s)");
                    if (iwarnsR > 0)
                        allModStr.append(" (").append(iwarnsR).append(" recent)");
                    allModStr.append('\n');
                }
                if (imutes > 0)
                {
                    allModStr.append(imutes).append(" instance mute(s)");
                    if (imutesR > 0)
                        allModStr.append(" (").append(imutesR).append(" recent)");
                    allModStr.append('\n');
                }
                if (ikicks > 0)
                {
                    allModStr.append(ikicks).append(" instance kick(s)");
                    if (ikicksR > 0)
                        allModStr.append(" (").append(ikicksR).append(" recent)");
                    allModStr.append('\n');
                }
                if (gkicks > 0)
                {
                    allModStr.append(gkicks).append(" group kick(s)");
                    if (gkicksR > 0)
                        allModStr.append(" (").append(gkicksR).append(" recent)");
                    allModStr.append('\n');
                }
                if (gbans > 0)
                {
                    allModStr.append(gbans).append(" group ban(s)");
                    if (gbansR > 0)
                        allModStr.append(" (").append(gbansR).append(" recent)");
                    allModStr.append('\n');
                }
                history = allModStr.toString();
            }
            {
                String prevModStr = String.format("%s by %s", GroupAuditType.of(mostRecent.entry.getEventType()).title, mostRecent.entry.getActorDisplayName()),
                       prevModUrl = mostRecent.getMessageUrl();
                if (prevModUrl != null)
                    prevModStr = String.format("[%s](%s)", prevModStr, prevModUrl);
                recent = prevModStr;
            }
            if (Objects.equals(GroupAuditType.INSTANCE_KICK.id, entryMeta.entry.getEventType())
             && Objects.equals(GroupAuditType.USER_BAN.id, mostRecent.entry.getEventType())
             && Objects.equals(entryMeta.entry.getActorId(), mostRecent.entry.getActorId())
             && entryMeta.entry.getCreatedAt().minusMinutes(5L).isBefore(mostRecent.entry.getCreatedAt()))
            {
                parentEntryMeta = mostRecent;
                reactiveKickFromBan = true;
            }
        }
        this.emitUserModeration(scarlet, entryMeta, actor, target, actorMeta, targetMeta, history, recent, parentEntryMeta, reactiveKickFromBan);
        if (entryMeta.hasMessage()) LOG.info("Moderation audit {} parent delivery stage finished after {} ms",
            entryMeta.entry.getId(), java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - moderationStarted));
    }

    public void emitInstanceCreate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, String location);
    /** @return whether the follow-into-new-instance client auto-launch is enabled. */
    public default boolean isLaunchOnInstanceCreateEnabled()
    {
        return false;
    }
    /** Cold-launch the VRChat client into {@code location} when the auto-launch feature is on; deduplicated so the same instance is only launched into once. */
    public default void launchClientIntoInstanceIfEnabled(String location)
    {
    }
    /** Background, bounded startup recovery; never restarts a pre-existing VRChat client. */
    public default void maybeJoinActiveInstanceOnStartup()
    {
    }
    public default boolean isStartupInstanceJoinPending()
    {
        return false;
    }
    public default void processInstanceCreate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        this.emitInstanceCreate(scarlet, entryMeta, entryMeta.entry.getTargetId());
    }

    public void emitInstanceClose(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, String location);
    public default void processInstanceClose(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        this.emitInstanceClose(scarlet, entryMeta, entryMeta.entry.getTargetId());
    }

    public void emitMemberJoin(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta);
    public default void processMemberJoin(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        this.emitMemberJoin(scarlet, entryMeta);
    }

    public void emitMemberLeave(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta);
    public default void processMemberLeave(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        this.emitMemberLeave(scarlet, entryMeta);
    }

    public void emitPostCreate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, GroupAuditType.PostCreateComponent post);
    public default void processPostCreate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        GroupAuditType.PostCreateComponent post = entryMeta.getData(GroupAuditType.PostCreateComponent.class);
        this.emitPostCreate(scarlet, entryMeta, post);
    }

    public void emitAnnouncement(
        Scarlet scarlet,
        ScarletData.AuditEntryMetadata entryMeta,
        GroupAuditType.AnnouncementComponent announcement
    );

    public default void processAnnouncement(
        Scarlet scarlet,
        ScarletData.AuditEntryMetadata entryMeta
    ) {
        GroupAuditType.AnnouncementComponent announcement = entryMeta.getData(
            GroupAuditType.AnnouncementComponent.class
        );
        this.emitAnnouncement(scarlet, entryMeta, announcement);
    }

    public void emitMemberRoleAssign(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, User target, GroupAuditType.RoleRefComponent role);
    public default void processMemberRoleAssign(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        GroupAuditType.RoleRefComponent role = entryMeta.getData(GroupAuditType.RoleRefComponent.class);
        User target = scarlet.vrc.getUser(entryMeta.entry.getTargetId());
        this.emitMemberRoleAssign(scarlet, entryMeta, target, role);
    }

    public void emitMemberRoleUnassign(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, User target, GroupAuditType.RoleRefComponent role);
    public default void processMemberRoleUnassign(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        GroupAuditType.RoleRefComponent role = entryMeta.getData(GroupAuditType.RoleRefComponent.class);
        User target = scarlet.vrc.getUser(entryMeta.entry.getTargetId());
        this.emitMemberRoleUnassign(scarlet, entryMeta, target, role);
    }

    public void emitMemberUserUpdate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, Map<String, GroupAuditType.UpdateSubComponent> updates);
    public default void processMemberUserUpdate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        Map<String, GroupAuditType.UpdateSubComponent> updates = entryMeta.getData(GroupAuditType.UpdateSubComponent.TYPE_TOKEN);
        this.emitMemberUserUpdate(scarlet, entryMeta, updates);
    }

    public void emitRoleCreate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, GroupAuditType.RoleCreateComponent role);
    public default void processRoleCreate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        GroupAuditType.RoleCreateComponent role = entryMeta.getData(GroupAuditType.RoleCreateComponent.class);
        this.emitRoleCreate(scarlet, entryMeta, role);
    }

    public void emitRoleDelete(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, GroupAuditType.RoleDeleteComponent role);
    public default void processRoleDelete(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        GroupAuditType.RoleDeleteComponent role = entryMeta.getData(GroupAuditType.RoleDeleteComponent.class);
        this.emitRoleDelete(scarlet, entryMeta, role);
    }

    public void emitRoleUpdate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, Map<String, GroupAuditType.UpdateSubComponent> updates);
    public default void processRoleUpdate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        Map<String, GroupAuditType.UpdateSubComponent> updates = entryMeta.getData(GroupAuditType.UpdateSubComponent.TYPE_TOKEN);
        this.emitRoleUpdate(scarlet, entryMeta, updates);
    }

    public void emitInviteCreate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta);
    public default void processInviteCreate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        this.emitInviteCreate(scarlet, entryMeta);
    }

    public void emitRequestBlock(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta);
    public default void processRequestBlock(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        this.emitRequestBlock(scarlet, entryMeta);
    }

    public void emitRequestCreate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta);
    public default void processRequestCreate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        this.emitRequestCreate(scarlet, entryMeta);
    }

    public void emitRequestReject(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta);
    public default void processRequestReject(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        this.emitRequestReject(scarlet, entryMeta);
    }

    public void emitUpdate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, Map<String, GroupAuditType.UpdateSubComponent> updates);
    public default void processUpdate(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        Map<String, GroupAuditType.UpdateSubComponent> updates = entryMeta.getData(GroupAuditType.UpdateSubComponent.TYPE_TOKEN);
        this.emitUpdate(scarlet, entryMeta, updates);
    }

    public void emitDefault(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta);
    public default void processDefault(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta)
    {
        this.emitDefault(scarlet, entryMeta);
    }

    public void emitExtendedInstanceInactive(Scarlet scarlet, String location, String auditEntryId, ScarletData.InstanceEmbedMessage instanceEmbedMessage);
    public void emitExtendedStaffJoin(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName);
    public void emitExtendedStaffLeave(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName);
    public void emitExtendedUserJoin(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName);
    public void emitExtendedUserLeave(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName);
    public void emitExtendedUserAvatar(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String avatarDisplayName, String[] potentialIds);
    public void emitExtendedUserVideo(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String videoUrl, String videoTitle);
    public void emitExtendedVtkInitiated(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String optActorId, String optActorDisplayName);
    public void emitExtendedUserSpawnPedestal(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String contentType, String contentId);
    public void emitExtendedUserSpawnSticker(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String stickerId);
    public void emitExtendedUserSpawnPrint(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String printId, Print print);
    public void emitExtendedUserSpawnEmoji(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String emojiId, InventoryItem emoji);
    public void emitExtendedUserSpawnProp(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String propId, Prop prop);
    public void tryEmitExtendedAvatarBundles(Scarlet scarlet, LocalDateTime timestamp, String location, String name, ModelFile modelFile, VersionedFile file);
    public void emitExtendedInstanceMonitor(Scarlet scarlet, String location, ScarletData.InstanceEmbedMessage instanceEmbedMessage);
    public void emitExtendedInstanceEnforcement(Scarlet scarlet, String location, String worldName, String reason);
    public void tryEmitExtendedSuggestedModeration(Scarlet scarlet, User target);
    public void tryEmitExtendedWatchedModeration(Scarlet scarlet, User target);
    public void emitModSummary(Scarlet scarlet, OffsetDateTime endOfDay);
    public void emitOutstandingMod(Scarlet scarlet, OffsetDateTime endOfDay);
    public void emitActionFailure(Scarlet scarlet, Action action, String format, Object... args);
    public static class Action
    {
        public Action()
        {
        }
        public Action(String action, String actorId, String targetId, String location)
        {
            this.action = action;
            this.actorId = actorId;
            this.targetId = targetId;
            this.location = location;
        }
        public Action(String action, String actorId, String targetId, String location, String requesterDiscordId,
            String discordGuildId, String vrchatGroupId)
        {
            this(action, actorId, targetId, location);
            this.requesterDiscordId = requesterDiscordId;
            this.discordGuildId = discordGuildId;
            this.vrchatGroupId = vrchatGroupId;
        }
        public String action;
        public String actorId;
        public String targetId;
        public String location;
        /** Original human requester; legacy queued bans without this identity are refused. */
        public String requesterDiscordId;
        public String discordGuildId;
        public String vrchatGroupId;
    }

}
