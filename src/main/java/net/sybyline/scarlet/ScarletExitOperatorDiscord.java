package net.sybyline.scarlet;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.actionrow.ActionRow;

/** Staff-only, two-step approval of one current-session client exit; no instance mutation. */
final class ScarletExitOperatorDiscord
{
    static final String PERMISSION = "vrchat-client-exit-override";
    static final String REVIEW = "scarlet-exit-review:", CONFIRM = "scarlet-exit-confirm:";
    final ScarletDiscordJDA discord;
    final Confirmations confirmations = new Confirmations();
    ScarletExitOperatorDiscord(ScarletDiscordJDA discord) { this.discord = discord; }

    static final class Confirmation
    {
        final String notice, user, guild, channel;
        final long created;
        Confirmation(String notice, String user, String guild, String channel, long created)
        { this.notice = notice; this.user = user; this.guild = guild; this.channel = channel; this.created = created; }
    }
    static final class Confirmations
    {
        private final Map<String, Confirmation> pending = new HashMap<>();
        synchronized String create(String notice, String user, String guild, String channel, long now)
        {
            this.pending.values().removeIf(c -> now - c.created < 0 || now - c.created >= TimeUnit.MINUTES.toNanos(1));
            if (this.pending.size() >= 64) return null;
            String token = UUID.randomUUID().toString();
            this.pending.put(token, new Confirmation(notice, user, guild, channel, now));
            return token;
        }
        synchronized String consume(String token, String user, String guild, String channel, long now)
        {
            Confirmation c = this.pending.get(token);
            if (c == null || !Objects.equals(c.user, user) || !Objects.equals(c.guild, guild)
                || !Objects.equals(c.channel, channel)) return null;
            this.pending.remove(token);
            return now - c.created >= 0 && now - c.created < TimeUnit.MINUTES.toNanos(1) ? c.notice : null;
        }
    }

    void emit(String token, String counts)
    {
        TextChannel channel = this.discord.getOpsAlertChannel();
        if (channel == null) return;
        channel.sendMessageEmbeds(new EmbedBuilder().setTitle("VRChat exit needs operator review")
            .setColor(0xFEE75C).setDescription("Scarlet has locally observed only its own VRChat account for at least five minutes. "
                + "The instance is confirmed closed, but the API still reports other users. Automatic exit remains blocked.\n\n"
                + counts + "\n\nAn authorized operator may review a normal exit of Scarlet's VRChat client. "
                + "This bypasses only the API occupancy disagreement. Live tracking, sole-self roster, closure and process ownership must still pass. "
                + "It does not close another instance or force-kill VRChat.\n\n"
                + "Permission: `" + PERMISSION + "` (Other); server owner/administrators are allowed by default. Review expires in 10 minutes.")
            .build()).setAllowedMentions(Collections.emptyList())
            .setComponents(ActionRow.of(Button.secondary(REVIEW + token, "Review VRChat exit")))
            .queue(message -> {}, error -> Scarlet.LOG.warn("Client-exit operator alert delivery failed"));
    }

    boolean allowed(Member member, TextChannel channel)
    {
        return member != null && channel != null && Objects.equals(member.getGuild().getId(), this.discord.guildSf)
            && Objects.equals(channel.getId(), this.discord.opsAlertChannelSf)
            && Objects.equals(channel.getGuild().getId(), this.discord.guildSf)
            && member.hasPermission(channel, Permission.VIEW_CHANNEL)
            && this.discord.perms.check(member, PERMISSION, false);
    }

    boolean handle(ButtonInteractionEvent event)
    {
        String id = event.getComponentId();
        if (id == null || !(id.startsWith(REVIEW) || id.startsWith(CONFIRM))) return false;
        TextChannel channel = this.discord.getOpsAlertChannel();
        if (!event.isFromGuild() || channel == null || !Objects.equals(event.getGuild().getId(), this.discord.guildSf)
            || !Objects.equals(event.getChannel().getId(), channel.getId())
            || !event.getMessage().getAuthor().getId().equals(this.discord.jda.getSelfUser().getId())
            || !this.allowed(event.getMember(), channel))
        {
            event.reply("This control requires operator permission in the configured operations channel.").setEphemeral(true).queue();
            return true;
        }
        event.deferReply(true).queue(hook -> {
            try { this.discord.scarlet.exec.execute(() -> {
                try
                {
                    // REST refresh, not only the member attached to an old interaction.
                    Member member = event.getGuild().retrieveMemberById(event.getUser().getId()).useCache(false).submit().get(5, TimeUnit.SECONDS);
                    if (!this.allowed(member, channel))
                    { hook.editOriginal("Permission or channel configuration changed; no exit requested.").queue(); return; }
                    ScarletClientExitMonitor monitor = this.discord.scarlet.eventListener.clientExitMonitor;
                    if (id.startsWith(REVIEW))
                    {
                        String notice = id.substring(REVIEW.length());
                        if (!monitor.operatorNoticeValid(notice))
                        { hook.editOriginal("This report is expired or no longer matches the monitored session. No exit requested.").queue(); return; }
                        String token = this.confirmations.create(notice, member.getId(), event.getGuild().getId(), channel.getId(), System.nanoTime());
                        if (token == null) { hook.editOriginal("Too many pending reviews; try again shortly.").queue(); return; }
                        hook.editOriginal("Confirm a normal exit of Scarlet's VRChat client despite the API player count? "
                            + "Only that occupancy disagreement may be bypassed. All other safety checks will run again. "
                            + "This confirmation is yours alone and expires in 60 seconds.")
                            .setComponents(ActionRow.of(Button.danger(CONFIRM + token, "Confirm normal VRChat exit"))).queue();
                    }
                    else
                    {
                        String notice = this.confirmations.consume(id.substring(CONFIRM.length()), member.getId(), event.getGuild().getId(), channel.getId(), System.nanoTime());
                        if (notice == null) { hook.editOriginal("Confirmation expired, already used, or belongs to another operator.").queue(); return; }
                        java.util.concurrent.atomic.AtomicReference<Member> fresh = new java.util.concurrent.atomic.AtomicReference<>(member);
                        java.util.concurrent.atomic.AtomicLong checkedAt = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
                        Runnable refreshPermission = () -> {
                            fresh.set(null);
                            try {
                                Member updated = event.getGuild().retrieveMemberById(member.getId()).useCache(false).submit().get(5, TimeUnit.SECONDS);
                                checkedAt.set(System.nanoTime()); fresh.set(updated);
                            }
                            catch (Exception ex) { if (ex instanceof InterruptedException) Thread.currentThread().interrupt(); }
                        };
                        String result = monitor.operatorExit(notice, () -> {
                            long age = System.nanoTime() - checkedAt.get();
                            Member cached = event.getGuild().getMemberById(member.getId());
                            return age >= 0 && age <= TimeUnit.SECONDS.toNanos(10) && this.allowed(fresh.get(), channel)
                                && (cached == null || this.allowed(cached, channel));
                        }, refreshPermission);
                        hook.editOriginal(result).queue();
                        this.discord.emitOpsAlert("Operator VRChat exit result", "Operator ID: `" + member.getId() + "`\n" + result, 0xFEE75C);
                    }
                }
                catch (Exception ex)
                {
                    if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                    hook.editOriginal("Operator request could not complete. No force-close fallback is used; check Scarlet's local diagnostics.").queue();
                    Scarlet.LOG.warn("Client-exit operator request unavailable ({})", ex.getClass().getSimpleName());
                }
            }); }
            catch (RuntimeException ex) { hook.editOriginal("Scarlet is busy or stopping; no exit requested.").queue(); }
        });
        return true;
    }
}
