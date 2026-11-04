package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletVRChatBanEntryPointsTest.*;

import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;
import net.sybyline.scarlet.server.discord.DInteractions;
import net.sybyline.scarlet.server.discord.DPerms.PermType;

/** Real JDA event routing and delegated permission rules, without starting Discord. */
class ScarletDiscordModalAuthorizationTest
{
    @TempDir Path temp;

    static Modal form()
    {
        return Modal.create("settings:synthetic", "Synthetic form")
            .addComponents(Label.of("Value", TextInput.create("value", TextInputStyle.SHORT).build())).build();
    }

    <T> T event(Class<T> type, Fixture f, String operation, String modalId, Member member, AtomicReference<Modal> opened) throws Exception
    {
        return ScarletConfigCommandMappingsTest.event(type, (method, args) -> {
            if (method.equals("getUser")) return proxy(net.dv8tion.jda.api.entities.User.class,
                (m, a) -> m.equals("getId") ? REQUESTER : null);
            if (method.equals("getMember")) return member;
            if (method.equals("getGuild")) return f.member.getGuild();
            if (method.equals("getName")) return operation.split(" ")[0];
            if (method.equals("getSubcommandName")) return operation.contains(" ") ? operation.split(" ")[1] : null;
            if (method.equals("getFullCommandName")) return operation;
            if (method.equals("getModalId")) return modalId;
            if (method.equals("replyModal")) opened.set((Modal)args[0]);
            if (method.equals("isAcknowledged")) return true;
            return null;
        }, f.messages);
    }

    @Test void everyFlowRechecksOriginalSlashPermissionAndPreservesDelegation() throws Exception
    {
        Fixture f = new Fixture(temp);
        DInteractions interactions = new DInteractions(false, null);
        interactions.setModalAuthorization(f.discord::authorizeModalSession);
        put(f.discord, "interactions", interactions);
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<Modal> opened = new AtomicReference<>();
        for (String operation : new String[]{"settings edit", "set-ticket-age-verify-message", "schedule set-roles", "schedule set-platforms"})
        {
            f.perms.set(PermType.SLASH_COMMAND, f.member, operation, true);
            SlashCommandInteractionEvent opener = event(SlashCommandInteractionEvent.class, f, operation, null, f.member, opened);
            interactions.submitModalFlow(opener, form(), submitted -> writes.incrementAndGet());
            f.perms.set(PermType.SLASH_COMMAND, f.member, operation, false);
            f.discord.new JDAEvents().onModalInteraction(event(ModalInteractionEvent.class, f, operation, opened.get().getId(), f.member, opened));
            assertEquals(0, writes.get(), operation);
            // An explicit delegated command grant remains sufficient; no owner/admin requirement is introduced.
            f.perms.set(PermType.SLASH_COMMAND, f.member, operation, true);
            interactions.submitModalFlow(opener, form(), submitted -> writes.incrementAndGet());
            f.discord.new JDAEvents().onModalInteraction(event(ModalInteractionEvent.class, f, operation, opened.get().getId(), f.member, opened));
            assertEquals(1, writes.getAndSet(0), operation);
        }
    }

    @Test void submissionUsesCurrentMemberRolesInsteadOfOpenerRoles() throws Exception
    {
        Fixture f = new Fixture(temp);
        DInteractions interactions = new DInteractions(false, null);
        interactions.setModalAuthorization(f.discord::authorizeModalSession);
        put(f.discord, "interactions", interactions);
        Role grantedRole = proxy(Role.class, (m, a) -> m.equals("getId") ? "300" : null);
        Member openerMember = proxy(Member.class, (m, a) -> {
            if (m.equals("getId")) return REQUESTER;
            if (m.equals("getGuild")) return f.member.getGuild();
            if (m.equals("getRoles")) return Collections.singletonList(grantedRole);
            return null;
        });
        f.perms.set(PermType.SLASH_COMMAND, grantedRole, "settings edit", true);
        assertTrue(f.perms.checkSlash(openerMember, "settings edit"));
        assertFalse(f.perms.checkSlash(f.member, "settings edit"));
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<Modal> opened = new AtomicReference<>();
        interactions.submitModalFlow(event(SlashCommandInteractionEvent.class, f, "settings edit", null, openerMember, opened),
            form(), submitted -> writes.incrementAndGet());
        f.discord.new JDAEvents().onModalInteraction(event(ModalInteractionEvent.class, f, "settings edit", opened.get().getId(), f.member, opened));
        assertEquals(0, writes.get());
    }
}
