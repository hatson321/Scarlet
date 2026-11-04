package net.sybyline.scarlet;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import io.github.vrchatapi.model.User;
import net.sybyline.scarlet.util.LinuxNotificationInstallDialogs;
import net.sybyline.scarlet.util.Platform;
import net.sybyline.scarlet.util.ScarletToast;

/**
 * Native desktop notification delivery for supported Windows and Linux hosts.
 *
 * <p>This component intentionally has no phone pairing, network listener, push
 * service, remote delivery, QR-code, or device-registration behavior.</p>
 */
public class ScarletDesktopNotifications
{
    static final Logger LOG = LoggerFactory.getLogger("Scarlet/DesktopNotifications");

    public enum NotificationType
    {
        WATCHED_USER_JOIN,
        WATCHED_GROUP_JOIN,
        WATCHED_AVATAR,
        VOTE_TO_KICK,
        MODERATION,
        STAFF,
        NEW_PLAYER,
        MIXED_CHARACTER_NAME,
        SUSPICIOUS_PRONOUNS
    }

    public ScarletDesktopNotifications(Scarlet scarlet)
    {
        this.scarlet = scarlet;
        this.enabled = scarlet.settings.new FileValuedBoolean("notify_desktop_enabled", "Desktop notifications", false);
        this.notifyWatchedUsers = scarlet.settings.new FileValuedBoolean("toast_notify_watched_users", "Toast: watched users", true);
        this.notifyWatchedGroups = scarlet.settings.new FileValuedBoolean("toast_notify_watched_groups", "Toast: watched groups", true);
        this.notifyWatchedAvatars = scarlet.settings.new FileValuedBoolean("toast_notify_watched_avatars", "Toast: watched avatars", true);
        this.notifyVotesToKick = scarlet.settings.new FileValuedBoolean("toast_notify_votes_to_kick", "Toast: votes-to-kick", true);
        this.notifyModeration = scarlet.settings.new FileValuedBoolean("toast_notify_moderation", "Toast: moderation", true);
        this.notifyStaff = scarlet.settings.new FileValuedBoolean("toast_notify_staff", "Toast: staff joins/leaves", false);
        this.notifyNewPlayers = scarlet.settings.new FileValuedBoolean("toast_notify_new_players", "Toast: new players", false);
        this.notifyMixedNames = scarlet.settings.new FileValuedBoolean("toast_notify_mixed_character_names", "Toast: mixed-character names", false);
        this.notifySuspiciousPronouns = scarlet.settings.new FileValuedBoolean("toast_notify_suspicious_pronouns", "Toast: suspicious pronouns", true);
        this.sendTest = scarlet.settings.new FileValuedVoid("Send desktop test notification", "Send", this::sendTest);
    }

    final Scarlet scarlet;
    final ScarletSettings.FileValued<Boolean> enabled,
                                             notifyWatchedUsers,
                                             notifyWatchedGroups,
                                             notifyWatchedAvatars,
                                             notifyVotesToKick,
                                             notifyModeration,
                                             notifyStaff,
                                             notifyNewPlayers,
                                             notifyMixedNames,
                                             notifySuspiciousPronouns;
    final ScarletSettings.FileValued<Void> sendTest;
    final AtomicBoolean installPrompted = new AtomicBoolean(false);

    public boolean wants(NotificationType type)
    {
        return this.enabled.get() && this.enabledFor(type);
    }

    public void notifyWatchedUserJoined(String displayName, String userId, ScarletWatchedEntities.WatchedEntity watchedUser, String location)
    {
        boolean critical = watchedUser != null
            && (watchedUser.critical || watchedUser.type == ScarletWatchedEntities.WatchedEntity.Type.MALICIOUS);
        this.show(NotificationType.WATCHED_USER_JOIN,
            critical ? "Critical watched user joined" : "Watched user joined",
            nonBlank(watchedUser == null ? null : watchedUser.message, displayName + " joined the instance"));
    }

    public void notifyWatchedGroupJoined(String displayName, String userId, ScarletWatchedGroups.WatchedGroup watchedGroup, String location)
    {
        boolean critical = watchedGroup != null
            && (watchedGroup.critical || watchedGroup.type == ScarletWatchedGroups.WatchedGroup.Type.MALICIOUS);
        this.show(NotificationType.WATCHED_GROUP_JOIN,
            critical ? "Critical watched group joined" : "Watched group joined",
            displayName + " matched watched group: "
                + nonBlank(watchedGroup == null ? null : watchedGroup.message,
                    watchedGroup == null ? "unknown group" : watchedGroup.id));
    }

    public void notifyWatchedAvatar(String displayName, String userId, String avatarDisplayName, String[] potentialIds, ScarletWatchedEntities.WatchedEntity watchedAvatar, String location)
    {
        boolean critical = watchedAvatar != null
            && (watchedAvatar.critical || watchedAvatar.type == ScarletWatchedEntities.WatchedEntity.Type.MALICIOUS);
        this.show(NotificationType.WATCHED_AVATAR,
            critical ? "Critical watched avatar detected" : "Watched avatar detected",
            displayName + " may be wearing " + nonBlank(avatarDisplayName, "a watched avatar"));
    }

    public void notifyVoteToKick(String targetDisplayName, String targetUserId, String actorDisplayName, String actorUserId, String location)
    {
        String body = actorDisplayName == null
            ? "A vote-to-kick was started against " + targetDisplayName
            : actorDisplayName + " started a vote-to-kick against " + targetDisplayName;
        this.show(NotificationType.VOTE_TO_KICK, "Vote-to-kick started", body);
    }

    public void notifyStaffJoined(String displayName, String userId, String location)
    {
        this.show(NotificationType.STAFF, "Staff joined", displayName + " joined the instance");
    }

    public void notifyStaffLeft(String displayName, String userId, String location)
    {
        this.show(NotificationType.STAFF, "Staff left", displayName + " left the instance");
    }

    public void notifyNewPlayerJoined(String displayName, String userId, long accountAgeDays, String location)
    {
        this.show(NotificationType.NEW_PLAYER, "New VRChat account joined",
            displayName + " joined VRChat " + accountAgeDays + " day(s) ago");
    }

    public void notifyMixedCharacterName(String displayName, String userId, String location)
    {
        this.show(NotificationType.MIXED_CHARACTER_NAME, "Mixed-character name",
            displayName + " has a suspicious mixed-character name");
    }

    public void notifySuspiciousPronouns(String displayName, String userId, String pronouns, String reason, String location)
    {
        this.show(NotificationType.SUSPICIOUS_PRONOUNS, "Suspicious pronouns",
            displayName + ": " + pronouns + " (" + reason + ")");
    }

    public void notifyModeration(ScarletData.AuditEntryMetadata entryMeta, User actor, User target)
    {
        if (entryMeta == null || entryMeta.entry == null)
            return;
        GroupAuditLogEntryUnknown entry = entryMeta.entry;
        GroupAuditType type = GroupAuditType.of(entry.getEventType());
        String typeTitle = type == null ? GroupAuditType.title(entry.getEventType()) : type.title;
        String targetName = target != null ? target.getDisplayName() : entry.getTargetId();
        String actorName = actor != null
            ? actor.getDisplayName()
            : entryMeta.hasAuxActor() ? entryMeta.auxActorDisplayName : entry.getActorDisplayName();
        this.show(NotificationType.MODERATION, typeTitle,
            typeTitle + " for " + targetName + " by " + nonBlank(actorName, "unknown actor"));
    }

    boolean enabledFor(NotificationType type)
    {
        switch (type)
        {
        case WATCHED_USER_JOIN: return this.notifyWatchedUsers.get();
        case WATCHED_GROUP_JOIN: return this.notifyWatchedGroups.get();
        case WATCHED_AVATAR: return this.notifyWatchedAvatars.get();
        case VOTE_TO_KICK: return this.notifyVotesToKick.get();
        case MODERATION: return this.notifyModeration.get();
        case STAFF: return this.notifyStaff.get();
        case NEW_PLAYER: return this.notifyNewPlayers.get();
        case MIXED_CHARACTER_NAME: return this.notifyMixedNames.get();
        case SUSPICIOUS_PRONOUNS: return this.notifySuspiciousPronouns.get();
        default: return false;
        }
    }

    void show(NotificationType type, String title, String body)
    {
        if (!this.wants(type))
            return;
        try
        {
            if (!ScarletToast.show(title, body))
                this.promptLinuxNotifierInstall(false);
        }
        catch (Throwable ex)
        {
            LOG.debug("Desktop toast failed for {}", type, ex);
        }
    }

    void sendTest()
    {
        if (!this.enabled.get())
        {
            this.info("Desktop notifications are disabled", "Enable Desktop notifications first.");
            return;
        }
        if (ScarletToast.show("Scarlet", "Desktop notifications are working."))
            return;
        if (this.promptLinuxNotifierInstall(true))
            return;
        this.info("No desktop notifier available",
            "Scarlet could not find a way to show a desktop notification on this system. "
          + "On Linux, install 'notify-send' (the libnotify / libnotify-bin package).");
    }

    boolean promptLinuxNotifierInstall(boolean userInitiated)
    {
        if (Platform.CURRENT != Platform.$NIX)
            return false;
        if (LinuxNotificationInstallDialogs.isNotifierInstalled())
        {
            if (!userInitiated && !this.installPrompted.compareAndSet(false, true))
                return true;
            if (userInitiated)
                this.info("Desktop notifier failed",
                    "Scarlet found notify-send, but the notification command failed. "
                  + "Make sure your desktop notification service and DBus session are running, "
                  + "and that notifications are not blocked or muted.");
            else
                LOG.warn("notify-send is installed, but the desktop notification command failed");
            return true;
        }
        if (!userInitiated && !this.installPrompted.compareAndSet(false, true))
            return true;

        this.scarlet.execModal.execute(() ->
        {
            Component parent = this.scarlet.ui == null ? null : this.scarlet.ui.getParentComponent();
            LinuxNotificationInstallDialogs.InstallDialogResult result =
                LinuxNotificationInstallDialogs.showInstallFlowIfNeeded(parent);
            if (result == LinuxNotificationInstallDialogs.InstallDialogResult.INSTALL_APPROVED_SUCCESS
             || result == LinuxNotificationInstallDialogs.InstallDialogResult.ALREADY_INSTALLED)
            {
                ScarletToast.resetAvailabilityCache();
                ScarletToast.show("Scarlet", "Desktop notifications are working.");
            }
        });
        return true;
    }

    void info(String title, String message)
    {
        if (GraphicsEnvironment.isHeadless())
        {
            LOG.info("{}: {}", title, message);
            return;
        }
        this.scarlet.execModal.execute(() ->
        {
            Component parent = this.scarlet.ui == null ? null : this.scarlet.ui.getParentComponent();
            JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE);
        });
    }

    static String nonBlank(String first, String fallback)
    {
        if (first == null)
            return fallback;
        first = first.trim();
        return first.isEmpty() ? fallback : first;
    }
}
